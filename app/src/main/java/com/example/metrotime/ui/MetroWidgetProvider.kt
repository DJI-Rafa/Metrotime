package com.example.metrotime.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.metrotime.work.WidgetTickService
import com.example.metrotime.work.WidgetUpdateService

class MetroWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CLEAR_AND_REFRESH = "com.example.metrotime.CLEAR_AND_REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CLEAR_AND_REFRESH) {
            // When the refresh button is clicked:
            // 1. Stop any existing 5-second update loop.
            WidgetTickService.stop(context)
            // 2. Start a full, immediate update (location search + data fetch).
            WidgetUpdateService.requestFetch(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // When the widget is first added, do nothing.
        // The user must click "Atualizar" to start the process.
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // When the last widget is removed, ensure the update loop is stopped.
        WidgetTickService.stop(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // On a standard system update, just request a simple redraw without fetching new data.
        WidgetUpdateService.requestRedraw(context)
    }
}
