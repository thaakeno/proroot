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

unset DISPLAY PULSE_SERVER LD_PRELOAD LD_LIBRARY_PATH
unset ANLAND_NO_DRM_DEVICE ANLAND_DRM_DEVICE EGL_PLATFORM
unset MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER
unset FD_FORCE_KGSL XWAYLAND_FORCE_KGSL_SURFACELESS

mkdir -p "$runtime" /tmp/.X11-unix
chmod 0700 "$runtime"
chmod 1777 /tmp /tmp/.X11-unix
rm -f "$runtime"/wayland-* "$runtime"/proroot-session.env

export XDG_RUNTIME_DIR="$runtime"
export XDG_CURRENT_DESKTOP=KDE
export XDG_SESSION_DESKTOP=KDE
export XDG_SESSION_TYPE=wayland
export QT_QPA_PLATFORM=wayland
export QT_SCALE_FACTOR="$scale"

export ANLAND=1
export ANLAND_SOCKET=/tmp/anland/display_daemon.sock
export ANLAND_NO_DRM_DEVICE=1
export ANLAND_PIPEWIRE_UNRESTRICTED=1

export EGL_PLATFORM=surfaceless
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export TURNIP_KMD=kgsl
export GALLIUM_DRIVER=freedreno
export FD_FORCE_KGSL=1
export XWAYLAND_FORCE_KGSL_SURFACELESS=1
export PROROOT_REFRESH_HZ="$refresh"

exec dbus-run-session -- /usr/local/lib/proroot/start-user-session.sh
