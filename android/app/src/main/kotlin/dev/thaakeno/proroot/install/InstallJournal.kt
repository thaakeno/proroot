package dev.thaakeno.proroot.install

import dev.thaakeno.proroot.runtime.CommandResult
import dev.thaakeno.proroot.runtime.RuntimePaths
import dev.thaakeno.proroot.runtime.RuntimePhase
import dev.thaakeno.proroot.runtime.RuntimeStatus
import java.time.Instant

class InstallJournal(
    private val paths: RuntimePaths,
) {
    private val lock = Any()
    private var lastLoggedAtMs = 0L
    private var lastPhase: RuntimePhase? = null
    private var lastMessage: String? = null
    private var lastPercent = -1

    fun begin() {
        synchronized(lock) {
            paths.ensureHostDirectories()
            if (paths.installLog.isFile) {
                paths.previousInstallLog.delete()
                paths.installLog.copyTo(paths.previousInstallLog, overwrite = true)
            }
            paths.installLog.writeText(
                buildString {
                    appendLine("===== Proroot Linux install =====")
                    appendLine("started=${Instant.now()}")
                    appendLine()
                },
            )
            paths.lastInstallFailure.delete()
            paths.installInProgress.writeText(Instant.now().toString())
            lastLoggedAtMs = 0L
            lastPhase = null
            lastMessage = null
            lastPercent = -1
        }
    }

    fun status(status: RuntimeStatus) {
        val now = System.currentTimeMillis()
        val percent = (status.progress.coerceIn(0.0, 1.0) * 100.0).toInt()
        synchronized(lock) {
            val changed = status.phase != lastPhase ||
                status.message != lastMessage ||
                percent != lastPercent
            if (!changed && now - lastLoggedAtMs < 1_000L) return

            append(
                "[" + Instant.now() + "] " +
                    "phase=" + status.phase.name + " progress=" + percent + "% " +
                    "downloaded=" + status.downloadedBytes + "/" + status.totalBytes + " " +
                    "speed=" + status.speedBytesPerSecond + " " +
                    "message=" + status.message + "\n",
            )
            lastLoggedAtMs = now
            lastPhase = status.phase
            lastMessage = status.message
            lastPercent = percent
        }
    }

    fun commandStart(command: String) {
        synchronized(lock) {
            append(
                buildString {
                    appendLine()
                    appendLine("===== command =====")
                    appendLine(command)
                    appendLine("----- live output -----")
                },
            )
        }
    }

    fun commandOutput(line: String) {
        synchronized(lock) {
            append(line)
            if (!line.endsWith("\n")) append("\n")
        }
    }

    fun commandEnd(result: CommandResult) {
        synchronized(lock) {
            append("exit=${result.exitCode}\n")
            appendLineSeparator()
        }
    }

    private fun appendLineSeparator() {
        append("----- end output -----\n")
    }

    fun command(command: String, result: CommandResult) {
        synchronized(lock) {
            append(
                buildString {
                    appendLine()
                    appendLine("===== command =====")
                    appendLine(command)
                    appendLine("exit=${result.exitCode}")
                    if (!result.successful) {
                        appendLine("----- full output -----")
                        append(result.output)
                        if (!result.output.endsWith("\n")) appendLine()
                        appendLine("----- end output -----")
                    }
                },
            )
        }
    }

    fun failure(error: Throwable) {
        val report = buildString {
            appendLine("===== installation failure =====")
            appendLine("time=${Instant.now()}")
            appendLine(error.stackTraceToString())
        }
        synchronized(lock) {
            append("\n$report")
            paths.lastInstallFailure.writeText(report)
            paths.installInProgress.delete()
        }
    }

    fun success() {
        synchronized(lock) {
            append("\n===== installation complete ${Instant.now()} =====\n")
            paths.lastInstallFailure.delete()
            paths.installInProgress.delete()
        }
    }

    fun lastFailure(): String? =
        paths.lastInstallFailure.takeIf { it.isFile }?.readText()

    fun wasInterrupted(): Boolean = paths.installInProgress.isFile

    private fun append(text: String) {
        paths.installLog.parentFile?.mkdirs()
        paths.installLog.appendText(text)
    }
}
