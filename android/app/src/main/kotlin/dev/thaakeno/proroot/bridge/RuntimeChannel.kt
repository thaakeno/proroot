package dev.thaakeno.proroot.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.thaakeno.proroot.display.DisplaySettings
import dev.thaakeno.proroot.display.LinuxDisplayRegistry
import dev.thaakeno.proroot.runtime.RuntimeEngine
import dev.thaakeno.proroot.runtime.RuntimeEvents
import dev.thaakeno.proroot.runtime.RuntimeStatus
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RuntimeChannel(
    context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val engine = RuntimeEngine.get(context)
    private val methods = MethodChannel(messenger, "dev.thaakeno.proroot/runtime")
    private val events = EventChannel(messenger, "dev.thaakeno.proroot/runtime_events")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var sink: EventChannel.EventSink? = null
    private val statusListener: (RuntimeStatus) -> Unit = { status ->
        main.post { sink?.success(status.asMap()) }
    }

    fun attach() {
        methods.setMethodCallHandler(this)
        events.setStreamHandler(this)
    }

    fun detach() {
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        RuntimeEvents.remove(statusListener)
        sink = null
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink) {
        sink = eventSink
        RuntimeEvents.add(statusListener)
        eventSink.success(engine.status().asMap())
    }

    override fun onCancel(arguments: Any?) {
        RuntimeEvents.remove(statusListener)
        sink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> result.success(engine.status().asMap())
            "install" -> {
                engine.install()
                result.success(null)
            }
            "start" -> {
                engine.start()
                result.success(null)
            }
            "stop" -> {
                engine.stop()
                result.success(null)
            }
            "reset" -> {
                engine.reset()
                result.success(null)
            }
            "exec" -> {
                val command = call.argument<String>("command")
                if (command.isNullOrBlank()) {
                    result.error("invalid_command", "Command is empty", null)
                    return
                }
                scope.launch {
                    runCatching { engine.exec(command) }
                        .onSuccess { commandResult ->
                            main.post {
                                if (commandResult.successful) result.success(commandResult.output)
                                else result.error(
                                    "command_failed",
                                    "Linux command exited ${commandResult.exitCode}",
                                    commandResult.output,
                                )
                            }
                        }
                        .onFailure { error ->
                            main.post { result.error("exec_failed", error.message, null) }
                        }
                }
            }
            "desktopApps" -> scope.launch {
                runCatching { engine.desktopApps() }
                    .onSuccess { apps -> main.post { result.success(apps) } }
                    .onFailure { error -> main.post { result.error("apps_failed", error.message, null) } }
            }
            "launchDesktopApp" -> {
                val desktopId = call.argument<String>("desktopId")
                if (desktopId.isNullOrBlank()) {
                    result.error("invalid_desktop_id", "Desktop id is empty", null)
                } else {
                    scope.launch {
                        runCatching { engine.launchDesktopApp(desktopId) }
                            .onSuccess { main.post { result.success(null) } }
                            .onFailure { error ->
                                main.post {
                                    result.error("launch_failed", error.message, null)
                                }
                            }
                    }
                }
            }
            "showKeyboard" -> result.success(LinuxDisplayRegistry.showKeyboard())
            "setPointerCapture" -> result.success(
                LinuxDisplayRegistry.setPointerCapture(
                    call.argument<Boolean>("enabled") == true,
                ),
            )
            "deviceInfo" -> scope.launch {
                runCatching { engine.deviceInfo() }
                    .onSuccess { info -> main.post { result.success(info) } }
                    .onFailure { error ->
                        main.post {
                            result.error(
                                "device_info_failed",
                                error.message,
                                error.stackTraceToString().takeLast(4_000),
                            )
                        }
                    }
            }
            "diagnostics" -> scope.launch {
                runCatching { engine.diagnostics() }
                    .onSuccess { diagnostics ->
                        main.post { result.success(diagnostics) }
                    }
                    .onFailure { error ->
                        main.post {
                            result.error(
                                "diagnostics_failed",
                                error.message,
                                error.stackTraceToString().takeLast(8_000),
                            )
                        }
                    }
            }
            "setDisplayOptions" -> {
                val refresh = call.argument<Number>("refreshRate")?.toInt() ?: 120
                val scale = call.argument<Number>("scale")?.toDouble() ?: 1.0
                val inputMode = call.argument<String>("inputMode") ?: "trackpad"
                engine.setDisplayOptions(refresh, scale)
                DisplaySettings.update(refresh, scale, inputMode)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }
}
