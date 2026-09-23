#!/bin/bash
set -u

log_dir="${XDG_RUNTIME_DIR:?}/anland-logs"
mkdir -p "$log_dir"
log="$log_dir/plasma-shell-exits.log"

# KWin and applications retain their own graphics settings. The shell's
# second Qt Quick OpenGL window is the last event logged before its D-Bus name
# disappears on this device, so isolate only plasmashell from that render path.
export QT_QUICK_BACKEND=software
export QSG_INFO=1

printf '%s starting plasmashell backend=%s wayland=%s\n' \
    "$(date -u +%FT%TZ)" "$QT_QUICK_BACKEND" "${WAYLAND_DISPLAY:-unset}" >>"$log"
plasmashell
status=$?
printf '%s plasmashell exited status=%s signal=%s\n' \
    "$(date -u +%FT%TZ)" "$status" "$([[ "$status" -gt 128 ]] && echo "$((status - 128))" || echo none)" >>"$log"
exit "$status"
