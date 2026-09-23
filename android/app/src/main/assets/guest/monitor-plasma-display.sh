#!/bin/bash
set -u

log_dir="${XDG_RUNTIME_DIR:?}/anland-logs"
log="$log_dir/plasma-display.log"
mkdir -p "$log_dir"

shell_owned() {
    dbus-send --session --print-reply=literal --reply-timeout=2000 \
        --dest=org.freedesktop.DBus /org/freedesktop/DBus \
        org.freedesktop.DBus.NameHasOwner string:org.kde.plasmashell \
        2>/dev/null | grep -q 'true'
}

shell_responds() {
    dbus-send --session --print-reply --reply-timeout=2000 \
        --dest=org.kde.plasmashell /PlasmaShell \
        org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1
}

missing=0
restarts=0
sample=0
printf '%s monitor started wayland=%s\n' "$(date -u +%FT%TZ)" "${WAYLAND_DISPLAY:-unset}" >>"$log"

while :; do
    if shell_owned; then
        missing=0
        if shell_responds; then state=responsive; else state=unresponsive; fi
    else
        missing=$((missing + 1))
        state=missing
    fi

    printf '%s shell=%s missingSamples=%s\n' \
        "$(date -u +%FT%TZ)" "$state" "$missing" >>"$log"

    # KDE's read-only scripting API distinguishes an absent desktop
    # containment from a live containment that has stopped drawing.
    sample=$((sample + 1))
    if [[ "$state" == responsive && $((sample % 6)) -eq 1 ]]; then
        layout="$(dbus-send --session --print-reply=literal --reply-timeout=2000 \
            --dest=org.kde.plasmashell /PlasmaShell \
            org.kde.PlasmaShell.evaluateScript \
            'string:print("desktops=" + desktops().length + " panels=" + panels().length)' \
            2>&1)"
        printf '%s containment=%s\n' "$(date -u +%FT%TZ)" "${layout//$'\n'/ }" >>"$log"
    fi

    # KWin can keep drawing application windows after plasmashell exits. Recover
    # only when its D-Bus name stays absent; an alive but black containment needs
    # the captured shell/KWin logs, not a blind restart loop.
    if [[ "$missing" -ge 3 && "$restarts" -lt 3 ]]; then
        restarts=$((restarts + 1))
        printf '%s restarting absent plasmashell attempt=%s\n' \
            "$(date -u +%FT%TZ)" "$restarts" >>"$log"
        plasmashell >>"$log_dir/plasmashell-restart.log" 2>&1 &
        missing=0
        sleep 10
    fi

    sleep 5
done
