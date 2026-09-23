#!/usr/bin/env python3
"""Create per-session desktop-entry overrides for runtime families.

This is capability-based rather than app-id based. Chromium/Electron share one
policy and Mozilla-family applications share another, so newly installed ARM64
apps inherit the same compatibility behavior without maintaining app-name
patches.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
from functools import lru_cache
from pathlib import Path

HOME = Path(os.environ.get("HOME", "/home/linux"))
OVERRIDE_DIR = HOME / ".local/share/applications"
WRAPPER = "/usr/local/lib/proroot/launch-app-runtime.sh"
SEARCH_DIRS = (
    Path("/usr/share/applications"),
    Path("/usr/local/share/applications"),
)

FIELD_CODE = re.compile(r"^%[fFuUdDnNickvm]$")
STATIC_SCAN_BYTES = 8 * 1024 * 1024
SHELL_EXEC = re.compile(r"(?:^|\n)\s*exec\s+([/][^\s\"']+)")


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
    if tokens[0] in ("env", "/usr/bin/env"):
        index = 1
        while index < len(tokens):
            token = tokens[index]
            if token == "--":
                index += 1
                break
            if token in ("-u", "--unset"):
                index += 2
                continue
            if token.startswith("--unset=") or token.startswith("-u"):
                index += 1
                continue
            if "=" in token and not token.startswith("-"):
                index += 1
                continue
            break
    if index >= len(tokens):
        return None

    command = tokens[index]
    if FIELD_CODE.match(command):
        return None
    if command.startswith("/") or Path(command).is_absolute():
        return command
    return shutil.which(command)


@lru_cache(maxsize=None)
def classify(executable: str, depth: int = 0) -> str | None:
    """Classify a launcher without executing it.

    Startup happens before the Wayland compositor exists, so probing arbitrary
    desktop executables with --version can launch Qt/GTK programs too early and
    crash the guest runtime. Read-only inspection keeps this setup side-effect
    free while still detecting Chromium/Electron launchers and binaries.
    """
    path = Path(executable)
    try:
        resolved = path.resolve(strict=True)
    except OSError:
        return None

    lowered_path = str(resolved).lower()
    lowered_name = resolved.name.lower()
    if "firefox" in lowered_name or "mozilla" in lowered_path:
        return "mozilla"
    if "electron" in lowered_name:
        return "electron"
    if "chromium" in lowered_path or "chrome" in lowered_name or "brave" in lowered_path:
        return "chromium"

    if depth > 3:
        return None
    try:
        with resolved.open("rb") as source:
            raw_sample = source.read(STATIC_SCAN_BYTES)
    except OSError:
        return None
    sample = raw_sample.lower()

    electron_markers = (
        b"electron_run_as_node",
        b"electron_no_attach_console",
        b"resources/app.asar",
        b"electron/js2c",
    )
    chromium_markers = (
        b"chrome-sandbox",
        b"chrome_crashpad_handler",
        b"chrome_wrapper",
        b"chromium",
        b"ozone-platform",
    )
    mozilla_markers = (
        b"moz_disable_content_sandbox",
        b"moz_disable_gpu_sandbox",
        b"moz_webrender",
        b"xre_main",
    )

    if any(marker in sample for marker in electron_markers):
        return "electron"
    if any(marker in sample for marker in chromium_markers):
        return "chromium"
    if any(marker in sample for marker in mozilla_markers):
        return "mozilla"
    # Vendor launchers such as /usr/bin/code often resolve to a short shell
    # script that execs the actual Electron binary. Inspect the target without
    # running the script or adding a product-name special case.
    if sample.startswith(b"#!"):
        script = raw_sample.decode("utf-8", "replace")
        for match in SHELL_EXEC.finditer(script):
            nested = Path(match.group(1))
            if nested == resolved:
                continue
            family = classify(str(nested), depth + 1)
            if family:
                return family
    return None

def override_exec(text: str, family: str) -> str:
    section = ""
    output: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("[") and line.endswith("]"):
            section = line
        if (section == "[Desktop Entry]" or section.startswith("[Desktop Action ")) and line.startswith("Exec="):
            command = raw.split("=", 1)[1]
            # Desktop actions (new window/private window) are independent
            # launch paths. Apply the same family policy to each action.
            output.append(f"Exec={WRAPPER} {family} {command}")
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
                print(f"{desktop.name}: skipped (unresolved Exec)")
                continue
            family = classify(executable)
            if family is None:
                print(f"{desktop.name}: skipped (unclassified {executable})")
                continue

            target = OVERRIDE_DIR / desktop.name
            target.write_text(
                override_exec(text, family),
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
