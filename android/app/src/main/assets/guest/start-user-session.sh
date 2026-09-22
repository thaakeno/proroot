#!/bin/bash
set -euo pipefail

runtime="${XDG_RUNTIME_DIR:?XDG_RUNTIME_DIR is required}"
log_dir="$runtime/anland-logs"
pipewire_config="$runtime/anland-pipewire-config"
pulse_dir="${PULSE_RUNTIME_PATH:-$runtime/anland-pulse}"

# KDE ships both org.kde.plasma.core and org.kde.ksvg with a QML plugin named
# libcorebindingsplugin.so. Normal glibc can load both absolute paths at once,
# but ProRoot's clean-room linker can alias same-basename plugin loads. That
# leaves KSvg's C++ types unregistered even though the package and libraries are
# present, producing a black Plasma shell ("KSvg.SvgItem is not a type").
# Give the two plugins unique filenames and make their qmldir files point at
# those copies. This is idempotent and is reapplied after package upgrades.
prepare_unique_qml_plugin() {
    local module="$1"
    local unique="$2"
    local qml_import_path="${QML_IMPORT_PATH:-}"
    local qml_root="${qml_import_path%%:*}"

    if [[ -z "$qml_root" || ! -d "$qml_root" ]]; then
        qml_root=/usr/lib/aarch64-linux-gnu/qt6/qml
    fi

    local module_dir="$qml_root/$module"
    local original="$module_dir/libcorebindingsplugin.so"
    local renamed="$module_dir/lib${unique}.so"
    local qmldir="$module_dir/qmldir"

    [[ -f "$original" && -f "$qmldir" ]] || return 0

    cp -f "$original" "$renamed"
    sed -i \
        -e "s/^linktarget corebindingsplugin$/linktarget $unique/" \
        -e "s/^optional plugin corebindingsplugin$/optional plugin $unique/" \
        -e "s/^plugin corebindingsplugin$/plugin $unique/" \
        -e '/^prefer /d' \
        "$qmldir"

    if ! grep -Eq "^(optional )?plugin $unique$" "$qmldir"; then
        echo "Failed to retarget QML plugin for $module" >&2
        return 1
    fi

    echo "QML plugin isolated: $module -> lib${unique}.so"
}

prepare_unique_qml_plugin org/kde/plasma/core proroot_plasma_corebindingsplugin
prepare_unique_qml_plugin org/kde/ksvg proroot_ksvg_corebindingsplugin

mkdir -p "$log_dir" "$pulse_dir" \
    "$pipewire_config/pipewire/pipewire.conf.d" \
    "$pipewire_config/wireplumber/wireplumber.conf.d"

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
session_pid=""
launcher_pid=""

cleanup_audio() {
    stop_pid "$pulse_pid"
    stop_pid "$wireplumber_pid"
    stop_pid "$pipewire_pid"
    rm -f "$runtime/pipewire-0" "$runtime/pipewire-0.lock" "$pulse_dir/native"
}

cleanup_session() {
    stop_pid "$launcher_pid"
    rm -f "$runtime/proroot-app-launcher.sock"
    stop_pid "$session_pid"
    if [[ -n "$session_pid" ]]; then
        wait "$session_pid" >/dev/null 2>&1 || true
    fi
}
trap 'cleanup_session; cleanup_audio' EXIT INT TERM

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

# Prefer native Wayland for browser/Electron families globally. This is session
# policy, not an Apps-tab per-application rewrite.
export MOZ_ENABLE_WAYLAND=1
export ELECTRON_OZONE_PLATFORM_HINT=wayland

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
        QT_QPA_PLATFORM QML_IMPORT_PATH QML2_IMPORT_PATH QML_IMPORT_TRACE QT_DEBUG_PLUGINS QT_SCALE_FACTOR \
        GDK_BACKEND SDL_VIDEODRIVER CLUTTER_BACKEND \
        ANLAND ANLAND_SOCKET ANLAND_NO_DRM_DEVICE ANLAND_PIPEWIRE_UNRESTRICTED \
        EGL_PLATFORM MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER \
        FD_FORCE_KGSL PROROOT_REFRESH_HZ         MOZ_ENABLE_WAYLAND ELECTRON_OZONE_PLATFORM_HINT
    do
        persist_env "$name"
    done
} >"$env_file"
chmod 0600 "$env_file"

xdg-user-dirs-update >/dev/null 2>&1 || true
kwriteconfig6 --file startkderc --group General --key systemdBoot false >/dev/null 2>&1 || true

# This is a phone-hosted compositor with a software Qt Quick path. Avoid desktop
# effects/indexers that burn CPU/GPU for almost no value on a 1200px mobile view.
kwriteconfig6 --file baloofilerc --group "Basic Settings" --key Indexing-Enabled false >/dev/null 2>&1 || true
kwriteconfig6 --file kwinrc --group Plugins --key blurEnabled false >/dev/null 2>&1 || true
kwriteconfig6 --file kwinrc --group Plugins --key contrastEnabled false >/dev/null 2>&1 || true
kwriteconfig6 --file kdeglobals --group KDE --key AnimationDurationFactor 0.65 >/dev/null 2>&1 || true

/usr/local/lib/proroot/check-qml-runtime.sh --files-only

plasma_ready() {
    [[ -n "$session_pid" ]] || return 1
    kill -0 "$session_pid" >/dev/null 2>&1 || return 1
    find "$runtime" -maxdepth 1 -type s -name 'wayland-*' -print -quit | grep -q . || return 1
    dbus-send \
        --session \
        --print-reply=literal \
        --dest=org.freedesktop.DBus \
        /org/freedesktop/DBus \
        org.freedesktop.DBus.NameHasOwner \
        string:org.kde.plasmashell 2>/dev/null | grep -q 'true'
}

wait_for_plasma() {
    local attempts="${1:-250}"
    while [[ "$attempts" -gt 0 ]]; do
        if [[ -n "$session_pid" ]] && ! kill -0 "$session_pid" >/dev/null 2>&1; then
            return 1
        fi
        if plasma_ready; then
            return 0
        fi
        sleep 0.1
        attempts=$((attempts - 1))
    done
    return 1
}

# Anland's minimal Plasma path: KWin owns Wayland and starts plasmashell.
# This avoids ksmserver/kcminit, which are the processes that abort in the
# full startplasma-wayland session under this rootless Android runtime.
kwin_wayland plasmashell &
session_pid=$!

if ! wait_for_plasma 300; then
    echo "KWin/Plasma shell did not become healthy" >&2
    exit 70
fi

# Launch Android-requested apps from this exact KDE session instead of spawning
# a second ProRoot runtime. This keeps Wayland, DBus, audio and GPU environment
# identical to launching the same icon from Plasma.
wayland_socket="$(find "$runtime" -maxdepth 1 -type s -name 'wayland-*' -print -quit)"
if [[ -n "$wayland_socket" ]]; then
    export WAYLAND_DISPLAY="${wayland_socket##*/}"
    /usr/local/lib/proroot/desktop-launcher-bridge.py         >"$log_dir/desktop-launcher.log" 2>&1 &
    launcher_pid=$!
    for _ in {1..40}; do
        [[ -S "$runtime/proroot-app-launcher.sock" ]] && break
        kill -0 "$launcher_pid" >/dev/null 2>&1 || break
        sleep 0.05
    done
fi

wait "$session_pid"
