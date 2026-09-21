#!/bin/bash
set -euo pipefail

mkdir -p /run/dbus /run/lock
rm -f /run/dbus/system_bus_socket /run/dbus/pid /run/proroot-upower.ready

dbus_pid=""
upower_pid=""

cleanup() {
    if [[ -n "$upower_pid" ]]; then
        kill "$upower_pid" >/dev/null 2>&1 || true
    fi
    if [[ -n "$dbus_pid" ]]; then
        kill "$dbus_pid" >/dev/null 2>&1 || true
    fi
    rm -f /run/proroot-upower.ready /run/dbus/system_bus_socket /run/dbus/pid
}
trap cleanup EXIT INT TERM

dbus-daemon --system --nofork --nopidfile &
dbus_pid=$!

attempts=80
while [[ ! -S /run/dbus/system_bus_socket && "$attempts" -gt 0 ]]; do
    if ! kill -0 "$dbus_pid" >/dev/null 2>&1; then
        wait "$dbus_pid"
        exit $?
    fi
    sleep 0.1
    attempts=$((attempts - 1))
done

[[ -S /run/dbus/system_bus_socket ]]

/usr/local/lib/proroot/host-upower-bridge.py &
upower_pid=$!

attempts=80
while [[ ! -f /run/proroot-upower.ready && "$attempts" -gt 0 ]]; do
    if ! kill -0 "$upower_pid" >/dev/null 2>&1; then
        wait "$upower_pid"
        exit $?
    fi
    sleep 0.1
    attempts=$((attempts - 1))
done

[[ -f /run/proroot-upower.ready ]]
wait "$dbus_pid"
