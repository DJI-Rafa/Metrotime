package com.example.metrotime.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == WidgetUpdateService.ACTION_HEARTBEAT) {
            val serviceIntent = Intent(context, WidgetUpdateService::class.java).apply {
                action = WidgetUpdateService.ACTION_FETCH_DATA
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                // On Android 12+, starting foreground service from background may fail
                // in certain conditions. The alarm will retry in 15 minutes.
                e.printStackTrace()
            }
        }
    }
}
