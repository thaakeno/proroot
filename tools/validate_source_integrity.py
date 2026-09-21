#!/usr/bin/env python3
"""Fail fast on accidental source-file blowups or duplicated source blocks.

This guard exists because replacement-string semantics in editing tooling can
expand small changes into repeated copies of the surrounding file. It is kept
dependency-free so it can run as the first CI validation step.
"""

from __future__ import annotations

import hashlib
import pathlib
import subprocess
import sys
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[1]

CRITICAL_LIMITS = {
    "android/app/src/main/kotlin/dev/thaakeno/proroot/install/RuntimeInstaller.kt": (900, 55_000),
    "android/app/src/main/kotlin/dev/thaakeno/proroot/runtime/RuntimeDiagnostics.kt": (350, 24_000),
    "android/app/src/main/kotlin/dev/thaakeno/proroot/runtime/RuntimeEngine.kt": (500, 34_000),
    "android/app/src/main/kotlin/dev/thaakeno/proroot/install/DesktopProvisioner.kt": (650, 40_000),
}

SOURCE_SUFFIXES = {".kt", ".kts", ".dart", ".sh", ".py", ".yml", ".yaml"}
MAX_GROWTH_RATIO = 3.0
MIN_GROWTH_BYTES = 32_768
DUPLICATE_WINDOW_LINES = 16
MAX_IDENTICAL_WINDOWS = 3


def fail(message: str) -> None:
    print(f"source-integrity: ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        fail(f"required source file is missing: {relative}")
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        fail(f"source file is not UTF-8: {relative}")


def check_critical_limits() -> None:
    for relative, (max_lines, max_bytes) in CRITICAL_LIMITS.items():
        text = read_text(relative)
        lines = text.splitlines()
        size = len(text.encode("utf-8"))
        if len(lines) > max_lines:
            fail(f"{relative} has {len(lines)} lines; ceiling is {max_lines}")
        if size > max_bytes:
            fail(f"{relative} is {size} bytes; ceiling is {max_bytes}")

        if relative.endswith(".kt"):
            package_count = sum(
                1 for line in lines if line.startswith("package ")
            )
            if package_count != 1:
                fail(
                    f"{relative} has {package_count} Kotlin package declarations; "
                    "expected exactly 1"
                )


def check_duplicate_windows() -> None:
    for relative in CRITICAL_LIMITS:
        text = read_text(relative)
        lines = text.splitlines()
        if len(lines) < DUPLICATE_WINDOW_LINES * 2:
            continue

        counts: Counter[str] = Counter()
        samples: dict[str, str] = {}
        for index in range(0, len(lines) - DUPLICATE_WINDOW_LINES + 1):
            block_lines = lines[index : index + DUPLICATE_WINDOW_LINES]
            block = "\n".join(block_lines).strip()
            if len(block) < 320:
                continue
            digest = hashlib.sha256(block.encode("utf-8")).hexdigest()
            counts[digest] += 1
            samples.setdefault(digest, block_lines[0].strip())

        duplicated = [
            (digest, count)
            for digest, count in counts.items()
            if count > MAX_IDENTICAL_WINDOWS
        ]
        if duplicated:
            digest, count = max(duplicated, key=lambda item: item[1])
            fail(
                f"{relative} contains an identical {DUPLICATE_WINDOW_LINES}-line "
                f"source window {count} times (starts with "
                f"{samples[digest]!r}); likely accidental source duplication"
            )


def git_output(*args: str) -> str | None:
    try:
        return subprocess.check_output(
            ["git", *args],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def check_parent_growth() -> None:
    parent = git_output("rev-parse", "HEAD^")
    if not parent:
        print("source-integrity: parent commit unavailable; skipping growth comparison")
        return

    changed = git_output("diff", "--name-only", parent, "HEAD")
    if not changed:
        return

    for relative in changed.splitlines():
        path = ROOT / relative
        if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
            continue

        try:
            current_size = path.stat().st_size
            previous = subprocess.check_output(
                ["git", "show", f"{parent}:{relative}"],
                cwd=ROOT,
                stderr=subprocess.DEVNULL,
            )
        except subprocess.CalledProcessError:
            continue

        previous_size = len(previous)
        if previous_size <= 0:
            continue

        growth = current_size - previous_size
        ratio = current_size / previous_size
        if growth >= MIN_GROWTH_BYTES and ratio >= MAX_GROWTH_RATIO:
            fail(
                f"{relative} grew from {previous_size} to {current_size} bytes "
                f"({ratio:.1f}x) in one commit"
            )


def main() -> None:
    check_critical_limits()
    check_duplicate_windows()
    check_parent_growth()
    print("source-integrity: OK")


if __name__ == "__main__":
    main()
