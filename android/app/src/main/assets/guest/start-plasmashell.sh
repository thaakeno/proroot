#!/bin/bash
set -u

log_dir="${XDG_RUNTIME_DIR:?}/anland-logs"
mkdir -p "$log_dir"
log="$log_dir/plasma-shell-exits.log"

sample_shell_startup() {
    local shell_pid="$1" sample=0
    local status_fields available_kb oom_score oom_adj memory_cgroup memory_root memory_stats

    memory_cgroup="$(awk -F: '$2 == "memory" { print $3; exit }' "/proc/$shell_pid/cgroup" 2>/dev/null)"
    memory_root=""
    for candidate in /dev/memcg /sys/fs/cgroup/memory; do
        if [[ -n "$memory_cgroup" && -r "$candidate$memory_cgroup/memory.usage_in_bytes" ]]; then
            memory_root="$candidate$memory_cgroup"
            break
        fi
    done

    printf '%s shell pid=%s cgroup=%s memoryCgroup=%s\n' \
        "$(date -u +%FT%TZ)" "$shell_pid" \
        "$(tr '\n' ' ' </proc/"$shell_pid"/cgroup 2>/dev/null || echo unavailable)" \
        "${memory_root:-unavailable}" >>"$log"

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
        memory_stats=unavailable
        if [[ -n "$memory_root" ]]; then
            memory_stats="$(for name in usage_in_bytes limit_in_bytes failcnt; do
                value="$(cat "$memory_root/memory.$name" 2>/dev/null || echo unavailable)"
                printf '%s=%s,' "$name" "$value"
            done)"
        fi
        printf '%s shell pid=%s sample=%s %s MemAvailableKiB=%s oomScore=%s oomAdj=%s memoryCgroup=%s\n' \
            "$(date -u +%FT%TZ)" "$shell_pid" "$sample" "$status_fields" \
            "${available_kb:-unavailable}" "$oom_score" "$oom_adj" "$memory_stats" >>"$log"
        sample=$((sample + 1))
        sleep 1
    done
}

# KWin's no-DRM path already forces Qt Quick software rendering. On this
# Android device, the separate OpenGL plasmashell is SIGKILLed during startup
# when the phone's phantom-process quota is exhausted. Starting its known-good
# software scene graph avoids the black interval while other Wayland clients
# remain free to use Mesa's Adreno driver.
unset QSG_RHI_BACKEND QSG_RENDER_LOOP QMLSCENE_DEVICE
export QT_QUICK_BACKEND=software
export QSG_INFO=1

printf '%s starting plasmashell backend=%s renderLoop=%s wayland=%s\n' \
    "$(date -u +%FT%TZ)" "$QT_QUICK_BACKEND" "${QSG_RENDER_LOOP:-default}" "${WAYLAND_DISPLAY:-unset}" >>"$log"
plasmashell &
shell_pid=$!
sample_shell_startup "$shell_pid" &
wait "$shell_pid"
status=$?
printf '%s plasmashell exited status=%s signal=%s\n' \
    "$(date -u +%FT%TZ)" "$status" "$([[ "$status" -gt 128 ]] && echo "$((status - 128))" || echo none)" >>"$log"

exit "$status"
