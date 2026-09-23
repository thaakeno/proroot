package dev.thaakeno.proroot.runtime

import dev.thaakeno.proroot.display.LinuxDisplayRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File

internal fun runtimeFailureHandler(paths: RuntimePaths) = CoroutineExceptionHandler { _, failure ->
    runCatching {
        File(paths.logsDir, "android-runtime-failure.log").appendText(
            "${System.currentTimeMillis()} ${failure.stackTraceToString()}\n",
        )
    }
    RuntimeEvents.publish(
        RuntimeStatus(
            phase = RuntimePhase.failed,
            message = "Android runtime operation failed",
            detail = failure.stackTraceToString().takeLast(12_000),
            installed = paths.installMarker.isFile,
            running = false,
        ),
    )
}

internal fun shutdownRuntimeComponents(
    session: DesktopSession,
    systemServices: SystemServicesSession,
    daemon: AnlandDaemon,
): List<String> {
    val failures = mutableListOf<String>()
    fun stopStep(name: String, action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            failures += "$name: ${failure.stackTraceToString()}"
        }
    }
    stopStep("desktop") { session.stop() }
    stopStep("system services") { systemServices.stop() }
    val consumerStopped = runCatching { LinuxDisplayRegistry.stopConsumerAndAwait() }
        .onFailure { failure -> failures += "display consumer: ${failure.stackTraceToString()}" }
        .getOrDefault(false)
    if (!consumerStopped) failures += "display consumer did not finish stopping"
    // nativeStop owns the daemon transport. A timeout must never tear its
    // socket down while a native worker may still be using it.
    if (consumerStopped) stopStep("Anland daemon") { daemon.stop() }
    return failures
}
