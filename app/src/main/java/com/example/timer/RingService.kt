package com.example.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

class RingService : Service() {

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var userStopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        val p = getSharedPreferences("timer", Context.MODE_PRIVATE)
        val soundOn = p.getBoolean("sound", true)
        val idx = p.getInt("sound_idx", 0).coerceIn(0, Synth.NAMES.size - 1)
        val sec = p.getInt("sound_sec", 10).coerceIn(1, 60)
        val count = p.getInt("repeat_count", 1).coerceIn(1, 5)
        val gap = (p.getFloat("repeat_gap_min", 1f).coerceIn(0.1f, 60f) * 60).toInt()
        val vol = p.getInt("volume", 70).coerceIn(10, 100) / 100f
        val vibrate = p.getBoolean("vibrate", true)

        instance = this
        ensureChannel(this)
        val first = buildNotification(1, count)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(RING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(RING_ID, first)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timer:ring").also {
            it.acquire((count * (sec + gap) + 30) * 1000L)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        worker = Thread {
            try {
                for (i in 1..count) {
                    nm.notify(RING_ID, buildNotification(i, count))
                    if (vibrate) vibrateNow()
                    if (soundOn) Synth.play(idx, sec, vol) else Thread.sleep(sec * 1000L)
                    if (i < count) Thread.sleep(gap * 1000L)
                }
            } catch (_: InterruptedException) {
            } finally {
                Handler(Looper.getMainLooper()).post { finish() }
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun stopRinging() {
        userStopped = true
        val w = worker
        if (w != null) w.interrupt() else stopSelf()
    }

    private fun finish() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        worker = null
        instance = null
        if (!userStopped) notifyDone(this)
        stopSelf()
    }

    private fun vibrateNow() {
        @Suppress("DEPRECATION")
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 300, 400), -1))
    }

    private fun buildNotification(i: Int, count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Таймер")
            .setContentText(if (count > 1) "Сигнал $i из $count" else "Время вышло")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Стоп", stop
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "timer_done"
        const val RING_ID = 1
        const val DONE_ID = 2
        const val ACTION_STOP = "com.example.timer.STOP"

        @Volatile private var instance: RingService? = null
        val isRinging: Boolean get() = instance != null
        fun stop() { instance?.stopRinging() }

        fun ensureChannel(c: Context) {
            val ch = NotificationChannel(CHANNEL_ID, "Таймер", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(false)
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }

        fun notifyDone(c: Context) {
            ensureChannel(c)
            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Таймер")
                .setContentText("Время вышло")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(DONE_ID, n)
        }
    }
}
