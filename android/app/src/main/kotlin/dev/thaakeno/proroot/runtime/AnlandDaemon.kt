package dev.thaakeno.proroot.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

data class AnlandConnectionState(
    val consumerConnected: Boolean,
    val producerConnected: Boolean,
) {
    val ready: Boolean get() = consumerConnected && producerConnected
}

class AnlandDaemon(
    context: Context,
    private val paths: RuntimePaths,
) {
    private val executable =
        File(context.applicationInfo.nativeLibraryDir, "libanland_daemon.so")
    private var process: Process? = null

    @Synchronized
    fun start() {
        val existing = process
        if (existing?.isAlive == true && paths.anlandSocket.exists()) return

        if (existing?.isAlive == true) {
            existing.destroy()
            existing.waitFor(500, TimeUnit.MILLISECONDS)
            if (existing.isAlive) existing.destroyForcibly()
        }
        process = null

        check(executable.isFile) {
            "Anland daemon is missing from nativeLibraryDir"
        }

        paths.anlandDir.mkdirs()
        paths.anlandSocket.delete()
        paths.logsDir.mkdirs()

        val log = File(paths.logsDir, "anland-daemon.log")
        log.writeText("===== Anland display transport start =====\n")

        process = ProcessBuilder(
            executable.absolutePath,
            "--socket",
            paths.anlandSocket.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (paths.anlandSocket.exists() && process?.isAlive == true) return
            if (process?.isAlive != true) break
            Thread.sleep(25)
        }

        val exit = process?.takeIf { !it.isAlive }?.exitValue()
        process = null
        paths.anlandSocket.delete()
        error(
            "Anland daemon did not create its control socket" +
                (exit?.let { " (exit $it)" } ?: ""),
        )
    }

    @Synchronized
    fun stop() {
        val active = process
        if (active != null) {
            active.destroy()
            if (!active.waitFor(2, TimeUnit.SECONDS)) {
                active.destroyForcibly()
                active.waitFor(1, TimeUnit.SECONDS)
            }
        }
        process = null
        paths.anlandSocket.delete()
    }

    fun isRunning(): Boolean =
        process?.isAlive == true && paths.anlandSocket.exists()

    fun connectionState(): AnlandConnectionState {
        if (!isRunning()) return AnlandConnectionState(false, false)

        val log = File(paths.logsDir, "anland-daemon.log")
        if (!log.isFile) return AnlandConnectionState(false, false)

        var consumer = false
        var producer = false
        log.useLines { lines ->
            lines.forEach { line ->
                when {
                    "daemon: listening on" in line -> {
                        // A new daemon/session invalidates every old connection
                        // state in the file.
                        consumer = false
                        producer = false
                    }
                    "daemon: shutdown" in line -> {
                        consumer = false
                        producer = false
                    }
                    "daemon: consumer connected" in line -> consumer = true
                    "daemon: consumer disconnected" in line -> consumer = false
                    "daemon: producer connected" in line -> producer = true
                    "daemon: producer disconnected" in line -> producer = false
                }
            }
        }
        return AnlandConnectionState(consumer, producer)
    }
}
