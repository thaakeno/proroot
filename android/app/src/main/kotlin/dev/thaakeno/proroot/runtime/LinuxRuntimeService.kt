package dev.thaakeno.proroot.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class LinuxRuntimeService : Service() {
    companion object {
        const val ACTION_KEEP_ALIVE = "dev.thaakeno.proroot.KEEP_ALIVE"
        const val ACTION_STOP = "dev.thaakeno.proroot.STOP"
        private const val CHANNEL_ID = "linux_runtime"
        private const val NOTIFICATION_ID = 840
    }

    private val listener: (RuntimeStatus) -> Unit = { updateNotification(it) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        RuntimeEvents.add(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RuntimeEngine.get(this).stop()
        }
        startForeground(NOTIFICATION_ID, notification(RuntimeEvents.latest))
        return START_STICKY
    }

    override fun onDestroy() {
        RuntimeEvents.remove(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Linux runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the local Linux desktop running"
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(status: RuntimeStatus) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(status),
        )
    }

    private fun notification(status: RuntimeStatus): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (status.running) "Linux PC is running" else "Linux PC")
            .setContentText(status.message)
            .setOngoing(
                status.running || status.phase in setOf(
                    RuntimePhase.downloading,
                    RuntimePhase.extracting,
                    RuntimePhase.provisioning,
                    RuntimePhase.starting,
                ),
            )
            .setOnlyAlertOnce(true)

        if (status.phase == RuntimePhase.downloading && status.totalBytes > 0) {
            builder.setProgress(1000, (status.progress * 1000).toInt(), false)
        }
        return builder.build()
    }
}
