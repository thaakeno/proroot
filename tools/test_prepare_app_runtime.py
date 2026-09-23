"""Regression tests for generic desktop-entry runtime routing."""

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/guest/prepare-app-runtime.py"
SPEC = importlib.util.spec_from_file_location("prepare_app_runtime", SCRIPT)
assert SPEC and SPEC.loader
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class PrepareAppRuntimeTest(unittest.TestCase):
    def test_env_options_resolve_real_executable(self) -> None:
        self.assertEqual(
            runtime.executable_from_exec("env -u WAYLAND_DISPLAY MODE=fast /opt/vendor/app %U"),
            "/opt/vendor/app",
        )

    def test_electron_binary_is_classified_without_product_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "generic-editor"
            binary.write_bytes(b"\x7fELF" + b"\0" * 64 + b"resources/app.asar")
            self.assertEqual(runtime.classify(str(binary)), "electron")

    def test_packaged_electron_binary_is_classified_without_embedded_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "generic-editor"
            binary.write_bytes(b"\x7fELF" + b"\0" * 64)
            manifest = root / "resources/app/package.json"
            manifest.parent.mkdir(parents=True)
            manifest.write_text('{"name":"generic-editor"}')
            (root / "chrome-sandbox").write_bytes(b"helper")
            self.assertEqual(runtime.classify(str(binary)), "electron")

    def test_desktop_actions_use_the_same_runtime_family(self) -> None:
        desktop = (
            "[Desktop Entry]\nExec=/opt/browser %U\n"
            "[Desktop Action Private]\nExec=/opt/browser --private %U\n"
        )
        generated = runtime.override_exec(desktop, "chromium")
        self.assertIn("Exec=/usr/local/lib/proroot/launch-app-runtime.sh chromium /opt/browser %U", generated)
        self.assertIn("Exec=/usr/local/lib/proroot/launch-app-runtime.sh chromium /opt/browser --private %U", generated)

    @unittest.skipUnless(os.name == "posix", "guest desktop entries use POSIX paths")
    def test_shell_launcher_is_followed_and_overridden(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "vendor-app"
            binary.write_bytes(b"\x7fELF" + b"\0" * 64 + b"electron_run_as_node")
            launcher = root / "vendor-launcher"
            launcher.write_text(f"#!/bin/sh\nexec {binary} \"$@\"\n")
            desktop_dir = root / "applications"
            desktop_dir.mkdir()
            (desktop_dir / "vendor.desktop").write_text(
                "[Desktop Entry]\nName=Vendor\n"
                f"Exec=env -u WAYLAND_DISPLAY MODE=fast {launcher} %U\n"
                "Type=Application\n[Desktop Action NewWindow]\n"
                f"Exec={launcher} --new-window\n"
            )
            override_dir = root / "home/.local/share/applications"
            old_search, old_override = runtime.SEARCH_DIRS, runtime.OVERRIDE_DIR
            try:
                runtime.SEARCH_DIRS = (desktop_dir,)
                runtime.OVERRIDE_DIR = override_dir
                self.assertEqual(runtime.main(), 0)
            finally:
                runtime.SEARCH_DIRS, runtime.OVERRIDE_DIR = old_search, old_override
            generated = (override_dir / "vendor.desktop").read_text()
            self.assertIn("launch-app-runtime.sh electron env -u WAYLAND_DISPLAY", generated)
            self.assertIn("launch-app-runtime.sh electron " + str(launcher) + " --new-window", generated)


if __name__ == "__main__":
    unittest.main()
