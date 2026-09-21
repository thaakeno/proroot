#!/bin/bash
set -euo pipefail

env_file="$XDG_RUNTIME_DIR/proroot-session.env"
{
    printf 'export DBUS_SESSION_BUS_ADDRESS=%q\n' "$DBUS_SESSION_BUS_ADDRESS"
    printf 'export XDG_RUNTIME_DIR=%q\n' "$XDG_RUNTIME_DIR"
} >"$env_file"
chmod 0600 "$env_file"

pipewire >"$XDG_RUNTIME_DIR/pipewire.log" 2>&1 &
wireplumber >"$XDG_RUNTIME_DIR/wireplumber.log" 2>&1 &

exec startplasma-wayland
