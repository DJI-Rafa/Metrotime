package com.example.metrotime.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.metrotime.data.ArrivalsRepo
import com.example.metrotime.work.MetroWidgetViews
import com.example.metrotime.work.WidgetTickService
import com.example.metrotime.work.WidgetUpdateService

class MetroWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CLEAR_AND_REFRESH = "com.example.metrotime.CLEAR_AND_REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CLEAR_AND_REFRESH) {
            // When the refresh button is clicked:
            // Start a full, immediate update (location search + data fetch).
            // The service naturally replaces its loop internally via coroutine cancellation.
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
        // Desenha já com o que houver: o Android não deixa arrancar o serviço daqui (fica em segundo
        // plano) e o widget ficava vazio até se tocar em atualizar
        appWidgetManager.updateAppWidget(appWidgetIds, MetroWidgetViews.home(context, ArrivalsRepo.currentArrivals.value))
        WidgetUpdateService.requestRedraw(context)
    }
}
