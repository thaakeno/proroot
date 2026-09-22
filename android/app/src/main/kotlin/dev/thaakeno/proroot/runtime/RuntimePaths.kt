package dev.thaakeno.proroot.runtime

import android.content.Context
import android.system.Os
import java.io.File

class RuntimePaths(context: Context) {
    private val files = context.filesDir

    val machineDir = File(files, "machine")
    val rootfs = File(machineDir, "rootfs")
    val rootfsStaging = File(machineDir, "rootfs.staging")
    val rootfsPrevious = File(machineDir, "rootfs.previous")
    val cacheDir = File(files, "runtime-cache")
    val tmpDir = File(files, "runtime-tmp")
    val installProotLibDir = File(files, "install-proot-libs")
    val aptArchivesDir = File(files, "apt-archives")
    val logsDir = File(files, "logs")
    val installLog = File(logsDir, "install.log")
    val previousInstallLog = File(logsDir, "install.previous.log")
    val lastInstallFailure = File(logsDir, "last-install-failure.log")
    private val prorootCrashNames = listOf(
        "proroot-sigsegv-maps.txt",
        "proroot-sigbus-maps.txt",
        "proroot-sigtrap-maps.txt",
    )
    val prorootCrashDumps: List<File>
        get() = listOf(
            files,
            machineDir,
            File(rootfs, "home/linux"),
            File(rootfs, "root"),
            tmpDir,
        ).flatMap { directory ->
            prorootCrashNames.map { name -> File(directory, name) }
        }
    val installInProgress = File(machineDir, ".installing")
    val anlandDir = File(files, "anland")
    val anlandSocket = File(anlandDir, "display_daemon.sock")
    val sharedDir = File(files, "shared")
    val shmDir = File(files, "runtime-shm")

    val procCompatDir = File(files, "proc-compat")
    val procStat = File(procCompatDir, "stat")
    val procUptime = File(procCompatDir, "uptime")
    val procLoadavg = File(procCompatDir, "loadavg")
    val procVersion = File(procCompatDir, "version")
    val procVmstat = File(procCompatDir, "vmstat")
    val procZoneinfo = File(procCompatDir, "zoneinfo")
    val procSwaps = File(procCompatDir, "swaps")
    val procVmallocinfo = File(procCompatDir, "vmallocinfo")
    val procFilesystems = File(procCompatDir, "filesystems")
    val procPciDevices = File(procCompatDir, "bus/pci/devices")
    val hostInfoFile = File(procCompatDir, "host-info")

    val installMarker = File(machineDir, ".installed-v1")
    val previousInstallMarker = File(machineDir, ".installed-v1.previous")

    fun ensureHostDirectories() {
        listOf(
            machineDir,
            cacheDir,
            tmpDir,
            installProotLibDir,
            aptArchivesDir,
            File(aptArchivesDir, "partial"),
            logsDir,
            anlandDir,
            sharedDir,
            shmDir,
            procCompatDir,
            procPciDevices.parentFile,
        ).filterNotNull().forEach {
            if (!it.exists()) check(it.mkdirs()) { "Could not create " + it.absolutePath }
        }
        runCatching { Os.chmod(shmDir.absolutePath, 0x3FF) }
    }

    fun cleanupStaleInstallStateIfVerified() {
        val ready = File(rootfs, ".proroot-runtime-ready")
        if (!installMarker.isFile || !rootfs.isDirectory || !ready.isFile) return

        installInProgress.delete()
        val resumable = File(rootfsStaging, ".proroot-staging-resumable")
        if (rootfsStaging.isDirectory && !resumable.isFile) {
            rootfsStaging.deleteRecursively()
        }
    }

    fun resetTransientState() {
        anlandSocket.delete()
        tmpDir.deleteRecursively()
        check(tmpDir.mkdirs() || tmpDir.isDirectory) { "Could not recreate runtime tmp" }
        shmDir.deleteRecursively()
        check(shmDir.mkdirs() || shmDir.isDirectory) { "Could not recreate runtime shared memory" }
        runCatching { Os.chmod(shmDir.absolutePath, 0x3FF) }
    }
}
