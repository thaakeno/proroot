package dev.thaakeno.proroot.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class ProrootRunner(
    private val context: Context,
    private val paths: RuntimePaths,
) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val launcher = File(nativeDir, "libproroot.so")

    init {
        check(launcher.isFile) { "libproroot.so is missing from nativeLibraryDir" }
    }

    fun command(
        rootfs: File = paths.rootfs,
        workingDirectory: String = "/home/linux",
        shellCommand: String,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): ProcessBuilder {
        val args = mutableListOf(
            launcher.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "--link2symlink",
            "-w", workingDirectory,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "${paths.anlandDir.absolutePath}:/tmp/anland",
            "-b", "${paths.sharedDir.absolutePath}:/mnt/android",
            "/bin/bash", "-lc", shellCommand,
        )

        return ProcessBuilder(args)
            .directory(paths.machineDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(hostEnvironment(extraEnvironment))
            }
    }

    fun exec(
        command: String,
        timeoutSeconds: Long = 120,
        rootfs: File = paths.rootfs,
    ): CommandResult {
        val process = command(rootfs = rootfs, workingDirectory = "/root", shellCommand = command).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { output.appendLine(it) }
            }
        }.apply { start() }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(2_000)
        return CommandResult(
            exitCode = if (completed) process.exitValue() else 124,
            output = output.toString(),
        )
    }

    fun startSession(shellCommand: String): Process {
        return command(
            workingDirectory = "/home/linux",
            shellCommand = shellCommand,
            extraEnvironment = mapOf(
                "PROROOT_LOG_APPEND" to File(paths.logsDir, "proroot-crash.log").absolutePath,
            ),
        ).start()
    }

    private fun hostEnvironment(extra: Map<String, String>): MutableMap<String, String> {
        val env = mutableMapOf(
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to paths.tmpDir.absolutePath,
            "PROROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "PATH" to "/system/bin:/system/xbin",
            "LANG" to "C.UTF-8",
        )
        env.putAll(extra)
        return env
    }
}
