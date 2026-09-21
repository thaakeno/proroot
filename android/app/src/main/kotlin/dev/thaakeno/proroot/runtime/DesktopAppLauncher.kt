package dev.thaakeno.proroot.runtime

import java.io.File

class DesktopAppLauncher(
    private val runner: ProrootRunner,
    private val paths: RuntimePaths,
) {
    fun launch(desktopId: String) {
        require(desktopId.matches(Regex("[A-Za-z0-9._+-]+"))) { "Invalid desktop id" }
        val appId = desktopId.removeSuffix(".desktop")
        val logFile = File(paths.logsDir, "desktop-apps.log")
        logFile.appendText("\n=== launch $desktopId ===\n")

        val process = runner.startDetachedUser(
            shellCommand = "/usr/local/lib/proroot/launch-desktop-app.sh '$appId'",
            logFile = logFile,
        )

        Thread {
            val code = process.waitFor()
            logFile.appendText("=== launcher $desktopId exited $code ===\n")
        }.apply {
            name = "desktop-app-${appId.take(32)}"
            isDaemon = true
            start()
        }
    }
}
