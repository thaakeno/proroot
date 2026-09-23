#!/bin/bash
set -u

log_dir="${XDG_RUNTIME_DIR:?}/anland-logs"
mkdir -p "$log_dir"
log="$log_dir/plasma-shell-exits.log"

sample_shell_startup() {
    local shell_pid="$1" sample=0
    local status_fields available_kb oom_score oom_adj events

    printf '%s shell pid=%s cgroup=%s\n' \
        "$(date -u +%FT%TZ)" "$shell_pid" \
        "$(tr '\n' ' ' </proc/"$shell_pid"/cgroup 2>/dev/null || echo unavailable)" >>"$log"

    # The GPU shell was SIGKILLed nine seconds after launch in build 47.
    # Sample that startup window without repeatedly probing the live guest.
    while (( sample < 15 )); do
        if [[ ! -r "/proc/$shell_pid/status" ]]; then
            printf '%s shell pid=%s proc=gone sample=%s\n' \
                "$(date -u +%FT%TZ)" "$shell_pid" "$sample" >>"$log"
            return
        fi
        status_fields="$(awk '/^(VmRSS|VmHWM|RssAnon|RssFile|VmSwap|Threads):/ { printf "%s%s%s ", $1, $2, $3 }' "/proc/$shell_pid/status" 2>/dev/null)"
        available_kb="$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo 2>/dev/null)"
        oom_score="$(cat "/proc/$shell_pid/oom_score" 2>/dev/null || echo unavailable)"
        oom_adj="$(cat "/proc/$shell_pid/oom_score_adj" 2>/dev/null || echo unavailable)"
        events="$(tr '\n' ',' </sys/fs/cgroup/memory.events 2>/dev/null || echo unavailable)"
        printf '%s shell pid=%s sample=%s %s MemAvailableKiB=%s oomScore=%s oomAdj=%s memoryEvents=%s\n' \
            "$(date -u +%FT%TZ)" "$shell_pid" "$sample" "$status_fields" \
            "${available_kb:-unavailable}" "$oom_score" "$oom_adj" "$events" >>"$log"
        sample=$((sample + 1))
        sleep 1
    done
}

# Keep the shell on the Adreno OpenGL path. Only its Qt Quick render loop is
# switched to basic to test the threaded scene graph seen before it exited.
unset QT_QUICK_BACKEND QMLSCENE_DEVICE
export QSG_RHI_BACKEND=opengl
export QSG_RENDER_LOOP=basic
export QSG_INFO=1

printf '%s starting plasmashell backend=%s renderLoop=%s wayland=%s\n' \
    "$(date -u +%FT%TZ)" "$QSG_RHI_BACKEND" "$QSG_RENDER_LOOP" "${WAYLAND_DISPLAY:-unset}" >>"$log"
plasmashell &
shell_pid=$!
sample_shell_startup "$shell_pid" &
wait "$shell_pid"
status=$?
printf '%s plasmashell exited status=%s signal=%s\n' \
    "$(date -u +%FT%TZ)" "$status" "$([[ "$status" -gt 128 ]] && echo "$((status - 128))" || echo none)" >>"$log"

if [[ "$status" -eq 137 ]]; then
    # Build 46 kept this shell alive with software Qt Quick. Preserve the GPU
    # failure trace, then restore the desktop without restarting KWin or apps.
    unset QSG_RHI_BACKEND QSG_RENDER_LOOP
    export QT_QUICK_BACKEND=software
    printf '%s restarting plasmashell backend=software after GPU SIGKILL\n' \
        "$(date -u +%FT%TZ)" >>"$log"
    plasmashell
    status=$?
    printf '%s fallback plasmashell exited status=%s\n' \
        "$(date -u +%FT%TZ)" "$status" >>"$log"
fi
exit "$status"
