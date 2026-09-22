#!/usr/bin/env python3
"""Create per-session desktop-entry overrides for Chromium/Electron runtimes.

This is capability-based rather than app-id based: any desktop entry whose
executable identifies itself as Chromium or Electron gets the same PRoot
sandbox/Wayland launcher. PRoot cannot provide Chromium's Linux namespaces.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
from pathlib import Path

HOME = Path(os.environ.get("HOME", "/home/linux"))
OVERRIDE_DIR = HOME / ".local/share/applications"
WRAPPER = "/usr/local/lib/proroot/launch-chromium-runtime.sh"
SEARCH_DIRS = (
    Path("/usr/share/applications"),
    Path("/usr/local/share/applications"),
)

FIELD_CODE = re.compile(r"^%[fFuUdDnNickvm]$")


def main_exec(text: str) -> str | None:
    in_desktop = False
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("[") and line.endswith("]"):
            in_desktop = line == "[Desktop Entry]"
            continue
        if in_desktop and line.startswith("Exec="):
            return raw.split("=", 1)[1]
    return None


def executable_from_exec(exec_line: str) -> str | None:
    try:
        tokens = shlex.split(exec_line, posix=True)
    except ValueError:
        return None
    if not tokens:
        return None

    index = 0
    if tokens[0] == "env":
        index = 1
        while index < len(tokens) and "=" in tokens[index] and not tokens[index].startswith("-"):
            index += 1
    if index >= len(tokens):
        return None

    command = tokens[index]
    if FIELD_CODE.match(command):
        return None
    if command.startswith("/"):
        return command
    return shutil.which(command)


def classify(executable: str) -> str | None:
    path = Path(executable)
    try:
        resolved = path.resolve(strict=True)
    except OSError:
        return None

    sample = b""
    try:
        with resolved.open("rb") as source:
            sample = source.read(2 * 1024 * 1024).lower()
    except OSError:
        pass

    if b"electron_run_as_node" in sample or b"electron_no_attach_console" in sample:
        return "electron"
    if b"chrome-sandbox" in sample or b"chromium" in sample:
        return "chromium"

    try:
        completed = subprocess.run(
            [str(resolved), "--version"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=2,
            check=False,
            text=True,
        )
        version = completed.stdout.lower()
    except (OSError, subprocess.TimeoutExpired):
        version = ""

    if "chromium" in version:
        return "chromium"
    if "electron" in version:
        return "electron"
    return None


def override_exec(text: str, family: str, original_exec: str) -> str:
    in_desktop = False
    output: list[str] = []
    replaced = False
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("[") and line.endswith("]"):
            in_desktop = line == "[Desktop Entry]"
        if in_desktop and not replaced and line.startswith("Exec="):
            output.append(f"Exec={WRAPPER} {family} {original_exec}")
            replaced = True
        else:
            output.append(raw)
    return "\n".join(output) + "\n"


def main() -> int:
    OVERRIDE_DIR.mkdir(parents=True, exist_ok=True)
    managed: set[str] = set()

    for directory in SEARCH_DIRS:
        if not directory.is_dir():
            continue
        for desktop in sorted(directory.glob("*.desktop")):
            try:
                text = desktop.read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                continue
            exec_line = main_exec(text)
            if not exec_line or WRAPPER in exec_line:
                continue
            executable = executable_from_exec(exec_line)
            if not executable:
                continue
            family = classify(executable)
            if family is None:
                continue

            target = OVERRIDE_DIR / desktop.name
            target.write_text(
                override_exec(text, family, exec_line),
                encoding="utf-8",
            )
            managed.add(desktop.name)
            print(f"{desktop.name}: {family} runtime compatibility")

    marker = OVERRIDE_DIR / ".proroot-managed-runtime-overrides"
    previous = set(marker.read_text().splitlines()) if marker.is_file() else set()
    for stale in previous - managed:
        target = OVERRIDE_DIR / stale
        try:
            target.unlink()
        except FileNotFoundError:
            pass
    marker.write_text("\n".join(sorted(managed)) + ("\n" if managed else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
