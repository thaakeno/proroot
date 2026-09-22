package dev.thaakeno.proroot.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.thaakeno.proroot.display.LinuxDisplayRegistry
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

    private val startupStageOrder = listOf(
        "Preparing Linux runtime",
        "Repairing package links",
        "Checking package database",
        "Repairing interrupted packages",
        "Refreshing Debian package metadata",
        "Installing Plasma runtime modules",
        "Verifying Plasma runtime",
        "Checking Plasma QML runtime",
        "Starting display transport",
        "Starting system services",
        "Starting KDE Plasma",
        "Validating KDE desktop",
        "Applying desktop settings",
    )

    init {
        val recoveryFailure = runCatching {
            installer.recoverInterruptedActivation()
        }.exceptionOrNull()
        val installed = paths.installMarker.isFile && paths.rootfs.isDirectory
        val lastInstallFailure = installer.lastFailure()
        val interruptedInstall = installer.wasInterrupted()
        val previousRuntimeCrash = File(paths.logsDir, "proroot-crash.log")
            .takeIf { it.isFile }
            ?.readText()
            ?.takeIf { it.contains("[proroot] SIGSEGV") || it.contains("[proroot] SIGABRT") }

        RuntimeEvents.publish(
            when {
                recoveryFailure != null -> RuntimeStatus(
                    phase = RuntimePhase.failed,
                    message = "Linux environment recovery failed",
                    detail = recoveryFailure.stackTraceToString().takeLast(16_000),
                    installed = installed,
                    running = false,
                )
                installed && previousRuntimeCrash != null -> RuntimeStatus(
                    phase = RuntimePhase.failed,
                    progress = 0.0,
                    message = "Previous Linux session crashed",
                    detail = previousRuntimeCrash
                        .lineSequence()
                        .lastOrNull { line ->
                            line.contains("[proroot] SIGSEGV") ||
                                line.contains("[proroot] SIGABRT") ||
                                line.contains("[proroot] SIGBUS")
                        }
                        ?: previousRuntimeCrash.takeLast(2_000),
                    installed = true,
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

                val startupStartedAt = System.nanoTime()
                val startupHistory = mutableListOf<String>()
                val publishStage: (String) -> Unit = { stage ->
                    if (startupHistory.lastOrNull() != stage) startupHistory += stage
                    publishStarting(stage, startupHistory, startupStartedAt)
                }
                publishStage("Preparing Linux runtime")
                paths.prorootCrashDumps.forEach(File::delete)

                val failure = runCatching {
                    installer.prepareInstalledRuntime(publishStage)

                    publishStage("Checking Plasma QML runtime")
                    val qmlProbe = runner.exec(
                        command = "/usr/local/lib/proroot/check-qml-runtime.sh --files-only",
                        timeoutSeconds = 10,
                        fakeRoot = false,
                    )
                    File(paths.logsDir, "qml-runtime.log").writeText(qmlProbe.output)
                    check(qmlProbe.successful) {
                        "Plasma QML import probe failed via ${runner.runtimeId} " +
                            "(exit ${qmlProbe.exitCode}):\n" +
                            qmlProbe.output.takeLast(4_000)
                    }

                    startDesktopOnce(publishStage)
                }.exceptionOrNull()

                if (failure == null) {
                    publishRunning("KDE Plasma is running")
                    return@withLock
                }

                val consumerStopped = stopDisplayConsumerBeforeRuntime()
                session.stop()
                systemServices.stop()
                publishStartFailure(failure)
                stopDisplayTransportAfterConsumerStop(consumerStopped)
                stopForegroundHost()
            }
        }
    }

    private fun startDesktopOnce(onStage: (String) -> Unit) {
        paths.prorootCrashDumps.forEach(File::delete)
        paths.resetTransientState()
        listOf(
            "desktop-session.log",
            "system-services.log",
            "proroot-crash.log",
            "proroot-system-services.log",
        ).forEach { name ->
            File(paths.logsDir, name).delete()
        }
        onStage("Starting display transport")
        daemon.start()
        onStage("Starting system services")
        systemServices.start()
        onStage("Starting KDE Plasma")
        session.start(refreshRate, scale, onStage = onStage) { exitCode ->
            if (RuntimeEvents.latest.phase == RuntimePhase.running) {
                scope.launch {
                    mutex.withLock {
                        if (RuntimeEvents.latest.phase != RuntimePhase.running) return@withLock
                        val consumerStopped = stopDisplayConsumerBeforeRuntime()
                        RuntimeEvents.publish(
                            RuntimeStatus(
                                phase = RuntimePhase.failed,
                                message = "Linux desktop stopped",
                                detail = "Desktop process exited with code $exitCode. See diagnostics for full logs.",
                                installed = true,
                                running = false,
                            ),
                        )
                        systemServices.stop()
                        stopDisplayTransportAfterConsumerStop(consumerStopped)
                        stopForegroundHost()
                    }
                }
            }
        }
    }

    private fun publishStarting(
        message: String,
        history: List<String>,
        startedAtNanos: Long,
    ) {
        val index = startupStageOrder.indexOf(message)
            .takeIf { it >= 0 }
            ?: history.lastIndex.coerceAtLeast(0)
        val progress = ((index + 1).toDouble() / (startupStageOrder.size + 1))
            .coerceIn(0.04, 0.96)
        val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000_000L)
            .coerceAtLeast(0L)
        val recent = history.takeLast(5)
        val stageDetail = buildString {
            recent.forEachIndexed { recentIndex, stage ->
                val active = recentIndex == recent.lastIndex
                append(if (active) "› " else "✓ ")
                appendLine(stage)
            }
        }.trimEnd()

        RuntimeEvents.publish(
            RuntimeStatus(
                phase = RuntimePhase.starting,
                progress = progress,
                message = message,
                elapsedSeconds = elapsed,
                stageProgress = progress,
                stageDetail = stageDetail,
                completedItems = index.coerceAtLeast(0),
                totalItems = startupStageOrder.size,
                installed = true,
                running = false,
            ),
        )
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
        val recentLogs = paths.logsDir.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?.take(4)
            ?.joinToString(separator = "\n\n") { log ->
                buildString {
                    appendLine("===== ${log.name} =====")
                    append(log.readText().takeLast(3_500))
                }
            }
            .orEmpty()

        RuntimeEvents.publish(
            RuntimeStatus(
                phase = RuntimePhase.failed,
                message = "Could not start Linux",
                detail = buildString {
                    if (recentLogs.isNotBlank()) {
                        appendLine(recentLogs)
                        appendLine()
                    }
                    if (paths.prorootCrashDumps.any { it.isFile && it.length() > 0L }) {
                        appendLine("ProRoot crash dump captured; see diagnostics.prorootCrashDumps.")
                        appendLine()
                    }
                    appendLine("===== startup exception =====")
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
                val consumerStopped = stopDisplayConsumerBeforeRuntime()
                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = RuntimePhase.stopping,
                        message = "Stopping Linux",
                        installed = paths.installMarker.isFile,
                        running = false,
                    ),
                )
                session.stop()
                systemServices.stop()
                stopDisplayTransportAfterConsumerStop(consumerStopped)
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
                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = RuntimePhase.stopping,
                        message = "Resetting Linux",
                        installed = paths.installMarker.isFile,
                        running = session.isRunning(),
                    ),
                )
                val consumerStopped = stopDisplayConsumerBeforeRuntime()
                RuntimeEvents.publish(
                    RuntimeStatus(
                        phase = RuntimePhase.stopping,
                        message = "Resetting Linux",
                        installed = paths.installMarker.isFile,
                        running = false,
                    ),
                )
                session.stop()
                systemServices.stop()
                stopDisplayTransportAfterConsumerStop(consumerStopped)
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

    private fun stopDisplayConsumerBeforeRuntime(): Boolean {
        val stopped = LinuxDisplayRegistry.stopConsumerAndAwait()
        if (!stopped) {
            File(paths.logsDir, "anland-daemon.log").appendText(
                "display consumer did not stop within 5s; deferring daemon teardown to avoid JNI race\n",
            )
        }
        return stopped
    }

    private fun stopDisplayTransportAfterConsumerStop(consumerStopped: Boolean) {
        val detached = LinuxDisplayRegistry.awaitDetached(timeoutMs = 2_500)
        if (consumerStopped || detached) {
            daemon.stop()
        } else {
            File(paths.logsDir, "anland-daemon.log").appendText(
                "display consumer is still attached after runtime stop; keeping daemon alive to avoid JNI teardown race\n",
            )
        }
    }

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
