package com.example.metrotime.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.metrotime.work.BusWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Widget com as próximas chegadas a uma paragem de autocarro. O desenho está no BusWidgetUpdater. */
class BusWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.metrotime.BUS_WIDGET_REFRESH"

        fun refresh(context: Context) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { BusWidgetUpdater.updateAll(context.applicationContext) } }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refreshAsync(context) else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { BusWidgetUpdater.updateAll(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
