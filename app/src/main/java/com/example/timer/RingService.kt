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
        val infinite = p.getBoolean("repeat_inf", false)
        val count = if (infinite) Int.MAX_VALUE else p.getInt("repeat_count", 1).coerceIn(1, 100)
        val gap = (p.getFloat("repeat_gap_min", 1f).coerceIn(0.1f, 60f) * 60).toInt()
        val vol = p.getInt("volume", 70).coerceIn(10, 100) / 100f
        val vibrate = p.getBoolean("vibrate", true)
        val strike = p.getFloat("strike_gap", 3f).coerceIn(0.5f, 30f).toDouble()
        val media = p.getBoolean("media_volume", true)
        val boost = p.getBoolean("boost", false)
        val single = p.getBoolean("single_ring", false)
        val pitchMain = p.getInt("pitch_main", 0).coerceIn(-24, 24)
        val finalOn = !infinite && count > 1 && p.getBoolean("final_on", false)
        val finalIdx = p.getInt("final_idx", 3).coerceIn(0, Synth.NAMES.size - 1)
        val pitchFinal = p.getInt("pitch_final", 0).coerceIn(-24, 24)

        instance = this
        ensureChannel(this)
        val first = buildNotification(1, count)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(RING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(RING_ID, first)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lockMs = if (infinite) 12L * 3600 * 1000 else (count.toLong() * (sec + gap) + 30) * 1000
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timer:ring").also { it.acquire(lockMs) }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        worker = Thread {
            try {
                var i = 1
                while (i <= count) {
                    nm.notify(RING_ID, buildNotification(i, count))
                    if (vibrate) vibrateNow()
                    val last = finalOn && i == count
                    if (soundOn) {
                        Synth.play(
                            if (last) finalIdx else idx, sec, vol, strike, media,
                            if (last) pitchFinal else pitchMain, boost, single
                        )
                    } else {
                        Thread.sleep(sec * 1000L)
                    }
                    if (i < count) Thread.sleep(gap * 1000L)
                    i++
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
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        worker = null
        instance = null
        if (!userStopped) notifyDone(this)
        stopSelf()
    }

    private fun vibrateNow() {
        @Suppress("DEPRECATION")
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 400, 300, 400)
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
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
        val text = when {
            count == Int.MAX_VALUE -> "Сигнал $i"
            count > 1 -> "Сигнал $i из $count"
            else -> "Время вышло"
        }
        return builder(this)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Таймер")
            .setContentText(text)
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

        @Suppress("DEPRECATION")
        private fun builder(c: Context): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL_ID) else Notification.Builder(c)

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
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
            val n = builder(c)
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
