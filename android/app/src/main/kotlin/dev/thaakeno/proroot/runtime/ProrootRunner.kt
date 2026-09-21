package dev.thaakeno.proroot.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class ProrootRunner(
    private val context: Context,
    private val paths: RuntimePaths,
) : GuestRunner {
    override val runtimeId: String = "proroot"

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val launcher = File(nativeDir, "libproroot.so")
    private val runtimeLib = File(nativeDir, "libproroot-runtime.so")
    private val linkerLib = File(nativeDir, "libproroot-linker.so")
    private val stubLoader = File(nativeDir, "libproroot-stub-loader.so")
    private val bridgeLib = File(nativeDir, "libproroot-bridge.so")
    private val procCompat = ProcCompatBridge(context, paths)
    private val networkBridge = HostNetworkBridge(context)

    init {
        listOf(
            launcher,
            runtimeLib,
            linkerLib,
            stubLoader,
            bridgeLib,
        ).forEach { file ->
            check(file.isFile && file.length() > 0L) {
                "Missing proroot runtime file in nativeLibraryDir: ${file.name}"
            }
        }
        procCompat.start()
    }

    fun command(
        rootfs: File = paths.rootfs,
        workingDirectory: String = "/home/linux",
        shellCommand: String,
        fakeRoot: Boolean = true,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): ProcessBuilder {
        networkBridge.sync(rootfs)
        procCompat.refresh()

        val args = mutableListOf(
            launcher.absolutePath,
            "-r", rootfs.absolutePath,
        )
        if (fakeRoot) args += "-0"
        args += listOf("-w", workingDirectory)

        addBind(args, "/dev", "/dev")
        addBind(args, "/dev/urandom", "/dev/random")
        addBind(args, "/proc", "/proc")
        addBind(args, "/sys", "/sys")
        addBind(args, "/system", "/system")
        addBind(args, "/apex", "/apex")
        addBind(args, "/proc/self/fd", "/dev/fd")

        args += listOf(
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
            "--link2symlink",
            "/bin/bash", "-lc", shellCommand,
        )

        return ProcessBuilder(args)
            .directory(paths.machineDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(hostEnvironment(fakeRoot, extraEnvironment))
            }
    }

    override fun exec(
        command: String,
        timeoutSeconds: Long,
        rootfs: File?,
        fakeRoot: Boolean,
    ): CommandResult {
        val resolvedRootfs = rootfs ?: paths.rootfs
        val process = command(
            rootfs = resolvedRootfs,
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
            name = "proroot-command-output"
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
        command(
            workingDirectory = "/home/linux",
            shellCommand = shellCommand,
            fakeRoot = false,
            extraEnvironment = mapOf(
                "PROROOT_LOG_APPEND" to
                    File(paths.logsDir, "proroot-system-services.log").absolutePath,
            ),
        ).start()

    override fun startSession(shellCommand: String): Process =
        command(
            workingDirectory = "/home/linux",
            shellCommand = shellCommand,
            fakeRoot = false,
            extraEnvironment = mapOf(
                "PROROOT_LOG_APPEND" to
                    File(paths.logsDir, "proroot-crash.log").absolutePath,
            ),
        ).start()

    override fun startDetachedUser(shellCommand: String, logFile: File): Process {
        logFile.parentFile?.mkdirs()
        return command(
            workingDirectory = "/home/linux",
            shellCommand = shellCommand,
            fakeRoot = false,
            extraEnvironment = mapOf(
                "PROROOT_LOG_APPEND" to
                    File(paths.logsDir, "proroot-app-runtime.log").absolutePath,
            ),
        )
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    private fun addBind(
        args: MutableList<String>,
        source: String,
        target: String,
    ) {
        if (!File(source).exists()) return
        args += listOf("-b", "$source:$target")
    }

    private fun hostEnvironment(
        fakeRoot: Boolean,
        extra: Map<String, String>,
    ): MutableMap<String, String> {
        val user = if (fakeRoot) "root" else "linux"
        val home = if (fakeRoot) "/root" else "/home/linux"
        val env = mutableMapOf(
            "HOME" to home,
            "USER" to user,
            "LOGNAME" to user,
            "SHELL" to "/bin/bash",
            "TMPDIR" to paths.tmpDir.absolutePath,
            "PROROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "PROROOT_LIB_PATH" to runtimeLib.absolutePath,
            "PROROOT_LINKER_PATH" to linkerLib.absolutePath,
            "PROROOT_STUB_LOADER" to stubLoader.absolutePath,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
        )
        env.putAll(extra)
        return env
    }
}
