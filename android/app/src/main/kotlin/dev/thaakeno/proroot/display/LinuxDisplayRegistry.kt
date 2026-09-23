package dev.thaakeno.proroot.display

import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LinuxDisplayRegistry {
    @Volatile
    private var active: WeakReference<LinuxSurfaceView>? = null
    @Volatile
    private var consumerOwner: WeakReference<LinuxSurfaceView>? = null

    fun attach(view: LinuxSurfaceView) {
        active = WeakReference(view)
    }

    fun isActive(view: LinuxSurfaceView): Boolean = active?.get() === view

    fun detach(view: LinuxSurfaceView) {
        if (active?.get() === view) {
            active?.clear()
            active = null
        }
    }

    fun ownsConsumer(view: LinuxSurfaceView): Boolean = consumerOwner?.get() === view

    fun consumerStarting(view: LinuxSurfaceView) {
        consumerOwner?.get()?.takeIf { it !== view }?.onConsumerOwnershipLost()
        consumerOwner = null
    }

    fun consumerStarted(view: LinuxSurfaceView) {
        consumerOwner = WeakReference(view)
    }

    fun consumerStopped(view: LinuxSurfaceView) {
        if (ownsConsumer(view)) consumerOwner = null
    }

    fun stopConsumerAndAwait(timeoutMs: Long = 5_000): Boolean {
        val view = consumerOwner?.get() ?: active?.get() ?: return true
        val stopped = CountDownLatch(1)
        var successful = false
        val posted = view.post {
            try {
                successful = runCatching { view.prepareForRuntimeStop() }
                    .getOrElse { failure ->
                        Log.e("LinuxDisplayRegistry", "Display consumer stop failed", failure)
                        false
                    }
            } finally {
                stopped.countDown()
            }
        }
        if (!posted) return false
        return stopped.await(timeoutMs, TimeUnit.MILLISECONDS) && successful
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
