#!/bin/bash
set -euo pipefail

uid="$(id -u)"
if user="$(id -un 2>/dev/null)"; then
    :
else
    user="${USER:-linux}"
    printf 'system-services: NSS username lookup unavailable for uid=%s; using USER=%s\n' "$uid" "$user" >&2
fi
printf 'system-services uid=%s user=%s\n' "$uid" "$user"
if [[ "$uid" -eq 0 ]]; then
    echo "system services must use the desktop user identity" >&2
    exit 64
fi

mkdir -p /run/dbus /run/lock
rm -f \
    /run/dbus/system_bus_socket \
    /run/dbus/pid \
    /run/proroot-upower.ready \
    /run/proroot-login1.ready \
    /run/proroot-system-activation.ready

dbus_pid=""
upower_pid=""
login1_pid=""

cleanup() {
    if [[ -n "$login1_pid" ]]; then
        kill "$login1_pid" >/dev/null 2>&1 || true
    fi
    if [[ -n "$upower_pid" ]]; then
        kill "$upower_pid" >/dev/null 2>&1 || true
    fi
    if [[ -n "$dbus_pid" ]]; then
        kill "$dbus_pid" >/dev/null 2>&1 || true
    fi
    rm -f \
        /run/proroot-upower.ready \
        /run/proroot-login1.ready \
        /run/proroot-system-activation.ready \
        /run/dbus/system_bus_socket \
        /run/dbus/pid
}
trap cleanup EXIT INT TERM

wait_for_file() {
    local file="$1"
    local pid="$2"
    local attempts=80

    while [[ ! -e "$file" && "$attempts" -gt 0 ]]; do
        if ! kill -0 "$pid" >/dev/null 2>&1; then
            wait "$pid"
            return $?
        fi
        sleep 0.1
        attempts=$((attempts - 1))
    done
    [[ -e "$file" ]]
}

dbus-daemon --nofork --nopidfile --config-file=/usr/local/lib/proroot/system-bus.conf &
dbus_pid=$!
wait_for_file /run/dbus/system_bus_socket "$dbus_pid"

/usr/local/lib/proroot/host-upower-bridge.py &
upower_pid=$!
wait_for_file /run/proroot-upower.ready "$upower_pid"

/usr/local/lib/proroot/host-login1-bridge.py &
login1_pid=$!
wait_for_file /run/proroot-login1.ready "$login1_pid"

activatable="$(
    dbus-send \
        --system \
        --print-reply=literal \
        --dest=org.freedesktop.DBus \
        /org/freedesktop/DBus \
        org.freedesktop.DBus.ListActivatableNames
)"
grep -q 'org.freedesktop.PolicyKit1' <<<"$activatable"
grep -q 'org.freedesktop.PackageKit' <<<"$activatable"
printf '%s\n' "$$" >/run/proroot-system-activation.ready

wait "$dbus_pid"
