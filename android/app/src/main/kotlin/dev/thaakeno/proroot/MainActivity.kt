package dev.thaakeno.proroot

import android.os.Bundle
import dev.thaakeno.proroot.bridge.RuntimeChannel
import dev.thaakeno.proroot.display.LinuxDisplayFactory
import dev.thaakeno.proroot.runtime.RuntimePaths
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import java.io.File

class MainActivity : FlutterActivity() {
    companion object {
        @Volatile private var crashHandlerInstalled = false
    }

    private var runtimeChannel: RuntimeChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!crashHandlerInstalled) synchronized(MainActivity::class.java) {
            if (!crashHandlerInstalled) {
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                val log = File(RuntimePaths(applicationContext).logsDir, "android-uncaught-exception.log")
                Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                    runCatching {
                        log.parentFile?.mkdirs()
                        log.appendText(
                            "${System.currentTimeMillis()} thread=${thread.name}\n" +
                                failure.stackTraceToString() + "\n",
                        )
                    }
                    previous?.uncaughtException(thread, failure)
                }
                crashHandlerInstalled = true
            }
        }
    }

    // Keep Flutter itself off a SurfaceView. The Linux desktop owns the only
    // SurfaceView in this Activity; this avoids Android/Flutter compositor
    // conflicts where the Anland producer is healthy but its surface is black.
    override fun getRenderMode(): RenderMode = RenderMode.texture

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        runtimeChannel = RuntimeChannel(
            context = applicationContext,
            messenger = flutterEngine.dartExecutor.binaryMessenger,
        ).also { it.attach() }

        flutterEngine.platformViewsController.registry.registerViewFactory(
            LinuxDisplayFactory.VIEW_TYPE,
            LinuxDisplayFactory(),
        )
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        runtimeChannel?.detach()
        runtimeChannel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
