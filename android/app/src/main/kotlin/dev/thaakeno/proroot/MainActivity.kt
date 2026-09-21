package dev.thaakeno.proroot

import dev.thaakeno.proroot.bridge.RuntimeChannel
import dev.thaakeno.proroot.display.LinuxDisplayFactory
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var runtimeChannel: RuntimeChannel? = null

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
