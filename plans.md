# Execution plan

Baseline: `fix/runtime-pc-hardening` at `f275de42aa44e8fcc0953581c37e2220f1ce9016`, the earlier Build 42 phone report. The source checkout was clean before this work. The handoff reports surface destruction during a live desktop, Android crash on Stop without a native backtrace, KWin software Qt Quick, repeated `plasmashell --replace`, Code SUID sandbox failure, and uncertain fresh Brave/Firefox status. New source version: `0.2.0-dev.2+43`.

## Milestones

1. **Evidence and upstream contracts.** Inspect current Android/guest/native paths, Anland pinned source, KWin Anland patch, ProRoot decompilation where directly relevant, and primary Chromium/Mozilla reports. Record which observations are current versus historical. Acceptance: each proposed change has a falsifiable failure mechanism.
2. **Display ownership and shutdown.** Guard the process-global native consumer against stale platform views, propagate stop failures, and capture Android exception stacks. A true Android BufferQueue destruction still requires a native rebind; phone cycles remain an external validation gate.
3. **Plasma and services.** Remove D-Bus-name-triggered replacement loop; own process lifetime and establish service readiness ordering. Acceptance: no code path launches `plasmashell --replace` just because its D-Bus name is briefly absent.
4. **Generic app launch and runtime.** Route desktop/menu/Android/session-restore launches through capability policy; test generated `.desktop` entries and wrapper parsing. Acceptance: Code, Brave, and Firefox launch commands reach the wrapper in a guest-like fixture. This does not itself establish their phone runtime stability.
5. **GPU architecture.** Inspect KWin's no-DRM path and Mesa/Anland/XWayland contracts. Implement only an upstream-compatible GPU-backed path supported by evidence; do not lie about KGSL being DRM. Acceptance: renderer probe distinguishes KWin, client Vulkan, XWayland, and browser GPU processes. Final hardware claim requires device evidence.
6. **Verification and packaging.** Run focused static/unit/source checks as fixes land. Only after all source-level fixes are complete, build one APK, then verify on the target phone if access is available. If not, report the exact unverified paths.

## Risks and guardrails

- Android app sandbox may deny kernel namespaces, FUSE, netlink, and DRM interfaces. Do not promise equivalent behavior to privileged Linux.
- Native Anland changes can create use-after-free. Require lifecycle logs and a symbolized tombstone for the Stop crash before asserting root cause.
- Browser flags can hide GPU or security failures. Prefer shared capability contracts and report degraded state explicitly.
- The decompiled v1.2.8 archive is mechanically reconstructed, not original source. Keep shipped binaries pinned.
- Do not build an APK as an exploratory loop, perform visual verification, or redesign the home tab.

## Verification ledger

- [x] Freeze branch and inspect Build 42 evidence.
- [ ] Confirm upstream Anland native teardown semantics.
- [x] Guard display consumer ownership and shutdown failure paths in source; phone verification pending.
- [x] Remove D-Bus-name-triggered Plasma replacement loop.
- [x] Fix launcher routing for desktop actions and live Wayland display selection; guest verification pending.
- [x] Capture unhandled Android exceptions and expose KWin Qt Quick renderer evidence.
- [ ] Resolve KWin Qt Quick software path without fake DRM.
- [ ] Resolve browser GPU process and Firefox content failures with real device evidence.
- [ ] Build final APK once source-level work is complete.
- [ ] Verify target phone flows, or record unavailable validation.
