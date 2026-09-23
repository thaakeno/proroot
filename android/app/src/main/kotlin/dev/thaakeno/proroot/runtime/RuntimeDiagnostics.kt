package dev.thaakeno.proroot.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import dev.thaakeno.proroot.BuildConfig
import dev.thaakeno.proroot.install.RuntimeInstaller
import java.io.File
import java.security.MessageDigest

class RuntimeDiagnostics(
    private val context: Context,
    private val paths: RuntimePaths,
    private val runner: GuestRunner,
    private val daemon: AnlandDaemon,
    private val systemServices: SystemServicesSession,
    private val session: DesktopSession,
    private val installer: RuntimeInstaller,
    private val appCatalog: DesktopAppCatalog,
) {
    fun collect(status: RuntimeStatus): Map<String, Any?> {
        val installed = paths.installMarker.isFile && paths.rootfs.isDirectory
        val crashLog = File(paths.logsDir, "proroot-crash.log")
        val recordedRuntimeCrash = crashLog
            .takeIf { it.isFile }
            ?.readText()
            ?.let { log ->
                log.contains("[proroot] SIGSEGV") ||
                    log.contains("[proroot] SIGABRT") ||
                    log.contains("[proroot] SIGBUS")
            } == true
        val liveDesktop = session.isRunning() || status.running
        val safeDiagnostics = installed &&
            (liveDesktop || recordedRuntimeCrash || status.phase == RuntimePhase.failed)
        val logs = paths.logsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.associate { file ->
                file.name to readTail(file, 120_000)
            }
            ?: emptyMap()
        val kwinQtQuickRenderer = when {
            logs["desktop-session.log"]?.contains("forcing Qt Quick to use the software renderer") == true ->
                "software (KWin no-DRM QPA path)"
            else -> "unverified; inspect desktop-session.log Qt scene graph output"
        }
        val prorootCrashDumps = paths.prorootCrashDumps
            .filter { it.isFile && it.length() > 0L }
            .distinctBy { it.absolutePath }
            .associate { dump ->
                "${dump.parentFile?.name}/${dump.name}" to mapOf(
                    "path" to dump.absolutePath,
                    "bytes" to dump.length(),
                    "lastModified" to dump.lastModified(),
                    "content" to readTail(dump, 180_000),
                )
            }
        val sessionEnvironment = File(paths.rootfs, "run/user")
            .listFiles()
            ?.asSequence()
            ?.map { File(it, "proroot-session.env") }
            ?.firstOrNull { it.isFile }
            ?.let { readTail(it, 20_000) }

        val probes = if (installed && !safeDiagnostics) {
            linkedMapOf(
                "guestIdentity" to probe(
                    "id -u; id -un; printf 'HOME=%s\\n' \"\$HOME\"",
                    fakeRoot = false,
                ),
                "gpu" to probe(
                    """
                    set -e
                    test -c /dev/kgsl-3d0
                    MESA_LOADER_DRIVER_OVERRIDE=kgsl \
                    TURNIP_KMD=kgsl \
                    GALLIUM_DRIVER=freedreno \
                      vulkaninfo --summary 2>&1 | sed -n '1,45p'
                    """.trimIndent(),
                    fakeRoot = false,
                    timeoutSeconds = 12,
                ),
                "systemBus" to probe(
                    """
                    set -e
                    test -S /run/dbus/system_bus_socket
                    dbus-send \
                      --system \
                      --print-reply=literal \
                      --dest=org.freedesktop.DBus \
                      /org/freedesktop/DBus \
                      org.freedesktop.DBus.ListActivatableNames
                    """.trimIndent(),
                    fakeRoot = false,
                    timeoutSeconds = 8,
                ),
                "policyKitActivation" to probe(
                    """
                    dbus-send \
                      --system \
                      --print-reply \
                      --dest=org.freedesktop.PolicyKit1 \
                      /org/freedesktop/PolicyKit1/Authority \
                      org.freedesktop.DBus.Peer.Ping
                    """.trimIndent(),
                    fakeRoot = false,
                    timeoutSeconds = 8,
                ),
                "packageKitActivation" to probe(
                    """
                    dbus-send \
                      --system \
                      --print-reply \
                      --dest=org.freedesktop.PackageKit \
                      /org/freedesktop/PackageKit \
                      org.freedesktop.DBus.Peer.Ping
                    """.trimIndent(),
                    fakeRoot = false,
                    timeoutSeconds = 12,
                ),
                "dpkgHealth" to probe(
                    """
                    set +e
                    printf 'status: '
                    stat -c '%s bytes' /var/lib/dpkg/status 2>&1
                    printf 'status-old: '
                    stat -c '%s bytes' /var/lib/dpkg/status-old 2>&1
                    printf 'packages: '
                    dpkg-query -W 2>/dev/null | wc -l
                    echo 'audit:'
                    dpkg --audit 2>&1 || true
                    echo 'pending updates:'
                    find /var/lib/dpkg/updates -maxdepth 1 -type f -printf '%f %s\n' 2>/dev/null | sort | head -n 60
                    echo 'backups:'
                    ls -lht /var/backups/dpkg.status* 2>/dev/null | head -n 20 || true
                    echo 'status targets:'
                    readlink /var/lib/dpkg/status 2>/dev/null || true
                    readlink /var/lib/dpkg/status-old 2>/dev/null || true
                    exit 0
                    """.trimIndent(),
                    fakeRoot = true,
                    timeoutSeconds = 8,
                ),
                "plasmaQmlRuntime" to probe(
                    "/usr/local/lib/proroot/check-qml-runtime.sh --files-only",
                    fakeRoot = false,
                    timeoutSeconds = 10,
                ),
                "desktopApplications" to probe(
                    """
                    set -e
                    brave-browser-stable --version
                    firefox-esr --version
                    code --version | head -n1
                    test -x /usr/bin/konsole
                    test -x /usr/bin/dolphin
                    test -x /usr/bin/kate
                    """.trimIndent(),
                    fakeRoot = false,
                    timeoutSeconds = 10,
                ),
            ).apply {
                if (session.isRunning()) {
                    val desktopHealth = session.health()
                    put(
                        "desktopHealth",
                        mapOf(
                            "ok" to desktopHealth.successful,
                            "exitCode" to desktopHealth.exitCode,
                            "output" to desktopHealth.output.takeLast(12_000),
                        ),
                    )
                    put(
                        "display",
                        probe(
                            """
                            . /usr/local/lib/proroot/session-env.sh
                            load_proroot_session_env
                            kscreen-doctor -j | head -c 12000
                            """.trimIndent(),
                            fakeRoot = false,
                            timeoutSeconds = 8,
                        ),
                    )
                }
            }
        } else if (safeDiagnostics) {
            mapOf(
                "safeMode" to mapOf(
                    "ok" to true,
                    "exitCode" to 0,
                    "output" to if (liveDesktop) {
                        "Live guest probes skipped while KDE is running. Diagnostics are read-only so the active ProRoot/Anland session is not disturbed."
                    } else {
                        "Live guest probes skipped after a failed desktop start. Persistent logs, crash maps, Android exit history and binary hashes are still included."
                    },
                ),
            )
        } else {
            emptyMap()
        }

        return linkedMapOf(
            "status" to status.asMap(),
            "build" to mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "gitSha" to BuildConfig.GIT_SHA,
                "prorootVersion" to BuildConfig.PROROOT_VERSION,
            ),
            "prorootLibraries" to prorootLibraries(),
            "androidProcessExitHistory" to androidExitHistory(),
            "sessionEnvironment" to sessionEnvironment,
            "nativeLibraryDir" to context.applicationInfo.nativeLibraryDir,
            "rootfs" to paths.rootfs.absolutePath,
            "containerRuntime" to runner.runtimeId,
            "rollbackAvailable" to installer.canRollback(),
            "anlandSocket" to paths.anlandSocket.absolutePath,
            "anlandSocketReady" to paths.anlandSocket.exists(),
            "anlandDaemon" to daemon.isRunning(),
            "systemServices" to systemServices.isRunning(),
            "desktopProcess" to session.isRunning(),
            "kwinQtQuickRenderer" to kwinQtQuickRenderer,
            "desktopUid" to android.os.Process.myUid(),
            "installedApps" to if (installed) appCatalog.list().size else 0,
            "installInProgress" to paths.installInProgress.isFile,
            "stagingRootfsExists" to paths.rootfsStaging.isDirectory,
            "downloadCacheBytes" to paths.cacheDir
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() },
            "aptArchiveCacheBytes" to paths.aptArchivesDir
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() },
            "stagingResumeMarker" to File(
                paths.rootfsStaging,
                ".proroot-staging-resumable",
            ).isFile,
            "installLogBytes" to paths.installLog.takeIf { it.isFile }?.length().orZero(),
            "prorootCrashDumps" to prorootCrashDumps,
            "probes" to probes,
            "logs" to logs,
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun prorootLibraries(): Map<String, Any?> {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return nativeDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("libproroot") && it.name.endsWith(".so") }
            ?.sortedBy { it.name }
            ?.associate { library ->
                library.name to mapOf(
                    "bytes" to library.length(),
                    "sha256" to sha256(library),
                )
            }
            ?: emptyMap()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun androidExitHistory(): List<Map<String, Any?>> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 6).map { info ->
                val trace = runCatching {
                    info.traceInputStream
                        ?.bufferedReader()
                        ?.use { reader -> reader.readText().takeLast(24_000) }
                }.getOrNull()
                linkedMapOf<String, Any?>(
                    "timestamp" to info.timestamp,
                    "reason" to exitReason(info.reason),
                    "reasonCode" to info.reason,
                    "status" to info.status,
                    "importance" to info.importance,
                    "description" to info.description,
                ).apply {
                    if (!trace.isNullOrBlank()) put("trace", trace)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "OTHER"
    }

    private fun readTail(file: File, maxChars: Int): String {
        val text = runCatching { file.readText() }
            .getOrElse { error -> return "<could not read: ${error.message}>" }
        if (text.length <= maxChars) return text
        return "[truncated to last $maxChars characters]\n" + text.takeLast(maxChars)
    }

    private fun probe(
        command: String,
        fakeRoot: Boolean,
        timeoutSeconds: Long = 6,
    ): Map<String, Any?> {
        val result = runCatching {
            runner.exec(
                command = command,
                timeoutSeconds = timeoutSeconds,
                fakeRoot = fakeRoot,
            )
        }.getOrElse { error ->
            return mapOf(
                "ok" to false,
                "exitCode" to -1,
                "output" to error.stackTraceToString().takeLast(8_000),
            )
        }

        return mapOf(
            "ok" to result.successful,
            "exitCode" to result.exitCode,
            "output" to result.output.takeLast(12_000),
        )
    }
}
