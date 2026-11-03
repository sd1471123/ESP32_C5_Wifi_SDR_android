package io.github.sd1471123.sdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process — and with it the USB capture thread — alive while the board streams.
 *
 * Without a foreground service the activity going behind anything (a notification the user
 * taps, the notification shade, a call, the ROM's own cleaner) is enough for the system to
 * freeze the process: the capture thread stops reading, the firmware blocks in its USB write
 * and the link looks dropped. A partial wake lock additionally keeps the CPU awake while the
 * screen is off, so a paused read never strands the board either.
 */
class KeepaliveService : Service() {

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFY_ID, notification())
        } catch (_: Exception) {
            // Android 14 refuses a connectedDevice service without the USB grant; the link
            // itself still works as a plain background thread, so just back out quietly.
            stopSelf()
            return START_NOT_STICKY
        }
        if (lock == null) {
            val pm = getSystemService(PowerManager::class.java)
            lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "C5Sdr:capture")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm?.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_sdr)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFY_ID = 4242
    }
}

/** Start/stop helpers; a refused service must never break the capture link itself. */
object Keepalive {

    fun start(ctx: Context) {
        val i = Intent(ctx, KeepaliveService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        } catch (_: Exception) {
        }
    }

    fun stop(ctx: Context) {
        try {
            ctx.stopService(Intent(ctx, KeepaliveService::class.java))
        } catch (_: Exception) {
        }
    }
}
