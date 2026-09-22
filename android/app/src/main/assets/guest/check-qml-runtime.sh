#!/bin/bash
set -euo pipefail

default_qml_root=/usr/lib/aarch64-linux-gnu/qt6/qml
qml_root="$(qtpaths6 --query QT_INSTALL_QML 2>/dev/null || true)"
if [[ -z "$qml_root" || ! -d "$qml_root" ]]; then
    qml_root="$default_qml_root"
fi

echo "qt_qml_root=$qml_root"
echo "qtpaths6=$(command -v qtpaths6 || true)"
echo "qmlscene6=$(command -v qmlscene6 || true)"
dpkg-query -W -f='${binary:Package}\t${Version}\n' \
    plasma-desktoptheme qml6-module-org-kde-ksvg libplasma6 2>/dev/null || true

test -x /usr/bin/qmlscene6
test -f "$qml_root/org/kde/plasma/core/qmldir"
test -r "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so"
test -f "$qml_root/org/kde/ksvg/qmldir"
test -r "$qml_root/org/kde/ksvg/libcorebindingsplugin.so"

echo "===== org.kde.plasma.core/qmldir ====="
cat "$qml_root/org/kde/plasma/core/qmldir"
echo "===== plasma core plugin dependencies ====="
ldd "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so"
if ldd "$qml_root/org/kde/plasma/core/libcorebindingsplugin.so" | grep -q 'not found'; then
    echo "Plasma core QML plugin has unresolved shared libraries" >&2
    exit 69
fi

echo "===== KSvg plugin dependencies ====="
ldd "$qml_root/org/kde/ksvg/libcorebindingsplugin.so"
if ldd "$qml_root/org/kde/ksvg/libcorebindingsplugin.so" | grep -q 'not found'; then
    echo "KSvg QML plugin has unresolved shared libraries" >&2
    exit 69
fi

echo "Plasma QML files and shared-library dependencies OK"
if [[ "${1:-}" == "--files-only" ]]; then
    exit 0
fi

echo "Executable QML probe requested"
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
Item { Component.onCompleted: Qt.quit() }
EOF
set +e
XDG_RUNTIME_DIR="$runtime" QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software QML_IMPORT_TRACE=1 \
    qmlscene6 -I "$qml_root" "$probe" >"$log" 2>&1
rc=$?
set -e
cat "$log"
exit "$rc"
