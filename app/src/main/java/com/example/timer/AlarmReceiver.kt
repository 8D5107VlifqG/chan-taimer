package com.example.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().putInt("state", 0).apply()
        try {
            context.startForegroundService(Intent(context, RingService::class.java))
        } catch (_: Exception) {
            RingService.notifyDone(context)
        }
    }
}
