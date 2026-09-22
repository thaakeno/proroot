package dev.thaakeno.proroot.display

import android.os.Looper
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

    fun stopConsumerAndWait(timeoutMs: Long = 1_500): Boolean {
        val view = active?.get() ?: return true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.stopConsumerForRuntime()
            return true
        }

        val stopped = CountDownLatch(1)
        if (!view.post {
                try {
                    view.stopConsumerForRuntime()
                } finally {
                    stopped.countDown()
                }
            }
        ) {
            return false
        }
        return stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
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
