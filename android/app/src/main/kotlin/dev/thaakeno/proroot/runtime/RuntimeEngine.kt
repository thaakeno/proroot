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
    private val installRunner = InstallProotRunner(context, paths)
    private val daemon = AnlandDaemon(context, paths)
    private val systemServices = SystemServicesSession(runner, paths)
    private val session = DesktopSession(runner, paths, daemon)
    private val installer = RuntimeInstaller(
        context = context,
        paths = paths,
        installRunner = installRunner,
    )
    private val appCatalog = DesktopAppCatalog(paths)
    private val appLauncher = DesktopAppLauncher(runner, paths)
    private val diagnosticsCollector = RuntimeDiagnostics(
        context = context,
        paths = paths,
        runner = runner,
        daemon = daemon,
        systemServices = systemServices,
        session = session,
        installer = installer,
        appCatalog = appCatalog,
    )

    @Volatile private var refreshRate = 120
    @Volatile private var scale = 1.0

    init {
        val recoveryFailure = runCatching {
            installer.recoverInterruptedActivation()
        }.exceptionOrNull()
        val installed = paths.installMarker.isFile && paths.rootfs.isDirectory
        val lastInstallFailure = installer.lastFailure()
        val interruptedInstall = installer.wasInterrupted()

        RuntimeEvents.publish(
            when {
                recoveryFailure != null -> RuntimeStatus(
                    phase = RuntimePhase.failed,
                    message = "Linux environment recovery failed",
                    detail = recoveryFailure.stackTraceToString().takeLast(16_000),
                    installed = installed,
                    running = false,
                )
                installed -> RuntimeStatus(
                    phase = RuntimePhase.ready,
                    progress = 1.0,
                    message = "Ready",
                    installed = true,
                    running = false,
                )
                lastInstallFailure != null -> RuntimeStatus(
                    phase = RuntimePhase.failed,
                    message = "Last installation failed",
                    detail = lastInstallFailure.takeLast(16_000),
                    installed = false,
                    running = false,
                )
                interruptedInstall -> RuntimeStatus(
                    phase = RuntimePhase.failed,
                    message = "Previous installation was interrupted",
                    detail = paths.installLog
                        .takeIf { it.isFile }
                        ?.readText()
                        ?.takeLast(16_000),
                    installed = false,
                    running = false,
                )
                else -> RuntimeStatus(
                    phase = RuntimePhase.missing,
                    message = "Linux environment is not installed",
                    installed = false,
                    running = false,
                )
            },
        )
    }

    fun status(): RuntimeStatus = RuntimeEvents.latest

    fun install() {
        scope.launch {
            mutex.withLock {
                if (session.isRunning()) return@withLock
                startForegroundHost()
                try {
                    installer.install(RuntimeEvents::publish)
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.ready,
                            progress = 1.0,
                            message = "Linux PC is ready",
                            installed = true,
                        ),
                    )
                    stopForegroundHost()
                } catch (t: Throwable) {
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.failed,
                            message = "Installation failed",
                            detail = installer.lastFailure()?.takeLast(16_000)
                                ?: t.stackTraceToString().takeLast(16_000),
                            installed = paths.installMarker.isFile,
                        ),
                    )
                    stopForegroundHost()
                }
            }
        }
    }

    fun start() {
        scope.launch {
            mutex.withLock {
                if (!paths.installMarker.isFile) {
                    RuntimeEvents.publish(RuntimeStatus(phase = RuntimePhase.missing, message = "Install Linux first"))
                    return@withLock
                }
                if (session.isRunning()) return@withLock
                startForegroundHost()

                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = RuntimePhase.starting,
                        message = "Preparing Linux runtime",
                        installed = true,
                    ),
                )

                var firstFailure = runCatching {
                    installer.prepareInstalledRuntime()
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.starting,
                            message = "Starting KDE Plasma",
                            installed = true,
                        ),
                    )
                    startDesktopOnce()
                }.exceptionOrNull()
                if (firstFailure == null) {
                    publishRunning("KDE Plasma is running")
                    return@withLock
                }

                session.stop()
                systemServices.stop()
                daemon.stop()

                if (installer.canRollback()) {
                    RuntimeEvents.publish(
                        RuntimeStatus(
                            phase = RuntimePhase.starting,
                            message = "Recovering previous Linux environment",
                            detail = firstFailure.message,
                            installed = true,
                        ),
                    )

                    val recoveryFailure = runCatching {
                        installer.rollback()
                        paths.resetTransientState()
                        startDesktopOnce()
                    }.exceptionOrNull()

                    if (recoveryFailure == null) {
                        publishRunning("Recovered previous Linux environment")
                        return@withLock
                    }

                    session.stop()
                    systemServices.stop()
                    daemon.stop()
                    publishStartFailure(recoveryFailure, firstFailure)
                    return@withLock
                }

                publishStartFailure(firstFailure)
            }
        }
    }

    private fun startDesktopOnce() {
        paths.resetTransientState()
        daemon.start()
        systemServices.start()
        session.start(refreshRate, scale) { exitCode ->
            if (RuntimeEvents.latest.phase == RuntimePhase.running) {
                scope.launch {
                    mutex.withLock {
                        if (RuntimeEvents.latest.phase != RuntimePhase.running) return@withLock
                        systemServices.stop()
                        daemon.stop()
                        RuntimeEvents.publish(
                            RuntimeStatus(
                                phase = RuntimePhase.failed,
                                message = "Linux desktop stopped",
                                detail = "Desktop process exited with code $exitCode. See diagnostics for full logs.",
                                installed = true,
                            ),
                        )
                        stopForegroundHost()
                    }
                }
            }
        }
    }

    private fun publishRunning(message: String) {
        RuntimeEvents.publish(
            RuntimeStatus(
                phase = RuntimePhase.running,
                progress = 1.0,
                message = message,
                installed = true,
                running = true,
            ),
        )
    }

    private fun publishStartFailure(primary: Throwable, original: Throwable? = null) {
        RuntimeEvents.publish(
            RuntimeStatus(
                phase = RuntimePhase.failed,
                message = "Could not start Linux",
                detail = buildString {
                    appendLine(primary.stackTraceToString())
                    if (original != null && original !== primary) {
                        appendLine()
                        appendLine("Initial runtime failure:")
                        append(original.stackTraceToString())
                    }
                }.takeLast(16_000),
                installed = paths.installMarker.isFile,
            ),
        )
        stopForegroundHost()
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
                systemServices.stop()
                daemon.stop()
                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = if (paths.installMarker.isFile) RuntimePhase.ready else RuntimePhase.missing,
                        message = if (paths.installMarker.isFile) "Ready" else "Linux environment is not installed",
                        installed = paths.installMarker.isFile,
                    ),
                )
                stopForegroundHost()
            }
        }
    }

    fun reset() {
        scope.launch {
            mutex.withLock {
                session.stop()
                systemServices.stop()
                daemon.stop()
                paths.rootfs.deleteRecursively()
                paths.rootfsStaging.deleteRecursively()
                paths.rootfsPrevious.deleteRecursively()
                paths.installMarker.delete()
                paths.previousInstallMarker.delete()
                RuntimeEvents.publish(RuntimeStatus())
                stopForegroundHost()
            }
        }
    }

    fun exec(command: String): CommandResult {
        check(paths.installMarker.isFile) { "Linux is not installed" }
        return runner.exec(command)
    }

    fun desktopApps(): List<Map<String, Any?>> = appCatalog.list().map(DesktopApp::asMap)

    fun launchDesktopApp(desktopId: String) {
        check(session.isRunning()) { "Linux desktop is not running" }
        appLauncher.launch(desktopId)
    }

    fun setDisplayOptions(refresh: Int, scale: Double) {
        refreshRate = refresh.coerceIn(60, 165)
        val safeScale = scale.coerceIn(0.75, 2.0)
        val scaleChanged = this.scale != safeScale
        this.scale = safeScale

        if (scaleChanged && session.isRunning()) {
            scope.launch {
                mutex.withLock {
                    if (!session.isRunning()) return@withLock
                    val result = session.applyScale(safeScale)
                    if (!result.successful) {
                        File(paths.logsDir, "display-controls.log").appendText(
                            "Live scale $safeScale failed (exit ${result.exitCode}):\n${result.output}\n",
                        )
                    }
                }
            }
        }
    }

    fun diagnostics(): Map<String, Any?> =
        diagnosticsCollector.collect(status())

    private fun startForegroundHost() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LinuxRuntimeService::class.java)
                .setAction(LinuxRuntimeService.ACTION_KEEP_ALIVE),
        )
    }

    private fun stopForegroundHost() {
        context.stopService(Intent(context, LinuxRuntimeService::class.java))
    }
}
