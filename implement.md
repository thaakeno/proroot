# Implementation contract

Work from `plans.md` and continue across milestones without asking for routine approval. Preserve the clean baseline and make narrow, evidence-backed edits. Add regression tests for failure mechanisms that can be tested locally. Run focused validation after each milestone and update the verification ledger. Do not convert a successful build into a claim that desktop, GPU, browser, or shutdown behavior works on the phone. Hold the APK build until the source fixes are complete. Keep user-visible updates concise and factual. Completion requires measured display stability, safe Stop, stable Plasma, hardware-renderer evidence at each layer, and fresh Brave/Firefox/Code launch and browsing tests on the target device; otherwise report the precise unresolved gate.
# Implementation notes

The Android display consumer is process-global in upstream Anland. The registry now tracks its owning view separately from the most recently attached Flutter platform view. A stale view clears its local state when rebind starts and cannot stop the newer consumer. The Stop path checks the posted native stop result and keeps the daemon alive if that stop fails or times out.

Build 42's Android exit reason 4 means an unhandled managed exception. The Activity now writes uncaught exception stacks to `android-uncaught-exception.log`; the runtime coroutine handler records background failures. All shutdown paths use ordered, guarded cleanup, and Reset declines to delete the rootfs after a failed shutdown.

The KWin wrapper currently suppresses XWayland after a SIGSYS report. Chromium/Electron launch policy previously required `DISPLAY` and forced X11, so the two scripts contradicted each other. The family wrapper now selects an existing X11 socket or the live Wayland socket. Desktop action commands receive the same policy as the main desktop entry. The Android app-launch socket stays open until its reply is read.

The pinned Debian KWin package forces Qt Quick software rendering when it lacks a DRM device. This is an upstream package-level limitation; no local environment flag can turn `/dev/kgsl-3d0` into a DRM render node. `QSG_INFO=1` now records the scene graph choice and diagnostics report the known software message. Full GPU compatibility requires a validated KWin allocator/context change and device evidence.
