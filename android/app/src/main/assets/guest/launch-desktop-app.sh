#!/bin/bash
set -euo pipefail

app_id="${1:?desktop id required}"
. /usr/local/lib/proroot/session-env.sh
load_proroot_session_env

# Keep app launches native-Wayland. Xwayland is intentionally not part of this
# Android session because it currently hits Android seccomp under proroot.
export MOZ_ENABLE_WAYLAND=1
export ELECTRON_OZONE_PLATFORM_HINT=wayland

case "$app_id" in
    brave-browser|brave-browser-stable)
        exec brave-browser-stable             --no-sandbox             --ozone-platform=wayland             --enable-features=WaylandWindowDecorations
        ;;
    firefox-esr|firefox)
        exec firefox-esr
        ;;
    code|code-url-handler)
        exec code             --no-sandbox             --ozone-platform=wayland             --enable-features=WaylandWindowDecorations
        ;;
    *)
        exec gtk-launch "$app_id"
        ;;
esac
