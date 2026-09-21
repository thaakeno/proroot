package dev.thaakeno.proroot.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stable install/provisioning backend.
 *
 * This runner is deliberately install-only. Interactive sessions, desktop
 * processes and launched applications remain on [ProrootRunner].
 */
class InstallProotRunner(
    context: Context,
    private val paths: RuntimePaths,
) : GuestRunner {
    override val runtimeId: String = "proot-installer"

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
            check(file.isFile && file.length() > 0L) {
                "Missing installer runtime file: ${file.name}"
            }
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
    ): CommandResult =
        runCommand(command, timeoutSeconds, rootfs, fakeRoot) { }

    override fun execStreaming(
        command: String,
        timeoutSeconds: Long,
        rootfs: File?,
        fakeRoot: Boolean,
        onOutput: (String) -> Unit,
    ): CommandResult =
        runCommand(command, timeoutSeconds, rootfs, fakeRoot, onOutput)

    private fun runCommand(
        command: String,
        timeoutSeconds: Long,
        rootfs: File?,
        fakeRoot: Boolean,
        onOutput: (String) -> Unit,
    ): CommandResult {
        val targetRootfs = rootfs ?: paths.rootfs
        val process = command(
            rootfs = targetRootfs,
            workingDirectory = if (fakeRoot) "/root" else "/home/linux",
            shellCommand = command,
            fakeRoot = fakeRoot,
        ).start()

        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                    onOutput(line)
                }
            }
        }.apply {
            name = "installer-runtime-output"
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

    override fun startSystemService(shellCommand: String): Process =
        unsupported()

    override fun startSession(shellCommand: String): Process =
        unsupported()

    override fun startDetachedUser(shellCommand: String, logFile: File): Process =
        unsupported()

    private fun unsupported(): Nothing =
        error("Classic PRoot is restricted to installation and provisioning")

    private fun command(
        rootfs: File,
        workingDirectory: String,
        shellCommand: String,
        fakeRoot: Boolean,
    ): ProcessBuilder {
        networkBridge.sync(rootfs)
        procCompat.refresh()
        prepareDependencies()

        // Keep the old global .l2s directory reachable for rootfses created by
        // earlier builds, but do not point PROOT_L2S_DIR at it anymore. New
        // link2symlink intermediates stay beside their files, so unrelated
        // packages no longer share one global basename namespace.
        val legacyL2s = File(rootfs, ".l2s").apply { mkdirs() }
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
            "-b", "${legacyL2s.absolutePath}:${legacyL2s.absolutePath}",
        )

        addBind(args, "/dev", "/dev")
        addBind(args, "/dev/urandom", "/dev/random")
        addBind(args, "/proc", "/proc")
        addBind(args, "/sys", "/sys")
        addBind(args, "/system", "/system")
        addBind(args, "/apex", "/apex")
        addBind(args, "/proc/self/fd", "/dev/fd")

        args += listOf(
            "-b", "${paths.aptArchivesDir.absolutePath}:/var/cache/apt/archives",
            "-b", "${paths.shmDir.absolutePath}:/dev/shm",
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
            "-b", "${paths.anlandDir.absolutePath}:/tmp/anland",
            "-b", "${paths.sharedDir.absolutePath}:/mnt/android",
            "-b", "${paths.hostInfoFile.absolutePath}:/run/proroot-host-info",
            "/bin/bash", "-c", shellCommand,
        )

        return ProcessBuilder(args)
            .directory(paths.machineDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(guestEnvironment(fakeRoot))
            }
    }

    private fun addBind(
        args: MutableList<String>,
        source: String,
        target: String,
    ) {
        if (!File(source).exists()) return
        args += listOf("-b", "$source:$target")
    }

    @Synchronized
    private fun prepareDependencies() {
        paths.installProotLibDir.mkdirs()
        paths.aptArchivesDir.mkdirs()
        File(paths.aptArchivesDir, "partial").mkdirs()
        copyAlias(talloc, File(paths.installProotLibDir, "libtalloc.so.2"))
        copyAlias(androidShmem, File(paths.installProotLibDir, "libandroid-shmem.so"))
    }

    private fun copyAlias(source: File, target: File) {
        if (!target.isFile || target.length() != source.length()) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        runCatching { Os.chmod(target.absolutePath, 0x1ED) }
    }

    private fun guestEnvironment(
        fakeRoot: Boolean,
    ): MutableMap<String, String> {
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
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_LOADER_32" to loader32.absolutePath,
            "LD_LIBRARY_PATH" to
                "${paths.installProotLibDir.absolutePath}:${nativeDir.absolutePath}",
        )
    }
}
