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

pgrep -x kwin_wayland >/dev/null
pgrep -x plasmashell >/dev/null

test -n "${WAYLAND_DISPLAY:-}"
test -S "${XDG_RUNTIME_DIR:?}/$WAYLAND_DISPLAY"

dbus-send \
    --session \
    --print-reply=literal \
    --dest=org.freedesktop.DBus \
    /org/freedesktop/DBus \
    org.freedesktop.DBus.NameHasOwner \
    string:org.kde.plasmashell 2>/dev/null | grep -q 'true'

dbus-send \
    --system \
    --print-reply \
    --dest=org.freedesktop.DBus \
    /org/freedesktop/DBus \
    org.freedesktop.DBus.Peer.Ping >/dev/null

kscreen-doctor -j > /tmp/proroot-desktop-health.json
grep -q '"connected": true' /tmp/proroot-desktop-health.json
grep -q '"enabled": true' /tmp/proroot-desktop-health.json
