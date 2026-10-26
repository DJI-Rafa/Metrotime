package com.example.metrotime.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.metrotime.work.CpWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Widget com as próximas partidas de uma estação CP. O desenho está no CpWidgetUpdater. */
class CpWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.metrotime.CP_WIDGET_REFRESH"

        fun refresh(context: Context) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { CpWidgetUpdater.updateAll(context.applicationContext) } }
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
                runCatching { CpWidgetUpdater.updateAll(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
