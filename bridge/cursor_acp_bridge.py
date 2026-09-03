#!/usr/bin/env python3
"""Cursor Keyboard -- on-device ACP bridge daemon.

Spawns the Cursor CLI in Agent-Client-Protocol (ACP) mode ("agent acp") and
exposes it to the Cursor Keyboard app over a local TCP socket using the same
newline-delimited JSON-RPC framing that ACP uses over stdio.

The bridge also serves a small custom namespace (cursor_keyboard/*) directly,
notably cursor_keyboard/get_context, which captures the current shell/terminal
context inside the chroot (see termux_context.py) and returns it to the
keyboard so the agent has a starting point when helping with a command.

The keyboard can push a user-scoped Cursor API key over cursor_keyboard/authenticate;
the bridge holds it in memory only (never touching disk) and passes it to the
agent via --api-key. A cursor_keyboard/status call reports health (agent binary,
version, auth state, workspace) without spawning an agent.

Control methods (cursor_keyboard/*) are handled locally and never spawn an
agent process; the agent is spawned lazily on the first real ACP message
(e.g. initialize).

Transports
----------
The bridge accepts two kinds of connection, auto-detected from the first bytes:

  * Newline-delimited TCP (default / local chroot / direct TLS). The keyboard
    connects with ``tcp://host:port`` or ``tls://host:port``.
  * WebSocket over HTTP (for Cloudflare Tunnel / reverse proxies). The keyboard
    connects with ``wss://public-host`` (or ``https://public-host``); the proxy
    terminates TLS and forwards a WebSocket upgrade to this origin, where the
    same ACP message stream flows inside WebSocket text frames.

Usage
-----
    python3 cursor_acp_bridge.py [--host 127.0.0.1] [--port 9043]
                                 [--agent <path-to-agent>] [--api-key <key>]
                                 [--workspace <cwd>]
                                 [--tls-cert <cert.pem> --tls-key <key.pem>]
                                 [--token <shared-secret>]

Auth: pass a user-scoped Cursor API key via --api-key or the CURSOR_API_KEY
environment variable, or push it at runtime from the keyboard via
cursor_keyboard/authenticate; otherwise the bridge relies on the agent already
being logged in (agent login) inside the chroot.

Exposing the bridge outside the device is only safe behind a TLS-terminating
proxy (e.g. Cloudflare Tunnel) AND a shared --token. When --token is set, every
client must present it on the first message before any agent is spawned.

Requirements: Python 3.8+ (stdlib only).
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import shlex
import shutil
import signal
import socket
import ssl
import struct
import subprocess
import sys
import threading

from termux_context import capture_context

CONTEXT_PREFIX = "cursor_keyboard/"

_module_api_key = None
_api_key_lock = threading.Lock()


def log(msg: str) -> None:
    sys.stderr.write(f"[bridge] {msg}\n")
    sys.stderr.flush()


def get_api_key():
    with _api_key_lock:
        return _module_api_key


def set_api_key(key: str) -> None:
    global _module_api_key
    with _api_key_lock:
        _module_api_key = key


def find_agent(agent_path: str) -> str | None:
    """Resolve the agent binary, including the cursor installer's ~/.local/bin."""
    candidates = [agent_path]
    if os.sep not in agent_path:
        candidates.append(os.path.expanduser("~/.local/bin/" + agent_path))
    for candidate in candidates:
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return shutil.which(agent_path)


def agent_available(agent_path: str) -> bool:
    return find_agent(agent_path) is not None


def agent_version(agent_path: str):
    path = find_agent(agent_path)
    if path is None:
        return None
    try:
        out = subprocess.run(
            [path, "--version"],
            capture_output=True, text=True, timeout=8,
        )
        return (out.stdout or out.stderr).strip() or None
    except Exception:
        return None


def build_agent_argv(args) -> list[str]:
    argv = [args.agent, "acp"]
    api_key = get_api_key() or args.api_key or os.environ.get("CURSOR_API_KEY")
    if api_key:
        argv += ["--api-key", api_key]
    return argv


class Transport:
    """Abstract message transport. One JSON message per `send` / `recv`."""

    def send(self, text: str) -> None:
        raise NotImplementedError

    def recv(self) -> str | None:
        """Return the next text message, or None once the connection closes."""
        raise NotImplementedError

    def close(self) -> None:
        raise NotImplementedError


class TCPTransport(Transport):
    """Newline-delimited JSON over a raw (optionally TLS) socket."""

    def __init__(self, sock: socket.socket, initial: bytes = b"") -> None:
        self.sock = sock
        self._buf = initial
        self._send_lock = threading.Lock()

    def send(self, text: str) -> None:
        if not text.endswith("\n"):
            text += "\n"
        with self._send_lock:
            try:
                self.sock.sendall(text.encode())
            except OSError:
                pass

    def recv(self) -> str | None:
        try:
            while True:
                if b"\n" in self._buf:
                    line, self._buf = self._buf.split(b"\n", 1)
                    text = line.decode().strip()
                    if text:
                        return text
                    continue
                data = self.sock.recv(4096)
                if not data:
                    return None
                self._buf += data
        except OSError:
            return None

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


class WebSocketTransport(Transport):
    """Minimal WebSocket server transport (RFC 6455) so a TLS-terminating
    proxy (e.g. Cloudflare Tunnel) can forward ``wss://``/``https://`` to us."""

    GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    def __init__(self, sock: socket.socket, initial: bytes = b"") -> None:
        self.sock = sock
        self._rbuf = b""
        self._send_lock = threading.Lock()
        self._handshake(initial)

    # ---- HTTP upgrade ----------------------------------------------------

    def _read_http_headers(self, initial: bytes) -> dict:
        data = initial
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise OSError("closed during handshake")
            data += chunk
        head, _, rest = data.partition(b"\r\n\r\n")
        self._rbuf = rest
        headers: dict[str, str] = {}
        lines = head.decode("iso-8859-1").split("\r\n")
        for line in lines[1:]:
            if ":" in line:
                key, _, value = line.partition(":")
                headers[key.strip().lower()] = value.strip()
        return headers

    def _handshake(self, initial: bytes) -> None:
        headers = self._read_http_headers(initial)
        upgrade = headers.get("upgrade", "").lower()
        key = headers.get("sec-websocket-key", "")
        if upgrade != "websocket" or not key:
            body = b"Cursor Keyboard bridge: OK\n"
            resp = (
                "HTTP/1.1 200 OK\r\n"
                "Content-Type: text/plain\r\n"
                "Content-Length: " + str(len(body)) + "\r\n"
                "Connection: close\r\n\r\n"
            )
            self.sock.sendall(resp.encode() + body)
            raise OSError("not a websocket upgrade")
        accept = base64.b64encode(
            hashlib.sha1((key + WebSocketTransport.GUID).encode()).digest()
        ).decode()
        resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
        )
        self.sock.sendall(resp.encode())

    # ---- frame I/O -------------------------------------------------------

    def _recv_exact(self, n: int) -> bytes:
        while len(self._rbuf) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise OSError("closed")
            self._rbuf += chunk
        out = self._rbuf[:n]
        self._rbuf = self._rbuf[n:]
        return out

    def _read_frame(self):
        b1, b2 = self._recv_exact(2)
        fin = bool(b1 & 0x80)
        opcode = b1 & 0x0F
        masked = bool(b2 & 0x80)
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._recv_exact(8))[0]
        mask = self._recv_exact(4) if masked else b""
        payload = self._recv_exact(length)
        if masked and mask:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return fin, opcode, payload

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        header = bytearray()
        header.append(0x80 | (opcode & 0x0F))
        n = len(payload)
        if n < 126:
            header.append(n)
        elif n < 65536:
            header.append(126)
            header += struct.pack(">H", n)
        else:
            header.append(127)
            header += struct.pack(">Q", n)
        self.sock.sendall(bytes(header) + payload)

    def send(self, text: str) -> None:
        try:
            self._send_frame(0x1, text.encode())
        except OSError:
            pass

    def recv(self) -> str | None:
        fragments = b""
        try:
            while True:
                fin, opcode, payload = self._read_frame()
                if opcode == 0x8:  # close
                    self._send_close()
                    return None
                if opcode == 0x9:  # ping -> pong
                    self._send_frame(0xA, payload)
                    continue
                if opcode == 0xA:  # pong
                    continue
                fragments += payload
                if fin:
                    return fragments.decode("utf-8", "replace")
        except OSError:
            return None

    def _send_close(self) -> None:
        try:
            self._send_frame(0x8, b"")
        except OSError:
            pass

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        header = bytearray()
        header.append(0x80 | (opcode & 0x0F))
        n = len(payload)
        if n < 126:
            header.append(n)
        elif n < 65536:
            header.append(126)
            header += struct.pack(">H", n)
        else:
            header.append(127)
            header += struct.pack(">Q", n)
        with self._send_lock:
            try:
                self.sock.sendall(bytes(header) + payload)
            except OSError:
                pass

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


class AgentProcess:
    """Owns an `agent acp` child process (lazily spawned) and relays to a client.

    The agent child is only created once a real ACP message (such as
    ``initialize``) arrives; pure ``cursor_keyboard/*`` control messages are
    answered locally without creating a process.
    """

    def __init__(self, transport: Transport, args) -> None:
        self.transport = transport
        self.args = args
        self._send_lock = threading.Lock()
        self._closed = threading.Event()
        self._spawn_lock = threading.Lock()
        self._pending_lines = []
        self._spawned = False
        self._token_gate_passed = args.token is None
        self.proc = None

    def send_line(self, line: str) -> None:
        """Thread-safe write of one JSON message to the client."""
        if not line.endswith("\n"):
            line += "\n"
        with self._send_lock:
            self.transport.send(line)

    def _handle_message(self, text: str) -> bool:
        """Process one message; return False to close the connection."""
        text = text.strip()
        if not text:
            return True
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            # Not JSON: pass through raw to the agent.
            self._write_stdin(text + "\n")
            return True
        if not self._token_gate_passed:
            # The gate either consumes the authenticate message or rejects the
            # connection, so it fully owns handling until the gate is passed.
            return self._try_token_gate(msg)
        if self._dispatch_local(msg):
            return True
        return self._try_forward_to_agent(msg)

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
        elif method == "cursor_keyboard/authenticate":
            params = msg.get("params") or {}
            key = params.get("apiKey", "")
            token = params.get("token", "")
            if key:
                set_api_key(key)
            if key or token:
                result = {"ok": True}
            else:
                error = {"code": -32602, "message": "Missing apiKey"}
        elif method == "cursor_keyboard/status":
            result = self._build_status()
        else:
            error = {"code": -32601, "message": f"Method not found: {method}"}

        response = {"jsonrpc": "2.0", "id": rpc_id}
        if error:
            response["error"] = error
        else:
            response["result"] = result
        self.send_line(json.dumps(response))
        return True

    def _try_token_gate(self, msg: dict) -> bool:
        """Authenticate the connection with the shared token. True keeps it open."""
        method = msg.get("method", "")
        if method != "cursor_keyboard/authenticate":
            return self._reject(msg)
        token = (msg.get("params") or {}).get("token", "")
        if token and hmac.compare_digest(token, self.args.token or ""):
            self._token_gate_passed = True
            key = (msg.get("params") or {}).get("apiKey", "")
            if key:
                set_api_key(key)
            response = {"jsonrpc": "2.0", "id": msg.get("id"), "result": {"ok": True}}
            self.send_line(json.dumps(response))
            return True
        return self._reject(msg)

    def _reject(self, msg: dict) -> bool:
        """Refuse an unauthorized connection and close it."""
        if msg.get("id") is not None:
            response = {
                "jsonrpc": "2.0",
                "id": msg.get("id"),
                "error": {"code": -32001, "message": "unauthorized"},
            }
            self.send_line(json.dumps(response))
        self._closed.set()
        return False

    def _build_status(self) -> dict:
        key = get_api_key() or self.args.api_key or os.environ.get("CURSOR_API_KEY")
        return {
            "agent_installed": agent_available(self.args.agent),
            "agent_version": agent_version(self.args.agent),
            "authenticated": bool(key),
            "authenticated_from_device": bool(get_api_key()),
            "workspace": self.args.workspace,
            "workspace_exists": os.path.isdir(self.args.workspace),
            "bridge_pid": os.getpid(),
            "tls": bool(getattr(self.args, "tls_cert", None)),
            "token_required": bool(getattr(self.args, "token", None)),
            "endpoint": f"{self.args.host}:{self.args.port}",
        }

    def _maybe_spawn(self):
        """Spawn the agent once, then flush buffered lines. Returns the process."""
        with self._spawn_lock:
            if self._spawned:
                return self.proc
            argv = build_agent_argv(self.args)
            log(f"spawning: {shlex.join(argv)}")
            proc = subprocess.Popen(
                argv,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            self.proc = proc
            self._spawned = True
            threading.Thread(target=self._relay_stdout_to_client, daemon=True).start()
            threading.Thread(target=self._relay_stderr, daemon=True).start()
            for line in self._pending_lines:
                self._write_stdin(line)
            self._pending_lines.clear()
            return proc

    def _write_stdin(self, text: str) -> None:
        try:
            if self.proc is not None:
                self.proc.stdin.write(text)
                self.proc.stdin.flush()
        except OSError:
            self._closed.set()

    def _try_forward_to_agent(self, msg: dict) -> bool:
        try:
            self._maybe_spawn()
        except Exception as e:
            log(f"failed to spawn agent: {e}")
            if msg.get("id") is not None:
                resp = {
                    "jsonrpc": "2.0",
                    "id": msg.get("id"),
                    "error": {"code": -32603, "message": f"agent not available: {e}"},
                }
                self.send_line(json.dumps(resp))
            self._closed.set()
            return False
        self._write_stdin(json.dumps(msg) + "\n")
        return True

    def _relay_stdout_to_client(self) -> None:
        """agent stdout -> client (one JSON message per line/frame)."""
        try:
            if self.proc is None:
                return
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
            if self.proc is None:
                return
            for line in self.proc.stderr:
                sys.stderr.write(line)
        except Exception:
            pass

    def _relay_client_to_stdin(self) -> None:
        """client -> agent stdin, intercepting cursor_keyboard/*."""
        try:
            while not self._closed.is_set():
                text = self.transport.recv()
                if text is None:
                    break
                if not self._handle_message(text):
                    return
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            self._closed.set()
            self._shutdown()

    def _shutdown(self) -> None:
        if self.proc is not None:
            if self.proc.poll() is None:
                try:
                    self.proc.terminate()
                    self.proc.wait(timeout=3)
                except Exception:
                    try:
                        self.proc.kill()
                    except Exception:
                        pass
            try:
                if self.proc.stdin is not None:
                    self.proc.stdin.close()
            except OSError:
                pass

    def run(self) -> None:
        threading.Thread(target=self._relay_client_to_stdin, daemon=True).start()
        self._closed.wait()
        self._shutdown()


def make_transport(conn: socket.socket) -> Transport:
    """Decide raw-TCP vs WebSocket from the first bytes, then upgrade."""
    conn.settimeout(1.0)
    head = b""
    try:
        while len(head) < 16:
            try:
                chunk = conn.recv(16 - len(head))
            except socket.timeout:
                break
            if not chunk:
                break
            head += chunk
    except (OSError, ssl.SSLError) as e:
        log(f"transport handshake error: {e}")
        conn.settimeout(None)
        return None
    conn.settimeout(None)
    if not head:
        return None
    if head.startswith((b"GET", b"POST")):
        return WebSocketTransport(conn, initial=head)
    return TCPTransport(conn, initial=head)


def handle_transport(transport: Transport, args, addr) -> None:
    log(f"client connected: {addr}")
    agent = AgentProcess(transport, args)
    try:
        agent.run()
    finally:
        transport.close()
    log(f"client disconnected: {addr}")


def _serve_conn(conn: socket.socket, args, addr) -> None:
    try:
        transport = make_transport(conn)
    except Exception as e:
        log(f"handshake error from {addr}: {e}")
        try:
            conn.close()
        except OSError:
            pass
        return
    if transport is None:
        try:
            conn.close()
        except OSError:
            pass
        return
    handle_transport(transport, args, addr)


def main() -> int:
    parser = argparse.ArgumentParser(description="Cursor Keyboard ACP bridge")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9043)
    parser.add_argument("--agent", default=os.environ.get("CURSOR_AGENT_BIN", "agent"))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--workspace", default=os.getcwd())
    parser.add_argument("--tls-cert", default=None,
                        help="Path to a PEM certificate; combined with --tls-key enables TLS.")
    parser.add_argument("--tls-key", default=None,
                        help="Path to the PEM private key for --tls-cert.")
    parser.add_argument("--token", default=None,
                        help="Require clients to present this shared token before any agent runs.")
    args = parser.parse_args()

    if args.workspace:
        os.chdir(args.workspace)

    ssl_ctx = None
    if args.tls_cert and args.tls_key:
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(args.tls_cert, args.tls_key)
        log(f"TLS enabled (cert={args.tls_cert})")
    elif bool(args.tls_cert) != bool(args.tls_key):
        log("WARNING: --tls-cert and --tls-key must be provided together; TLS disabled.")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(5)
    log(f"listening on {args.host}:{args.port} (agent={args.agent})")
    if args.token:
        log("shared token required for all clients")
    elif not (args.host.startswith("127.0.0.1") or args.host == "localhost"):
        log("WARNING: bound to a non-loopback address without a token or TLS; any client can connect")

    def stop(signum, _frame):
        log(f"signal {signum}, shutting down")
        server.close()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    while True:
        try:
            conn, addr = server.accept()
            if ssl_ctx is not None:
                conn = ssl_ctx.wrap_socket(conn, server_side=True)
        except (OSError, ssl.SSLError) as e:
            log(f"accept error: {e}")
            continue
        threading.Thread(target=_serve_conn, args=(conn, args, addr), daemon=True).start()

    return 0


if __name__ == "__main__":
    sys.exit(main())
