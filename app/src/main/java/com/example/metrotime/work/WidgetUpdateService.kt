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
import com.example.metrotime.ui.LockWidgetProvider
import com.example.metrotime.ui.MetroWidgetProvider
import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Single unified service that:
 *  1. Fetches station data (GPS or fixed station)
 *  2. Runs a 1-second tick loop to update the widget + lock screen notification
 *  3. Re-fetches from the API every 15 ticks
 */
class WidgetUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var remoteSource: RemoteArrivalsSource
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        remoteSource = RemoteArrivalsSource(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        const val ACTION_FETCH_DATA = "com.example.metrotime.FETCH_DATA"
        const val ACTION_REDRAW_WIDGET = "com.example.metrotime.REDRAW_WIDGET"

        private const val CHANNEL_ID = "metro_live"
        private const val NOTIFICATION_ID = 1

        fun requestFetch(context: Context) {
            val intent = Intent(context, WidgetUpdateService::class.java).apply { action = ACTION_FETCH_DATA }
            context.startForegroundService(intent)
        }

        fun requestRedraw(context: Context) {
            val intent = Intent(context, WidgetUpdateService::class.java).apply { action = ACTION_REDRAW_WIDGET }
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        when (intent?.action) {
            ACTION_FETCH_DATA -> {
                // Use the live notification as the foreground notification itself
                startForeground(NOTIFICATION_ID, buildLiveNotification())
                scope.coroutineContext.cancelChildren()
                scope.launch {
                    // Step 1: Fetch initial data
                    fetchNewData()

                    // Step 2: Do one immediate update
                    withContext(Dispatchers.Main) {
                        val notif = buildLiveNotification()
                        notificationManager.notify(NOTIFICATION_ID, notif)
                        redrawWidget()
                        redrawLockWidget()
                    }

                    // Step 3: Run tick loop (200 iterations = ~3.3 minutes)
                    var loops = 0
                    while (isActive && loops < 200) {
                        try {
                            // Re-fetch from API every 15 seconds
                            if (loops > 0 && loops % 15 == 0) {
                                ArrivalsRepo.currentArrivals.value?.station?.let { station ->
                                    val data = remoteSource.fetchArrivals(station.apiId)
                                    ArrivalsRepo.newArrivals(data)
                                }
                            }

                            // Update widget + foreground notification every second
                            withContext(Dispatchers.Main) {
                                notificationManager.notify(NOTIFICATION_ID, buildLiveNotification())
                                redrawWidget()
                                redrawLockWidget()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                        delay(1000)
                        loops++
                    }

                    stopSelf(startId)
                }
            }
            ACTION_REDRAW_WIDGET -> {
                startForeground(NOTIFICATION_ID, buildLiveNotification())
                scope.launch {
                    withContext(Dispatchers.Main) {
                        redrawWidget()
                        redrawLockWidget()
                    }
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_STICKY
    }

    // ─── Data Fetching ──────────────────────────────────────────────

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
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    // ─── Widget Redraw ──────────────────────────────────────────────

    private fun redrawWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, MetroWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(componentName)
        if (ids.isEmpty()) return

        val views = RemoteViews(packageName, R.layout.widget_metro)

        val refreshIntent = Intent(this, MetroWidgetProvider::class.java).apply {
            action = MetroWidgetProvider.ACTION_CLEAR_AND_REFRESH
        }
        val piRefresh = PendingIntent.getBroadcast(
            this, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnRefresh, piRefresh)

        val data = ArrivalsRepo.currentArrivals.value
        val arrivalTextViews = listOf(R.id.txtArrival1, R.id.txtArrival2, R.id.txtArrival3)

        if (data == null) {
            views.setTextViewText(R.id.txtStation, "A carregar...")
            arrivalTextViews.forEach { views.setTextViewText(it, "") }
            views.setTextViewText(R.id.txtArrival1, "Clique em \"Atualizar\" para começar.")
        } else {
            views.setTextViewText(R.id.txtStation, "Estação: ${data.station.name} (linha ${data.station.line})")

            val secsElapsed = ((System.currentTimeMillis() - data.fetchedAtMillis) / 1000).toInt()
            val upcomingArrivals = data.arrivals
                .map { it.copy(minutes = maxOf(0, it.minutes - secsElapsed)) }
                .filter { it.minutes > 0 }
                .sortedBy { it.minutes }
                .take(3)

            for (i in 0..2) {
                val tvId = arrivalTextViews[i]
                if (i < upcomingArrivals.size) {
                    val arrival = upcomingArrivals[i]
                    val timeText = formatTime(arrival.minutes)
                    views.setTextViewText(tvId, "Destino: ${arrival.direction}  Tempo: $timeText")
                    views.setTextColor(tvId, if (arrival.minutes <= 60) Color.RED else Color.WHITE)
                } else {
                    views.setTextViewText(tvId, "")
                }
            }

            if (upcomingArrivals.isEmpty()) {
                views.setTextViewText(R.id.txtArrival1, "Sem comboios próximos")
            }

            views.setTextViewText(R.id.txtUpdated, "Atualizado há ${secsElapsed}s")
        }

        manager.updateAppWidget(ids, views)
    }

    // ─── Lock Glass Widget ──────────────────────────────────────────

    private fun redrawLockWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, LockWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(componentName)
        if (ids.isEmpty()) return

        val views = RemoteViews(packageName, R.layout.widget_lock)
        val data = ArrivalsRepo.currentArrivals.value

        if (data == null) {
            views.setTextViewText(R.id.txtLockStation, "Metrotime")
            views.setTextViewText(R.id.txtLockUpdated, "")
            views.setTextViewText(R.id.txtLockDest1, "A carregar...")
            views.setTextViewText(R.id.txtLockTime1, "")
            views.setTextViewText(R.id.txtLockDest2, "")
            views.setTextViewText(R.id.txtLockTime2, "")
            views.setTextViewText(R.id.txtLockDest3, "")
            views.setTextViewText(R.id.txtLockTime3, "")
        } else {
            views.setTextViewText(R.id.txtLockStation, data.station.name)

            val secsElapsed = ((System.currentTimeMillis() - data.fetchedAtMillis) / 1000).toInt()
            views.setTextViewText(R.id.txtLockUpdated, "${secsElapsed}s")

            val upcomingArrivals = data.arrivals
                .map { it.copy(minutes = maxOf(0, it.minutes - secsElapsed)) }
                .filter { it.minutes > 0 }
                .sortedBy { it.minutes }
                .take(3)

            val destIds = listOf(R.id.txtLockDest1, R.id.txtLockDest2, R.id.txtLockDest3)
            val timeIds = listOf(R.id.txtLockTime1, R.id.txtLockTime2, R.id.txtLockTime3)

            for (i in 0..2) {
                if (i < upcomingArrivals.size) {
                    val a = upcomingArrivals[i]
                    views.setTextViewText(destIds[i], "→ ${a.direction}")
                    views.setTextViewText(timeIds[i], formatTime(a.minutes))
                } else {
                    views.setTextViewText(destIds[i], "")
                    views.setTextViewText(timeIds[i], "")
                }
            }

            if (upcomingArrivals.isEmpty()) {
                views.setTextViewText(R.id.txtLockDest1, "Sem comboios próximos")
                views.setTextViewText(R.id.txtLockTime1, "")
            }
        }

        manager.updateAppWidget(ids, views)
    }

    // ─── Lock Screen / Notification ─────────────────────────────────

    private fun buildLiveNotification(): Notification {
        val data = ArrivalsRepo.currentArrivals.value
        val viewsCollapsed = RemoteViews(packageName, R.layout.notification_lockscreen_collapsed)
        val viewsExpanded = RemoteViews(packageName, R.layout.notification_lockscreen_expanded)

        if (data == null) {
            viewsCollapsed.setTextViewText(R.id.txtCollapsedTitle, "🚇 Metrotime")
            viewsCollapsed.setTextViewText(R.id.txtCollapsedSubtitle, "A procurar estação...")
            viewsCollapsed.setTextViewText(R.id.txtCollapsedTime, "")
            viewsExpanded.setTextViewText(R.id.txtExpandedStation, "🚇 Metrotime")
            viewsExpanded.setTextViewText(R.id.txtExpandedUpdated, "A conectar...")
            viewsExpanded.setTextViewText(R.id.txtExpandedArr1, "Por favor aguarde...")
            viewsExpanded.setTextViewText(R.id.txtExpandedArr2, "")
            viewsExpanded.setTextViewText(R.id.txtExpandedArr3, "")
        } else {
            val secsElapsed = ((System.currentTimeMillis() - data.fetchedAtMillis) / 1000).toInt()
            val upcomingArrivals = data.arrivals
                .map { it.copy(minutes = maxOf(0, it.minutes - secsElapsed)) }
                .filter { it.minutes > 0 }
                .sortedBy { it.minutes }
                .take(3)

            viewsCollapsed.setTextViewText(R.id.txtCollapsedTitle, "🚇 ${data.station.name}")
            viewsExpanded.setTextViewText(R.id.txtExpandedStation, "🚇 ${data.station.name}")
            viewsExpanded.setTextViewText(R.id.txtExpandedUpdated, "Atualizado há ${secsElapsed}s")

            if (upcomingArrivals.isNotEmpty()) {
                val next = upcomingArrivals.first()
                viewsCollapsed.setTextViewText(R.id.txtCollapsedSubtitle, "Próx: ${next.direction}")
                viewsCollapsed.setTextViewText(R.id.txtCollapsedTime, formatTime(next.minutes))

                val tvIds = listOf(R.id.txtExpandedArr1, R.id.txtExpandedArr2, R.id.txtExpandedArr3)
                for (i in 0..2) {
                    if (i < upcomingArrivals.size) {
                        val a = upcomingArrivals[i]
                        viewsExpanded.setTextViewText(tvIds[i], "● ${a.direction}  —  ${formatTime(a.minutes)}")
                    } else {
                        viewsExpanded.setTextViewText(tvIds[i], "")
                    }
                }
            } else {
                viewsCollapsed.setTextViewText(R.id.txtCollapsedSubtitle, "Sem comboios próximos")
                viewsCollapsed.setTextViewText(R.id.txtCollapsedTime, "")
                viewsExpanded.setTextViewText(R.id.txtExpandedArr1, "Sem comboios próximos")
                viewsExpanded.setTextViewText(R.id.txtExpandedArr2, "")
                viewsExpanded.setTextViewText(R.id.txtExpandedArr3, "")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(viewsCollapsed)
            .setCustomBigContentView(viewsExpanded)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ─── Channel & Helpers ──────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Metro ao Vivo", NotificationManager.IMPORTANCE_DEFAULT)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setShowBadge(false)
        channel.description = "Tempos de espera do metro em tempo real"
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatTime(totalSeconds: Int): String {
        if (totalSeconds <= 30) return "A chegar!"
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds.toLong())
        val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel() }
}
