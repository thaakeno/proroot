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

# KWin's Android/Anland backend is native Wayland. Do not start Xwayland here:
# Android's seccomp rejects a syscall in the pinned Xwayland build under proroot.
# This is the same direct session shape upstream Anland uses for rootless fallback.
session_pid=""
cleanup_session() {
    if [[ -n "$session_pid" ]]; then
        kill "$session_pid" >/dev/null 2>&1 || true
    fi
    pkill -x plasmashell >/dev/null 2>&1 || true
    pkill -x kwin_wayland >/dev/null 2>&1 || true
}
trap 'cleanup_session; cleanup_audio' EXIT INT TERM

# Keep Plasma's classic non-systemd path explicit for helpers started later.
kwriteconfig6 --file startkderc --group General --key systemdBoot false >/dev/null 2>&1 || true

kwin_wayland plasmashell &
session_pid=$!

# Wait for the compositor, shell and published Wayland socket. If any one of
# these never appears, fail the session instead of reporting a fake "Running".
healthy=0
for _ in $(seq 1 200); do
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

if [[ "$healthy" -ne 1 ]]; then
    echo "Plasma Wayland compositor did not become healthy" >&2
    exit 70
fi

# Optional session helpers. Plasma/DBus will also activate these on demand; starting
# them here avoids depending on a systemd --user instance that Android does not have.
command -v kded6 >/dev/null 2>&1 && kded6 >/dev/null 2>&1 &
command -v krunner >/dev/null 2>&1 && krunner >/dev/null 2>&1 &

wait "$session_pid"

