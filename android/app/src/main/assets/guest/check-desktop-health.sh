#!/bin/bash
set -euo pipefail

. /usr/local/lib/proroot/session-env.sh
load_proroot_session_env

pgrep -x kwin_wayland >/dev/null
pgrep -x plasmashell >/dev/null

test -n "${WAYLAND_DISPLAY:-}"
test -S "${XDG_RUNTIME_DIR:?}/$WAYLAND_DISPLAY"

kscreen-doctor -j > /tmp/proroot-desktop-health.json
grep -q '"connected": true' /tmp/proroot-desktop-health.json
grep -q '"enabled": true' /tmp/proroot-desktop-health.json
