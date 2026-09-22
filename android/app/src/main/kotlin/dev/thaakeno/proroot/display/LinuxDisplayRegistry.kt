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

    fun awaitDetached(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (active?.get() != null && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        return active?.get() == null
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
