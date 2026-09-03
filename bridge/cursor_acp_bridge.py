#!/usr/bin/env python3
"""Cursor Keyboard -- on-device ACP bridge daemon.

Spawns the Cursor CLI in Agent-Client-Protocol (ACP) mode ("agent acp") and
exposes it to the Cursor Keyboard app over a local TCP socket using the same
newline-delimited JSON-RPC framing that ACP uses over stdio.

The bridge also serves a small custom namespace (cursor_keyboard/*) directly,
notably cursor_keyboard/get_context, which captures the current shell/terminal
context inside the chroot (see termux_context.py) and returns it to the
keyboard so the agent has a starting point when helping with a command.

Usage
-----
    python3 cursor_acp_bridge.py [--host 127.0.0.1] [--port 9043]
                                 [--agent <path-to-agent>] [--api-key <key>]
                                 [--workspace <cwd>]

Auth: pass a user-scoped Cursor API key via --api-key or the CURSOR_API_KEY
environment variable; otherwise the bridge relies on the agent already being
logged in (agent login) inside the chroot.

Requirements: Python 3.8+ (stdlib only).
"""

import argparse
import json
import os
import shlex
import signal
import socket
import subprocess
import sys
import threading

from termux_context import capture_context

CONTEXT_PREFIX = "cursor_keyboard/"


def log(msg: str) -> None:
    sys.stderr.write(f"[bridge] {msg}\n")
    sys.stderr.flush()


class AgentProcess:
    """Owns the `agent acp` child process and relays to/from a client socket."""

    def __init__(self, argv: list[str], client_sock: socket.socket) -> None:
        self.argv = argv
        self.client_sock = client_sock
        self._send_lock = threading.Lock()
        self._closed = threading.Event()
        self.proc = subprocess.Popen(
            argv,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )

    def send_line(self, line: str) -> None:
        """Thread-safe write of one JSON line to the client socket."""
        with self._send_lock:
            try:
                self.client_sock.sendall(line.encode())
            except (BrokenPipeError, ConnectionResetError, OSError):
                self._closed.set()

    def _dispatch_local(self, msg: dict) -> bool:
        """Handle cursor_keyboard/* methods locally; return True if handled."""
        method = msg.get("method", "")
        if not method.startswith(CONTEXT_PREFIX):
            return False
        rpc_id = msg.get("id")
        result = None
        error = None
        if method == "cursor_keyboard/get_context":
            result = {"context": capture_context()}
        elif method == "cursor_keyboard/ping":
            result = {"pong": True}
        else:
            error = {"code": -32601, "message": f"Method not found: {method}"}

        response = {"jsonrpc": "2.0", "id": rpc_id}
        if error:
            response["error"] = error
        else:
            response["result"] = result
        self.send_line(json.dumps(response))
        return True

    def _relay_stdout_to_client(self) -> None:
        """agent stdout -> client socket (one JSON message per line)."""
        try:
            for line in self.proc.stdout:
                if self._closed.is_set():
                    break
                self.send_line(line)
        except OSError:
            pass
        finally:
            self._closed.set()
            self._shutdown()

    def _relay_stderr(self) -> None:
        """agent stderr -> bridge stderr so runtime errors stay visible."""
        try:
            for line in self.proc.stderr:
                sys.stderr.write(line)
        except Exception:
            pass

    def _relay_client_to_stdin(self) -> None:
        """client socket -> agent stdin, intercepting cursor_keyboard/*."""
        buf = b""
        try:
            while not self._closed.is_set():
                data = self.client_sock.recv(4096)
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    text = line.decode().strip()
                    if not text:
                        continue
                    try:
                        msg = json.loads(text)
                    except json.JSONDecodeError:
                        # Not JSON: pass through raw to the agent.
                        self._write_stdin(text + "\n")
                        continue
                    if self._dispatch_local(msg):
                        continue
                    self._write_stdin(text + "\n")
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            self._closed.set()
            self._shutdown()

    def _write_stdin(self, text: str) -> None:
        try:
            self.proc.stdin.write(text)
            self.proc.stdin.flush()
        except OSError:
            self._closed.set()

    def _shutdown(self) -> None:
        if self.proc.poll() is None:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=3)
            except Exception:
                self.proc.kill()

    def run(self) -> None:
        threads = [
            threading.Thread(target=self._relay_stdout_to_client, daemon=True),
            threading.Thread(target=self._relay_stderr, daemon=True),
            threading.Thread(target=self._relay_client_to_stdin, daemon=True),
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self._closed.set()
        self._shutdown()


def build_agent_argv(args) -> list[str]:
    argv = [args.agent, "acp"]
    api_key = args.api_key or os.environ.get("CURSOR_API_KEY")
    if api_key:
        argv += ["--api-key", api_key]
    return argv


def handle_client(conn: socket.socket, args, addr) -> None:
    log(f"client connected: {addr}")
    argv = build_agent_argv(args)
    log(f"spawning: {shlex.join(argv)}")
    agent = AgentProcess(argv, conn)
    try:
        agent.run()
    finally:
        try:
            conn.close()
        except OSError:
            pass
    log(f"client disconnected: {addr}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Cursor Keyboard ACP bridge")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9043)
    parser.add_argument("--agent", default=os.environ.get("CURSOR_AGENT_BIN", "agent"))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--workspace", default=os.getcwd())
    args = parser.parse_args()

    if args.workspace:
        os.chdir(args.workspace)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(5)
    log(f"listening on {args.host}:{args.port} (agent={args.agent})")

    def stop(signum, _frame):
        log(f"signal {signum}, shutting down")
        server.close()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    while True:
        try:
            conn, addr = server.accept()
        except OSError:
            break
        threading.Thread(target=handle_client, args=(conn, args, addr), daemon=True).start()

    return 0


if __name__ == "__main__":
    sys.exit(main())
