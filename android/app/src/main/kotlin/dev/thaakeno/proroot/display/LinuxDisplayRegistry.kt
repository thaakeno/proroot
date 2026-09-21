package dev.thaakeno.proroot.display

import java.lang.ref.WeakReference

object LinuxDisplayRegistry {
    @Volatile
    private var active: WeakReference<LinuxSurfaceView>? = null

    fun attach(view: LinuxSurfaceView) {
        active = WeakReference(view)
    }

    fun detach(view: LinuxSurfaceView) {
        if (active?.get() === view) {
            active?.clear()
            active = null
        }
    }

    fun showKeyboard(): Boolean {
        val view = active?.get() ?: return false
        view.post { view.showKeyboard() }
        return true
    }

    fun setPointerCapture(enabled: Boolean): Boolean {
        val view = active?.get() ?: return false
        view.post { view.setPointerCaptureEnabled(enabled) }
        return true
    }
}
