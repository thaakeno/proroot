#!/usr/bin/env python3
"""Launch .desktop applications inside the already-running KDE session.

Android's Apps tab talks to this Unix socket instead of spawning another
ProRoot instance for every click. That makes launches use the exact same
Wayland/DBus/PipeWire environment as apps started from Plasma itself.
"""

from __future__ import annotations

import os
import re
import signal
import socket
import subprocess
import sys
from pathlib import Path

APP_ID = re.compile(r"^[A-Za-z0-9._+-]+$")
runtime = Path(os.environ["XDG_RUNTIME_DIR"])
socket_path = runtime / "proroot-app-launcher.sock"
log_path = runtime / "proroot-apps.log"
server: socket.socket | None = None
running = True


def shutdown(*_: object) -> None:
    global running
    running = False
    if server is not None:
        try:
            server.close()
        except OSError:
            pass


def reply(conn: socket.socket, message: str) -> None:
    try:
        conn.sendall((message + "\n").encode("utf-8"))
    except OSError:
        pass


for sig in (signal.SIGINT, signal.SIGTERM):
    signal.signal(sig, shutdown)

try:
    socket_path.unlink()
except FileNotFoundError:
    pass

server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(str(socket_path))
os.chmod(socket_path, 0o600)
server.listen(8)
server.settimeout(1.0)

with open(log_path, "ab", buffering=0) as log:
    log.write(b"===== KDE session app launcher ready =====\n")
    while running:
        try:
            conn, _ = server.accept()
        except socket.timeout:
            continue
        except OSError:
            if running:
                raise
            break

        with conn:
            try:
                raw = conn.recv(512)
                app_id = raw.decode("utf-8", "strict").strip()
            except (UnicodeError, OSError):
                reply(conn, "ERR invalid request")
                continue

            if not APP_ID.fullmatch(app_id):
                reply(conn, "ERR invalid desktop id")
                continue

            try:
                subprocess.Popen(
                    ["gtk-launch", app_id],
                    cwd=os.environ.get("HOME", "/home/linux"),
                    env=os.environ.copy(),
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                    close_fds=True,
                )
                reply(conn, "OK")
            except Exception as exc:
                log.write(
                    f"launch {app_id!r} failed: {type(exc).__name__}: {exc}\n".encode(
                        "utf-8", "replace"
                    )
                )
                reply(conn, f"ERR {exc}")

try:
    socket_path.unlink()
except FileNotFoundError:
    pass
