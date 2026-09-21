package dev.thaakeno.proroot.runtime

import java.io.File
import java.util.concurrent.TimeUnit

class SystemServicesSession(
    private val runner: GuestRunner,
    private val paths: RuntimePaths,
) {
    private var process: Process? = null
    private var logThread: Thread? = null

    @Synchronized
    fun start() {
        if (process?.isAlive == true &&
            systemBusSocket().exists() &&
            upowerReady().exists() &&
            login1Ready().exists() &&
            activationReady().exists()
        ) return

        stop()
        systemBusSocket().delete()
        upowerReady().delete()
        login1Ready().delete()
        activationReady().delete()

        val started = runner.startSystemService(
            "exec /usr/local/lib/proroot/start-system-services.sh",
        )
        process = started

        val logFile = File(paths.logsDir, "system-services.log")
        logThread = Thread {
            logFile.outputStream().buffered().use { output ->
                started.inputStream.copyTo(output)
            }
        }.apply {
            name = "system-services-log"
            isDaemon = true
            start()
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            if (
                systemBusSocket().exists() &&
                upowerReady().exists() &&
                login1Ready().exists() &&
                activationReady().exists() &&
                started.isAlive
            ) {
                val busProbe = verifyBusFromDesktopIdentity()
                if (busProbe.successful) return

                stop()
                error(
                    "Linux system D-Bus was created but is not reachable from the desktop identity. " +
                        busProbe.output.takeLast(2_000),
                )
            }
            if (!started.isAlive) break
            Thread.sleep(50)
        }

        stop()
        error("Linux system D-Bus did not become ready")
    }

    private fun verifyBusFromDesktopIdentity(): CommandResult =
        runner.exec(
            command = """
                set -e
                id -u | grep -vq '^0
            """.trimIndent(),
            timeoutSeconds = 6,
            fakeRoot = false,
        )

    @Synchronized
    fun stop() {
        val active = process
        if (active != null) {
            active.destroy()
            if (!active.waitFor(2, TimeUnit.SECONDS)) active.destroyForcibly()
        }
        process = null
        logThread?.join(500)
        logThread = null
        systemBusSocket().delete()
        upowerReady().delete()
        login1Ready().delete()
        activationReady().delete()
        File(paths.rootfs, "run/dbus/pid").delete()
    }

    fun isRunning(): Boolean =
        process?.isAlive == true &&
            systemBusSocket().exists() &&
            upowerReady().exists() &&
            login1Ready().exists() &&
            activationReady().exists()

    private fun upowerReady(): File = File(paths.rootfs, "run/proroot-upower.ready")

    private fun login1Ready(): File = File(paths.rootfs, "run/proroot-login1.ready")

    private fun activationReady(): File =
        File(paths.rootfs, "run/proroot-system-activation.ready")

    private fun systemBusSocket(): File =
        File(paths.rootfs, "run/dbus/system_bus_socket")
}

                test -S /run/dbus/system_bus_socket
                dbus-send \
                  --system \
                  --print-reply=literal \
                  --dest=org.freedesktop.DBus \
                  /org/freedesktop/DBus \
                  org.freedesktop.DBus.ListNames \
                  | tee /tmp/proroot-system-bus-names.txt
                grep -q 'org.freedesktop.UPower' /tmp/proroot-system-bus-names.txt
                grep -q 'org.freedesktop.login1' /tmp/proroot-system-bus-names.txt
            """.trimIndent(),
            timeoutSeconds = 6,
            fakeRoot = false,
        )

    @Synchronized
    fun stop() {
        val active = process
        if (active != null) {
            active.destroy()
            if (!active.waitFor(2, TimeUnit.SECONDS)) active.destroyForcibly()
        }
        process = null
        logThread?.join(500)
        logThread = null
        systemBusSocket().delete()
        upowerReady().delete()
        login1Ready().delete()
        activationReady().delete()
        File(paths.rootfs, "run/dbus/pid").delete()
    }

    fun isRunning(): Boolean =
        process?.isAlive == true &&
            systemBusSocket().exists() &&
            upowerReady().exists() &&
            login1Ready().exists() &&
            activationReady().exists()

    private fun upowerReady(): File = File(paths.rootfs, "run/proroot-upower.ready")

    private fun login1Ready(): File = File(paths.rootfs, "run/proroot-login1.ready")

    private fun activationReady(): File =
        File(paths.rootfs, "run/proroot-system-activation.ready")

    private fun systemBusSocket(): File =
        File(paths.rootfs, "run/dbus/system_bus_socket")
}
