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
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.metrotime.R
import com.example.metrotime.data.ArrivalsRepo
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.history.NearbyStation
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.location.Geo
import com.example.metrotime.location.Locator
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.LockWidgetProvider
import com.example.metrotime.ui.MetroWidgetProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.net.URL
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
    private val lastNotifiedFriends = mutableMapOf<String, String>() // Map FriendID to TrainName
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
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

                    // Step 3: Run tick loop continuously
                    var loops = 0
                    while (isActive) {
                        try {
                            // Re-fetch from API every 15 seconds
                            if (loops > 0 && loops % 15 == 0) {
                                ArrivalsRepo.currentArrivals.value?.station?.let { station ->
                                    val data = remoteSource.fetchArrivals(station.apiId)
                                    ArrivalsRepo.newArrivals(data)
                                }
                            }

                            // Share location to Firebase every 60 seconds (if enabled)
                            if (loops % 60 == 0) {
                                val uid = FirebaseAuth.getInstance().currentUser?.uid
                                if (uid != null) {
                                    if (Prefs.isContinuousLocation(this@WidgetUpdateService)) {
                                        val loc = Locator.getCurrentLocation(this@WidgetUpdateService)
                                        if (loc != null) {
                                            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                            val map = mapOf(
                                                "lat" to loc.first,
                                                "lon" to loc.second,
                                                "timestamp" to System.currentTimeMillis()
                                            )
                                            db.getReference("locations").child(uid).updateChildren(map)
                                            
                                            // 1. Check if LOCAL USER is on a train
                                            checkLocalUserOnTrain(uid, loc.first, loc.second)
                                            
                                            // 1b. Check proximity to stations for trip history
                                            checkStationProximity(loc.first, loc.second)
                                        }
                                    }
                                    
                                    // 2. Read friends' train status to send notifications
                                    checkFriendsTrainStatus(uid)
                                }
                            }

                            // 3. Read admin messages every 10 seconds for fast delivery
                            if (loops % 10 == 0) {
                                val uid = FirebaseAuth.getInstance().currentUser?.uid
                                if (uid != null) {
                                    checkAdminMessages(uid)
                                }
                            }

                            // 4. Warm up CP GTFS Route Cache every 5 minutes (or at loop 0)
                            if (loops % 300 == 0) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val response = com.example.metrotime.data.cp.CpApi.service.getVehicles()
                                        val activeVehicles = response.vehicles.filter {
                                            it.status != com.example.metrotime.data.cp.VehicleStatus.COMPLETED && 
                                            it.status != com.example.metrotime.data.cp.VehicleStatus.CANCELLED
                                        }
                                        com.example.metrotime.data.cp.CpRouteRepository.queueUnknownVehicles(activeVehicles)
                                    } catch (e: Exception) {
                                        // Ignore API errors in background
                                    }
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
        val destIds = listOf(R.id.txtDest1, R.id.txtDest2)
        val timeIds = listOf(
            listOf(R.id.txtTime1_1, R.id.txtTime1_2, R.id.txtTime1_3),
            listOf(R.id.txtTime2_1, R.id.txtTime2_2, R.id.txtTime2_3)
        )

        if (data == null) {
            views.setTextViewText(R.id.txtStation, "A carregar...")
            views.setTextColor(R.id.txtStation, Color.WHITE)
            destIds.forEach { views.setTextViewText(it, "") }
            timeIds.flatten().forEach { views.setTextViewText(it, "") }
            views.setTextViewText(R.id.txtDest1, "A aguardar...")
            views.setTextViewText(R.id.txtUpdated, "Clica em ↻ para iniciar")
        } else {
            views.setTextViewText(R.id.txtStation, data.station.name)
            val lineColor = when (data.station.line.lowercase()) {
                "amarela" -> Color.parseColor("#FFD54F")
                "azul" -> Color.parseColor("#4FC3F7")
                "verde" -> Color.parseColor("#69F0AE")
                "vermelha" -> Color.parseColor("#FF5252")
                else -> Color.WHITE
            }
            views.setTextColor(R.id.txtStation, lineColor)

            val secsElapsed = ((System.currentTimeMillis() - data.fetchedAtMillis) / 1000).toInt()
            val upcomingArrivals = data.arrivals
                .map { it.copy(minutes = maxOf(0, it.minutes - secsElapsed)) }
                .filter { it.minutes > 0 }
                .sortedBy { it.minutes }

            val grouped = upcomingArrivals.groupBy { it.direction }.toList().take(2)

            for (i in 0..1) {
                if (i < grouped.size) {
                    val (direction, arrivals) = grouped[i]
                    views.setTextViewText(destIds[i], direction)
                    views.setTextColor(destIds[i], Color.WHITE)
                    
                    val times = arrivals.take(3)
                    for (j in 0..2) {
                        if (j < times.size) {
                            val arrival = times[j]
                            val prefix = if (data.isEstimated) "~" else ""
                            views.setTextViewText(timeIds[i][j], prefix + formatTime(arrival.minutes))
                            views.setTextColor(timeIds[i][j], if (data.isEstimated) Color.parseColor("#F59E0B") else if (arrival.minutes <= 30) Color.RED else Color.WHITE)
                        } else {
                            views.setTextViewText(timeIds[i][j], "")
                        }
                    }
                } else {
                    views.setTextViewText(destIds[i], "")
                    for (j in 0..2) {
                        views.setTextViewText(timeIds[i][j], "")
                    }
                }
            }

            if (upcomingArrivals.isEmpty()) {
                views.setTextViewText(R.id.txtDest1, "Sem comboios próximos")
                views.setTextColor(R.id.txtDest1, Color.WHITE)
            }

            val estimatedTag = if (data.isEstimated) " (estimado)" else ""
            views.setTextViewText(R.id.txtUpdated, "Atualizado há ${secsElapsed}s$estimatedTag")
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

        val refreshIntent = Intent(this, LockWidgetProvider::class.java).apply {
            action = LockWidgetProvider.ACTION_REFRESH
        }
        val piRefresh = PendingIntent.getBroadcast(
            this, 1, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetLockRoot, piRefresh)

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
                    val prefix = if (data.isEstimated) "~" else ""
                    views.setTextViewText(destIds[i], "→ ${a.direction}")
                    views.setTextViewText(timeIds[i], prefix + formatTime(a.minutes))
                } else {
                    views.setTextViewText(destIds[i], "")
                    views.setTextViewText(timeIds[i], "")
                }
            }

            if (upcomingArrivals.isEmpty()) {
                views.setTextViewText(R.id.txtLockDest1, "Sem comboios próximos")
                views.setTextViewText(R.id.txtLockTime1, "")
            }

            val estimatedTag = if (data.isEstimated) " (e)" else ""
            views.setTextViewText(R.id.txtLockUpdated, "${secsElapsed}s$estimatedTag")
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
            val estimatedTag = if (data.isEstimated) " (estimado)" else ""
            viewsExpanded.setTextViewText(R.id.txtExpandedUpdated, "Atualizado há ${secsElapsed}s$estimatedTag")

            if (upcomingArrivals.isNotEmpty()) {
                val next = upcomingArrivals.first()
                val prefix = if (data.isEstimated) "~" else ""
                viewsCollapsed.setTextViewText(R.id.txtCollapsedSubtitle, "Próx: ${next.direction}")
                viewsCollapsed.setTextViewText(R.id.txtCollapsedTime, prefix + formatTime(next.minutes))

                val tvIds = listOf(R.id.txtExpandedArr1, R.id.txtExpandedArr2, R.id.txtExpandedArr3)
                for (i in 0..2) {
                    if (i < upcomingArrivals.size) {
                        val a = upcomingArrivals[i]
                        viewsExpanded.setTextViewText(tvIds[i], "● ${a.direction}  —  $prefix${formatTime(a.minutes)}")
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
            .setSmallIcon(R.drawable.ic_stat_metro)
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

    private val myTrainTracking = mutableMapOf<String, Int>()

    private suspend fun checkLocalUserOnTrain(uid: String, myLat: Double, myLon: Double) {
        try {
            val trainsResponse = try { URL("https://api.comboios.live/api/v1/trains").readText() } catch (e: Exception) { null }
            if (trainsResponse == null) return

            val jsonArray = JSONArray(trainsResponse)
            val currentMatches = mutableListOf<String>()
            val trainDetails = mutableMapOf<String, Triple<String, String, String>>() // id -> (line, dest, name)

            for (i in 0 until jsonArray.length()) {
                val t = jsonArray.getJSONObject(i)
                val trainLat = t.optDouble("latitude", 0.0)
                val trainLon = t.optDouble("longitude", 0.0)
                val trainId = t.optString("id")
                if (trainLat == 0.0 || trainLon == 0.0 || trainId.isEmpty()) continue
                
                val line = t.optString("line")
                val dest = t.optString("destination")
                val trainName = if (line.isNotEmpty() && line != "null") "$line $trainId" else "Comboio $trainId"
                trainDetails[trainId] = Triple(line, dest, trainName)

                // EXACT SAME MOMENT GPS COMPARISON
                val dist = distance(myLat, myLon, trainLat, trainLon)
                if (dist < 200) { // 200 meters tolerance
                    currentMatches.add(trainId)
                }
            }

            // Cleanup old tracking
            myTrainTracking.keys.retainAll(currentMatches.toSet())
            var confirmedTrainName: String? = null
            var confirmedDest: String? = null

            for (trainId in currentMatches) {
                val count = myTrainTracking.getOrDefault(trainId, 0) + 1
                myTrainTracking[trainId] = count
                
                if (count >= 3) { // 3 consecutive minutes matching this train
                    val details = trainDetails[trainId]
                    if (details != null) {
                        confirmedTrainName = details.third
                        confirmedDest = details.second
                    }
                }
            }

            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
            if (confirmedTrainName != null && confirmedDest != null) {
                val updates = mapOf(
                    "currentTrain" to confirmedTrainName,
                    "trainDestination" to confirmedDest
                )
                db.getReference("locations").child(uid).updateChildren(updates)
            } else {
                // Not on any train (or lost tracking)
                val updates = mapOf(
                    "currentTrain" to null,
                    "trainDestination" to null
                )
                db.getReference("locations").child(uid).updateChildren(updates)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun checkFriendsTrainStatus(uid: String) {
        try {
            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
            val friendsSnap = db.getReference("users").child(uid).child("friends").get().await()
            val friendIds = friendsSnap.children.mapNotNull { it.key }
            if (friendIds.isEmpty()) return

            for (fid in friendIds) {
                val locSnap = db.getReference("locations").child(fid).get().await()
                val currentTrain = locSnap.child("currentTrain").getValue(String::class.java)
                val destination = locSnap.child("trainDestination").getValue(String::class.java)
                val ts = locSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                
                if (currentTrain != null && destination != null && (System.currentTimeMillis() - ts < 10 * 60 * 1000)) {
                    val lastTrainNotified = lastNotifiedFriends[fid] as? String
                    if (lastTrainNotified != currentTrain) { // Only notify ONCE per train journey
                        val userSnap = db.getReference("users").child(fid).get().await()
                        val name = userSnap.child("name").getValue(String::class.java) ?: "Um amigo"
                        
                        // Check if local user set a custom message for this friend
                        val settingsSnap = db.getReference("users").child(uid).child("friendSettings").child(fid).get().await()
                        val customTemplate = settingsSnap.child("customMessage").getValue(String::class.java)
                        
                        val messageToDisplay = if (customTemplate != null && customTemplate.isNotEmpty()) {
                            customTemplate
                                .replace("{name}", name)
                                .replace("{train}", currentTrain)
                                .replace("{dest}", destination)
                        } else {
                            val templates = listOf(
                                "$name acabou de entrar no $currentTrain rumo a $destination.",
                                "Lá vai $name a bordo do $currentTrain! O destino final é $destination.",
                                "$name está a viajar no $currentTrain com destino a $destination.",
                                "Próxima paragem: $destination! ($name apanhou o $currentTrain)."
                            )
                            templates.random()
                        }
                        
                        sendTrainNotification(name, messageToDisplay, fid.hashCode())
                        lastNotifiedFriends[fid] = currentTrain
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun sendTrainNotification(friendName: String, messageText: String, notifId: Int) {
        val channelId = "friend_train_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Alertas de Viagem dos Amigos",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, com.example.metrotime.ui.LiveMapActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val cpDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.cp_logo)
        val cpLogo = if (cpDrawable is android.graphics.drawable.BitmapDrawable) {
            cpDrawable.bitmap
        } else {
            val bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            cpDrawable?.setBounds(0, 0, canvas.width, canvas.height)
            cpDrawable?.draw(canvas)
            bmp
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setLargeIcon(cpLogo)
            .setContentTitle("$friendName está a viajar!")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(notifId, notification)
    }

    private suspend fun checkAdminMessages(uid: String) {
        try {
            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
            val ref = db.getReference("adminMessages").child(uid)
            val snap = ref.get().await()
            for (child in snap.children) {
                val msg = child.getValue(String::class.java)
                if (msg != null) {
                    sendAdminNotification(msg, child.key.hashCode())
                    child.ref.removeValue() // Delete after reading
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendAdminNotification(messageText: String, notifId: Int) {
        val isAlert = messageText.startsWith("ALERT:")
        val displayText = if (isAlert) messageText.removePrefix("ALERT:") else messageText
        val title = if (isAlert) "Alertas: $displayText" else "Equipa Metrotime \uD83D\uDE87"
        val body = if (isAlert) displayText else messageText

        val channelId = "admin_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Mensagens da Equipa",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, com.example.metrotime.ui.LiveMapActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(notifId, notification)
    }

    private var cachedStationsForHistory: List<NearbyStation>? = null

    private suspend fun checkStationProximity(myLat: Double, myLon: Double) {
        if (cachedStationsForHistory == null) {
            val list = mutableListOf<NearbyStation>()
            // Metro
            val metro = StationsRepo.loadStations(this)
            list.addAll(metro.map { NearbyStation(it.id, it.name, "metro", it.line, it.lat, it.lon) })
            
            // CP
            try {
                val cpResp = com.example.metrotime.data.cp.CpApi.service.getStations()
                list.addAll(cpResp.stations.mapNotNull { cpStation ->
                    val lat = cpStation.latitude?.toDoubleOrNull()
                    val lon = cpStation.longitude?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        NearbyStation(cpStation.code, cpStation.designation, "cp", cpStation.railways?.joinToString(" ") ?: "", lat, lon)
                    } else null
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
            cachedStationsForHistory = list
        }
        
        cachedStationsForHistory?.let { allStations ->
            TripHistoryManager.onLocationUpdate(this@WidgetUpdateService, myLat, myLon, allStations)
            
            val ready = TripHistoryManager.getReadyDetections(this@WidgetUpdateService)
            if (ready.isNotEmpty() && ready.size != lastNotifiedPendingCount) {
                lastNotifiedPendingCount = ready.size
                sendPendingConfirmationNotification(ready.size)
            } else if (ready.isEmpty()) {
                lastNotifiedPendingCount = 0
            }
        }
    }

    private var lastNotifiedPendingCount = 0

    private fun sendPendingConfirmationNotification(count: Int) {
        val channelId = "metro_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alertas Metro", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, com.example.metrotime.ui.SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val text = if (count == 1) "Tens 1 estação pendente para confirmar. Ganha os teus pontos!" else "Tens $count estações pendentes para confirmar. Regista a tua viagem!"

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_metro) // Certifica-te que este ícone existe
            .setContentTitle("Viagem detetada! 🚇")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(9999, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel() }
}
