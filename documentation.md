# ProRoot PC workbench

Repository: `thaakeno/proroot`, branch `fix/runtime-pc-hardening`. Android Flutter/Kotlin embeds ProRoot v1.2.8, an Anland consumer/daemon, and a Debian 13 KDE/Anland producer. The target is a rootless Snapdragon 8 Elite Gen 5 phone.

Important paths: `android/app/src/main/kotlin/dev/thaakeno/proroot/display` owns the Android surface; `android/app/src/main/cpp` integrates pinned Anland; `android/app/src/main/assets/guest` starts Plasma and creates application launch policy; `lib/features/desktop` embeds the platform view.

The previous phone artifact was Build 42 at `f275de4`. CI source validation and APK build passed for that artifact, but its handoff records black display after `surfaceDestroyed`, a Stop crash without native stack, KWin software Qt Quick, a Plasma replacement loop, and Code bypassing its intended Chromium policy. Prior Brave and Firefox errors need fresh timestamps before being treated as current. The new source version is `0.2.0-dev.2+43` and has no APK yet.

Local checks: `git status --short`; source validation through `tools/validate_source_integrity.py`; focused Python tests for launcher generation; shell syntax via `bash -n` when Bash is available. Full Android builds and target-phone verification are deliberately deferred until the source-level changes are complete.
# Verification and open limits

Baseline: `f275de42aa44e8fcc0953581c37e2220f1ce9016` on `fix/runtime-pc-hardening`.

Local Python launcher tests and Bash syntax checks pass. The default Java 25.0.2 is rejected by Gradle's Kotlin parser; retrying with the installed Java 17 progressed to a missing `android/local.properties` and Flutter SDK path in this sparse checkout. The repository CI provisions Flutter and Java 17. No APK has been built and no visual verification has been performed.

There is no attached Android device. Therefore the Build 42 Stop stack, current XWayland SIGSYS syscall, browser GPU-process failures, Firefox shared-memory failure, and Plasma/Qt Quick renderer cannot be reproduced or verified on the target phone. The source fixes address proven routing and ownership defects; they do not establish universal Linux app or GPU compatibility.

Upstream XWayland's Debian packaging already includes KGSL surfaceless and dma-buf patches. Its reported SIGSYS in this app has no captured syscall number, so the source does not re-enable KWin XWayland blindly. The Chromium/Electron family launcher uses the live Wayland socket until XWayland is actually present.

Next device evidence: `android-uncaught-exception.log`, `android-runtime-failure.log`, `anland-consumer-lifecycle.log`, `anland-daemon.log`, `desktop-session.log`, `anland-logs/app-runtime-compat.log`, `proroot-app-runtime.log`, fresh Firefox/Brave/Code stderr and Chromium `chrome://gpu` output. Capture a symbolized tombstone if Android exit reason 5 occurs, and the denied syscall number for any XWayland SIGSYS.
