#!/bin/bash
set -euo pipefail

qml_root=/usr/lib/aarch64-linux-gnu/qt6/qml
export QML_IMPORT_PATH="$qml_root"
export QML2_IMPORT_PATH="$qml_root"

test -x /usr/bin/qmlscene6
test -f "$qml_root/org/kde/plasma/core/qmldir"
test -r "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so"
test -f "$qml_root/org/kde/ksvg/qmldir"
test -r "$qml_root/org/kde/ksvg/libcorebindingsplugin.so"

if ldd "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so" | grep -q 'not found'; then
    echo "Plasma core QML plugin has unresolved shared libraries" >&2
    ldd "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so" >&2 || true
    exit 69
fi

if [[ "${1:-}" == "--files-only" ]]; then
    exit 0
fi

runtime="${XDG_RUNTIME_DIR:-/tmp/proroot-qml-runtime-$(id -u)}"
mkdir -p "$runtime"
chmod 0700 "$runtime"
probe="$(mktemp /tmp/proroot-qml-probe.XXXXXX.qml)"
log=/tmp/proroot-qml-probe.log
trap 'rm -f "$probe"' EXIT

cat >"$probe" <<'EOF'
import QtQuick
import org.kde.plasma.core as PlasmaCore
import org.kde.ksvg as KSvg

Item {
    Component.onCompleted: Qt.quit()
}
EOF

set +e
XDG_RUNTIME_DIR="$runtime" \
QT_QPA_PLATFORM=offscreen \
QT_QUICK_BACKEND=software \
qmlscene6 -I "$qml_root" "$probe" >"$log" 2>&1
rc=$?
set -e

cat "$log"
if [[ "$rc" -ne 0 ]]; then
    echo "Plasma QML import probe failed with exit $rc" >&2
    exit "$rc"
fi

echo "Plasma QML imports OK"
