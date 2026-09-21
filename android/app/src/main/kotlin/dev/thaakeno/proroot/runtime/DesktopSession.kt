package dev.thaakeno.proroot.runtime

import java.io.File
import java.util.concurrent.TimeUnit

class DesktopSession(
    private val runner: ProrootRunner,
    private val paths: RuntimePaths,
) {
    private var process: Process? = null
    private var logThread: Thread? = null

    @Synchronized
    fun start(refreshRate: Int, scale: Double, onExit: (Int) -> Unit) {
        if (process?.isAlive == true) return

        val safeRefresh = refreshRate.coerceIn(60, 165)
        val safeScale = scale.coerceIn(0.75, 2.0)

        val script = """
            set -e
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

            export XDG_RUNTIME_DIR=/run/user/1000
            export XDG_CURRENT_DESKTOP=KDE
            export XDG_SESSION_DESKTOP=KDE
            export XDG_SESSION_TYPE=wayland
            export QT_QPA_PLATFORM=wayland
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
            export PROROOT_REFRESH_HZ=$safeRefresh
            export QT_SCALE_FACTOR=$safeScale

            install -d -m 0700 -o 1000 -g 1000 /run/user/1000
            install -d -m 1777 /tmp/.X11-unix
            rm -f /run/user/1000/wayland-* /run/user/1000/proroot-session.env

            dbus-daemon --system --fork --nopidfile >/dev/null 2>&1 || true

            exec runuser -u linux -- env                 HOME=/home/linux USER=linux LOGNAME=linux SHELL=/bin/bash                 LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8                 XDG_RUNTIME_DIR=/run/user/1000                 XDG_CURRENT_DESKTOP=KDE XDG_SESSION_DESKTOP=KDE XDG_SESSION_TYPE=wayland                 QT_QPA_PLATFORM=wayland QT_SCALE_FACTOR=$safeScale                 ANLAND=1 ANLAND_SOCKET=/tmp/anland/display_daemon.sock                 ANLAND_NO_DRM_DEVICE=1 ANLAND_PIPEWIRE_UNRESTRICTED=1 EGL_PLATFORM=surfaceless                 MESA_LOADER_DRIVER_OVERRIDE=kgsl TURNIP_KMD=kgsl GALLIUM_DRIVER=freedreno                 FD_FORCE_KGSL=1 XWAYLAND_FORCE_KGSL_SURFACELESS=1                 dbus-run-session -- bash -lc '
                    printf "export DBUS_SESSION_BUS_ADDRESS=%q\n" "${DBUS_SESSION_BUS_ADDRESS}"                       > /run/user/1000/proroot-session.env
                    pipewire >/tmp/pipewire.log 2>&1 &
                    wireplumber >/tmp/wireplumber.log 2>&1 &
                    exec startplasma-wayland
                '
        """.trimIndent()

        val started = runner.startSession(script)
        process = started
        val logFile = File(paths.logsDir, "desktop-session.log")
        logThread = Thread {
            logFile.outputStream().buffered().use { output ->
                started.inputStream.copyTo(output)
            }
        }.apply {
            name = "desktop-session-log"
            start()
        }

        Thread {
            val code = started.waitFor()
            synchronized(this) {
                if (process === started) process = null
            }
            onExit(code)
        }.apply {
            name = "desktop-session-waiter"
            start()
        }

        waitForWayland(started)
    }

    private fun waitForWayland(started: Process) {
        val runtimeDir = File(paths.rootfs, "run/user/1000")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            check(started.isAlive) { "Plasma exited before creating a Wayland socket" }
            val ready = runtimeDir.listFiles()?.any { file ->
                file.name.startsWith("wayland-") && !file.name.endsWith(".lock")
            } == true
            if (ready) return
            Thread.sleep(50)
        }
        error("Plasma did not create a Wayland socket within 20 seconds")
    }

    @Synchronized
    fun stop() {
        val active = process ?: return
        active.destroy()
        if (!active.waitFor(3, TimeUnit.SECONDS)) active.destroyForcibly()
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
