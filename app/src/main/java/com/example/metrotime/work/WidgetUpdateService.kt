package com.example.metrotime.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.metrotime.R
import com.example.metrotime.data.ArrivalsRepo
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.location.Geo
import com.example.metrotime.location.Locator
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.MetroWidgetProvider
import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class WidgetUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var remoteSource: RemoteArrivalsSource

    override fun onCreate() {
        super.onCreate()
        remoteSource = RemoteArrivalsSource(this)
    }

    companion object {
        const val ACTION_FETCH_DATA = "com.example.metrotime.FETCH_DATA"
        const val ACTION_REDRAW_WIDGET = "com.example.metrotime.REDRAW_WIDGET"

        private const val NOTIFICATION_CHANNEL_ID = "widget_update"
        private const val NOTIFICATION_ID = 1

        fun requestFetch(context: Context) {
            val intent = Intent(context, WidgetUpdateService::class.java).apply { action = ACTION_FETCH_DATA }
            context.startForegroundService(intent)
        }

        fun requestRedraw(context: Context) {
            val intent = Intent(context, WidgetUpdateService::class.java).apply { action = ACTION_REDRAW_WIDGET }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FETCH_DATA -> {
                startForeground(NOTIFICATION_ID, createNotification())
                scope.launch { 
                    fetchNewData()
                    redrawWidget()
                    stopSelf(startId)
                }
            }
            ACTION_REDRAW_WIDGET -> {
                scope.launch { 
                    redrawWidget()
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun fetchNewData() {
        try {
            val fixedId = Prefs.getFixedStation(this)
            val station = if (fixedId != null) {
                StationsRepo.loadStations(this).find { it.id == fixedId }
            } else {
                val loc = Locator.getCurrentLocation(this)
                if (loc != null) Geo.nearestStation(loc.first, loc.second, StationsRepo.loadStations(this)) else null
            }

            if (station != null) {
                val data = remoteSource.fetchArrivals(station.apiId)
                ArrivalsRepo.newArrivals(data)
                WidgetTickService.start(this)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private fun redrawWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, MetroWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(componentName)
        if (ids.isEmpty()) return

        val views = RemoteViews(packageName, R.layout.widget_metro)
        
        val refreshIntent = Intent(this, MetroWidgetProvider::class.java).apply { action = MetroWidgetProvider.ACTION_CLEAR_AND_REFRESH }
        val piRefresh = PendingIntent.getBroadcast(this, 0, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btnRefresh, piRefresh)

        val data = ArrivalsRepo.currentArrivals.value
        val arrivalTextViews = listOf(R.id.txtArrival1, R.id.txtArrival2, R.id.txtArrival3)

        if (data == null) {
            views.setTextViewText(R.id.txtStation, "A carregar...")
            arrivalTextViews.forEach { views.setTextViewText(it, "") } // Clear all arrival texts
            views.setTextViewText(R.id.txtArrival1, "Clique em \"Atualizar\" para começar.")
        } else {
            val stationText = "Estação: ${data.station.name} (linha ${data.station.line})"
            views.setTextViewText(R.id.txtStation, stationText)

            val upcomingArrivals = data.arrivals.filter { it.minutes > 0 }.sortedBy { it.minutes }.take(3)

            for (i in 0..2) {
                val arrivalViewId = arrivalTextViews[i]
                if (i < upcomingArrivals.size) {
                    val arrival = upcomingArrivals[i]
                    val timeText = formatTime(arrival.minutes)
                    val arrivalText = "Destino: ${arrival.direction}  Tempo: $timeText"
                    views.setTextViewText(arrivalViewId, arrivalText)

                    // Set color to red if time is 60 seconds or less
                    if (arrival.minutes <= 60) {
                        views.setTextColor(arrivalViewId, Color.RED)
                    } else {
                        views.setTextColor(arrivalViewId, Color.WHITE)
                    }
                } else {
                    // Clear unused TextViews
                    views.setTextViewText(arrivalViewId, "")
                }
            }
            
            if (upcomingArrivals.isEmpty()) {
                views.setTextViewText(R.id.txtArrival1, "Sem comboios próximos")
            }

            val secs = ((System.currentTimeMillis() - data.fetchedAtMillis) / 1000).toInt()
            views.setTextViewText(R.id.txtUpdated, "Atualizado há $secs s")
        }
        
        manager.updateAppWidget(ids, views)
    }

    private fun formatTime(totalSeconds: Int): String {
        if (totalSeconds <= 30) return "A chegar!"
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds.toLong())
        val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d min", minutes, seconds)
    }
    
    private fun createNotification(): Notification {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Widget Updates", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("A procurar a estação mais próxima...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel() }
}
