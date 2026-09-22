#!/bin/bash
set -euo pipefail

family="${1:?runtime family required}"
shift
executable="${1:?application executable required}"
shift

# Refresh the live KDE environment at launch time. In particular, KWin reserves
# an X11 socket before lazy-starting Xwayland, so the first X11 client can wake
# Xwayland without hard-coding :0.
if [[ -r /usr/local/lib/proroot/session-env.sh ]]; then
    # shellcheck disable=SC1091
    . /usr/local/lib/proroot/session-env.sh
    load_proroot_session_env
fi

unset LIBGL_ALWAYS_SOFTWARE
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export TURNIP_KMD=kgsl
export GALLIUM_DRIVER=freedreno
export FD_FORCE_KGSL=1

case "$family" in
    chromium|electron)
        # Chromium's native Wayland path assumes a DRM-enumerable render node.
        # Android exposes Adreno through KGSL instead. Use KWin's patched
        # Xwayland bridge: Xwayland itself is accelerated by KGSL surfaceless
        # EGL and refuses llvmpipe/softpipe, while Chromium renders with
        # ANGLE -> Vulkan -> Turnip.
        if [[ -z "${DISPLAY:-}" ]]; then
            echo "No KWin Xwayland display is available" >&2
            exit 73
        fi
        export ELECTRON_OZONE_PLATFORM_HINT=x11
        unset WAYLAND_DISPLAY
        exec "$executable" \
            --no-sandbox \
            --disable-gpu-sandbox \
            --ozone-platform=x11 \
            --use-gl=angle \
            --use-angle=vulkan \
            --enable-features=Vulkan,DefaultANGLEVulkan,VulkanFromANGLE \
            --ignore-gpu-blocklist \
            --enable-gpu-rasterization \
            --disable-software-rasterizer \
            --disable-gpu-memory-buffer-compositor-resources \
            --disable-gpu-memory-buffer-video-frames \
            --disable-video-capture-use-gpu-memory-buffer \
            --disable-zero-copy \
            "$@"
        ;;
    mozilla)
        # Gecko's Linux sandboxes depend on seccomp/namespace behavior that an
        # Android app sandbox cannot expose through rootless PRoot. Disable
        # those process sandboxes explicitly instead of letting child processes
        # crash, while forcing hardware WebRender on the KGSL renderer.
        export MOZ_ENABLE_WAYLAND=1
        export MOZ_WEBRENDER=1
        export MOZ_ACCELERATED=1
        export MOZ_DISABLE_CONTENT_SANDBOX=1
        export MOZ_DISABLE_GMP_SANDBOX=1
        export MOZ_DISABLE_GPU_SANDBOX=1
        export MOZ_DISABLE_RDD_SANDBOX=1
        export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1
        export MOZ_DISABLE_UTILITY_SANDBOX=1
        exec "$executable" "$@"
        ;;
    *)
        exec "$executable" "$@"
        ;;
esac
