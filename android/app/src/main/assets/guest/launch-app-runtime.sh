#!/bin/bash
set -euo pipefail

family="${1:?runtime family required}"
shift
executable="${1:?application executable required}"
shift

# Desktop Exec may begin with `env NAME=value` or `env -u NAME`. Keep that
# environment when placing the family policy before the actual program.
original_env=()
if [[ "$executable" == env || "$executable" == /usr/bin/env ]]; then
    while (($#)); do
        case "$1" in
            --) shift; break ;;
            -u|--unset)
                (($# >= 2)) || { echo "env unset argument missing" >&2; exit 64; }
                original_env+=("$1" "$2")
                shift 2
                ;;
            -u*|--unset=*|*=*) original_env+=("$1"); shift ;;
            *) break ;;
        esac
    done
    executable="${1:?application executable required after env}"
    shift
fi

runtime_dir="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
runtime_log="$runtime_dir/proroot-app-runtime.log"
mkdir -p "$runtime_dir" >/dev/null 2>&1 || true
log_runtime() {
    printf '%s family=%s executable=%s %s\n' "$(date -Is)" "$family" "$executable" "$*" >>"$runtime_log" 2>/dev/null || true
}
log_runtime "invoked"

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

namespace_signature() {
    printf '%s|%s|%s\n' \
        "$(readlink /proc/self/ns/user 2>/dev/null || true)" \
        "$(readlink /proc/self/ns/pid 2>/dev/null || true)" \
        "$(readlink /proc/self/ns/net 2>/dev/null || true)"
}

namespace_sandbox_available() {
    command -v unshare >/dev/null 2>&1 || return 1
    local outer inner
    outer="$(namespace_signature)"
    inner="$(
        unshare --user --map-root-user --pid --fork --net \
            sh -c 'printf "%s|%s|%s\n" "$(readlink /proc/self/ns/user)" "$(readlink /proc/self/ns/pid)" "$(readlink /proc/self/ns/net)"' \
            2>/dev/null
    )" || return 1
    [[ -n "$inner" && "$inner" != "$outer" ]]
}

case "$family" in
    chromium|electron)
        if [[ -n "${DISPLAY:-}" ]] && [[ -S "/tmp/.X11-unix/X${DISPLAY#:}" ]]; then
            ozone_platform=x11
            export ELECTRON_OZONE_PLATFORM_HINT=x11
            unset WAYLAND_DISPLAY
        elif [[ -n "${WAYLAND_DISPLAY:-}" ]] && [[ -S "${XDG_RUNTIME_DIR:?}/$WAYLAND_DISPLAY" ]]; then
            ozone_platform=wayland
            export ELECTRON_OZONE_PLATFORM_HINT=wayland
            unset DISPLAY XAUTHORITY
        else
            echo "No live X11 or Wayland display is available" >&2
            log_runtime "display=unavailable"
            exit 73
        fi
        log_runtime "display=$ozone_platform"

        sandbox_args=()
        if namespace_sandbox_available; then
            # Preserve Chromium's namespace isolation whenever the real kernel
            # actually creates separate user/PID/network namespaces. PRoot's
            # syscall translation and Android's inherited seccomp policy make
            # Chromium's own seccomp layer unreliable, so disable only that
            # layer instead of throwing away the namespace sandbox as well.
            sandbox_args+=(--disable-setuid-sandbox --disable-seccomp-filter-sandbox)
            log_runtime "sandbox=kernel-userns"
        else
            # Android still isolates the whole Linux runtime under the app UID.
            # On kernels that deny unprivileged namespaces, Chromium cannot
            # construct its Linux layer-1 sandbox at all; --no-sandbox is then
            # the only supported launch mode rather than a fake chmod-4755
            # helper that cannot become kernel root inside PRoot.
            sandbox_args+=(--no-sandbox)
            log_runtime "sandbox=android-app-boundary kernel-userns=unavailable"
        fi

        exec env "${original_env[@]}" "$executable" \
            "${sandbox_args[@]}" \
            --ozone-platform="$ozone_platform" \
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
        export MOZ_ENABLE_WAYLAND=1
        export MOZ_WEBRENDER=1
        export MOZ_ACCELERATED=1

        if namespace_sandbox_available; then
            # Firefox can discover real user namespaces itself. Leave its
            # normal content/media/socket sandboxes enabled in that case.
            unset MOZ_DISABLE_CONTENT_SANDBOX MOZ_DISABLE_GMP_SANDBOX
            unset MOZ_DISABLE_GPU_SANDBOX MOZ_DISABLE_RDD_SANDBOX
            unset MOZ_DISABLE_SOCKET_PROCESS_SANDBOX MOZ_DISABLE_UTILITY_SANDBOX
            log_runtime "sandbox=mozilla-native-userns"
        else
            # Do not make Firefox repeatedly crash child processes trying to
            # create namespaces the Android kernel refuses. Disable only the
            # OS sandbox layers; multiprocess/site isolation stays intact and
            # the complete Linux guest remains inside Android's app sandbox.
            export MOZ_DISABLE_CONTENT_SANDBOX=1
            export MOZ_DISABLE_GMP_SANDBOX=1
            export MOZ_DISABLE_GPU_SANDBOX=1
            export MOZ_DISABLE_RDD_SANDBOX=1
            export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1
            export MOZ_DISABLE_UTILITY_SANDBOX=1
            log_runtime "sandbox=android-app-boundary kernel-userns=unavailable"
        fi

        exec env "${original_env[@]}" "$executable" "$@"
        ;;
    *)
        log_runtime "sandbox=application-default"
        exec env "${original_env[@]}" "$executable" "$@"
        ;;
esac
