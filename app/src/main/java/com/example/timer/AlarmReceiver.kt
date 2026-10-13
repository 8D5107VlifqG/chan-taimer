package com.example.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().putInt("state", 0).apply()
        val svc = Intent(context, RingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        } catch (_: Exception) {
            RingService.notifyDone(context)
        }
    }
}
