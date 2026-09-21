package dev.thaakeno.proroot.runtime

import android.content.Context
import dev.thaakeno.proroot.install.RuntimeInstaller
import java.io.File

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
        val logs = paths.logsDir.listFiles()
            ?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?.associate { it.name to it.readText() }
            ?: emptyMap()

        val probes = if (installed) {
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
                "plasmaQmlRuntime" to probe(
                    """
                    set -e
                    test -f /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/plasma/core/qmldir
                    test -r /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/plasma/core/libcorebindingsplugin.so
                    test -f /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/qmldir
                    test -r /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/libcorebindingsplugin.so
                    dpkg-query -W plasma-desktoptheme qml6-module-org-kde-ksvg
                    """.trimIndent(),
                    fakeRoot = false,
                    timeoutSeconds = 8,
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
        } else {
            emptyMap()
        }

        return linkedMapOf(
            "status" to status.asMap(),
            "nativeLibraryDir" to context.applicationInfo.nativeLibraryDir,
            "rootfs" to paths.rootfs.absolutePath,
            "containerRuntime" to runner.runtimeId,
            "rollbackAvailable" to installer.canRollback(),
            "anlandSocket" to paths.anlandSocket.absolutePath,
            "anlandSocketReady" to paths.anlandSocket.exists(),
            "anlandDaemon" to daemon.isRunning(),
            "systemServices" to systemServices.isRunning(),
            "desktopProcess" to session.isRunning(),
            "desktopUid" to android.os.Process.myUid(),
            "installedApps" to if (installed) appCatalog.list().size else 0,
            "installInProgress" to paths.installInProgress.isFile,
            "stagingRootfsExists" to paths.rootfsStaging.isDirectory,
            "downloadCacheBytes" to paths.cacheDir
                .walkTopDown()
                .filter(File::isFile)
                .sumOf { it.length() },
            "aptArchiveCacheBytes" to paths.aptArchivesDir
                .walkTopDown()
                .filter(File::isFile)
                .sumOf { it.length() },
            "stagingResumeMarker" to File(
                paths.rootfsStaging,
                ".proroot-staging-resumable",
            ).isFile,
            "installLogBytes" to paths.installLog.takeIf { it.isFile }?.length().orZero(),
            "probes" to probes,
            "logs" to logs,
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L

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
