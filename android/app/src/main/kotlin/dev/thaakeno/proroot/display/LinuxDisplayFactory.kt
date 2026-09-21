package dev.thaakeno.proroot.display

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class LinuxDisplayFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    companion object {
        const val VIEW_TYPE = "dev.thaakeno.proroot/display"
    }

    override fun create(
        context: Context,
        viewId: Int,
        args: Any?,
    ): PlatformView = LinuxDisplayView(context, viewId)
}
