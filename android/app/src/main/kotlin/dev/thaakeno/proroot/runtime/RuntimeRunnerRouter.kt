package dev.thaakeno.proroot.runtime

import java.io.File

data class RuntimeSelection(
    val selectedRuntime: String,
    val prorootExitCode: Int,
    val prorootOutput: String,
)

class RuntimeRunnerRouter(
    private val proroot: GuestRunner,
    private val proot: GuestRunner,
    private val paths: RuntimePaths,
) : GuestRunner {
    @Volatile
    private var active: GuestRunner =
        if (paths.runtimeModeFile.takeIf { it.isFile }?.readText()?.trim() == "proroot") {
            proroot
        } else {
            proot
        }

    override val runtimeId: String
        get() = active.runtimeId

    @Synchronized
    fun selectFor(rootfs: File): RuntimeSelection {
        val probe = runCatching {
            proroot.exec(
                command = "/bin/true",
                timeoutSeconds = 10,
                rootfs = rootfs,
                fakeRoot = true,
            )
        }.getOrElse { error ->
            CommandResult(
                exitCode = -1,
                output = error.stackTraceToString(),
            )
        }

        active = if (probe.successful) proroot else proot
        paths.runtimeModeFile.writeText(active.runtimeId)
        paths.runtimeProbeLog.writeText(
            buildString {
                appendLine("selected=${active.runtimeId}")
                appendLine("proroot_exit=${probe.exitCode}")
                appendLine("----- proroot probe -----")
                append(probe.output)
            },
        )

        return RuntimeSelection(
            selectedRuntime = active.runtimeId,
            prorootExitCode = probe.exitCode,
            prorootOutput = probe.output,
        )
    }

    @Synchronized
    fun forceStable(reason: String) {
        active = proot
        paths.runtimeModeFile.writeText("proot")
        paths.runtimeProbeLog.appendText(
            "\nforced_fallback=proot\nreason=$reason\n",
        )
    }

    fun ensureSelected(rootfs: File) {
        if (!paths.runtimeModeFile.isFile) selectFor(rootfs)
    }

    override fun exec(
        command: String,
        timeoutSeconds: Long,
        rootfs: File?,
        fakeRoot: Boolean,
    ): CommandResult = active.exec(command, timeoutSeconds, rootfs, fakeRoot)

    override fun startRootService(shellCommand: String): Process =
        active.startRootService(shellCommand)

    override fun startSession(shellCommand: String): Process =
        active.startSession(shellCommand)

    override fun startDetachedUser(shellCommand: String, logFile: File): Process =
        active.startDetachedUser(shellCommand, logFile)
}
