#!/bin/bash
set -euo pipefail

refresh="${1:-120}"
scale="${2:-1.0}"
uid="$(id -u)"
runtime="/run/user/$uid"

export HOME=/home/linux
export USER=linux
export LOGNAME=linux
export SHELL=/bin/bash
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export XDG_CONFIG_HOME=/home/linux/.config
export XDG_CACHE_HOME=/home/linux/.cache
export XDG_DATA_HOME=/home/linux/.local/share
export XDG_STATE_HOME=/home/linux/.local/state

# Never inherit Android/launcher graphics state. The session owns this environment.
unset DISPLAY PULSE_SERVER PIPEWIRE_RUNTIME_DIR PULSE_RUNTIME_PATH
unset LD_PRELOAD LD_LIBRARY_PATH
unset ANLAND_NO_DRM_DEVICE ANLAND_DRM_DEVICE EGL_PLATFORM
unset ANLAND_PIPEWIRE_UNRESTRICTED ANLAND_SOFTWARE_SESSION
unset MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER
unset FD_FORCE_KGSL XWAYLAND_FORCE_KGSL_SURFACELESS

mkdir -p     "$runtime"     /tmp/.X11-unix     "$XDG_CONFIG_HOME"     "$XDG_CACHE_HOME"     "$XDG_DATA_HOME"     "$XDG_STATE_HOME"
chmod 0700 "$runtime"
chmod 1777 /tmp /tmp/.X11-unix
rm -f     "$runtime"/wayland-*     "$runtime"/xauth_*     "$runtime"/proroot-session.env     /tmp/.X11-unix/X*     /tmp/.X*-lock

export XDG_RUNTIME_DIR="$runtime"
export XDG_CURRENT_DESKTOP=KDE
export XDG_SESSION_DESKTOP=KDE
export XDG_SESSION_TYPE=wayland
export QT_QPA_PLATFORM=wayland
export QT_SCALE_FACTOR="$scale"
export GDK_BACKEND=wayland,x11
export SDL_VIDEODRIVER=wayland
export CLUTTER_BACKEND=wayland

export ANLAND=1
export ANLAND_SOCKET=/tmp/anland/display_daemon.sock
export ANLAND_NO_DRM_DEVICE=1
export ANLAND_PIPEWIRE_UNRESTRICTED=1
export EGL_PLATFORM=surfaceless

if [[ -r /dev/kgsl-3d0 ]]; then
    export MESA_LOADER_DRIVER_OVERRIDE=kgsl
    export TURNIP_KMD=kgsl
    export GALLIUM_DRIVER=freedreno
    export FD_FORCE_KGSL=1
    export XWAYLAND_FORCE_KGSL_SURFACELESS=1
fi

export PROROOT_REFRESH_HZ="$refresh"
export PIPEWIRE_RUNTIME_DIR="$runtime"
export PULSE_RUNTIME_PATH="$runtime/anland-pulse"
export PULSE_SERVER="unix:$PULSE_RUNTIME_PATH/native"
export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket

exec dbus-run-session -- /usr/local/lib/proroot/start-user-session.sh
