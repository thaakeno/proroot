#!/bin/bash
set -euo pipefail

. /usr/local/lib/proroot/session-env.sh
load_proroot_session_env

qml_root=/usr/lib/aarch64-linux-gnu/qt6/qml
test -d "$qml_root"
test -f "$qml_root/org/kde/plasma/core/qmldir"
test -f "$qml_root/org/kde/ksvg/qmldir"
test -r "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so"
test -r "$qml_root/org/kde/ksvg/libcorebindingsplugin.so"

# DesktopSession already verifies that both sides of the Anland transport are
# connected. Inside the guest, the Wayland socket plus plasmashell owning its
# D-Bus name are the stable readiness signals. Avoid procps/kscreen probes here:
# they add unrelated raw-syscall and service dependencies to the critical path.
test -n "${WAYLAND_DISPLAY:-}"
test -S "${XDG_RUNTIME_DIR:?}/$WAYLAND_DISPLAY"

# PRoot deliberately has no /dev/dri render node. The supported Anland path
# uses surfaceless EGL while Mesa talks directly to KGSL. Verify the actual GPU,
# not a fake DRM pathname, and reject llvmpipe/lavapipe/softpipe.
test "${ANLAND_NO_DRM_DEVICE:-}" = "1"
test -z "${ANLAND_DRM_DEVICE:-}"
test -r /dev/kgsl-3d0
test "${MESA_LOADER_DRIVER_OVERRIDE:-}" = "kgsl"
test "${GALLIUM_DRIVER:-}" = "freedreno"

gpu_summary="$(
    MESA_LOADER_DRIVER_OVERRIDE=kgsl \
    TURNIP_KMD=kgsl \
    GALLIUM_DRIVER=freedreno \
    FD_FORCE_KGSL=1 \
      vulkaninfo --summary 2>&1
)"
if printf '%s\n' "$gpu_summary" | grep -Eiq 'llvmpipe|lavapipe|softpipe'; then
    echo "Software GPU renderer detected; refusing degraded desktop" >&2
    exit 1
fi
printf '%s\n' "$gpu_summary" | grep -Eiq 'Adreno|turnip'

dbus-send \
    --session \
    --print-reply=literal \
    --dest=org.freedesktop.DBus \
    /org/freedesktop/DBus \
    org.freedesktop.DBus.NameHasOwner \
    string:org.kde.plasmashell 2>/dev/null | grep -q 'true'
