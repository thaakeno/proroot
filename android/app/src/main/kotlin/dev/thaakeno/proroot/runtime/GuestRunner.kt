package dev.thaakeno.proroot.runtime

import java.io.File

interface GuestRunner {
    val runtimeId: String

    fun exec(
        command: String,
        timeoutSeconds: Long = 120,
        rootfs: File? = null,
        fakeRoot: Boolean = true,
    ): CommandResult

    fun execStreaming(
        command: String,
        timeoutSeconds: Long = 120,
        rootfs: File? = null,
        fakeRoot: Boolean = true,
        onOutput: (String) -> Unit,
    ): CommandResult {
        val result = exec(command, timeoutSeconds, rootfs, fakeRoot)
        if (result.output.isNotEmpty()) onOutput(result.output)
        return result
    }

    fun startRootService(shellCommand: String): Process

    fun startSession(shellCommand: String): Process

    fun startDetachedUser(shellCommand: String, logFile: File): Process
}
