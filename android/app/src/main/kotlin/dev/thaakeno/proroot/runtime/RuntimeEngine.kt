package dev.thaakeno.proroot.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.thaakeno.proroot.install.RuntimeInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class RuntimeEngine private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: RuntimeEngine? = null

        fun get(context: Context): RuntimeEngine =
            instance ?: synchronized(this) {
                instance ?: RuntimeEngine(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val paths = RuntimePaths(context).also { it.ensureHostDirectories() }
    private val runner = ProrootRunner(context, paths)
    private val daemon = AnlandDaemon(context, paths)
    private val session = DesktopSession(runner, paths)
    private val installer = RuntimeInstaller(context, paths, runner)

    @Volatile private var refreshRate = 120
    @Volatile private var scale = 1.0
    @Volatile private var performanceProfile = "balanced"

    init {
        val installed = paths.installMarker.isFile && paths.rootfs.isDirectory
        RuntimeEvents.publish(
            RuntimeStatus(
                phase = if (installed) RuntimePhase.ready else RuntimePhase.missing,
                message = if (installed) "Ready" else "Linux environment is not installed",
                installed = installed,
                running = false,
            ),
        )
    }

    fun status(): RuntimeStatus = RuntimeEvents.latest

    fun install() {
        startForegroundHost()
        scope.launch {
            mutex.withLock {
                if (session.isRunning()) return@withLock
                try {
                    installer.install { status -> RuntimeEvents.publish(status) }
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.ready,
                            progress = 1.0,
                            message = "Linux PC is ready",
                            installed = true,
                        ),
                    )
                } catch (t: Throwable) {
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.failed,
                            message = "Installation failed",
                            detail = t.stackTraceToString().takeLast(16_000),
                            installed = paths.installMarker.isFile,
                        ),
                    )
                }
            }
        }
    }

    fun start() {
        startForegroundHost()
        scope.launch {
            mutex.withLock {
                if (!paths.installMarker.isFile) {
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.missing,
                            message = "Install Linux first",
                        ),
                    )
                    return@withLock
                }
                if (session.isRunning()) return@withLock

                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = RuntimePhase.starting,
                        message = "Starting native Wayland desktop",
                        installed = true,
                    ),
                )
                try {
                    paths.resetTransientState()
                    daemon.start()
                    session.start(refreshRate, scale) { exitCode ->
                        if (RuntimeEvents.latest.phase != RuntimePhase.stopping) {
                            RuntimeEvents.publish(
                                RuntimeStatus(
                                    phase = RuntimePhase.failed,
                                    message = "Linux desktop stopped",
                                    detail = "Desktop process exited with code $exitCode. See diagnostics for full logs.",
                                    installed = true,
                                ),
                            )
                        }
                    }
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.running,
                            progress = 1.0,
                            message = "KDE Plasma is running",
                            installed = true,
                            running = true,
                        ),
                    )
                } catch (t: Throwable) {
                    daemon.stop()
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.failed,
                            message = "Could not start Linux",
                            detail = t.message,
                            installed = true,
                        ),
                    )
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = RuntimePhase.stopping,
                        message = "Stopping Linux",
                        installed = paths.installMarker.isFile,
                        running = true,
                    ),
                )
                session.stop()
                daemon.stop()
                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = if (paths.installMarker.isFile) RuntimePhase.ready else RuntimePhase.missing,
                        message = if (paths.installMarker.isFile) "Ready" else "Linux environment is not installed",
                        installed = paths.installMarker.isFile,
                    ),
                )
            }
        }
    }

    fun reset() {
        scope.launch {
            mutex.withLock {
                session.stop()
                daemon.stop()
                paths.rootfs.deleteRecursively()
                paths.rootfsStaging.deleteRecursively()
                paths.rootfsPrevious.deleteRecursively()
                paths.installMarker.delete()
                RuntimeEvents.publish(RuntimeStatus())
            }
        }
    }

    fun exec(command: String): CommandResult {
        check(paths.installMarker.isFile) { "Linux is not installed" }
        return runner.exec(command)
    }

    fun launchDesktopApp(desktopId: String) {
        require(desktopId.matches(Regex("[A-Za-z0-9._+-]+"))) { "Invalid desktop id" }
        scope.launch {
            val appId = desktopId.removeSuffix(".desktop")
            runner.exec(
                """
                runuser -u linux -- env                   HOME=/home/linux                   XDG_RUNTIME_DIR=/run/user/1000                   XDG_CURRENT_DESKTOP=KDE                   XDG_SESSION_TYPE=wayland                   WAYLAND_DISPLAY=wayland-0                   bash -lc 'gtk-launch $appId >/dev/null 2>&1 &'
                """.trimIndent(),
                timeoutSeconds = 10,
            )
        }
    }

    fun setDisplayOptions(refresh: Int, scale: Double) {
        refreshRate = refresh.coerceIn(60, 165)
        this.scale = scale.coerceIn(0.75, 2.0)
    }

    fun setPerformanceProfile(profile: String) {
        performanceProfile = when (profile) {
            "efficiency", "performance" -> profile
            else -> "balanced"
        }
    }

    fun diagnostics(): Map<String, Any?> {
        val logFiles = paths.logsDir.listFiles()
            ?.sortedByDescending(File::lastModified)
            ?.associate { it.name to it.readText().takeLast(32_000) }
            ?: emptyMap()
        return mapOf(
            "status" to status().asMap(),
            "nativeLibraryDir" to context.applicationInfo.nativeLibraryDir,
            "rootfs" to paths.rootfs.absolutePath,
            "anlandSocket" to paths.anlandSocket.absolutePath,
            "anlandDaemon" to daemon.isRunning(),
            "desktopProcess" to session.isRunning(),
            "performanceProfile" to performanceProfile,
            "logs" to logFiles,
        )
    }

    private fun startForegroundHost() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LinuxRuntimeService::class.java)
                .setAction(LinuxRuntimeService.ACTION_KEEP_ALIVE),
        )
    }
}
