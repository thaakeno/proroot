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

dbus-send \
    --session \
    --print-reply=literal \
    --dest=org.freedesktop.DBus \
    /org/freedesktop/DBus \
    org.freedesktop.DBus.NameHasOwner \
    string:org.kde.plasmashell 2>/dev/null | grep -q 'true'
