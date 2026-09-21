package dev.thaakeno.proroot.runtime

import android.content.Context
import java.io.File

class RuntimePaths(context: Context) {
    private val files = context.filesDir

    val machineDir = File(files, "machine")
    val rootfs = File(machineDir, "rootfs")
    val rootfsStaging = File(machineDir, "rootfs.staging")
    val rootfsPrevious = File(machineDir, "rootfs.previous")
    val cacheDir = File(files, "runtime-cache")
    val tmpDir = File(files, "runtime-tmp")
    val logsDir = File(files, "logs")
    val anlandDir = File(files, "anland")
    val anlandSocket = File(anlandDir, "display_daemon.sock")
    val sharedDir = File(files, "shared")
    val installMarker = File(machineDir, ".installed-v1")

    fun ensureHostDirectories() {
        listOf(machineDir, cacheDir, tmpDir, logsDir, anlandDir, sharedDir).forEach {
            if (!it.exists()) check(it.mkdirs()) { "Could not create " + it.absolutePath }
        }
    }

    fun resetTransientState() {
        anlandSocket.delete()
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
    }
}
