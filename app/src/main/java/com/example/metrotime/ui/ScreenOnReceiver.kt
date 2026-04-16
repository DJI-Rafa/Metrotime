package com.example.metrotime.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.metrotime.work.WidgetUpdateService

class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // When the screen turns on, request a full data fetch to get the latest info.
        WidgetUpdateService.requestFetch(context)
    }
}
