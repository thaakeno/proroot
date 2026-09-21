package dev.thaakeno.proroot.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.util.concurrent.TimeUnit

class ProotRunner(
    private val context: Context,
    private val paths: RuntimePaths,
) : GuestRunner {
    override val runtimeId: String = "proot"

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val launcher = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libprootloader.so")
    private val loader32 = File(nativeDir, "libprootloader32.so")
    private val talloc = File(nativeDir, "libtalloc.so")
    private val androidShmem = File(nativeDir, "libandroidshmem.so")
    private val procCompat = ProcCompatBridge(context, paths)
    private val networkBridge = HostNetworkBridge(context)

    init {
        listOf(launcher, loader, loader32, talloc, androidShmem).forEach { file ->
            check(file.isFile) { "Missing bundled proot runtime file: ${file.name}" }
        }
        paths.ensureHostDirectories()
        procCompat.start()
        prepareDependencies()
    }

    override fun exec(
        command: String,
        timeoutSeconds: Long,
        rootfs: File?,
        fakeRoot: Boolean,
    ): CommandResult {
        val process = command(
            rootfs = rootfs ?: paths.rootfs,
            workingDirectory = if (fakeRoot) "/root" else "/home/linux",
            shellCommand = command,
            fakeRoot = fakeRoot,
        ).start()

        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { output.appendLine(it) }
            }
        }.apply {
            name = "proot-command-output"
            isDaemon = true
            start()
        }

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

    override fun startRootService(shellCommand: String): Process =
        command(
            rootfs = paths.rootfs,
            workingDirectory = "/root",
            shellCommand = shellCommand,
            fakeRoot = true,
        ).start()

    override fun startSession(shellCommand: String): Process =
        command(
            rootfs = paths.rootfs,
            workingDirectory = "/home/linux",
            shellCommand = shellCommand,
            fakeRoot = false,
        ).start()

    override fun startDetachedUser(shellCommand: String, logFile: File): Process {
        logFile.parentFile?.mkdirs()
        return command(
            rootfs = paths.rootfs,
            workingDirectory = "/home/linux",
            shellCommand = shellCommand,
            fakeRoot = false,
        )
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    private fun command(
        rootfs: File,
        workingDirectory: String,
        shellCommand: String,
        fakeRoot: Boolean,
    ): ProcessBuilder {
        networkBridge.sync(rootfs)
        procCompat.refresh()
        prepareDependencies()

        val l2s = File(rootfs, ".l2s").apply { mkdirs() }
        val args = mutableListOf(
            launcher.absolutePath,
            "--link2symlink",
            "-L",
            "--kill-on-exit",
        )
        if (fakeRoot) args += "-0"
        args += listOf(
            "--rootfs=${rootfs.absolutePath}",
            "--cwd=$workingDirectory",
            "-b", "${l2s.absolutePath}:${l2s.absolutePath}",
            "-b", "/dev:/dev",
            "-b", "${paths.shmDir.absolutePath}:/dev/shm",
            "-b", "/proc:/proc",
            "-b", "${paths.procStat.absolutePath}:/proc/stat",
            "-b", "${paths.procUptime.absolutePath}:/proc/uptime",
            "-b", "${paths.procLoadavg.absolutePath}:/proc/loadavg",
            "-b", "${paths.procVersion.absolutePath}:/proc/version",
            "-b", "${paths.procVmstat.absolutePath}:/proc/vmstat",
            "-b", "${paths.procZoneinfo.absolutePath}:/proc/zoneinfo",
            "-b", "${paths.procSwaps.absolutePath}:/proc/swaps",
            "-b", "${paths.procVmallocinfo.absolutePath}:/proc/vmallocinfo",
            "-b", "${paths.procFilesystems.absolutePath}:/proc/filesystems",
            "-b", "${paths.procPciDevices.absolutePath}:/proc/bus/pci/devices",
            "-b", "/sys:/sys",
            "-b", "/system:/system",
            "-b", "/apex:/apex",
            "-b", "${paths.anlandDir.absolutePath}:/tmp/anland",
            "-b", "${paths.sharedDir.absolutePath}:/mnt/android",
            "-b", "${paths.hostInfoFile.absolutePath}:/run/proroot-host-info",
            "/bin/bash", "-lc", shellCommand,
        )

        return ProcessBuilder(args)
            .directory(paths.machineDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(guestEnvironment(fakeRoot, l2s))
            }
    }

    @Synchronized
    private fun prepareDependencies() {
        paths.prootLibDir.mkdirs()
        copyAlias(talloc, File(paths.prootLibDir, "libtalloc.so.2"))
        copyAlias(androidShmem, File(paths.prootLibDir, "libandroid-shmem.so"))
    }

    private fun copyAlias(source: File, target: File) {
        if (!target.isFile || target.length() != source.length()) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        runCatching { Os.chmod(target.absolutePath, 0x1ED) }
    }

    private fun guestEnvironment(fakeRoot: Boolean, l2s: File): MutableMap<String, String> {
        val user = if (fakeRoot) "root" else "linux"
        val home = if (fakeRoot) "/root" else "/home/linux"
        return mutableMapOf(
            "HOME" to home,
            "USER" to user,
            "LOGNAME" to user,
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "DEBIAN_FRONTEND" to "noninteractive",
            "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "PROOT_L2S_DIR" to l2s.absolutePath,
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_LOADER_32" to loader32.absolutePath,
            "LD_LIBRARY_PATH" to "${paths.prootLibDir.absolutePath}:${nativeDir.absolutePath}",
        )
    }
}
