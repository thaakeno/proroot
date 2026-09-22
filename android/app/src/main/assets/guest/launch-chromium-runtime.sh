#!/bin/bash
set -euo pipefail

family="${1:?runtime family required}"
shift
executable="${1:?application executable required}"
shift

# Linux Chromium/Electron sandboxes require namespaces/setuid semantics that a
# rootless PRoot session cannot provide. Keep one generic compatibility layer
# for the whole runtime family instead of patching individual applications.
common=(
    --no-sandbox
    --ozone-platform=wayland
    --enable-features=WaylandWindowDecorations
)

case "$family" in
    chromium|electron)
        exec "$executable"             "${common[@]}"             --use-angle=vulkan             "$@"
        ;;
    *)
        exec "$executable" "$@"
        ;;
esac
