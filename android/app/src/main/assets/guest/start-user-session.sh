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

export PIPEWIRE_RUNTIME_DIR="$runtime"
export PULSE_RUNTIME_PATH="$pulse_dir"
export PULSE_SERVER="unix:$pulse_dir/native"

env XDG_CONFIG_HOME="$pipewire_config" pipewire >"$log_dir/pipewire.log" 2>&1 &
pipewire_pid=$!
if wait_for_socket "$runtime/pipewire-0"; then
    env XDG_CONFIG_HOME="$pipewire_config" wireplumber >"$log_dir/wireplumber.log" 2>&1 &
    wireplumber_pid=$!
    env XDG_CONFIG_HOME="$pipewire_config" pipewire-pulse >"$log_dir/pipewire-pulse.log" 2>&1 &
    pulse_pid=$!
    wait_for_socket "$pulse_dir/native" 50 || true
fi

env_file="$runtime/proroot-session.env"
persist_env() {
    local name="$1"
    if [[ -v "$name" ]]; then
        printf 'export %s=%q\n' "$name" "${!name}"
    fi
}

{
    for name in \
        HOME USER LOGNAME SHELL LANG LC_ALL \
        XDG_CONFIG_HOME XDG_CACHE_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_RUNTIME_DIR \
        XDG_SESSION_TYPE XDG_CURRENT_DESKTOP XDG_SESSION_DESKTOP \
        DBUS_SESSION_BUS_ADDRESS DBUS_SYSTEM_BUS_ADDRESS \
        PIPEWIRE_RUNTIME_DIR PULSE_RUNTIME_PATH PULSE_SERVER \
        QT_QPA_PLATFORM QT_SCALE_FACTOR GDK_BACKEND SDL_VIDEODRIVER CLUTTER_BACKEND \
        ANLAND ANLAND_SOCKET ANLAND_NO_DRM_DEVICE ANLAND_PIPEWIRE_UNRESTRICTED \
        EGL_PLATFORM MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER \
        FD_FORCE_KGSL XWAYLAND_FORCE_KGSL_SURFACELESS PROROOT_REFRESH_HZ
    do
        persist_env "$name"
    done
} >"$env_file"
chmod 0600 "$env_file"

xdg-user-dirs-update >/dev/null 2>&1 || true

session_pid=""
cleanup_session() {
    if [[ -n "$session_pid" ]]; then
        kill "$session_pid" >/dev/null 2>&1 || true
    fi
}
trap 'cleanup_session; cleanup_audio' EXIT INT TERM

# Start the full Plasma session first. A PATH shim strips the --xwayland
# argument because Xwayland currently hits Android SIGSYS under proroot.
startplasma-wayland &
session_pid=$!

healthy=0
for _ in $(seq 1 120); do
    if ! kill -0 "$session_pid" >/dev/null 2>&1; then
        break
    fi
    if pgrep -x kwin_wayland >/dev/null 2>&1 &&
       pgrep -x plasmashell >/dev/null 2>&1 &&
       find "$runtime" -maxdepth 1 -type s -name 'wayland-*' -print -quit | grep -q .; then
        healthy=1
        break
    fi
    sleep 0.1
done

if [[ "$healthy" -eq 1 ]]; then
    # Keep this supervisor alive for the lifetime of KWin even if
    # startplasma-wayland's launcher process returns after startup.
    while pgrep -x kwin_wayland >/dev/null 2>&1; do
        sleep 1
    done
    wait "$session_pid" >/dev/null 2>&1 || true
    exit 0
fi

# Full-session startup can fail on rootless/non-systemd Android environments.
# Upstream Anland itself uses this direct Wayland fallback.
kill "$session_pid" >/dev/null 2>&1 || true
wait "$session_pid" >/dev/null 2>&1 || true
pkill -x kwin_wayland >/dev/null 2>&1 || true
pkill -x plasmashell >/dev/null 2>&1 || true
sleep 0.3

kwin_wayland plasmashell &
session_pid=$!
wait "$session_pid"
