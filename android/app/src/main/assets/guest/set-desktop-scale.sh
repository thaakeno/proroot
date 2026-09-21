#!/bin/bash
set -euo pipefail

scale="${1:?scale required}"
. /usr/local/lib/proroot/session-env.sh
load_proroot_session_env

exec python3 /usr/local/lib/proroot/set-desktop-scale.py "$scale"
