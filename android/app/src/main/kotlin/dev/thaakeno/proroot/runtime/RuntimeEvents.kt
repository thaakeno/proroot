package dev.thaakeno.proroot.runtime

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object RuntimeEvents {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(RuntimeStatus) -> Unit>()

    @Volatile
    var latest: RuntimeStatus = RuntimeStatus()
        private set

    fun publish(status: RuntimeStatus) {
        latest = status
        main.post {
            listeners.forEach { listener -> listener(status) }
        }
    }

    fun add(listener: (RuntimeStatus) -> Unit) {
        listeners += listener
        listener(latest)
    }

    fun remove(listener: (RuntimeStatus) -> Unit) {
        listeners -= listener
    }
}
