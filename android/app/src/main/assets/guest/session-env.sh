#!/bin/bash

load_proroot_session_env() {
    local runtime env_file wayland_socket x_socket display_number xauthority
    runtime="/run/user/$(id -u)"
    env_file="$runtime/proroot-session.env"

    [[ -r "$env_file" ]] || return 1
    . "$env_file"

    wayland_socket="$(find "$runtime" -maxdepth 1 -type s -name 'wayland-*' -print -quit 2>/dev/null)"
    [[ -n "$wayland_socket" ]] || return 1
    export WAYLAND_DISPLAY="${wayland_socket##*/}"

    x_socket="$(find /tmp/.X11-unix -maxdepth 1 -type s -name 'X*' -print -quit 2>/dev/null || true)"
    if [[ -n "$x_socket" ]]; then
        display_number="${x_socket##*/X}"
        export DISPLAY=":$display_number"

        xauthority="$(find "$runtime" -maxdepth 1 -type f -name 'xauth_*' -print -quit 2>/dev/null || true)"
        if [[ -n "$xauthority" ]]; then
            export XAUTHORITY="$xauthority"
        else
            unset XAUTHORITY
        fi
    else
        unset DISPLAY XAUTHORITY
    fi

    local -a activation_vars=(
        HOME USER LOGNAME SHELL LANG LC_ALL
        XDG_CONFIG_HOME XDG_CACHE_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_RUNTIME_DIR
        XDG_SESSION_TYPE XDG_CURRENT_DESKTOP XDG_SESSION_DESKTOP
        DBUS_SYSTEM_BUS_ADDRESS WAYLAND_DISPLAY
        PIPEWIRE_RUNTIME_DIR PULSE_RUNTIME_PATH PULSE_SERVER
        QT_QPA_PLATFORM QML_IMPORT_PATH QML2_IMPORT_PATH GDK_BACKEND SDL_VIDEODRIVER CLUTTER_BACKEND
        ANLAND ANLAND_SOCKET ANLAND_NO_DRM_DEVICE ANLAND_PIPEWIRE_UNRESTRICTED
        EGL_PLATFORM MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER
        FD_FORCE_KGSL XWAYLAND_FORCE_KGSL_SURFACELESS PROROOT_REFRESH_HZ
    )
    [[ -n "${DISPLAY:-}" ]] && activation_vars+=(DISPLAY)
    [[ -n "${XAUTHORITY:-}" ]] && activation_vars+=(XAUTHORITY)

    dbus-update-activation-environment "${activation_vars[@]}" >/dev/null
}
