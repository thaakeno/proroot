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
        val script = "exec /usr/local/lib/proroot/start-desktop.sh $safeRefresh $safeScale"

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
        val usersDir = File(paths.rootfs, "run/user")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)

        while (System.nanoTime() < deadline) {
            check(started.isAlive) { "Plasma exited before creating a Wayland socket" }
            val ready = usersDir.listFiles()?.any { runtimeDir ->
                runtimeDir.listFiles()?.any { file ->
                    file.name.startsWith("wayland-") && !file.name.endsWith(".lock")
                } == true
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
