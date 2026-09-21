package dev.thaakeno.proroot.runtime

import java.io.File
import java.util.concurrent.TimeUnit

class SystemServicesSession(
    private val runner: ProrootRunner,
    private val paths: RuntimePaths,
) {
    private var process: Process? = null
    private var logThread: Thread? = null

    @Synchronized
    fun start() {
        if (process?.isAlive == true && systemBusSocket().exists()) return

        stop()
        systemBusSocket().delete()

        val started = runner.startRootService(
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
            if (systemBusSocket().exists() && started.isAlive) return
            if (!started.isAlive) break
            Thread.sleep(50)
        }

        stop()
        error("Linux system D-Bus did not become ready")
    }

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
        File(paths.rootfs, "run/dbus/pid").delete()
    }

    fun isRunning(): Boolean = process?.isAlive == true && systemBusSocket().exists()

    private fun systemBusSocket(): File =
        File(paths.rootfs, "run/dbus/system_bus_socket")
}
