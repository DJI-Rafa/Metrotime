package com.example.metrotime.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.metrotime.data.ArrivalsRepo
import com.example.metrotime.work.MetroWidgetViews
import com.example.metrotime.work.WidgetUpdateService

class LockWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.metrotime.LOCK_REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            WidgetUpdateService.requestFetch(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Desenha já (o serviço pode não poder arrancar a partir daqui) e pede tempos novos
        appWidgetManager.updateAppWidget(appWidgetIds, MetroWidgetViews.lock(context, ArrivalsRepo.currentArrivals.value))
        WidgetUpdateService.requestFetch(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateService.requestFetch(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }
}
