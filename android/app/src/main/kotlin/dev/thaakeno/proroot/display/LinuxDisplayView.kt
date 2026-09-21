package dev.thaakeno.proroot.display

import android.content.Context
import android.view.View
import io.flutter.plugin.platform.PlatformView

class LinuxDisplayView(
    context: Context,
    @Suppress("UNUSED_PARAMETER") viewId: Int,
) : PlatformView {
    private val surface = LinuxSurfaceView(context)

    override fun getView(): View = surface

    override fun dispose() {
        surface.dispose()
    }
}
