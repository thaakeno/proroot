#!/bin/bash
set -euo pipefail

app_id="${1:?desktop id required}"
[[ "$app_id" =~ ^[A-Za-z0-9._+-]+$ ]] || {
    echo "invalid desktop id" >&2
    exit 64
}

. /usr/local/lib/proroot/session-env.sh
load_proroot_session_env

# Generic fallback only. Normal Apps-tab launches go through the persistent
# KDE-session socket, so this path deliberately contains no per-app rewrites.
exec gtk-launch "$app_id"
