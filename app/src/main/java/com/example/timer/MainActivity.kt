package com.example.timer

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvTime: TextView
    private lateinit var pickers: View
    private lateinit var npH: NumberPicker
    private lateinit var npM: NumberPicker
    private lateinit var npS: NumberPicker
    private lateinit var btnStart: Button
    private lateinit var btnReset: Button
    private lateinit var btnStopRing: Button

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("timer", Context.MODE_PRIVATE)

        tvTime = findViewById(R.id.tvTime)
        pickers = findViewById(R.id.pickers)
        npH = findViewById(R.id.npH)
        npM = findViewById(R.id.npM)
        npS = findViewById(R.id.npS)
        btnStart = findViewById(R.id.btnStart)
        btnReset = findViewById(R.id.btnReset)
        btnStopRing = findViewById(R.id.btnStopRing)
        btnStopRing.setOnClickListener { RingService.stop() }

        npH.minValue = 0; npH.maxValue = 23
        npM.minValue = 0; npM.maxValue = 59
        npS.minValue = 0; npS.maxValue = 59

        val last = prefs.getLong("duration", 5 * 60 * 1000L) / 1000
        npH.value = (last / 3600).toInt()
        npM.value = ((last / 60) % 60).toInt()
        npS.value = (last % 60).toInt()

        btnStart.setOnClickListener { onStartPause() }
        btnReset.setOnClickListener { onReset() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        RingService.stop()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(RingService.DONE_ID)
        if (prefs.getBoolean("keep_on", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        handler.removeCallbacks(ticker)
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun onStartPause() {
        val now = System.currentTimeMillis()
        when (prefs.getInt("state", 0)) {
            0 -> {
                val dur = (npH.value * 3600L + npM.value * 60L + npS.value) * 1000L
                if (dur <= 0) return
                val end = now + dur
                prefs.edit().putLong("duration", dur).putLong("end_at", end).putInt("state", 1).apply()
                scheduleAlarm(end)
            }
            1 -> {
                val left = prefs.getLong("end_at", now) - now
                prefs.edit().putLong("left", left).putInt("state", 2).apply()
                cancelAlarm()
            }
            2 -> {
                val end = now + prefs.getLong("left", 0)
                prefs.edit().putLong("end_at", end).putInt("state", 1).apply()
                scheduleAlarm(end)
            }
        }
        handler.removeCallbacks(ticker)
        ticker.run()
    }

    private fun onReset() {
        cancelAlarm()
        RingService.stop()
        prefs.edit().putInt("state", 0).apply()
        handler.removeCallbacks(ticker)
        ticker.run()
    }

    private fun render() {
        btnStopRing.visibility = if (RingService.isRinging) View.VISIBLE else View.GONE
        var state = prefs.getInt("state", 0)
        if (state == 1 && prefs.getLong("end_at", 0) - System.currentTimeMillis() <= 0) {
            prefs.edit().putInt("state", 0).apply()
            state = 0
        }
        when (state) {
            0 -> {
                pickers.visibility = View.VISIBLE
                tvTime.visibility = View.GONE
                btnStart.text = "Старт"
                btnReset.visibility = View.GONE
            }
            1 -> {
                pickers.visibility = View.GONE
                tvTime.visibility = View.VISIBLE
                tvTime.text = fmt(prefs.getLong("end_at", 0) - System.currentTimeMillis())
                btnStart.text = "Пауза"
                btnReset.visibility = View.VISIBLE
            }
            2 -> {
                pickers.visibility = View.GONE
                tvTime.visibility = View.VISIBLE
                tvTime.text = fmt(prefs.getLong("left", 0))
                btnStart.text = "Продолжить"
                btnReset.visibility = View.VISIBLE
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0, Intent(this, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleAlarm(end: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(end, show), alarmIntent())
    }

    private fun cancelAlarm() {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent())
    }
}
