#!/bin/bash
set -u

log_dir="${XDG_RUNTIME_DIR:?}/anland-logs"
mkdir -p "$log_dir"
log="$log_dir/plasma-shell-exits.log"

# Keep the shell on the Adreno OpenGL path. Only its Qt Quick render loop is
# switched to basic to test the threaded scene graph seen before it exited.
unset QT_QUICK_BACKEND QMLSCENE_DEVICE
export QSG_RHI_BACKEND=opengl
export QSG_RENDER_LOOP=basic
export QSG_INFO=1

printf '%s starting plasmashell backend=%s renderLoop=%s wayland=%s\n' \
    "$(date -u +%FT%TZ)" "$QSG_RHI_BACKEND" "$QSG_RENDER_LOOP" "${WAYLAND_DISPLAY:-unset}" >>"$log"
plasmashell
status=$?
printf '%s plasmashell exited status=%s signal=%s\n' \
    "$(date -u +%FT%TZ)" "$status" "$([[ "$status" -gt 128 ]] && echo "$((status - 128))" || echo none)" >>"$log"
exit "$status"
