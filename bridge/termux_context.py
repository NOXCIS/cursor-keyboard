#!/usr/bin/env python3
"""Cursor Keyboard -- Termux context capture.

Produces a small JSON context blob that describes the current terminal/shell
state so the Cursor agent has a starting point when it helps with a command.
This runs *inside* the on-device Debian chroot / Termux, where the actual shell
and history live, and is served to the keyboard over the bridge socket.

Context captured:
  - current working directory
  - tail of shell history (recent commands)
  - uname / OS identifiers
  - timestamps

Note: the *active* line being typed is read directly by the keyboard via
InputConnection#getSurroundingText(); this module supplies the surrounding
shell state that the IME cannot see.
"""

from __future__ import annotations

import json
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_HISTORY_LINES = 40


def _history_tail(n_lines: int) -> list[str]:
    """Return the last n_lines commands from the shell history file."""
    histfile = os.environ.get("HISTFILE") or ""
    candidates = [histfile, str(Path.home() / ".bash_history"), str(Path.home() / ".zsh_history")]
    for path in candidates:
        if not path:
            continue
        p = Path(path)
        if not p.exists():
            continue
        try:
            lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
            # zsh history includes a leading timestamp like ": 1680000000:0;cmd"
            cleaned = []
            for line in lines:
                cleaned.append(line.split(";", 1)[-1] if line.startswith(": ") else line)
            return cleaned[-n_lines:]
        except OSError:
            continue
    return []


def _active_window_title() -> str:
    try:
        out = subprocess.run(
            ["/bin/sh", "-c", 'printf "%s" "${WINDOWTITLE:-$TERM}"'],
            capture_output=True,
            text=True,
            timeout=2,
        ).stdout.strip()
        return out
    except Exception:
        return ""


def capture_context(history_lines: int = DEFAULT_HISTORY_LINES) -> dict:
    uname = os.uname() if hasattr(os, "uname") else None
    now = datetime.now(timezone.utc).isoformat()
    return {
        "captured_at": now,
        "pwd": os.getcwd(),
        "host": platform.node(),
        "uname": {
            "sysname": getattr(uname, "sysname", None),
            "release": getattr(uname, "release", None),
            "machine": getattr(uname, "machine", None),
            "platform": platform.platform(),
        },
        "shell": os.environ.get("SHELL", ""),
        "term": _active_window_title(),
        "history": _history_tail(history_lines),
        "cwd": os.getcwd(),
    }


def write_context_file(path: str, history_lines: int = DEFAULT_HISTORY_LINES) -> None:
    data = capture_context(history_lines)
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=2), encoding="utf-8")
    with open(p, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Capture Cursor Keyboard terminal context")
    ap.add_argument("--out", default="~/.config/cursor-keyboard/context.json")
    ap.add_argument("--history-lines", type=int, default=DEFAULT_HISTORY_LINES)
    args = ap.parse_args()
    write_context_file(os.path.expanduser(args.out), args.history_lines)
    print(f"context written to {os.path.expanduser(args.out)}")
