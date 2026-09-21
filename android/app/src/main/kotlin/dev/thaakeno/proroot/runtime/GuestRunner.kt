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

    fun startRootService(shellCommand: String): Process

    fun startSession(shellCommand: String): Process

    fun startDetachedUser(shellCommand: String, logFile: File): Process
}
