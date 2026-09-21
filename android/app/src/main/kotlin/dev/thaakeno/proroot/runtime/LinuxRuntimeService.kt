package dev.thaakeno.proroot.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.thaakeno.proroot.MainActivity

class LinuxRuntimeService : Service() {
    companion object {
        const val ACTION_KEEP_ALIVE = "dev.thaakeno.proroot.KEEP_ALIVE"
        const val ACTION_STOP = "dev.thaakeno.proroot.STOP"
        private const val CHANNEL_ID = "linux_runtime"
        private const val NOTIFICATION_ID = 840
    }

    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val listener: (RuntimeStatus) -> Unit = { status ->
        if (foregroundStarted) {
            updateWakeLock(status)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(status),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proroot:linux-runtime")
            .apply { setReferenceCounted(false) }
        RuntimeEvents.add(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RuntimeEngine.get(this).stop()
        }

        val status = RuntimeEvents.latest
        startForeground(NOTIFICATION_ID, notification(status))
        foregroundStarted = true
        updateWakeLock(status)
        return START_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        RuntimeEvents.remove(listener)
        releaseWakeLock()
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

    private fun updateWakeLock(status: RuntimeStatus) {
        val active = status.running || status.phase in setOf(
            RuntimePhase.downloading,
            RuntimePhase.extracting,
            RuntimePhase.provisioning,
            RuntimePhase.starting,
            RuntimePhase.stopping,
        )

        val lock = wakeLock ?: return
        if (active && !lock.isHeld) lock.acquire()
        if (!active && lock.isHeld) lock.release()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun notification(status: RuntimeStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LinuxRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (status.running) "Linux PC is running" else "Linux PC")
            .setContentText(status.message)
            .setContentIntent(openIntent)
            .setOngoing(
                status.running || status.phase in setOf(
                    RuntimePhase.downloading,
                    RuntimePhase.extracting,
                    RuntimePhase.provisioning,
                    RuntimePhase.starting,
                ),
            )
            .setOnlyAlertOnce(true)

        if (status.running) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopIntent,
            )
        }
        if (status.phase == RuntimePhase.downloading && status.totalBytes > 0) {
            builder.setProgress(1000, (status.progress * 1000).toInt(), false)
        }
        return builder.build()
    }
}
