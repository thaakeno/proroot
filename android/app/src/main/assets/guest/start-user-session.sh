#!/bin/bash
set -euo pipefail

runtime="${XDG_RUNTIME_DIR:?XDG_RUNTIME_DIR is required}"
log_dir="$runtime/anland-logs"
pipewire_config="$runtime/anland-pipewire-config"
pulse_dir="${PULSE_RUNTIME_PATH:-$runtime/anland-pulse}"

mkdir -p "$log_dir" "$pulse_dir"     "$pipewire_config/pipewire/pipewire.conf.d"     "$pipewire_config/wireplumber/wireplumber.conf.d"

wait_for_socket() {
    local socket="$1"
    local attempts="${2:-80}"
    while [[ ! -S "$socket" && "$attempts" -gt 0 ]]; do
        sleep 0.1
        attempts=$((attempts - 1))
    done
    [[ -S "$socket" ]]
}

stop_pid() {
    local pid="${1:-}"
    [[ -n "$pid" ]] || return 0
    kill "$pid" >/dev/null 2>&1 || true
}

pipewire_pid=""
wireplumber_pid=""
pulse_pid=""

cleanup_audio() {
    stop_pid "$pulse_pid"
    stop_pid "$wireplumber_pid"
    stop_pid "$pipewire_pid"
    rm -f "$runtime/pipewire-0" "$runtime/pipewire-0.lock" "$pulse_dir/native"
}
trap cleanup_audio EXIT INT TERM

# Android app UIDs do not map to a conventional login/seat. Make the private
# PipeWire graph explicitly usable by every process in this one Linux session.
cat >"$pipewire_config/pipewire/pipewire.conf.d/99-proroot-access.conf" <<'EOF'
module.access.args = {
    access.socket = {
        pipewire-0 = "unrestricted"
        pipewire-0-manager = "unrestricted"
    }
}
EOF

cat >"$pipewire_config/wireplumber/wireplumber.conf.d/99-proroot-access.conf" <<'EOF'
access.rules = [
  {
    matches = [ { access = "flatpak" } ]
    actions = {
      update-props = {
        access = "unrestricted"
        default_permissions = "all"
      }
    }
  }
]
EOF

export XDG_CONFIG_HOME="$pipewire_config"
export PIPEWIRE_RUNTIME_DIR="$runtime"
export PULSE_RUNTIME_PATH="$pulse_dir"
export PULSE_SERVER="unix:$pulse_dir/native"

pipewire >"$log_dir/pipewire.log" 2>&1 &
pipewire_pid=$!
if wait_for_socket "$runtime/pipewire-0"; then
    wireplumber >"$log_dir/wireplumber.log" 2>&1 &
    wireplumber_pid=$!
    pipewire-pulse >"$log_dir/pipewire-pulse.log" 2>&1 &
    pulse_pid=$!
    wait_for_socket "$pulse_dir/native" 50 || true
fi

env_file="$runtime/proroot-session.env"
{
    printf 'export DBUS_SESSION_BUS_ADDRESS=%q\n' "$DBUS_SESSION_BUS_ADDRESS"
    printf 'export DBUS_SYSTEM_BUS_ADDRESS=%q\n' "${DBUS_SYSTEM_BUS_ADDRESS:-unix:path=/run/dbus/system_bus_socket}"
    printf 'export XDG_RUNTIME_DIR=%q\n' "$XDG_RUNTIME_DIR"
    printf 'export PIPEWIRE_RUNTIME_DIR=%q\n' "$PIPEWIRE_RUNTIME_DIR"
    printf 'export PULSE_RUNTIME_PATH=%q\n' "$PULSE_RUNTIME_PATH"
    printf 'export PULSE_SERVER=%q\n' "$PULSE_SERVER"
    printf 'export XDG_SESSION_TYPE=wayland\n'
    printf 'export XDG_CURRENT_DESKTOP=KDE\n'
    printf 'export XDG_SESSION_DESKTOP=KDE\n'
    printf 'export QT_QPA_PLATFORM=wayland\n'
    printf 'export GDK_BACKEND=wayland,x11\n'
} >"$env_file"
chmod 0600 "$env_file"

xdg-user-dirs-update >/dev/null 2>&1 || true

set +e
startplasma-wayland
status=$?
set -e
exit "$status"
