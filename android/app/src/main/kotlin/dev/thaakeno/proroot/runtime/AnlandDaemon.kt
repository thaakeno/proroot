package dev.thaakeno.proroot.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class AnlandDaemon(
    context: Context,
    private val paths: RuntimePaths,
) {
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libanland_daemon.so")
    private var process: Process? = null

    @Synchronized
    fun start() {
        if (process?.isAlive == true) return
        check(executable.isFile) { "Anland daemon is missing from nativeLibraryDir" }
        paths.anlandDir.mkdirs()
        paths.anlandSocket.delete()

        process = ProcessBuilder(
            executable.absolutePath,
            "--socket",
            paths.anlandSocket.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(paths.logsDir, "anland-daemon.log")))
            .start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (paths.anlandSocket.exists()) return
            if (process?.isAlive != true) break
            Thread.sleep(25)
        }
        error("Anland daemon did not create its control socket")
    }

    @Synchronized
    fun stop() {
        val active = process ?: return
        active.destroy()
        if (!active.waitFor(1, TimeUnit.SECONDS)) active.destroyForcibly()
        process = null
        paths.anlandSocket.delete()
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
