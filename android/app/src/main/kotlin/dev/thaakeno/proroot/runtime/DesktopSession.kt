package dev.thaakeno.proroot.runtime

import java.io.File
import java.util.concurrent.TimeUnit

class DesktopSession(
    private val runner: GuestRunner,
    private val paths: RuntimePaths,
    private val daemon: AnlandDaemon,
) {
    private var process: Process? = null
    private var logThread: Thread? = null

    @Synchronized
    fun start(
        refreshRate: Int,
        scale: Double,
        onStage: (String) -> Unit = {},
        onExit: (Int) -> Unit,
    ) {
        if (process?.isAlive == true) return

        val safeRefresh = refreshRate.coerceIn(60, 165)
        val safeScale = scale.coerceIn(0.75, 2.0)
        val script = "exec /usr/local/lib/proroot/start-desktop.sh $safeRefresh"

        val started = runner.startSession(script)
        process = started

        val logFile = File(paths.logsDir, "desktop-session.log")
        logThread = Thread {
            logFile.outputStream().buffered().use { output ->
                started.inputStream.copyTo(output)
            }
        }.apply {
            name = "desktop-session-log"
            isDaemon = true
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
            isDaemon = true
            start()
        }

        onStage("Validating KDE desktop")
        waitForDesktopReady(started)
        if (safeScale != 1.0) {
            onStage("Applying desktop settings")
            val result = applyScale(safeScale)
            if (!result.successful) {
                File(paths.logsDir, "display-controls.log").appendText(
                    "Initial scale $safeScale failed (exit ${result.exitCode}):\n${result.output}\n",
                )
            }
        }
    }

    fun applyScale(scale: Double): CommandResult {
        val safeScale = scale.coerceIn(0.75, 2.0)
        return runner.exec(
            command = "/usr/local/lib/proroot/set-desktop-scale.sh $safeScale",
            timeoutSeconds = 15,
            fakeRoot = false,
        )
    }

    private fun waitForDesktopReady(started: Process) {
        val usersDir = File(paths.rootfs, "run/user")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        var stableSince = 0L
        var nextHealthProbe = 0L
        var lastHealth = CommandResult(1, "Desktop health probe has not run yet")

        while (System.nanoTime() < deadline) {
            check(started.isAlive) {
                "Plasma exited before the desktop became ready. " +
                    lastHealth.output.takeLast(2_000)
            }

            val sessionPublished = usersDir.listFiles()?.any { runtimeDir ->
                val envReady = File(runtimeDir, "proroot-session.env").isFile
                val socketReady = runtimeDir.listFiles()?.any { file ->
                    file.name.startsWith("wayland-") &&
                        !file.name.endsWith(".lock") &&
                        file.isFile.not()
                } == true
                envReady && socketReady
            } == true

            val now = System.nanoTime()
            if (sessionPublished && now >= nextHealthProbe) {
                lastHealth = health()
                nextHealthProbe = now + TimeUnit.MILLISECONDS.toNanos(400)
            }

            if (sessionPublished && lastHealth.successful) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= TimeUnit.MILLISECONDS.toNanos(1_000)) return
            } else {
                stableSince = 0L
            }
            Thread.sleep(75)
        }

        error(
            "Plasma did not become healthy within 40 seconds. " +
                lastHealth.output.takeLast(4_000),
        )
    }

    fun health(): CommandResult {
        if (process?.isAlive != true) {
            return CommandResult(1, "Desktop supervisor is not running")
        }

        val transport = daemon.connectionState()
        if (!transport.ready) {
            return CommandResult(
                1,
                "Anland transport is not ready: " +
                    "consumer=${transport.consumerConnected}, " +
                    "producer=${transport.producerConnected}",
            )
        }

        return runner.exec(
            command = "/usr/local/lib/proroot/check-desktop-health.sh",
            timeoutSeconds = 6,
            fakeRoot = false,
        )
    }

    @Synchronized
    fun stop() {
        val active = process ?: return
        active.destroy()
        if (!active.waitFor(3, TimeUnit.SECONDS)) active.destroyForcibly()
        process = null
        logThread?.join(500)
        logThread = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
