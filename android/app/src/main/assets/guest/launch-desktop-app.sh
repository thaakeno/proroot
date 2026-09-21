#!/bin/bash
set -euo pipefail

app_id="${1:?desktop id required}"
runtime="/run/user/$(id -u)"
env_file="$runtime/proroot-session.env"

test -r "$env_file"
. "$env_file"

socket="$(find "$runtime" -maxdepth 1 -type s -name 'wayland-*' 2>/dev/null | head -n1)"
test -n "$socket"

export WAYLAND_DISPLAY="${socket##*/}"
export XDG_CURRENT_DESKTOP=KDE
export XDG_SESSION_DESKTOP=KDE
export XDG_SESSION_TYPE=wayland
export QT_QPA_PLATFORM=wayland
export GDK_BACKEND=wayland,x11
export SDL_VIDEODRIVER=wayland

exec gtk-launch "$app_id"
