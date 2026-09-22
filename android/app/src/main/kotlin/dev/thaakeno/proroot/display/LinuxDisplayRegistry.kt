package dev.thaakeno.proroot.display

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    fun stopConsumerAndAwait(timeoutMs: Long = 5_000): Boolean {
        val view = active?.get() ?: return true
        val stopped = CountDownLatch(1)
        val posted = view.post {
            try {
                view.prepareForRuntimeStop()
            } finally {
                stopped.countDown()
            }
        }
        if (!posted) return false
        return stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
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
