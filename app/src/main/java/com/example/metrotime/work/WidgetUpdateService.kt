package com.example.metrotime.work

import android.app.AlarmManager
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
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.metrotime.R
import com.example.metrotime.data.ArrivalsRepo
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.UserDirectory
import com.example.metrotime.data.longOrNull
import com.example.metrotime.data.stringOrNull
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
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * Single unified service that:
 *  1. Fetches station data (GPS or fixed station)
 *  2. Updates the widget + lock screen notification at a lightweight interval
 *  3. Re-fetches the widgets' data (metro, bus stop, CP) every 10 seconds while the screen is on
 */
class WidgetUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastNotifiedFriends = mutableMapOf<String, String>() // Map FriendID to TrainName
    private lateinit var remoteSource: RemoteArrivalsSource
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null

    // Ecrã ligado: atualiza logo (o receiver declarado no manifest nunca recebia SCREEN_ON). Com o ecrã
    // desligado os widgets não se atualizam, por isso ao acender estavam com os dados de quando se apagou
    // (um autocarro "a chegar" que já tinha passado).
    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (loopJob?.isActive != true) return
            scope.launch {
                try {
                    refreshArrivals()
                    withContext(Dispatchers.Main) { redrawAll() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            scope.launch {
                try {
                    if (BusWidgetUpdater.hasWidgets(this@WidgetUpdateService)) BusWidgetUpdater.updateAll(this@WidgetUpdateService)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            scope.launch {
                try {
                    CpWidgetUpdater.updateAll(this@WidgetUpdateService)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        remoteSource = RemoteArrivalsSource(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Wake lock só é segurado enquanto há partilha contínua de localização (ver updateWakeLock);
        // antes ficava 4 h ligado sempre que o serviço arrancava.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "metrotime:location_keepalive").apply {
            setReferenceCounted(false)
        }

        // ContextCompat: as flags de exportação só existem no Android 13+ (a app instala-se desde o 12)
        androidx.core.content.ContextCompat.registerReceiver(this, screenOnReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        restoreTrainState()

        // Schedule WorkManager periodic keepalive as fallback
        LocationKeepAliveWorker.schedule(this)
    }

    companion object {
        const val ACTION_FETCH_DATA = "com.example.metrotime.FETCH_DATA"
        const val ACTION_REDRAW_WIDGET = "com.example.metrotime.REDRAW_WIDGET"
        const val ACTION_HEARTBEAT = "com.example.metrotime.HEARTBEAT"
        /** Botão "Não estou neste comboio" da notificação. */
        const val ACTION_TRAIN_NOT_ME = "com.example.metrotime.TRAIN_NOT_ME"
        private const val TRAIN_NOTIFICATION_ID = 4411
        private const val TRAIN_PREFS = "train_tracking"
        private const val EXTRA_FROM_UI = "from_ui"

        private const val CHANNEL_ID = "metro_live"
        /** Categoria da notificação discreta (a que o Android obriga enquanto o serviço corre); pode desligar-se. */
        const val CHANNEL_BACKGROUND = "background"
        private const val NOTIFICATION_ID = 1

        /** Abre, nas definições do Android, a categoria da notificação discreta (para a desligar). */
        fun backgroundChannelSettings(context: Context): Intent =
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_BACKGROUND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        private const val UI_REFRESH_INTERVAL_MS = 5_000L
        /** Dados novos para os widgets (metro, paragem, CP) com o ecrã ligado. */
        private const val WIDGET_REFRESH_MS = 10_000L
        /** O mesmo em voltas do ciclo principal. */
        private const val WIDGET_REFRESH_LOOPS = (WIDGET_REFRESH_MS / UI_REFRESH_INTERVAL_MS).toInt()
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** [fromUi] = pedido feito por um ecrã visível da app (permite o tipo "location" no Android 14). */
        fun requestFetch(context: Context, fromUi: Boolean = false) {
            val intent = Intent(context, WidgetUpdateService::class.java).apply {
                action = ACTION_FETCH_DATA
                putExtra(EXTRA_FROM_UI, fromUi)
            }
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
        scheduleHeartbeat()

        when (intent?.action) {
            ACTION_FETCH_DATA, null -> { // null = restarted by system via START_STICKY
                // Use the live notification as the foreground notification itself
                if (!startForegroundSafely(fromUi = intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                scope.coroutineContext.cancelChildren()
                loopJob = scope.launch {
                    // Step 1: Fetch initial data
                    fetchNewData()

                    // Step 2: Do one immediate update
                    withContext(Dispatchers.Main) { redrawAll() }

                    // Step 3: Refresh the UI every five seconds. Rebuilding RemoteViews
                    // every second creates avoidable binder work while the user scrolls.
                    var loops = 0
                    while (isActive) {
                        // Com o ecrã desligado ninguém vê o widget nem a notificação: só se mantém a
                        // partilha de localização e as mensagens (o receiver de SCREEN_ON atualiza logo)
                        val screenOn = powerManager.isInteractive
                        try {
                            val uid = FirebaseAuth.getInstance().currentUser?.uid
                            val sharing = uid != null && Prefs.isContinuousLocation(this@WidgetUpdateService) && hasLocationPermission()
                            // Registo de viagens: só no telemóvel, não precisa de sessão nem de partilhar
                            val tracking = Prefs.isTripTracking(this@WidgetUpdateService) && hasLocationPermission()
                            val following = CpFollow.current(this@WidgetUpdateService) != null
                            val busFollowing = BusFollow.isActive(this@WidgetUpdateService)
                            // Só os autocarros perto da hora prendem o telemóvel acordado (o batimento de 15 min
                            // volta a olhar para os que faltam horas)
                            val busSoon = busFollowing && BusFollow.hasSoon(this@WidgetUpdateService)
                            val exitAlarm = ExitAlarm.current(this@WidgetUpdateService)
                            val exitGps = exitAlarm?.isGps == true && hasLocationPermission()
                            LiveDebug.loop.value = LiveDebug.Loop(System.currentTimeMillis(), loops, screenOn, sharing, tracking)
                            // Os avisos ("prepara-te para sair", "o autocarro está a chegar") têm de chegar mesmo com o ecrã desligado
                            updateWakeLock(sharing || tracking || following || busSoon || exitAlarm != null)

                            // Comboio seguido: verificado a cada minuto, com ou sem ecrã
                            if (following && loops % 12 == 0) {
                                CpFollow.check(this@WidgetUpdateService)
                            }

                            // Autocarro seguido: a cada 30 s
                            if (busFollowing && loops % 6 == 0) {
                                BusFollow.check(this@WidgetUpdateService)
                            }

                            // Último metro/comboio para casa: a cada minuto (só faz alguma coisa à noite e longe de casa)
                            // (um erro aqui não pode saltar o resto da volta: perturbações, widgets, localização)
                            if (loops % 12 == 0) {
                                try {
                                    LastTripAlert.check(this@WidgetUpdateService)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    e.printStackTrace()
                                }
                            }

                            // Perturbações no metro e avisos CP: com ou sem ecrã (os intervalos contam-se lá dentro)
                            if (loops % 12 == 0) {
                                DisruptionAlerts.check(this@WidgetUpdateService)
                            }

                            // Widget CP: a cada 10 s, só com o ecrã ligado
                            if (screenOn && loops % WIDGET_REFRESH_LOOPS == 0) {
                                CpWidgetUpdater.updateAll(this@WidgetUpdateService)
                            }

                            // Widget da paragem: a cada 10 s, só com o ecrã ligado (a 30 s dizia "a chegar"
                            // quando o mapa já mostrava o autocarro depois da paragem)
                            if (screenOn && loops % WIDGET_REFRESH_LOOPS == 0 && BusWidgetUpdater.hasWidgets(this@WidgetUpdateService)) {
                                BusWidgetUpdater.updateAll(this@WidgetUpdateService)
                            }

                            // Metro: dados novos a cada 10 s; se ainda não há estação (ex.: GPS falhou
                            // no arranque) volta a procurá-la — antes o widget ficava em "A carregar..."
                            if (screenOn && loops > 0 && loops % WIDGET_REFRESH_LOOPS == 0) {
                                refreshArrivals()
                            }

                            // A cada 30 s: localização para os amigos (partilha), o registo de viagens e o aviso antes de sair
                            val locResult = if (loops % 6 == 0 && (sharing || tracking || exitGps)) {
                                Locator.getCurrentLocationWithSpeed(this@WidgetUpdateService)
                            } else null
                            locResult?.let {
                                LiveDebug.fix.value = LiveDebug.Fix(
                                    it.timeMs, it.latitude, it.longitude, it.accuracyM, it.speedMps.takeIf { s -> s >= 0f }?.let { s -> s * 3.6 }
                                )
                            }
                            if (loops % 6 == 0 && (sharing || tracking)) {
                                val share = sharing && !Prefs.isMapSharingBlocked(this@WidgetUpdateService)
                                if (locResult != null && uid != null) {
                                    if (share) {
                                        val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                                        val map = mapOf(
                                            "lat" to locResult.latitude,
                                            "lon" to locResult.longitude,
                                            "timestamp" to System.currentTimeMillis()
                                        )
                                        db.getReference("locations").child(uid).updateChildren(map)
                                    } else if (sharing) {
                                        // Clear location from Firebase if sharing is blocked
                                        FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("locations").child(uid).removeValue()
                                    }
                                    // Comboio em que vais: para os amigos (se partilhas) e para as viagens (se registas)
                                    if (share || tracking) {
                                        checkLocalUserOnTrain(uid, locResult.latitude, locResult.longitude, locResult.speedMps, share, tracking, locResult.accuracyM)
                                    }
                                }
                                // Viagens de metro: também sem leitura (no subterrâneo é isso que se espera)
                                if (tracking) recordTripLocation(locResult)
                            }

                            // Aviso antes de sair: pelo GPS a cada 30 s, pelo relógio (metro) a cada volta
                            if (exitAlarm != null && (!exitAlarm.isGps || loops % 6 == 0)) {
                                ExitAlarm.check(this@WidgetUpdateService, locResult)
                            }

                            // Read friends' train status to send notifications
                            if (loops % 6 == 0 && uid != null && sharing) {
                                checkFriendsTrainStatus(uid)
                            }

                            // 3. Read admin messages every 15 seconds.
                            if (loops % 3 == 0 && uid != null) {
                                checkAdminMessages(uid)
                            }

                            // RemoteViews updates cross process boundaries, so keep these bounded.
                            if (screenOn) {
                                withContext(Dispatchers.Main) { redrawAll() }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                        delay(UI_REFRESH_INTERVAL_MS)
                        loops++
                    }
                }
            }
            ACTION_TRAIN_NOT_ME -> {
                ignoredTrainId = activeTrainId
                ignoredUntil = System.currentTimeMillis() + 60 * 60 * 1000L
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                clearTrain(uid, uid != null && Prefs.isContinuousLocation(this), runCatching { FirebaseDatabase.getInstance(FIREBASE_DB_URL) }.getOrNull())
                TrainTrackingDebugger.log("🙅 Não ias neste comboio: esquecido")
                if (loopJob?.isActive != true) stopSelf(startId)
            }
            ACTION_REDRAW_WIDGET -> {
                if (!startForegroundSafely(fromUi = false)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                scope.launch {
                    withContext(Dispatchers.Main) {
                        redrawWidget()
                        redrawLockWidget()
                    }
                    // Só pára se o ciclo principal não estiver a correr: stopSelf(startId) com o pedido
                    // mais recente parava o serviço inteiro e matava as atualizações em direto
                    if (loopJob?.isActive != true) stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * No Android 14, um serviço em primeiro plano do tipo "location" sem permissão de localização
     * (ou arrancado em background sem "Permitir sempre") lança SecurityException e fecha a app.
     * Só se pede esse tipo quando é permitido; senão fica apenas "dataSync".
     */
    private fun startForegroundSafely(fromUi: Boolean): Boolean {
        val dataSync = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val canUseLocation = hasLocationPermission() &&
            (fromUi || hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        val type = if (canUseLocation) dataSync or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else dataSync
        return try {
            startForeground(NOTIFICATION_ID, buildLiveNotification(), type)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, buildLiveNotification(), dataSync)
                true
            } catch (e2: Exception) {
                e2.printStackTrace()
                false
            }
        }
    }

    /** Wake lock renovado a cada ciclo enquanto há partilha contínua; expira sozinho se o ciclo parar. */
    private fun updateWakeLock(needed: Boolean) {
        val lock = wakeLock ?: return
        try {
            if (needed) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            else if (lock.isHeld) lock.release()
        } catch (_: Exception) { }
    }

    private suspend fun refreshArrivals() {
        val station = ArrivalsRepo.currentArrivals.value?.station
        if (station == null) {
            fetchNewData()
        } else {
            ArrivalsRepo.newArrivals(remoteSource.fetchArrivals(station.apiId))
        }
    }

    private fun redrawAll() {
        // A discreta não muda: só os tempos do metro precisam de ser redesenhados
        if (Prefs.isLiveMetroNotification(this)) notificationManager.notify(NOTIFICATION_ID, buildLiveNotification())
        redrawWidget()
        redrawLockWidget()
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
        val ids = manager.getAppWidgetIds(ComponentName(this, MetroWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, MetroWidgetViews.home(this, ArrivalsRepo.currentArrivals.value))
    }

    // ─── Lock Glass Widget ──────────────────────────────────────────

    private fun redrawLockWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, LockWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, MetroWidgetViews.lock(this, ArrivalsRepo.currentArrivals.value))
    }

    // ─── Lock Screen / Notification ─────────────────────────────────

    private fun buildLiveNotification(): Notification {
        if (!Prefs.isLiveMetroNotification(this)) return buildQuietNotification()
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

    /**
     * A notificação que o Android obriga a mostrar enquanto o serviço corre (widgets, avisos de viagem, partilha
     * com os amigos): discreta (sem som, sem ícone na barra, fora do ecrã de bloqueio) e numa categoria própria
     * que se pode desligar sem perder nada. Tocar nela abre essa categoria nas definições do Android.
     */
    private fun buildQuietNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 4499, backgroundChannelSettings(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle("Metrotime em segundo plano")
            .setContentText("Widgets e avisos de viagem. Toca para esconder.")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    // ─── Channel & Helpers ──────────────────────────────────────────

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_BACKGROUND, "Em segundo plano", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aviso obrigatório do Android enquanto a app atualiza os widgets e os avisos. Podes desligar esta categoria: tudo continua a funcionar."
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
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

    private var lastCheckLat = 0.0
    private var lastCheckLon = 0.0
    private var lastCheckTime = 0L
    private var activeTrainId: String? = null
    private var activeTrainName: String? = null
    private var activeTrainDest: String? = null
    private var trainPathMeters = 0.0          // distância realmente percorrida (soma dos troços)
    /** Velocidades do GPS nos últimos ~25 s a bordo, e a maior mantida 20 s seguidos (para os distintivos). */
    private val trainSpeeds = ArrayDeque<Pair<Long, Double>>()
    private var trainTopKmh: Double? = null
    private var lockCandidateId: String? = null // comboio que bateu nas verificações anteriores
    private var lockCandidateHits = 0
    // Onde estávamos quando o candidato apareceu pela primeira vez (embarque, se não estávamos parados)
    private var candidateLat = 0.0
    private var candidateLon = 0.0
    private var candidateMs = 0L
    // A bordo: última estação onde estiveste com o comboio e as contagens para saber quando saíste (TrainRide)
    private var trainRide = com.example.metrotime.data.cp.TrainRide.State()
    // Traçado e paragens do comboio em que vais (para as estações e os km da viagem)
    private var trainTrack: com.example.metrotime.data.cp.CpTrack? = null
    private var trainStops: List<com.example.metrotime.data.cp.TrainMatcher.Stop> = emptyList()
    // "Não estou neste comboio": não volta a ser escolhido durante uma hora
    private var ignoredTrainId: String? = null
    private var ignoredUntil = 0L
    /** As linhas de comboio da rede, para saber se estamos em cima de uma (carregadas uma vez). */
    private val railTracks: List<com.example.metrotime.data.cp.CpTrack> by lazy {
        runCatching { com.example.metrotime.data.cp.CpNetwork.networkLines(this).map { com.example.metrotime.data.cp.CpTrack(it) } }.getOrDefault(emptyList())
    }
    // Último sítio onde estivemos parados (ex.: no cais) — melhor pista da estação de embarque
    private var lastStillLat = 0.0
    private var lastStillLon = 0.0
    private var lastStillTime = 0L
    private val speedHistory = mutableListOf<Double>() // Rolling window for smoothing

    /** Posição conhecida de um comboio e há quantos segundos foi medida. */
    private data class TrainFix(val id: String, val lat: Double, val lon: Double, val ageSec: Double, val bearing: Double? = null)

    /** Desde quando se vê cada comboio na mesma posição, e com que idade ela chegou da primeira vez. */
    private data class FixSeen(val lat: Double, val lon: Double, val sinceMs: Long, val ageSec: Double)
    private val trainFixSeen = mutableMapOf<String, FixSeen>()

    /**
     * A idade verdadeira de uma posição: a fonte (ruicosta) não diz quando a mediu, e a hora que vem com ela é a
     * da cópia para o Firebase. Uma posição que não muda há 2 min tem pelo menos 2 min (o 529 ficou parado no
     * mesmo ponto enquanto andava), e o comboio pode ter andado isso desde ela.
     */
    private fun withRealAge(fix: TrainFix, now: Long): TrainFix {
        val seen = trainFixSeen[fix.id]
        if (seen == null || seen.lat != fix.lat || seen.lon != fix.lon) {
            trainFixSeen[fix.id] = FixSeen(fix.lat, fix.lon, now, fix.ageSec)
            return fix
        }
        val age = seen.ageSec + (now - seen.sinceMs) / 1000.0
        return if (age > fix.ageSec) fix.copy(ageSec = age) else fix
    }

    /** Última posição de cada comboio vista aqui, para saber para onde vai (quando a CF não manda o rumo). */
    private val lastTrainFixes = mutableMapOf<String, Pair<Double, Double>>()
    private val lastTrainBearings = mutableMapOf<String, Double>()

    private fun trainBearing(fix: TrainFix): Double? {
        fix.bearing?.let { return it }
        val here = fix.lat to fix.lon
        val previous = lastTrainFixes[fix.id]
        if (previous != null && com.example.metrotime.data.cp.CpTrack.metres(previous, here) > 150) {
            lastTrainBearings[fix.id] = com.example.metrotime.data.cp.CpTrack.bearing(previous, here)
            lastTrainFixes[fix.id] = here
        } else if (previous == null) {
            lastTrainFixes[fix.id] = here
        }
        return lastTrainBearings[fix.id]
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    private fun parseFix(id: String, s: com.google.firebase.database.DataSnapshot, fallbackTs: Long, now: Long): TrainFix? {
        val lat = s.child("lat").value.asDouble() ?: s.child("latitude").value.asDouble() ?: return null
        val lon = s.child("lon").value.asDouble() ?: s.child("longitude").value.asDouble() ?: return null
        if (lat == 0.0 || lon == 0.0) return null
        val ts = (s.child("ts").value as? Number)?.toLong()
            ?: (s.child("timestamp").value as? Number)?.toLong()
            ?: fallbackTs
        val bearing = (s.child("bearing").value as? Number)?.toDouble()
        return TrainFix(id, lat, lon, (now - ts).coerceAtLeast(0L) / 1000.0, bearing)
    }

    /**
     * Lê as posições dos comboios. Usa o nó compacto `cp_positions` (poucos KB, escrito pela Cloud
     * Function) em vez de descarregar todo o `cp_vehicles`; se a função ainda não o escrever, usa
     * `cp_vehicles` como antes.
     */
    private suspend fun readTrainPositions(db: FirebaseDatabase, onlyId: String? = null): List<TrainFix> {
        val now = System.currentTimeMillis()
        val compact = db.getReference("cp_positions")
        if (compact.child("updatedAt").get().await().exists()) {
            val trains = compact.child("trains")
            return if (onlyId != null) {
                listOfNotNull(parseFix(onlyId, trains.child(onlyId).get().await(), now, now))
            } else {
                trains.get().await().children.mapNotNull { c -> c.key?.let { parseFix(it, c, now, now) } }
            }
        }
        val vehicles = db.getReference("cp_vehicles")
        val updatedAt = (vehicles.child("updatedAt").get().await().value as? Number)?.toLong() ?: now
        return if (onlyId != null) {
            listOfNotNull(parseFix(onlyId, vehicles.child("vehicles").child(onlyId).get().await(), updatedAt, now))
        } else {
            vehicles.child("vehicles").get().await().children.mapNotNull { c -> c.key?.let { parseFix(it, c, updatedAt, now) } }
        }
    }

    /**
     * Distância máxima aceitável entre nós e o comboio. As posições chegam com atraso (até ~1–2 min);
     * a 200 km/h um comboio anda >3 km nesse tempo, por isso a tolerância cresce com a idade da posição.
     */
    private fun toleranceMeters(baseMeters: Double, speedKmh: Double, ageSec: Double): Double =
        (baseMeters + (speedKmh / 3.6) * ageSec.coerceAtMost(180.0)).coerceAtMost(8_000.0)

    private suspend fun nearestCpStation(lat: Double, lon: Double, maxMeters: Double): NearbyStation? {
        val stations = cpStationsForTrips()
        return stations
            .map { it to distance(lat, lon, it.lat, it.lon) }
            .filter { it.second <= maxMeters }
            .minByOrNull { it.second }
            ?.first
    }

    // Comboio em que vais, para a viagem: estação e hora de embarque, serviço (AP, IC, U…) e número
    private var trainBoarding: com.example.metrotime.data.cp.TrainMatcher.Stop? = null
    private var trainStartMs = 0L
    private var trainService: String? = null
    private var trainNumber: String? = null

    /**
     * Em que comboio vais. [share] = escrever em "locations" para os amigos; [record] = guardar a viagem.
     *
     * Entrar: a mais de 30 km/h, a menos de 150 m de uma linha de comboio, e o mesmo comboio a bater em 3
     * leituras seguidas — no troço entre onde foi visto e onde estaria agora, ao longo do traçado dele
     * (antes bastava estar a 4–8 km de um comboio no mesmo sentido, e um carro na autoestrada ao lado "ia
     * no comboio"). Sair: pela tua localização ([com.example.metrotime.data.cp.TrainRide]) — só se sai numa
     * estação, e em cima da linha continuas no comboio. As estações de embarque e de saída são paragens desse
     * comboio, e os km medem-se pelo traçado.
     */
    private suspend fun checkLocalUserOnTrain(
        uid: String, myLat: Double, myLon: Double, nativeSpeedMps: Float = -1f, share: Boolean = true, record: Boolean = false,
        accuracyM: Float = 0f
    ) {
        val now = System.currentTimeMillis()
        val prevLat = lastCheckLat
        val prevLon = lastCheckLon
        val prevTime = lastCheckTime
        var speedKmh = 0.0
        if (nativeSpeedMps >= 0f) {
            speedKmh = nativeSpeedMps.toDouble() * 3.6
        } else if (prevTime > 0) {
            val dt = (now - prevTime) / 1000.0
            if (dt > 0 && dt < 120) speedKmh = distance(myLat, myLon, prevLat, prevLon) / dt * 3.6
        }
        speedKmh = speedKmh.coerceAtMost(220.0)
        speedHistory.add(speedKmh)
        if (speedHistory.size > 3) speedHistory.removeAt(0)
        val smoothedSpeed = speedHistory.average()
        val myBearing = if (prevTime > 0 && distance(prevLat, prevLon, myLat, myLon) > 150) {
            com.example.metrotime.data.cp.CpTrack.bearing(prevLat to prevLon, myLat to myLon)
        } else null
        lastCheckLat = myLat
        lastCheckLon = myLon
        lastCheckTime = now
        // Onde estivemos parados por último: o cais antes de embarcar, ou a estação onde saímos
        if (smoothedSpeed < 8.0) {
            lastStillLat = myLat
            lastStillLon = myLon
            lastStillTime = now
        }
        val user = com.example.metrotime.data.cp.TrainMatcher.User(myLat, myLon, smoothedSpeed, myBearing)

        try {
            val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
            val active = activeTrainId
            if (active != null) {
                if (nativeSpeedMps >= 0f && accuracyM in 0f..60f) recordTrainSpeed(now, nativeSpeedMps * 3.6)
                // Somar o troço desde a última leitura (descarta saltos de GPS impossíveis)
                if (prevTime > 0) {
                    val step = distance(prevLat, prevLon, myLat, myLon).toDouble()
                    val maxStep = (220.0 / 3.6) * ((now - prevTime) / 1000.0) + 500.0
                    if (step <= maxStep) trainPathMeters += step
                }
                // Paragens do comboio, se ainda não as temos (ex.: depois de o serviço reiniciar)
                if (trainStops.isEmpty()) trainStops = loadTrainStops(active)
                if (trainTrack == null) trainTrack = active.toIntOrNull()?.let { com.example.metrotime.data.cp.CpNetwork.trackForTrain(this, it) }

                val fix = readTrainPositions(db, active).firstOrNull()?.let { withRealAge(it, now) }
                val gap = fix?.let {
                    com.example.metrotime.data.cp.TrainMatcher.gap(
                        user, com.example.metrotime.data.cp.TrainMatcher.Train(it.id, it.lat, it.lon, it.ageSec, trainBearing(it)), trainTrack
                    )
                }
                // Sais do comboio pela TUA localização (TrainRide): em cima da linha dele ou numa estação continuas
                // nele; sais quando sais da estação para longe da linha. A posição do comboio só serve para ver se
                // ele já vai longe de ti (ficaste na estação, ou mudaste de comboio na mesma linha).
                val me = myLat to myLon
                val railM = trainTrack?.takeIf { it.line.size >= 2 }?.let { t ->
                    com.example.metrotime.data.cp.CpTrack.metres(me, t.pointAt(t.locate(me).s))
                } ?: com.example.metrotime.data.cp.TrainMatcher.distanceToRail(railTracks, me)
                val stops = trainStops.ifEmpty { cpStopsNear(me) }
                val (nextRide, exit) = com.example.metrotime.data.cp.TrainRide.step(
                    trainRide, com.example.metrotime.data.cp.TrainRide.Fix(myLat, myLon, accuracyM, now),
                    stops, trainStops.lastOrNull(), railM, gap, fix?.ageSec
                )
                trainRide = nextRide
                LiveDebug.onBoard.value = LiveDebug.OnBoard(
                    now, active, activeTrainName, activeTrainDest, trainService, trainBoarding?.name, trainStartMs, trainPathMeters / 1000.0,
                    trainTopKmh, trainStops.size, trainTrack != null, railM, gap, fix?.ageSec, trainRide.lastStation?.stop?.name,
                    trainRide.awayFixes, trainRide.stationOffRailFixes, trainRide.apartChecks
                )
                if (exit == null) {
                    val where = trainRide.lastStation?.stop?.name?.let { " · última estação: $it" } ?: ""
                    TrainTrackingDebugger.log(
                        "✅ A bordo do $activeTrainName (${railM.toInt()} m da linha; comboio ${gap?.let { "a ${it.toInt()} m" } ?: "sem posição"}$where)"
                    )
                    publishTrain(uid, share, db)
                    saveTrainState()
                    return
                }
                finishTrain(uid, share, record, db, exit)
                return
            }

            // Ainda em nenhum comboio: só se procura a andar depressa e em cima de uma linha de comboio
            if (smoothedSpeed < com.example.metrotime.data.cp.TrainMatcher.MIN_SPEED_KMH) {
                lockCandidateId = null
                lockCandidateHits = 0
                LiveDebug.search.value = LiveDebug.Search(
                    now, smoothedSpeed, verdict = "Devagar demais: só se procura comboio a ${com.example.metrotime.data.cp.TrainMatcher.MIN_SPEED_KMH.toInt()} km/h ou mais"
                )
                return
            }
            val railM = com.example.metrotime.data.cp.TrainMatcher.distanceToRail(railTracks, myLat to myLon)
            if (railTracks.isNotEmpty() && railM > com.example.metrotime.data.cp.TrainMatcher.MAX_RAIL_DISTANCE_M) {
                TrainTrackingDebugger.log("A ${"%.0f".format(smoothedSpeed)} km/h mas a ${railM.toInt()} m da linha de comboio: não é comboio")
                lockCandidateId = null
                lockCandidateHits = 0
                LiveDebug.search.value = LiveDebug.Search(
                    now, smoothedSpeed, railM,
                    verdict = "Longe da linha de comboio (máx. ${com.example.metrotime.data.cp.TrainMatcher.MAX_RAIL_DISTANCE_M.toInt()} m): não é comboio"
                )
                return
            }
            val trains = readTrainPositions(db)
                .filter { !(it.id == ignoredTrainId && now < ignoredUntil) }
                .filter { distance(myLat, myLon, it.lat, it.lon) < 15_000 }
                .map { withRealAge(it, now) }
                .map { com.example.metrotime.data.cp.TrainMatcher.Train(it.id, it.lat, it.lon, it.ageSec, trainBearing(it)) }
            val best = com.example.metrotime.data.cp.TrainMatcher.best(user, trains) { id ->
                id.toIntOrNull()?.let { com.example.metrotime.data.cp.CpNetwork.trackForTrain(this, it) }
            }
            if (best == null) {
                TrainTrackingDebugger.log("Nenhum comboio bate com a tua posição.")
                lockCandidateId = null
                lockCandidateHits = 0
                LiveDebug.search.value = LiveDebug.Search(
                    now, smoothedSpeed, railM, trains.size, verdict = "Nenhum dos ${trains.size} comboios perto bate com a tua posição"
                )
                return
            }
            val (train, gap) = best
            if (train.id == lockCandidateId) {
                lockCandidateHits++
            } else {
                lockCandidateId = train.id
                lockCandidateHits = 1
                candidateLat = if (prevTime > 0) prevLat else myLat
                candidateLon = if (prevTime > 0) prevLon else myLon
                candidateMs = if (prevTime > 0) prevTime else now
            }
            TrainTrackingDebugger.log("🚂 Comboio ${train.id} a ${gap.toInt()} m — ${lockCandidateHits}/${com.example.metrotime.data.cp.TrainMatcher.LOCK_CHECKS}")
            LiveDebug.search.value = LiveDebug.Search(
                now, smoothedSpeed, railM, trains.size, train.id, lockCandidateHits, gap,
                if (lockCandidateHits >= com.example.metrotime.data.cp.TrainMatcher.LOCK_CHECKS) "Entraste no comboio ${train.id}"
                else "O comboio ${train.id} bate: falta${if (com.example.metrotime.data.cp.TrainMatcher.LOCK_CHECKS - lockCandidateHits > 1) "m" else ""} ${com.example.metrotime.data.cp.TrainMatcher.LOCK_CHECKS - lockCandidateHits} verificação(ões)"
            )
            if (lockCandidateHits >= com.example.metrotime.data.cp.TrainMatcher.LOCK_CHECKS) startTrain(uid, share, db, train.id, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
    }

    /** Entrámos no comboio [trainId]: nome, destino, serviço, paragens, estação e hora de embarque. */
    private suspend fun startTrain(uid: String, share: Boolean, db: FirebaseDatabase, trainId: String, now: Long) {
        val details = db.getReference("cp_vehicles/vehicles").child(trainId).get().await()
        val dest = details.child("destination").child("designation").stringOrNull() ?: "Desconhecido"
        val serviceType = details.child("service").child("designation").stringOrNull() ?: ""
        // Serviço pelo código da CP (1 Alfa, 2 IC, 3 IR, 4 Regional, 5 Urbano…), para as estatísticas
        trainService = details.child("service").child("code").stringOrNull()
            ?.let { com.example.metrotime.data.cp.CpService.shortName(it) }?.takeIf { it != "?" }
        trainNumber = trainId
        activeTrainId = trainId
        activeTrainName = if (serviceType.isNotEmpty()) "$serviceType $trainId" else "Comboio $trainId"
        activeTrainDest = com.example.metrotime.data.cp.formatStationName(dest)
        trainTrack = trainId.toIntOrNull()?.let { com.example.metrotime.data.cp.CpNetwork.trackForTrain(this, it) }
        trainStops = loadTrainStops(trainId)

        // Embarque: onde estávamos parados (o cais) há menos de 15 min, ou onde o comboio apareceu
        val fromStill = lastStillTime > 0 && now - lastStillTime < 15 * 60 * 1000L
        var boardPoint = if (fromStill) lastStillLat to lastStillLon else candidateLat to candidateLon
        trainBoarding = stationNear(boardPoint)
        trainStartMs = if (fromStill) lastStillTime else candidateMs.takeIf { it > 0 } ?: now
        // Só se deu pelo comboio a meio do percurso: se a viagem anterior acabou numa paragem dele antes daqui
        // (e ele partiu de lá depois), embarcaste lá — ex.: metro até Santa Apolónia e o IC só bateu em Riachos
        if (!fromStill) {
            TripHistoryManager.lastTripEnd(this, now)?.let { (point, endMs) ->
                com.example.metrotime.data.cp.TrainMatcher.boardingFromPrevious(
                    trainStops, trainTrack, point, endMs, lastCheckLat to lastCheckLon, now
                )?.let { stop ->
                    trainBoarding = stop
                    trainStartMs = stop.departureMs ?: endMs
                    boardPoint = stop.lat to stop.lon
                    TrainTrackingDebugger.log("↩️ Embarque pela viagem anterior: ${stop.name}")
                }
            }
        }
        trainPathMeters = distance(boardPoint.first, boardPoint.second, lastCheckLat, lastCheckLon).toDouble()
        trainRide = com.example.metrotime.data.cp.TrainRide.State()
        lockCandidateId = null
        lockCandidateHits = 0
        TrainTrackingDebugger.log("🎯 Vais no $activeTrainName para $activeTrainDest (embarque: ${trainBoarding?.name ?: "?"})")
        publishTrain(uid, share, db)
        notifyTrain()
        saveTrainState()
    }

    /**
     * Saímos do comboio: a viagem (embarque, saída, horas, serviço e km pelo traçado) e limpa tudo. A saída é a
     * última estação onde estiveste com o comboio e a hora a que lá chegaste ([exit], do TrainRide).
     */
    private suspend fun finishTrain(uid: String, share: Boolean, record: Boolean, db: FirebaseDatabase, exit: com.example.metrotime.data.cp.TrainRide.Exit) {
        val exitStop = exit.stop
        val endMs = exit.endMs.coerceAtLeast(trainStartMs)
        val km = com.example.metrotime.data.cp.TrainMatcher.trackKm(trainTrack, trainBoarding, exitStop)
            ?: trainPathMeters / 1000.0
        TrainTrackingDebugger.log("❌ Saíste do $activeTrainName em ${exitStop?.name ?: "?"} (${exit.reason}): ${"%.1f".format(km)} km")
        if (record && km > 0.5) {
            val delay = trainNumber?.toIntOrNull()?.let { n -> exitStop?.code?.let { arrivalDelayMin(n, it) } }
            TripHistoryManager.trainTrip(
                this@WidgetUpdateService, trainBoarding?.asStation(), exitStop?.asStation(),
                trainStartMs.takeIf { it > 0 } ?: endMs, endMs, km, trainService, trainNumber,
                topKmh = trainTopKmh, delayMin = delay, strikeDay = isStrikeDay(db)
            )
        }
        clearTrain(uid, share, db)
    }

    /** Esquece o comboio (saída ou "não estou neste comboio"), sem registar nada. */
    private fun clearTrain(uid: String?, share: Boolean, db: FirebaseDatabase?) {
        activeTrainId = null
        activeTrainName = null
        activeTrainDest = null
        trainBoarding = null
        trainStartMs = 0L
        trainService = null
        trainNumber = null
        trainTrack = null
        trainStops = emptyList()
        trainPathMeters = 0.0
        trainSpeeds.clear()
        trainTopKmh = null
        trainRide = com.example.metrotime.data.cp.TrainRide.State()
        lockCandidateId = null
        lockCandidateHits = 0
        LiveDebug.onBoard.value = null
        if (share && uid != null && db != null) {
            db.getReference("locations").child(uid).updateChildren(mapOf("currentTrain" to null, "trainDestination" to null))
        }
        notificationManager.cancel(TRAIN_NOTIFICATION_ID)
        getSharedPreferences(TRAIN_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun publishTrain(uid: String, share: Boolean, db: FirebaseDatabase) {
        if (!share) return
        db.getReference("locations").child(uid).updateChildren(mapOf("currentTrain" to activeTrainName, "trainDestination" to activeTrainDest))
    }

    /** Paragem do comboio mais perto do ponto; sem paragens, a estação CP mais perto (até 3 km). */
    private suspend fun stationNear(point: Pair<Double, Double>): com.example.metrotime.data.cp.TrainMatcher.Stop? =
        com.example.metrotime.data.cp.TrainMatcher.nearestStop(trainStops, point)
            ?: nearestCpStation(point.first, point.second, 3_000.0)?.let {
                com.example.metrotime.data.cp.TrainMatcher.Stop(it.id, it.name, it.lat, it.lon)
            }

    private fun com.example.metrotime.data.cp.TrainMatcher.Stop.asStation() = NearbyStation(code, name, "cp", "", lat, lon)

    /** Estações da CP perto de um ponto, como paragens: para quando não se conseguiram as paragens do comboio. */
    private suspend fun cpStopsNear(at: Pair<Double, Double>, maxM: Double = 5_000.0): List<com.example.metrotime.data.cp.TrainMatcher.Stop> =
        cpStationsForTrips()
            .filter { distance(at.first, at.second, it.lat, it.lon).toDouble() <= maxM }
            .map { com.example.metrotime.data.cp.TrainMatcher.Stop(it.id, it.name, it.lat, it.lon) }

    /** Paragens do comboio (da CP), com coordenadas e a hora a que parte de cada uma; vazio se falhar. */
    /** Guarda a velocidade do GPS e, quando há 20 s seguidos acima de um valor, esse valor passa a ser o máximo mantido. */
    private fun recordTrainSpeed(now: Long, kmh: Double) {
        trainSpeeds.addLast(now to kmh)
        while (trainSpeeds.isNotEmpty() && now - trainSpeeds.first().first > 25_000L) trainSpeeds.removeFirst()
        val window = trainSpeeds.filter { now - it.first <= 20_000L }
        if (window.size >= 3 && now - window.first().first >= 15_000L) {
            val held = window.minOf { it.second }
            trainTopKmh = maxOf(trainTopKmh ?: 0.0, held)
        }
    }

    /** Minutos de atraso do comboio à chegada a [code] (as horas da CP); null sem dados. */
    private suspend fun arrivalDelayMin(number: Int, code: String): Int? = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            val (detail, date) = com.example.metrotime.data.cp.CpRepository.getTripDetail(number, null)
            val stops = detail.trainStops.orEmpty()
            val i = stops.indexOfLast { it.station?.code == code }.takeIf { it >= 0 } ?: return@withTimeoutOrNull null
            val m = com.example.metrotime.data.cp.CpTripClock.moments(date, stops)[i]
            val scheduled = m.scheduledArrival ?: m.scheduledDeparture ?: return@withTimeoutOrNull null
            val real = m.arrival ?: m.departure ?: return@withTimeoutOrNull null
            java.time.Duration.between(scheduled, real).toMinutes().toInt()
        }
    }.getOrNull()

    /**
     * Dia de greve ([com.example.metrotime.data.cp.CpStrike]): a CP anunciou greve para hoje (cp_avisos) e ela nota-se
     * nos comboios (cp_suppressions e cp_vehicles). Sem os dados todos, não é.
     */
    private suspend fun isStrikeDay(db: FirebaseDatabase): Boolean = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            val today = com.example.metrotime.data.cp.CpTime.today()
            val notices = com.example.metrotime.data.cp.CpRepository.getNotices()
            if (!com.example.metrotime.data.cp.CpStrike.announced(notices, today)) return@withTimeoutOrNull false
            val suppressed = (db.getReference("cp_suppressions/count").get().await().value as? Number)?.toInt()
                ?: return@withTimeoutOrNull false
            val running = (db.getReference("cp_vehicles/count").get().await().value as? Number)?.toInt()
                ?: return@withTimeoutOrNull false
            com.example.metrotime.data.cp.CpStrike.felt(suppressed, running)
        } ?: false
    }.getOrDefault(false)

    private suspend fun loadTrainStops(trainId: String): List<com.example.metrotime.data.cp.TrainMatcher.Stop> = runCatching {
        val number = trainId.toIntOrNull() ?: return@runCatching emptyList()
        val (detail, date) = com.example.metrotime.data.cp.CpRepository.getTripDetail(number, null)
        val stops = detail.trainStops.orEmpty()
        val moments = runCatching { com.example.metrotime.data.cp.CpTripClock.moments(date, stops) }.getOrNull()
        stops.mapIndexedNotNull { i, s ->
            val lat = s.latitude?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lon = s.longitude?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val departs = moments?.getOrNull(i)?.departureOrArrival
                ?.atZone(com.example.metrotime.data.cp.CpTime.LISBON)?.toInstant()?.toEpochMilli()
            com.example.metrotime.data.cp.TrainMatcher.Stop(
                s.station?.code ?: return@mapIndexedNotNull null,
                com.example.metrotime.data.cp.formatStationName(s.station.designation ?: ""),
                lat, lon, departs
            )
        }
    }.getOrDefault(emptyList())

    /** "Estás no IC 521 para Porto - Campanhã": tocar abre o comboio; o botão corrige se não for o teu. */
    private fun notifyTrain() {
        val channelId = "train_live"
        notificationManager.createNotificationChannel(NotificationChannel(channelId, "Comboio em que vais", NotificationManager.IMPORTANCE_LOW))
        val open = trainNumber?.toIntOrNull()?.let { number ->
            PendingIntent.getActivity(
                this, 4412,
                com.example.metrotime.ui.CpTripDetailActivity.newIntent(this, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notMe = PendingIntent.getService(
            this, 4410,
            Intent(this, WidgetUpdateService::class.java).setAction(ACTION_TRAIN_NOT_ME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle("Estás no $activeTrainName")
            .setContentText(listOfNotNull(activeTrainDest?.let { "para $it" }, trainBoarding?.name?.let { "desde $it" }).joinToString(" · "))
            .setContentIntent(open)
            .addAction(0, "Não estou neste comboio", notMe)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(TRAIN_NOTIFICATION_ID, notification)
    }

    /** Guarda o comboio em que vais: se o Android reiniciar o serviço a meio, a viagem não se perde. */
    private fun saveTrainState() {
        val id = activeTrainId ?: return
        val b = trainBoarding
        getSharedPreferences(TRAIN_PREFS, Context.MODE_PRIVATE).edit()
            .putString("id", id)
            .putString("name", activeTrainName)
            .putString("dest", activeTrainDest)
            .putString("service", trainService)
            .putLong("startMs", trainStartMs)
            .putFloat("pathMeters", trainPathMeters.toFloat())
            .putLong("savedMs", System.currentTimeMillis())
            .putString("boarding", b?.let { listOf(it.code, it.name, it.lat, it.lon).joinToString("|") })
            // A última estação onde estiveste com o comboio (a de saída) e o último ponto em cima da linha
            .putString("lastStation", trainRide.lastStation?.let { v -> listOf(v.stop.code, v.stop.name, v.stop.lat, v.stop.lon, v.arrivedMs).joinToString("|") })
            .putString("lastOnRail", trainRide.lastOnRail?.let { (lat, lon) -> listOf(lat, lon, trainRide.lastOnRailMs).joinToString("|") })
            .apply()
    }

    private fun restoreTrainState() {
        val p = getSharedPreferences(TRAIN_PREFS, Context.MODE_PRIVATE)
        val id = p.getString("id", null) ?: return
        // Ao fim de 6 h sem notícias, a viagem já acabou (e não se sabe onde)
        if (System.currentTimeMillis() - p.getLong("savedMs", 0L) > 6 * 60 * 60 * 1000L) {
            p.edit().clear().apply()
            return
        }
        activeTrainId = id
        activeTrainName = p.getString("name", null)
        activeTrainDest = p.getString("dest", null)
        trainService = p.getString("service", null)
        trainNumber = id
        trainStartMs = p.getLong("startMs", 0L)
        trainPathMeters = p.getFloat("pathMeters", 0f).toDouble()
        trainBoarding = p.getString("boarding", null)?.split("|")?.takeIf { it.size == 4 }?.let { (code, name, lat, lon) ->
            com.example.metrotime.data.cp.TrainMatcher.Stop(code, name, lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0)
        }
        val lastStation = p.getString("lastStation", null)?.split("|")?.takeIf { it.size == 5 }?.let { (code, name, lat, lon, at) ->
            val la = lat.toDoubleOrNull() ?: return@let null
            val lo = lon.toDoubleOrNull() ?: return@let null
            com.example.metrotime.data.cp.TrainRide.StationVisit(
                com.example.metrotime.data.cp.TrainMatcher.Stop(code, name, la, lo), at.toLongOrNull() ?: return@let null
            )
        }
        val onRail = p.getString("lastOnRail", null)?.split("|")?.takeIf { it.size == 3 }
        trainRide = com.example.metrotime.data.cp.TrainRide.State(
            lastStation = lastStation,
            lastOnRail = onRail?.let { (lat, lon) -> (lat.toDoubleOrNull() ?: return@let null) to (lon.toDoubleOrNull() ?: return@let null) },
            lastOnRailMs = onRail?.getOrNull(2)?.toLongOrNull() ?: 0L
        )
    }

    private suspend fun checkFriendsTrainStatus(uid: String) {
        try {
            val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
            // As amizades estão em friends/{uid} (antes lia users/{uid}/friends, que não existe,
            // e por isso estas notificações nunca disparavam)
            val friendsSnap = db.getReference("friends").child(uid).get().await()
            val friendIds = friendsSnap.children
                .filter { it.stringOrNull() == "accepted" }
                .mapNotNull { it.key }
            if (friendIds.isEmpty()) return

            for (fid in friendIds) {
                val locSnap = try {
                    db.getReference("locations").child(fid).get().await()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    continue
                }
                val currentTrain = locSnap.child("currentTrain").stringOrNull()
                val destination = locSnap.child("trainDestination").stringOrNull()
                val ts = locSnap.child("timestamp").longOrNull() ?: 0L

                if (currentTrain != null && destination != null && (System.currentTimeMillis() - ts < 10 * 60 * 1000)) {
                    val lastTrainNotified = lastNotifiedFriends[fid]
                    if (lastTrainNotified != currentTrain) { // Only notify ONCE per train journey
                        val name = runCatching { UserDirectory.getProfile(fid)?.name }.getOrNull() ?: "Um amigo"

                        // Check if local user set a custom message for this friend
                        val settingsSnap = db.getReference("users").child(uid).child("friendSettings").child(fid).get().await()
                        val customTemplate = settingsSnap.child("customMessage").stringOrNull()
                        
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
            val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
            val ref = db.getReference("adminMessages").child(uid)
            val snap = ref.get().await()
            for (child in snap.children) {
                val msg = child.stringOrNull()
                if (msg == null) {
                    child.ref.removeValue() // valor inválido: descartar em vez de o reler para sempre
                } else {
                    sendAdminNotification(msg, child.key.hashCode())
                    db.getReference("user_notifications").child(uid).push().setValue(
                        mapOf(
                            "message" to msg,
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
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
    private var cpStationsLoaded = false
    private var lastCpStationsAttemptMs = 0L

    /**
     * Estações de metro + CP usadas no histórico e na deteção de viagens. As da CP vêm da cache em
     * disco do CpRepository; se falharem, volta a tentar 10 min depois (antes ficava sem estações CP
     * até o serviço reiniciar).
     */
    private suspend fun stationsForHistory(): List<NearbyStation> {
        val now = System.currentTimeMillis()
        val current = cachedStationsForHistory
        if (current != null && (cpStationsLoaded || now - lastCpStationsAttemptMs < 10 * 60 * 1000L)) return current
        lastCpStationsAttemptMs = now

        val list = mutableListOf<NearbyStation>()
        val metro = StationsRepo.loadStations(this)
        list.addAll(metro.map { NearbyStation(it.id, it.name, "metro", it.line, it.lat, it.lon) })
        try {
            val cp = com.example.metrotime.data.cp.CpRepository.getStations(this)
            list.addAll(cp.mapNotNull { cpStation ->
                val lat = cpStation.latitude?.toDoubleOrNull()
                val lon = cpStation.longitude?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    NearbyStation(
                        cpStation.code,
                        com.example.metrotime.data.cp.formatStationName(cpStation.designation),
                        "cp",
                        cpStation.railways?.joinToString(" ") ?: "",
                        lat,
                        lon
                    )
                } else null
            })
            cpStationsLoaded = true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        cachedStationsForHistory = list
        return list
    }

    private suspend fun cpStationsForTrips(): List<NearbyStation> = stationsForHistory().filter { it.type == "cp" }

    /** Viagens de metro: cada leitura (ou a falta dela) vai para o detetor; as duvidosas pedem confirmação. */
    private fun recordTripLocation(loc: com.example.metrotime.location.LocationResult?) {
        val fix = loc?.let { com.example.metrotime.data.history.TripDetector.Fix(it.timeMs, it.latitude, it.longitude, it.accuracyM) }
        val pending = TripHistoryManager.onLocation(this@WidgetUpdateService, fix) ?: return
        sendPendingConfirmationNotification(pending.trip.fromName, pending.trip.toName ?: "")
    }

    private fun sendPendingConfirmationNotification(from: String, to: String) {
        val channelId = "metro_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alertas Metro", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, com.example.metrotime.ui.SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle("Foste de metro de $from para $to?")
            .setContentText("Toca para confirmar. Se não confirmares, não conta.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(9999, notification)
    }

    /**
     * Schedule a repeating alarm every 15 minutes as a heartbeat.
     * This ensures the service restarts even if Android kills it
     * and START_STICKY fails (common on aggressive OEM skins like MIUI/OneUI).
     */
    private fun scheduleHeartbeat() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, BootReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }
            val pi = PendingIntent.getBroadcast(
                this, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pi
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) { }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
    }
}
