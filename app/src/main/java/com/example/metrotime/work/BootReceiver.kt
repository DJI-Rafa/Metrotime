package com.example.metrotime.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, WidgetUpdateService::class.java).apply {
                action = WidgetUpdateService.ACTION_FETCH_DATA
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
