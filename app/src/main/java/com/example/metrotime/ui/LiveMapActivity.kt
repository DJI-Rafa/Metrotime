package com.example.metrotime.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.sources.GeoJsonSource
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.example.metrotime.data.bus.LiveBus
import com.example.metrotime.data.bus.BusStop
import org.json.JSONArray
import org.json.JSONObject

class LiveMapActivity : ComponentActivity() {

    private val viewModel: LiveMapViewModel by viewModels()

    // MapView is created and fully owned by the Activity, NOT Compose
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var styleReady = false

    /** Desenha os metros a cada imagem (pontos de longe, carruagens de perto, sem saltos). */
    private val metroRenderer = MetroRenderer()
    private val cpRenderer = CpTrainsRenderer()
    /** Enquanto a câmara anima para um metro, o "seguir" não a interrompe. */
    private var followHoldUntil = 0L

    /** Espera pela próxima imagem do ecrã (vsync): o desenho dos metros fica ao ritmo do ecrã, sem solavancos. */
    private suspend fun awaitVsync(): Long = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val callback = android.view.Choreographer.FrameCallback { nanos ->
            if (cont.isActive) cont.resumeWith(Result.success(nanos))
        }
        android.view.Choreographer.getInstance().postFrameCallback(callback)
        cont.invokeOnCancellation { android.view.Choreographer.getInstance().removeFrameCallback(callback) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startLocationTracking()
            showMyLocation()
        }
        // O serviço só arranca depois da resposta: com o tipo "location" sem permissão o Android 14
        // lançava SecurityException e fechava a app
        com.example.metrotime.work.WidgetUpdateService.requestFetch(this, fromUi = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warmUpLocation(applicationContext)

        requestPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)

        val vehicleIdToSelect = intent.getStringExtra(EXTRA_SELECT_BUS_VEHICLE)
        if (!vehicleIdToSelect.isNullOrEmpty()) {
            val operator = intent.getStringExtra(EXTRA_SELECT_BUS_OPERATOR)
            val line = intent.getStringExtra(EXTRA_SELECT_BUS_LINE)
            // Delay selection to allow entrance transition to finish smoothly
            window.decorView.postDelayed({
                viewModel.selectBusByVehicleId(vehicleIdToSelect, operator, line)
            }, 600)
        }

        val openBusSearch = intent.getBooleanExtra("extra_open_bus_search", false)
        if (openBusSearch) {
            viewModel.openBusSearch()
        }

        // Vindo do separador Autocarros: uma linha ou uma paragem
        intent.getStringExtra(EXTRA_BUS_ROUTE)?.let { viewModel.selectBusRouteByShortName(it) }
        intent.getStringExtra(EXTRA_BUS_STOP)?.split("|")?.takeIf { it.size == 5 }?.let { (operator, id, name, lat, lon) ->
            val stop = BusStop(id, name, lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0, operator)
            viewModel.loadAllBusStops()
            viewModel.selectBusStop(stop)
            viewModel.requestCamera(stop.lat, stop.lon)
        }

        // Vindo do detalhe de um comboio CP ("Ver no mapa")
        val cpTrainToFocus = intent.getIntExtra(EXTRA_FOCUS_CP_TRAIN, -1)
        if (cpTrainToFocus > 0) viewModel.focusCpTrain(cpTrainToFocus)

        // 1. Init MapLibre (must be first)
        MapLibre.getInstance(this)

        // 2. Create and boot the MapView with the saved state — CRITICAL
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)

        // 3. Load the map async
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                attributionGravity = Gravity.BOTTOM or Gravity.END
            }
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(38.737, -9.137))
                .zoom(11.5)
                .build()

            // Use CARTO dark matter — free, reliable dark theme
            map.setStyle(Style.Builder().fromUri(
                "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
            )) { style ->
                styleReady = true
                try {
                    setupMapLayers(this@LiveMapActivity, style)

                    // Race condition fix: draw everything immediately when style is loaded
                    metroRenderer.cutActive = applyMetroDisruptions(map, viewModel.metroStatuses.value, viewModel.metroTracks)
                    updateFriendMarkers(this@LiveMapActivity, map, viewModel.friendLocations.value)
                    val routeColor = viewModel.selectedBusRoute.value?.color
                    updateBusMarkers(map, viewModel.liveBuses.value, this@LiveMapActivity, routeColor, viewModel.selectedLiveBus.value?.bus?.vehicleId)
                    updateBusStops(map, viewModel.busRouteStops.value, routeColor)
                    updateBusRouteLine(map, viewModel.busRouteShape.value, routeColor)
                    updateAllBusStops(map, viewModel.allBusStops.value)
                    updateCpTrains(this@LiveMapActivity, map, viewModel.cpTrains.value, viewModel.selectedCpNumber.value)
                    // (a Fertagus entra no primeiro desenho do CpTrainsRenderer, logo a seguir)
                    val regional = viewModel.selectedRegional.value
                    updateRegionalVehicles(
                        map, this@LiveMapActivity,
                        if (viewModel.isBusMode.value) emptyList() else viewModel.regionalVehicles.value,
                        regional?.vehicle?.key
                    )
                    updateRegionalRoute(map, regional?.trip, regional?.vehicle?.color)
                    applyMapFilters(map, viewModel.mapFilters.value)
                    // A tua localização: depois das outras camadas, para o ponto azul ficar por cima de tudo
                    showMyLocation()
                    // Rede ferroviária (traçados reais) carregada fora da thread principal
                    lifecycleScope.launch {
                        val network = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { buildCpNetwork(this@LiveMapActivity) }.getOrNull()
                        }
                        if (network != null && styleReady) applyCpNetwork(map, network)
                        // (o texto da rede já vem feito: o mapa só o lê)
                        // Metro do Porto: linhas e estações como as de Lisboa (a rede vem do pacote MDP)
                        val porto = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching {
                                viewModel.metroTracks
                                com.example.metrotime.data.metro.PortoMetro.network?.let { buildPortoMetroFeatures(it) }
                            }.getOrNull()
                        }
                        if (porto != null && styleReady) applyPortoMetro(map, porto)
                    }

                    viewModel.selectedLiveBus.value?.bus?.let { bus ->
                        map.animateCamera(
                            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                                LatLng(bus.lat, bus.lon), 15.0
                            ), 800
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                map.addOnCameraIdleListener {
                    val c = map.cameraPosition
                    c.target?.let { viewModel.setMapCamera(it.latitude, it.longitude, c.zoom) }
                    if (styleReady) refreshRegionalVehicles(map, this@LiveMapActivity)
                }
                // Mexer no mapa com os dedos deixa de seguir o metro (o chip "Seguir o metro" volta a seguir)
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && viewModel.followMetro.value != null) {
                        viewModel.setFollowPaused(true)
                    }
                }
                map.addOnMapClickListener { point ->
                    val screen = map.projection.toScreenLocation(point)
                    // Comboios CP primeiro (ficam por cima de tudo); a área de toque é a etiqueta
                    val touch = android.graphics.RectF(screen.x - 24f, screen.y - 24f, screen.x + 24f, screen.y + 24f)
                    val cpHits = map.queryRenderedFeatures(touch, "cp-trains", "cp-train-dots")
                    if (cpHits.isNotEmpty()) {
                        // A Fertagus vem com a chave do veículo (abre o painel das outras redes, com o horário)
                        val key = cpHits[0].takeIf { it.hasProperty("key") }?.getStringProperty("key")
                        if (key != null) viewModel.selectRegional(key)
                        else cpHits[0].getStringProperty("number")?.toIntOrNull()?.let { viewModel.selectCpTrain(it) }
                        return@addOnMapClickListener true
                    }
                    // Outras redes: a pílula (de perto) ou o ponto (de longe), com a área de toque à volta do dedo
                    val regionalReach = 18f * resources.displayMetrics.density
                    val regionalTouch = android.graphics.RectF(screen.x - regionalReach, screen.y - regionalReach, screen.x + regionalReach, screen.y + regionalReach)
                    val regionalKey = map.queryRenderedFeatures(regionalTouch, REGIONAL_VEHICLES_LAYER, REGIONAL_DOTS_LAYER)
                        .firstNotNullOfOrNull { it.getStringProperty("key") }
                    if (regionalKey != null) {
                        viewModel.selectRegional(regionalKey)
                        return@addOnMapClickListener true
                    }
                    // Metros: as carruagens (de perto) ou o ponto (de longe); a área de toque é à volta do dedo
                    val metroReach = 20f * resources.displayMetrics.density
                    val metroTouch = android.graphics.RectF(screen.x - metroReach, screen.y - metroReach, screen.x + metroReach, screen.y + metroReach)
                    val metroKey = map.queryRenderedFeatures(metroTouch, "metro-cars-layer", "metro-dots-layer")
                        .mapNotNull { it.getStringProperty("key") }
                        .firstOrNull()?.removePrefix("ghost|")
                    if (metroKey != null && viewModel.onboard.value == null) {
                        viewModel.selectMetro(metroKey)
                        return@addOnMapClickListener true
                    }

                    val friendHits = map.queryRenderedFeatures(screen, "friends-layer")
                    if (friendHits.isNotEmpty()) {
                        val friendName = friendHits[0].getStringProperty("name") ?: "Amigo"
                        val friendTime = friendHits[0].getNumberProperty("timestamp")?.toLong() ?: 0L
                        if (friendTime > 0L) {
                            val diffMinutes = (System.currentTimeMillis() - friendTime) / 60000
                            val msg = if (diffMinutes <= 0) "$friendName atualizado agora mesmo" else "$friendName atualizado há $diffMinutes min"
                            android.widget.Toast.makeText(this@LiveMapActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        return@addOnMapClickListener true
                    }
                    
                    // Tap on bus vehicle marker
                    val busHits = map.queryRenderedFeatures(screen, "bus-vehicles-layer")
                    if (busHits.isNotEmpty()) {
                        val vehicleId = busHits[0].getStringProperty("vehicleId") ?: ""
                        if (vehicleId.isNotEmpty()) {
                            viewModel.selectLiveBus(vehicleId)
                            // Animate camera to center on the bus
                            val busLat = busHits[0].geometry()?.let {
                                (it as? org.maplibre.geojson.Point)?.latitude()
                            }
                            val busLon = busHits[0].geometry()?.let {
                                (it as? org.maplibre.geojson.Point)?.longitude()
                            }
                            if (busLat != null && busLon != null) {
                                map.animateCamera(
                                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                                        LatLng(busLat, busLon), 15.0
                                    ), 800
                                )
                            }
                        }
                        return@addOnMapClickListener true
                    }

                    // Estação do metro primeiro, só se o toque for mesmo no símbolo (as paragens à volta
                    // têm uma área de toque maior e ficavam com o toque)
                    val stationHits = map.queryRenderedFeatures(screen, "stations-layer", PORTO_STATIONS_LAYER)
                    if (stationHits.isNotEmpty()) {
                        val stationId = stationHits[0].getStringProperty("id") ?: return@addOnMapClickListener false
                        val stationName = stationHits[0].getStringProperty("name") ?: stationId
                        viewModel.selectStation(stationId, stationName)
                        return@addOnMapClickListener true
                    }

                    // Paragens: o sinal "P" (de perto), os pontos e as paragens da linha aberta. A área
                    // de toque é à volta do dedo; antes só contava a camada dos pontos, que desaparece
                    // com zoom 16, e era preciso acertar num ponto de 3 a 5 px
                    val reach = 18f * resources.displayMetrics.density
                    val stopTouch = android.graphics.RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
                    val busStopHit = map.queryRenderedFeatures(stopTouch, "bus-stops-layer", "all-stops-p", "all-stops-layer")
                        .filter { !it.hasProperty("point_count") && it.hasProperty("id") }
                        .minByOrNull { f ->
                            val g = f.geometry() as? org.maplibre.geojson.Point ?: return@minByOrNull Float.MAX_VALUE
                            val at = map.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
                            (at.x - screen.x) * (at.x - screen.x) + (at.y - screen.y) * (at.y - screen.y)
                        }
                    if (busStopHit != null) {
                        val stopId = busStopHit.getStringProperty("id")
                        val g = busStopHit.geometry() as? org.maplibre.geojson.Point
                        val stopOperator = busStopHit.getStringProperty("operator")?.takeIf { it.isNotEmpty() }
                            ?: viewModel.selectedBusRoute.value?.operator ?: "carris"
                        viewModel.clearSelectedLiveBus()
                        viewModel.selectBusStop(com.example.metrotime.data.bus.BusStop(
                            id = stopId,
                            name = busStopHit.getStringProperty("name") ?: stopId,
                            lat = busStopHit.getNumberProperty("lat")?.toDouble() ?: g?.latitude() ?: 0.0,
                            lon = busStopHit.getNumberProperty("lon")?.toDouble() ?: g?.longitude() ?: 0.0,
                            operator = stopOperator
                        ))
                        return@addOnMapClickListener true
                    }

                    
                    if (viewModel.onboard.value == null) viewModel.selectMetro(null)
                    viewModel.selectStation(null)
                    viewModel.clearSelectedBusStop()
                    viewModel.selectCpTrain(null)
                    viewModel.selectRegional(null)
                    // Um toque no mapa fecha também o autocarro aberto (os painéis já não fecham por se puxar)
                    viewModel.clearSelectedLiveBus()
                    false
                }
            }
        }

        // Os metros a cada imagem do ecrã (Choreographer), só com o mapa à frente: 60 por segundo enquanto se
        // anima (seletor de tempo, seguir um metro), 30 de perto e 20 de longe quando só eles mexem
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var lastNanos = 0L
                var lastCpNanos = 0L
                var locationAt = 0L
                var skipped = 0
                var detailed = false
                var animating = false
                var lastOffset = viewModel.metroOffsetMs.value
                while (true) {
                    val frameNanos = awaitVsync()
                    val offset = viewModel.metroOffsetMs.value
                    val followKey = viewModel.followMetro.value
                    val busy = animating || offset != lastOffset || followKey != null
                    val every = when {
                        busy -> 1
                        detailed -> 2
                        else -> 3
                    }
                    if (++skipped < every) continue
                    skipped = 0
                    lastOffset = offset
                    val dt = if (lastNanos == 0L) 1 / 60.0 else ((frameNanos - lastNanos) / 1e9).coerceIn(0.0, 0.5)
                    lastNanos = frameNanos
                    val map = mapLibreMap
                    if (map == null || !styleReady) continue
                    try {
                        // Os comboios CP, cerca de 12 vezes por segundo (andam devagar no ecrã)
                        val cpEvery = if (map.cameraPosition.zoom < CP_DOT_MAX_ZOOM) 500_000_000L else 80_000_000L
                        if (frameNanos - lastCpNanos >= cpEvery) {
                            val cpDt = if (lastCpNanos == 0L) 0.08 else ((frameNanos - lastCpNanos) / 1e9).coerceIn(0.0, 0.5)
                            lastCpNanos = frameNanos
                            val openKey = viewModel.selectedRegional.value?.vehicle?.key
                            cpRenderer.render(
                                this@LiveMapActivity, map, viewModel.cpTrains.value, viewModel.cpLive.value,
                                viewModel.selectedCpNumber.value, System.currentTimeMillis(), cpDt,
                                extra = viewModel.fertagusTrains.value.map { v ->
                                    ExtraTrain(v.key, "FT", v.lat, v.lon, v.delaySeconds, v.key == openKey)
                                }
                            )
                        }
                        val mf = viewModel.mapFilters.value
                        val frame = metroRenderer.render(
                            map, viewModel.metroTrains.value, viewModel.metroTracks, System.currentTimeMillis(),
                            offset, viewModel.selectedMetro.value, viewModel.onboard.value?.key,
                            followKey, dt
                        ) { metroLineShown(mf, it) }
                        detailed = frame.detailed
                        animating = frame.animating
                        // A câmara vai atrás do metro seguido, sem saltos
                        val nowEl = SystemClock.elapsedRealtime()
                        val target = frame.follow
                        val center = map.cameraPosition.target
                        if (followKey != null && target != null && center != null && !viewModel.followPaused.value && nowEl > followHoldUntil) {
                            val k = (dt * 4.0).coerceIn(0.0, 1.0)
                            map.moveCamera(
                                org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                                    LatLng(center.latitude + (target.first - center.latitude) * k, center.longitude + (target.second - center.longitude) * k)
                                )
                            )
                        }
                        // A tua localização (o ponto azul), para a estação mais perto no cartão da noite
                        if (nowEl - locationAt > 5_000L) {
                            locationAt = nowEl
                            runCatching {
                                map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
                            }.getOrNull()?.let { viewModel.setMyLocation(it.latitude, it.longitude) }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // 4. Observe trains, friends, and buses — update map markers
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Perturbações: o troço interrompido a cinzento
                    viewModel.metroStatuses.collect { statuses ->
                        if (styleReady) mapLibreMap?.let { metroRenderer.cutActive = applyMetroDisruptions(it, statuses, viewModel.metroTracks) }
                    }
                }
                launch {
                    // A bordo: o mapa aproxima-se e inclina-se para trás do teu metro; ao terminar volta a direito
                    var wasOnboard = false
                    viewModel.onboard.collect { board ->
                        val map = mapLibreMap
                        if (map != null && styleReady) {
                            if (board != null && !wasOnboard) {
                                val train = viewModel.metroTrain(board.key)
                                val track = viewModel.metroTracks[board.line]
                                val point = if (train != null && track != null) train.placeAt(System.currentTimeMillis(), track)?.let { track.point(it.s) } else null
                                val camera = CameraPosition.Builder(map.cameraPosition).zoom(15.6).tilt(45.0)
                                point?.let { camera.target(LatLng(it.first, it.second)) }
                                followHoldUntil = SystemClock.elapsedRealtime() + 1_400L
                                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(camera.build()), 1_200)
                            } else if (board == null && wasOnboard) {
                                map.animateCamera(
                                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.Builder(map.cameraPosition).tilt(0.0).zoom(14.0).build()
                                    ), 900
                                )
                            }
                        }
                        wasOnboard = board != null
                    }
                }
                launch {
                    // "Seguir" no painel do metro: a câmara vai até ele (de perto) e depois acompanha-o
                    viewModel.followMetro.collect { key ->
                        val map = mapLibreMap ?: return@collect
                        if (key == null || !styleReady || viewModel.onboard.value?.key == key) return@collect
                        val train = viewModel.metroTrain(key) ?: return@collect
                        val track = viewModel.metroTracks[train.line] ?: return@collect
                        val p = train.placeAt(System.currentTimeMillis() + viewModel.metroOffsetMs.value, track) ?: return@collect
                        val at = track.point(p.s)
                        followHoldUntil = SystemClock.elapsedRealtime() + 1_000L
                        map.animateCamera(
                            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(at.first, at.second), maxOf(map.cameraPosition.zoom, 15.0)),
                            850
                        )
                    }
                }
                launch {
                    viewModel.friendLocations.collect { friends ->
                        if (styleReady) {
                            mapLibreMap?.let { updateFriendMarkers(this@LiveMapActivity, it, friends) }
                        }
                    }
                }
                launch {
                    viewModel.liveBuses.collect { buses ->
                        if (styleReady) {
                            mapLibreMap?.let { updateBusMarkers(it, buses, this@LiveMapActivity, viewModel.selectedBusRoute.value?.color, viewModel.selectedLiveBus.value?.bus?.vehicleId) }
                        }
                    }
                }
                launch {
                    viewModel.busRouteStops.collect { stops ->
                        if (styleReady) {
                            mapLibreMap?.let { updateBusStops(it, stops, viewModel.selectedBusRoute.value?.color) }
                        }
                    }
                }
                launch {
                    viewModel.busRouteShape.collect { shape ->
                        if (styleReady) {
                            mapLibreMap?.let { updateBusRouteLine(it, shape, viewModel.selectedBusRoute.value?.color) }
                        }
                    }
                }
                launch {
                    // As paragens chegam aos bocados ao abrir (Carris, outras redes, CM, Norte e Centro): um só desenho
                    @OptIn(kotlinx.coroutines.FlowPreview::class)
                    viewModel.allBusStops.debounce(400).collect { allStops ->
                        if (styleReady) {
                            mapLibreMap?.let { updateAllBusStops(it, allStops) }
                        }
                    }
                }
                // Comboios CP: etiquetas, percurso do comboio aberto, filtros e pedidos de câmara
                // Os comboios CP são desenhados a cada imagem (CpTrainsRenderer, no ciclo do ecrã), todos com a mesma
                // conta do painel: o comboio aberto já não salta para outro sítio ao tocar nele
                launch {
                    kotlinx.coroutines.flow.combine(viewModel.cpTrip, viewModel.selectedCpTrain) { s, t -> s to t }
                        .collect { (state, train) ->
                            val route = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { buildCpRoute(this@LiveMapActivity, state, train) }.getOrNull()
                            } ?: return@collect
                            if (styleReady) mapLibreMap?.let { applyCpRoute(it, route) }
                        }
                }
                launch {
                    viewModel.mapFilters.collect { f ->
                        if (styleReady) mapLibreMap?.let { applyMapFilters(it, f) }
                    }
                }
                launch {
                    // Outras redes: os veículos (escondidos com uma linha de autocarro aberta) e o traçado do aberto
                    kotlinx.coroutines.flow.combine(
                        viewModel.regionalVehicles, viewModel.selectedRegional, viewModel.isBusMode, viewModel.selectedLiveBus
                    ) { vehicles, selected, busMode, liveBus ->
                        // Um autocarro da Carris/CM aberto a partir do ponto fica destacado nele
                        val cityKey = liveBus?.bus?.let { b ->
                            val op = if (b.operator == "carris_metropolitana") com.example.metrotime.data.regional.RegionalOperators.CM
                            else com.example.metrotime.data.regional.RegionalOperators.CARRIS
                            "${op.key}|${b.vehicleId}"
                        }
                        Triple(vehicles, selected?.let { it to it.vehicle.key } ?: (null to cityKey), busMode)
                    }
                        .collect { (vehicles, sel, busMode) ->
                            val selected = sel.first
                            val selectedKey = sel.second
                            val map = mapLibreMap ?: return@collect
                            if (!styleReady) return@collect
                            // O aberto aparece sempre, mesmo que os filtros o escondam (aberto a partir de uma paragem)
                            val open = selected?.vehicle?.takeIf { s ->
                                vehicles.none { it.key == s.key } && viewModel.fertagusTrains.value.none { it.key == s.key }
                            }
                            updateRegionalVehicles(map, this@LiveMapActivity, if (busMode) emptyList() else vehicles + listOfNotNull(open), selectedKey)
                            updateRegionalRoute(map, selected?.trip, selected?.vehicle?.color)
                        }
                }
                launch {
                    // Ao abrir um veículo de outra rede, o mapa vai até ele (centrado acima do painel)
                    var lastKey: String? = null
                    viewModel.selectedRegional.collect { state ->
                        val key = state?.vehicle?.key
                        if (key != null && key != lastKey && styleReady) {
                            mapLibreMap?.let { map ->
                                val sheet = resources.displayMetrics.heightPixels * 0.45
                                map.animateCamera(
                                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.Builder()
                                            .target(LatLng(state.vehicle.lat, state.vehicle.lon))
                                            .zoom(maxOf(map.cameraPosition.zoom, 14.5))
                                            .padding(0.0, 0.0, 0.0, sheet)
                                            .build()
                                    ), 900
                                )
                            }
                        } else if (key == null && lastKey != null && styleReady) {
                            mapLibreMap?.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0), 300)
                        }
                        lastKey = key
                    }
                }
                launch {
                    viewModel.cameraRequests.collect { (lat, lon, zoom) ->
                        // Pedido feito ao abrir o ecrã: espera que o mapa esteja pronto
                        while (mapLibreMap == null || !styleReady) kotlinx.coroutines.delay(100)
                        mapLibreMap?.animateCamera(
                            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 800
                        )
                    }
                }
                launch {
                    var lastSelectedVehicleId: String? = null
                    viewModel.selectedLiveBus.collect { state ->
                        val bus = state?.bus
                        // O autocarro escolhido fica maior e por cima dos outros
                        if (styleReady) {
                            mapLibreMap?.let { updateBusMarkers(it, viewModel.liveBuses.value, this@LiveMapActivity, viewModel.selectedBusRoute.value?.color, bus?.vehicleId) }
                        }
                        if (bus != null && bus.vehicleId != lastSelectedVehicleId) {
                            lastSelectedVehicleId = bus.vehicleId
                            // Leva o mapa até ao autocarro (sem ter de o procurar), centrado na parte
                            // do mapa que fica à vista por cima do painel do autocarro
                            if (styleReady) {
                                mapLibreMap?.let { map ->
                                    val sheet = resources.displayMetrics.heightPixels * 0.5
                                    map.animateCamera(
                                        org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.Builder()
                                                .target(LatLng(bus.lat, bus.lon))
                                                .zoom(16.0)
                                                .padding(0.0, 0.0, 0.0, sheet)
                                                .build()
                                        ), 900
                                    )
                                }
                            }
                        } else if (state == null) {
                            // Sem painel: o mapa volta a centrar no meio do ecrã
                            if (lastSelectedVehicleId != null && styleReady) {
                                mapLibreMap?.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0), 300)
                            }
                            lastSelectedVehicleId = null
                        }
                    }
                }
            }
        }

        // 5. Set Compose content — Compose just provides the overlay UI
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var isMaintenanceMode by remember { mutableStateOf(false) }
                val isAdmin = remember { com.example.metrotime.prefs.Prefs.isAdmin(this@LiveMapActivity) }

                DisposableEffect(Unit) {
                    val ref = com.google.firebase.database.FirebaseDatabase.getInstance(com.example.metrotime.data.FIREBASE_DB_URL)
                        .getReference("maintenance_mode")
                    val listener = object : com.google.firebase.database.ValueEventListener {
                        override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                            isMaintenanceMode = (snapshot.value as? Boolean) ?: false
                        }
                        override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                    }
                    ref.addValueEventListener(listener)
                    onDispose { ref.removeEventListener(listener) }
                }

                if (isMaintenanceMode && !isAdmin) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(72.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("App em manutenção", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Voltaremos assim que possível. A nossa equipa está a trabalhar para melhorar a tua experiência.", color = Color.Gray, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { finish() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))) {
                                Text("Voltar", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {

                        // The MapView is inserted as a native Android View
                        AndroidView(
                            factory = { mapView },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Compose overlay: top bar + loading + bottom sheet
                        LiveMapOverlay(
                            viewModel = viewModel,
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onStart()    { super.onStart();    mapView.onStart(); viewModel.setScreenVisible(true) }
    override fun onResume()   { super.onResume();   mapView.onResume(); viewModel.startLocationTracking(); showMyLocation() }

    /**
     * A tua localização no mapa, como no Google Maps: ponto azul com aro branco, o círculo azul claro da precisão
     * e, para onde estás virado (pela bússola), um feixe azul. Fica cinzento quando a localização deixa de chegar.
     * Precisa da permissão e do mapa carregado; chamado quando cada uma chega e ao voltar ao mapa (a permissão
     * pode ter sido dada nas definições entretanto). O MapLibre só pede a localização com o mapa visível.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun showMyLocation() {
        val map = mapLibreMap ?: return
        val style = map.style?.takeIf { styleReady && it.isFullyLoaded } ?: return
        val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return
        try {
            val component = map.locationComponent
            if (!component.isLocationComponentActivated) {
                val blue = 0xFF1A73E8.toInt()
                val options = org.maplibre.android.location.LocationComponentOptions.builder(this)
                    .foregroundTintColor(blue)
                    .backgroundTintColor(AndroidColor.WHITE)
                    .foregroundStaleTintColor(0xFF9AA0A6.toInt())
                    .backgroundStaleTintColor(AndroidColor.WHITE)
                    .accuracyColor(blue)
                    .accuracyAlpha(0.15f)
                    .bearingDrawable(com.example.metrotime.R.drawable.map_location_beam)
                    .elevation(4f)
                    .pulseEnabled(false)
                    .build()
                component.activateLocationComponent(
                    org.maplibre.android.location.LocationComponentActivationOptions.Builder(this, style)
                        .locationComponentOptions(options)
                        .useDefaultLocationEngine(true)
                        // Camada nativa do ponto azul: sem reescrever uma fonte GeoJSON a cada imagem
                        .useSpecializedLocationLayer(true)
                        .build()
                )
            }
            component.isLocationComponentEnabled = true
            // O mapa não te segue (ficas a ver o que escolheste); o feixe mostra para onde estás virado
            component.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
            component.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    override fun onPause()    { super.onPause();    mapView.onPause(); viewModel.stopLocationTracking() }
    override fun onStop()     { super.onStop();     mapView.onStop(); viewModel.setScreenVisible(false) }

    companion object {
        const val EXTRA_FOCUS_CP_TRAIN = "extra_focus_cp_train"
        /** Número de uma linha de autocarro a abrir no mapa. */
        const val EXTRA_BUS_ROUTE = "extra_bus_route"
        /** Paragem a abrir no mapa: "operador|id|nome|lat|lon". */
        const val EXTRA_BUS_STOP = "extra_bus_stop"
        /** Autocarro a mostrar no mapa (id do veículo), com o operador e a linha para o encontrar. */
        const val EXTRA_SELECT_BUS_VEHICLE = "extra_select_bus_vehicle_id"
        const val EXTRA_SELECT_BUS_OPERATOR = "extra_select_bus_operator"
        const val EXTRA_SELECT_BUS_LINE = "extra_select_bus_line"

        /** Abre o mapa centrado num autocarro com GPS. */
        fun busIntent(context: android.content.Context, vehicleId: String, operator: String, line: String): android.content.Intent =
            android.content.Intent(context, LiveMapActivity::class.java)
                .putExtra(EXTRA_SELECT_BUS_VEHICLE, vehicleId)
                .putExtra(EXTRA_SELECT_BUS_OPERATOR, operator)
                .putExtra(EXTRA_SELECT_BUS_LINE, line)
    }
    override fun onLowMemory(){ super.onLowMemory();mapView.onLowMemory() }
    override fun onDestroy()  { super.onDestroy();  mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Compose Overlay (pure UI, no MapLibre dependencies)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun LiveMapOverlay(viewModel: LiveMapViewModel, onBack: () -> Unit) {
    // Metro: os metros, o escolhido, a bordo, o seletor de tempo, perturbações e seguir
    val metroTrains by viewModel.metroTrains.collectAsState()
    val selectedMetro by viewModel.selectedMetro.collectAsState()
    val onboard by viewModel.onboard.collectAsState()
    val metroStatuses by viewModel.metroStatuses.collectAsState()
    val followKey by viewModel.followMetro.collectAsState()
    val followPaused by viewModel.followPaused.collectAsState()
    val myLocation by viewModel.myLocation.collectAsState()
    // Relógio dos painéis (contagens que descem ao segundo)
    val metroNow by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val selectedStation by viewModel.selectedStation.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()

    // Bus state
    val isBusSearchOpen by viewModel.isBusSearchOpen.collectAsState()
    val busSearchQuery by viewModel.busSearchQuery.collectAsState()
    val busSearchResults by viewModel.busSearchResults.collectAsState()
    val selectedBusRoute by viewModel.selectedBusRoute.collectAsState()
    val liveBuses by viewModel.liveBuses.collectAsState()
    val busRouteStops by viewModel.busRouteStops.collectAsState()
    val isBusMode by viewModel.isBusMode.collectAsState()
    val isBusLoading by viewModel.isBusLoading.collectAsState()
    val selectedDirection by viewModel.selectedDirection.collectAsState()
    val selectedBusStop by viewModel.selectedBusStop.collectAsState()
    val selectedLiveBus by viewModel.selectedLiveBus.collectAsState()

    // Comboios CP
    val mapFilters by viewModel.mapFilters.collectAsState()
    val cpTrains by viewModel.cpTrains.collectAsState()
    val cpServicesPresent by viewModel.cpServicesPresent.collectAsState()
    val cpUpdatedAt by viewModel.cpUpdatedAt.collectAsState()
    val selectedCpNumber by viewModel.selectedCpNumber.collectAsState()
    val selectedCpTrain by viewModel.selectedCpTrain.collectAsState()
    val cpTrip by viewModel.cpTrip.collectAsState()
    // Outras redes (MobiCascais, barcos, Covilhã, Viseu…) e o painel dos filtros
    val regionalVehicles by viewModel.regionalVehicles.collectAsState()
    val regionalAll by viewModel.regionalAll.collectAsState()
    val fertagusTrains by viewModel.fertagusTrains.collectAsState()
    val selectedRegional by viewModel.selectedRegional.collectAsState()
    var filtersOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var followRequest by remember { mutableStateOf<Pair<String, List<com.example.metrotime.data.cp.CpTrainStop>>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Top Bar ────────────────────────────────────────────────────────────
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkBackground.copy(alpha = 0.92f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (isBusMode) viewModel.clearBusSearch()
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                    val busRoute = selectedBusRoute
                    if (isBusMode && busRoute != null) {
                        BusLineBadge(busRoute.shortName, busRoute.color, height = 30.dp, fontSize = 16.sp)
                    } else {
                        Icon(imageVector = MapIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isBusMode) busRoute?.longName?.replace(" - ", " ↔ ") ?: "Autocarros" else "Mapa em direto",
                            color = Color.White,
                            fontSize = if (isBusMode) 15.sp else 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isBusMode) {
                            Text(
                                if (liveBuses.size == 1) "1 autocarro neste sentido" else "${liveBuses.size} autocarros neste sentido",
                                color = TextSecondaryDark,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else if (mapFilters.cp && cpUpdatedAt > 0) {
                            val time = java.time.Instant.ofEpochMilli(cpUpdatedAt).atZone(com.example.metrotime.data.cp.CpTime.LISBON)
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                            Text(
                                "${cpTrains.size} comboios CP · atualizado às $time",
                                color = TextSecondaryDark,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // Tudo o que se vê no mapa escolhe-se no painel dos filtros (antes: uma fila de botões por baixo)
                    if (!isBusSearchOpen && !isBusMode) {
                        FiltersButton(regionalFilterCount(mapFilters)) { filtersOpen = true }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { viewModel.toggleBusSearch() }) {
                        Icon(
                            imageVector = if (isBusSearchOpen) androidx.compose.material.icons.Icons.Filled.Close else androidx.compose.material.icons.Icons.Filled.Search,
                            contentDescription = if (isBusSearchOpen) "Fechar pesquisa" else "Pesquisar autocarro",
                            tint = Color.White
                        )
                    }
                }
            }

            if (!isBusSearchOpen && !isBusMode) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Perturbações: o aviso por cima, o troço a cinzento no mapa
                    if (mapFilters.metro) MetroDisruptionBanner(metroStatuses)
                    // À noite: os últimos metros na estação mais perto
                    val minute = metroServiceMinute(metroNow)
                    val nearest = myLocation?.let { nearestMetroStation(it) }
                    AnimatedVisibility(
                        visible = mapFilters.metro && nearest != null && com.example.metrotime.data.metro.MetroNight.showCard(minute) && onboard == null,
                        enter = slideInVertically { -it / 2 } + fadeIn(),
                        exit = slideOutVertically { -it / 2 } + fadeOut()
                    ) {
                        if (nearest != null) {
                            MetroNightCard(
                                com.example.metrotime.data.MetroLines.nameOf(nearest),
                                com.example.metrotime.data.metro.MetroNight.lastPasses(nearest, minute)
                            )
                        }
                    }
                    // A seguir um metro: o chip diz-o; depois de mexeres no mapa, toca para voltar a seguir
                    AnimatedVisibility(visible = followKey != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MetroFollowChip(paused = followPaused, onResume = { viewModel.followMetro(followKey) })
                        }
                    }
                }
            }

            // ── Bus Search Bar (animated) ──────────────────────────────────────
            AnimatedVisibility(visible = isBusSearchOpen) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1C1C20).copy(alpha = 0.95f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = busSearchQuery,
                            onValueChange = { viewModel.searchBuses(it) },
                            modifier = Modifier
                                .fillMaxWidth(),
                            leadingIcon = {
                                Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null, tint = TextSecondaryDark)
                            },
                            placeholder = { Text("Linha ou destino…", color = TextSecondaryDark) },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MetrotimeBlue,
                                unfocusedBorderColor = Color(0xFF3A3A3A),
                                focusedContainerColor = CardBackground,
                                unfocusedContainerColor = CardBackground,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White
                            ),
                            singleLine = true,
                        )

                        // Search results
                        if (busSearchResults.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                busSearchResults.take(20).forEach { route ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectBusRoute(route) },
                                        color = CardBackground,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BusLineBadge(route.shortName, route.color)
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    route.longName.replace(" - ", " ↔ "),
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    if (route.operator == "carris_metropolitana") "Carris Metropolitana"
                                                    else "Carris",
                                                    color = TextSecondaryDark,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Loading spinner ────────────────────────────────────────────────────
        if (isLoading || isBusLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // ── Bus Route Info Bottom Sheet ─────────────────────────────────────────
        val safeBusRoute = selectedBusRoute

        // Show LiveBusDetailSheet if a specific bus is selected
        AnimatedVisibility(
            visible = selectedLiveBus != null && !isBusLoading,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (selectedLiveBus != null) {
                LiveBusDetailSheet(
                    state = selectedLiveBus!!,
                    routeColor = safeBusRoute?.color,
                    onDismiss = { viewModel.clearSelectedLiveBus() }
                )
            }
        }

        // Show BusRouteInfoSheet when no individual bus or stop is selected
        AnimatedVisibility(
            visible = safeBusRoute != null && selectedLiveBus == null && selectedBusStop == null && !isBusLoading,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (safeBusRoute != null) {
                BusRouteInfoSheet(
                    route = safeBusRoute,
                    buses = liveBuses,
                    stops = busRouteStops,
                    selectedDirection = selectedDirection,
                    onDirectionSelected = { viewModel.setDirection(it) },
                    onBusSelected = { vehicleId -> viewModel.selectLiveBus(vehicleId) },
                    onDismiss = { viewModel.clearBusSearch() }
                )
            }
        }

        // ── Bus Stop Detail Bottom Sheet ────────────────────────────────────────
        AnimatedVisibility(
            visible = selectedBusStop != null && selectedLiveBus == null && !isBusLoading,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (selectedBusStop != null) {
                BusStopDetailSheet(
                    state = selectedBusStop!!,
                    onArrivalSelected = { arrival ->
                        viewModel.clearSelectedBusStop()
                        val vehicleId = arrival.vehicleId
                        if (arrival.operator.startsWith("pack:") || arrival.operator.startsWith("eleven:")) {
                            // Outras redes: o veículo em direto abre o painel delas; sem GPS, só a hora do horário
                            if (arrival.realtime && vehicleId != null) viewModel.selectRegional(vehicleId)
                            else android.widget.Toast.makeText(
                                context, "Este ainda não tem GPS: parte às ${arrival.scheduledTime ?: "?"}.", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else if (arrival.realtime && vehicleId != null) {
                            viewModel.selectBusByVehicleId(vehicleId, arrival.operator, arrival.routeShortName)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "Este autocarro ainda não tem GPS: a mostrar a linha ${arrival.routeShortName}.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            viewModel.selectBusRouteByShortName(arrival.routeShortName)
                        }
                    },
                    onDismiss = { viewModel.clearSelectedBusStop() }
                )
            }
        }

        // ── Veículo de outra rede (MobiCascais, barcos, Covilhã, Viseu…) ──────────
        AnimatedVisibility(
            visible = selectedRegional != null && !isBusMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // O último aberto fica durante a animação de saída
            val shown = remember { mutableStateOf<LiveMapViewModel.RegionalSelection?>(null) }
            selectedRegional?.let { shown.value = it }
            shown.value?.let { state ->
                // A Fertagus abre no painel dos comboios da CP (a azul, sem "Detalhes" e "Seguir", que são da CP)
                val fertagus = state.vehicle.operator.kind == com.example.metrotime.data.regional.VehicleKind.TRAIN
                val asCp = if (fertagus) remember(state.vehicle) { fertagusCpState(state.vehicle) } else null
                if (asCp != null) {
                    CpTrainSheet(
                        number = asCp.first.number,
                        train = asCp.second,
                        state = asCp.first,
                        onClose = { viewModel.selectRegional(null) },
                        onDetails = {},
                        onFollow = { _, _ -> },
                        accent = FERTAGUS_BLUE,
                        actions = false,
                        footnote = "Dados: TML · GO Hub (CC BY 4.0) · horas pelo horário da Fertagus com o atraso"
                    )
                } else {
                    RegionalVehicleSheet(state = state, now = metroNow, onClose = { viewModel.selectRegional(null) })
                }
            }
        }

        // ── Metro escolhido / a bordo / seletor de tempo ──────────────────────
        val safeStation = selectedStation
        val metroTrain = selectedMetro?.let { k -> metroTrains.firstOrNull { it.key == k } }
        val sheetSpring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
            dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )

        AnimatedVisibility(
            visible = metroTrain != null && !isBusMode && onboard == null,
            enter = slideInVertically(animationSpec = sheetSpring, initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // O último metro aberto fica durante a animação de saída
            val shown = remember { mutableStateOf<com.example.metrotime.data.metro.MetroTrain?>(null) }
            if (metroTrain != null) shown.value = metroTrain
            shown.value?.let { tr ->
                MetroTrainSheet(
                    train = tr,
                    track = viewModel.metroTracks[tr.line],
                    now = metroNow,
                    following = followKey == tr.key,
                    onBoard = { exit, alarm -> viewModel.startOnboard(tr.key, exit, alarm) },
                    onFollow = { viewModel.followMetro(if (followKey == tr.key) null else tr.key) },
                    onClose = { viewModel.selectMetro(null) }
                )
            }
        }

        AnimatedVisibility(
            visible = onboard != null && !isBusMode,
            enter = slideInVertically(animationSpec = sheetSpring, initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val shown = remember { mutableStateOf<LiveMapViewModel.Onboard?>(null) }
            if (onboard != null) shown.value = onboard
            shown.value?.let { b ->
                MetroOnboardCard(
                    board = b,
                    train = metroTrains.firstOrNull { it.key == b.key },
                    track = viewModel.metroTracks[b.line],
                    now = metroNow,
                    onAlarm = { viewModel.setOnboardAlarm(it) },
                    onStop = { viewModel.stopOnboard() }
                )
            }
        }

        // Seletor de tempo: só com o mapa livre (sem painéis abertos)
        // Com qualquer painel aberto (também o de um veículo das outras redes ou os filtros) o seletor sai
        val sheetOpen = metroTrain != null || safeStation != null || selectedCpNumber != null || onboard != null ||
            selectedBusStop != null || selectedLiveBus != null || selectedBusRoute != null ||
            selectedRegional != null || filtersOpen
        AnimatedVisibility(
            visible = !isBusMode && mapFilters.metro && !sheetOpen,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
        ) {
            MetroTimeBar(offsetFlow = viewModel.metroOffsetMs, onChange = { viewModel.setMetroOffset(it) })
        }

        AnimatedVisibility(
            visible = safeStation != null && !isBusMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (safeStation != null) {
                MetroStationSheet(safeStation, metroTrains, viewModel.metroTracks, metroNow) { viewModel.selectStation(null) }
            }
        }

        // ── Comboio CP aberto ─────────────────────────────────────────────────
        val cpNumber = selectedCpNumber
        AnimatedVisibility(
            visible = cpNumber != null && !isBusMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (cpNumber != null) {
                CpTrainSheet(
                    number = cpNumber,
                    train = selectedCpTrain,
                    state = cpTrip,
                    onClose = { viewModel.selectCpTrain(null) },
                    onDetails = { runDate ->
                        context.startActivity(CpTripDetailActivity.newIntent(context, cpNumber, runDate))
                    },
                    onFollow = { runDate, stops -> followRequest = runDate to stops }
                )
            }
        }

        val follow = followRequest
        if (follow != null && cpNumber != null) {
            val (runDate, stops) = follow
            val pos = cpProgress(cpTrip, selectedCpTrain)
            CpFollowDialog(
                trainNumber = cpNumber,
                runDate = runDate,
                service = selectedCpTrain?.service ?: "?",
                destination = selectedCpTrain?.destination,
                stops = stops,
                fromIndex = if (pos < 0) 1 else kotlin.math.floor(pos.toDouble()).toInt() + 1,
                boardCode = null,
                preselectCode = null,
                onDismiss = { followRequest = null }
            )
        }

        // ── Painel "O que ver no mapa" (por cima de tudo) ─────────────────────
        AnimatedVisibility(visible = filtersOpen, enter = fadeIn(), exit = fadeOut()) {
            // Os metros a andar agora: (Lisboa, Porto)
            val metroNowCount = remember(metroTrains) {
                val now = System.currentTimeMillis()
                val running = metroTrains.filter { tr -> viewModel.metroTracks[tr.line]?.let { tr.placeAt(now, it) } != null }
                running.count { !com.example.metrotime.data.metro.PortoMetro.isPorto(it.line) } to running.count { com.example.metrotime.data.metro.PortoMetro.isPorto(it.line) }
            }
            MapFiltersSheet(
                filters = mapFilters,
                vehicles = regionalAll,
                metroCount = metroNowCount.first,
                portoMetroCount = metroNowCount.second,
                cpCount = cpTrains.size,
                cpServices = cpServicesPresent,
                onChange = { viewModel.updateMapFilters(it) },
                onClose = { filtersOpen = false },
                onShown = { key, shown -> viewModel.setOperatorShown(key, shown) },
                onArea = { viewModel.showOnlyArea(it) },
                onLines = { key, lines -> viewModel.setOperatorLines(key, lines) },
                onReset = { viewModel.resetRegionalFilters() },
                onSearchCarris = {
                    filtersOpen = false
                    viewModel.openBusSearch()
                }
            )
        }
    }
}

// ── Bus Route Info Bottom Sheet ──────────────────────────────────────────────────

/**
 * Painel de uma linha: número na cor oficial, os dois sentidos em separadores e o percurso desenhado
 * como as linhas do metro, com os autocarros em cima e quantas paragens faltam ao próximo.
 */
@Composable
private fun BusRouteInfoSheet(
    route: com.example.metrotime.data.bus.BusRoute,
    buses: List<com.example.metrotime.data.bus.LiveBus>,
    stops: List<com.example.metrotime.data.bus.BusStop>,
    selectedDirection: Int,
    onDirectionSelected: (Int) -> Unit,
    onBusSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val lineColor = busColor(route.color)
    var isExpanded by remember { mutableStateOf(true) }
    // Em que paragem do percurso vai cada autocarro (a mais perto do GPS)
    val busAt: Map<Int, com.example.metrotime.data.bus.LiveBus> = remember(buses, stops) {
        val points = stops.map { it.lat to it.lon }
        buses.mapNotNull { bus ->
            com.example.metrotime.data.bus.BusRouteProgress.nearestStop(points, bus.lat to bus.lon)
                .takeIf { it >= 0 }?.let { it to bus }
        }.toMap()
    }
    val away = remember(busAt, stops.size) {
        com.example.metrotime.data.bus.BusRouteProgress.stopsAway(stops.size, busAt.keys)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    if (dragAmount > 8f) isExpanded = false else if (dragAmount < -8f) isExpanded = true
                }
            },
        color = DarkBackground,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp)
                    .width(40.dp).height(4.dp)
                    .background(Color(0xFF4A4D53), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BusLineBadge(route.shortName, route.color, height = 32.dp, fontSize = 17.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(route.longName.replace(" - ", " ↔ "), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (route.operator == "carris_metropolitana") "Carris Metropolitana" else "Carris") + " · " +
                            (if (buses.size == 1) "1 autocarro neste sentido" else "${buses.size} autocarros neste sentido"),
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondaryDark)
                }
            }

            AnimatedVisibility(visible = isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Spacer(Modifier.height(12.dp))
                    // Sentidos, no mesmo estilo dos separadores do resto da app
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardBackground)
                            .padding(3.dp)
                    ) {
                        listOf(0, 1).forEach { dir ->
                            val selected = selectedDirection == dir
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) MetrotimeBlue else Color.Transparent)
                                    .clickable { onDirectionSelected(dir) }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    getDirectionLabel(route, dir).replaceFirst("Para ", "para "),
                                    color = if (selected) Color.White else TextSecondaryDark,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (stops.isEmpty()) {
                        Text("A carregar o percurso…", color = TextSecondaryDark, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(stops.size, key = { "${stops[it].id}_$it" }) { i ->
                                BusRouteStopRow(
                                    name = stops[i].name,
                                    lineColor = lineColor,
                                    first = i == 0,
                                    last = i == stops.lastIndex,
                                    bus = busAt[i],
                                    away = away.getOrNull(i),
                                    onBusSelected = onBusSelected
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Uma paragem do percurso: a linha na cor dela desenhada por trás, nome e quantas paragens faltam. */
@Composable
private fun BusRouteStopRow(
    name: String,
    lineColor: Color,
    first: Boolean,
    last: Boolean,
    bus: com.example.metrotime.data.bus.LiveBus?,
    away: Int?,
    onBusSelected: (String) -> Unit
) {
    val bg = DarkBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .drawBehind {
                val x = 18.dp.toPx()
                val cy = size.height / 2
                val w = 4.dp.toPx()
                if (!first) drawLine(lineColor, Offset(x, 0f), Offset(x, cy), w)
                if (!last) drawLine(lineColor, Offset(x, cy), Offset(x, size.height), w)
                val r = (if (first || last) 8.dp else 6.dp).toPx()
                drawCircle(bg, r, Offset(x, cy))
                drawCircle(if (first || last) Color.White else lineColor, r, Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // O autocarro em cima da linha, nesta paragem; toca para o ver
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (bus != null) {
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(lineColor)
                        .clickable { onBusSelected(bus.vehicleId) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(BusIcon, contentDescription = "Autocarro nesta paragem", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (first || last) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 10.dp)
        )
        val label = com.example.metrotime.data.bus.BusRouteProgress.label(away)
        if (label.isNotEmpty()) {
            Text(label, color = if (away == 0) Color(0xFF6FCF73) else TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
// ── Live Bus Detail Bottom Sheet ─────────────────────────────────────────────────

/**
 * Autocarro da Carris ou da Carris Metropolitana: o mesmo painel dos autocarros das outras redes (linha na cor
 * oficial, destino, as paragens num traço com o autocarro por cima, tocar onde sais para o aviso).
 */
@Composable
private fun LiveBusDetailSheet(
    state: LiveMapViewModel.SelectedLiveBusState,
    routeColor: String?,
    onDismiss: () -> Unit
) {
    val bus = state.bus
    val context = LocalContext.current
    val lineHex = routeColor?.let { com.example.metrotime.data.bus.BusColors.normalize(it) }
        ?: com.example.metrotime.data.bus.BusColors.forRoute(bus.routeShortName, bus.operator)
    val cm = bus.operator == "carris_metropolitana"
    val kind = carrisKind(bus.routeShortName, bus.operator)
    val operator = remember(cm, kind) {
        (if (cm) com.example.metrotime.data.regional.RegionalOperators.CM else com.example.metrotime.data.regional.RegionalOperators.CARRIS).copy(kind = kind)
    }
    val destination = bus.destination.ifEmpty { state.schedule.lastOrNull()?.stopName.orEmpty() }
    // Os minutos até cada paragem andam sozinhos
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val stops = remember(state.schedule, state.delaySeconds, now) {
        val nowSec = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/Lisbon")).toLocalTime().toSecondOfDay()
        state.schedule.map { st ->
            // Com o horário da viagem: a hora prevista = a do horário + o atraso de agora
            val sched = st.scheduledSec
            val due = when {
                st.isPassed -> null
                sched != null -> {
                    val at = sched + (state.delaySeconds ?: 0)
                    val rel = if (at - nowSec > 12 * 3600) at - 24 * 3600 - nowSec else at - nowSec
                    (rel / 60).coerceAtLeast(0)
                }
                else -> minutesUntilClock(st.arrivalTime, now)
            }
            st to com.example.metrotime.data.regional.RegionalStop(
                name = st.stopName,
                lat = Double.NaN,
                lon = Double.NaN,
                clock = st.arrivalTime.takeIf { it.isNotBlank() },
                dueMinutes = due,
                passed = st.isPassed
            )
        }
    }
    val vehicle = com.example.metrotime.data.regional.RegionalVehicle(
        operator = operator,
        vehicleId = bus.vehicleId,
        lat = bus.lat,
        lon = bus.lon,
        line = bus.routeShortName,
        color = lineHex,
        status = bus.currentStatus.ifEmpty { null },
        destination = destination.ifEmpty { null },
        delaySeconds = state.delaySeconds
    )
    val trip = if (state.loading && state.schedule.isEmpty()) null else com.example.metrotime.data.regional.RegionalTrip(
        line = bus.routeShortName,
        lineName = null,
        color = lineHex,
        destination = destination.ifEmpty { null },
        stops = stops.map { it.second },
        shape = emptyList()
    )
    RegionalVehicleSheet(
        LiveMapViewModel.RegionalSelection(vehicle, trip, state.loading),
        now,
        onDismiss,
        locate = { stop -> stops.firstOrNull { it.second === stop }?.first?.let { busStopCoords(context, it.stopId, bus.operator) } }
    )
}

/** Minutos de agora até "18:28" (hora de Lisboa; à volta da meia-noite conta para o lado mais perto); 0 se já passou. */
private fun minutesUntilClock(clock: String, now: Long): Int? {
    val parts = clock.trim().split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/Lisbon"))
    var diff = (h * 60 + m) - (t.hour * 60 + t.minute)
    if (diff < -720) diff += 1440 else if (diff > 720) diff -= 1440
    return diff.coerceAtLeast(0)
}

/** Coordenadas de uma paragem pelo id (Carris: dados da app; Carris Metropolitana: lista da API, já em cache). */
private suspend fun busStopCoords(context: android.content.Context, stopId: String, operator: String): Pair<Double, Double>? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (operator == "carris_metropolitana") {
            com.example.metrotime.data.bus.BusMapRepository.getAllCmStops().find { it.id == stopId }?.let { it.lat to it.lon }
        } else {
            com.example.metrotime.data.bus.CarrisGtfsData.ensureLoaded(context)
            com.example.metrotime.data.bus.CarrisGtfsData.stops.find { it.id == stopId }?.let { it.lat to it.lon }
        }
    }

// ── Bus Stop Detail Bottom Sheet ─────────────────────────────────────────────────


/**
 * Paragem: sinal "P", nome e favorita; filtros por linha (toca num número para ver só essa linha,
 * noutro para a juntar, "Todas" volta a mostrar tudo) e as chegadas com o número na cor da linha.
 */
@Composable
private fun BusStopDetailSheet(
    state: LiveMapViewModel.SelectedStopState,
    onArrivalSelected: (com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val stop = state.stop
    var isFav by remember(stop.id) { mutableStateOf(com.example.metrotime.prefs.Prefs.isFavoriteBusStop(context, stop.operator, stop.id)) }
    // Autocarros com aviso ligado ("avisar quando estiver a chegar"), para o sino de cada um
    var followed by remember { mutableStateOf(com.example.metrotime.work.BusFollow.all(context)) }
    var selected by remember(stop.id) { mutableStateOf(setOf<String>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            now = System.currentTimeMillis()
            // O aviso acaba sozinho quando o autocarro passa: o sino volta a cinzento
            followed = com.example.metrotime.work.BusFollow.all(context)
        }
    }

    val lines = remember(state.arrivals) {
        state.arrivals.map { it.routeShortName to it.color }.distinctBy { it.first }
            .sortedWith(compareBy({ it.first.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it.first }))
    }
    // Todos os autocarros das próximas 5 h, um por linha da lista, por ordem de chegada (como no Google Maps)
    val rows = remember(state.arrivals, selected) {
        state.arrivals
            .filter { selected.isEmpty() || it.routeShortName in selected }
            .sortedWith(compareBy({ it.etaMinutes }, { it.routeShortName }))
    }
    // Abre a meio do ecrã (o mapa fica à vista); pela pega sobe até quase ao topo ou desce até só o cabeçalho
    val drag = rememberSheetDrag(listOf(0.32f, 0.5f, 0.9f), initial = 1, key = stop.id)
    val density = LocalDensity.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .sheetDrag(drag, density)
            .navigationBarsPadding(),
        color = DarkBackground,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // A pega e o cabeçalho agarram o painel; o resto (linhas e chegadas) desliza por baixo
            Column(Modifier.fillMaxWidth().sheetDragArea(drag, onDismiss).padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                SheetHandle(drag, onDismiss)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BusStopSign(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stop.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                stop.operator == "carris_metropolitana" -> "Paragem da Carris Metropolitana"
                                stop.operator.startsWith("eleven:") ->
                                    "Paragem · ${com.example.metrotime.data.regional.RegionalOperators.byKey(stop.operator)?.name ?: "outra rede"}"
                                stop.operator.startsWith("pack:") -> {
                                    val op = com.example.metrotime.data.regional.RegionalOperators.byPack(stop.operator.removePrefix("pack:"))
                                    when (op?.kind) {
                                        com.example.metrotime.data.regional.VehicleKind.BOAT -> "Cais · ${op.name}"
                                        com.example.metrotime.data.regional.VehicleKind.TRAIN,
                                        com.example.metrotime.data.regional.VehicleKind.SUBWAY,
                                        com.example.metrotime.data.regional.VehicleKind.TRAM -> "Estação · ${op.name}"
                                        else -> "Paragem · ${op?.name ?: "outra rede"}"
                                    }
                                }
                                else -> "Paragem da Carris"
                            },
                            color = TextSecondaryDark,
                            fontSize = 12.sp
                        )
                        // Põe esta paragem no widget (ou pede para o adicionar ao ecrã inicial)
                        Text(
                            "Mostrar no widget",
                            color = MetrotimeBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val entry = listOf(stop.operator, stop.id, stop.name.replace("|", "/"), stop.lat, stop.lon).joinToString("|")
                                    com.example.metrotime.prefs.Prefs.setBusWidgetStop(context, entry)
                                    val manager = android.appwidget.AppWidgetManager.getInstance(context)
                                    if (com.example.metrotime.work.BusWidgetUpdater.hasWidgets(context)) {
                                        BusWidgetProvider.refresh(context)
                                        android.widget.Toast.makeText(context, "O widget passa a mostrar ${stop.name}.", android.widget.Toast.LENGTH_SHORT).show()
                                    } else if (manager.isRequestPinAppWidgetSupported) {
                                        manager.requestPinAppWidget(android.content.ComponentName(context, BusWidgetProvider::class.java), null, null)
                                    } else {
                                        android.widget.Toast.makeText(context, "Adiciona o widget \"Paragem de autocarro\" no ecrã inicial.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                    // O caminho a pé até esta paragem, desde onde estás
                    IconButton(onClick = {
                        context.startActivity(WalkRouteActivity.newIntent(context, stop.lat, stop.lon, stop.name))
                    }) {
                        Icon(WalkIcon, contentDescription = "A pé até aqui", tint = MetrotimeBlue)
                    }
                    IconButton(onClick = {
                        com.example.metrotime.prefs.Prefs.toggleFavoriteBusStop(context, stop.operator, stop.id, stop.name, stop.lat, stop.lon)
                        isFav = !isFav
                    }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = if (isFav) "Remover das favoritas" else "Adicionar às favoritas",
                            tint = if (isFav) Color(0xFFFFCA28) else TextSecondaryDark
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondaryDark)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (lines.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val allOn = selected.isEmpty()
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 34.dp)
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(if (allOn) MetrotimeBlue else CardBackground)
                                    .border(1.dp, if (allOn) MetrotimeBlue else Color(0xFF3A3D42), RoundedCornerShape(17.dp))
                                    .clickable { selected = emptySet() }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Todas", color = if (allOn) Color.White else Color(0xFFDADDE2), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            lines.forEach { (number, hex) ->
                                val on = number in selected
                                val color = busColor(hex)
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 34.dp)
                                        .clip(RoundedCornerShape(17.dp))
                                        .background(if (on) color else CardBackground)
                                        .border(1.dp, if (on) color else Color(0xFF3A3D42), RoundedCornerShape(17.dp))
                                        .clickable { selected = if (on) selected - number else selected + number }
                                        .padding(start = 12.dp, end = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) busColor(com.example.metrotime.data.bus.BusColors.textOn(hex)) else color))
                                    Text(
                                        number,
                                        color = if (on) busColor(com.example.metrotime.data.bus.BusColors.textOn(hex)) else Color(0xFFDADDE2),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Próximas chegadas", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (state.updatedAt > 0) {
                            val secs = ((now - state.updatedAt) / 1000).coerceAtLeast(0)
                            Text(if (secs < 60) "atualizado há $secs s" else "atualizado há ${secs / 60} min", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                    }
                    when {
                        state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                        }
                        rows.isEmpty() -> Text(
                            "Nenhum autocarro a caminho desta paragem.",
                            color = TextSecondaryDark,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                        else -> Surface(color = CardBackground, shape = RoundedCornerShape(14.dp)) {
                            Column {
                                rows.forEachIndexed { i, a ->
                                    if (i > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(Color(0xFF3A3D42)))
                                    val mine = followed.firstOrNull { f -> f.stopId == stop.id && com.example.metrotime.work.BusFollow.matches(f, a, now) }
                                    StopArrivalRow(
                                        a = a,
                                        following = mine != null,
                                        onClick = { onArrivalSelected(a) },
                                        onBell = {
                                            val time = arrivalClock(a)
                                            if (mine != null) {
                                                com.example.metrotime.work.BusFollow.stop(context, mine.id)
                                                followed = com.example.metrotime.work.BusFollow.all(context)
                                                android.widget.Toast.makeText(context, "Aviso do ${a.routeShortName} das $time cancelado.", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                val f = com.example.metrotime.work.BusFollow.Followed(
                                                    operator = stop.operator,
                                                    stopId = stop.id,
                                                    stopName = stop.name,
                                                    stopLat = stop.lat,
                                                    stopLon = stop.lon,
                                                    line = a.routeShortName,
                                                    destination = a.destination,
                                                    vehicleId = a.vehicleId.takeIf { a.realtime },
                                                    scheduledTime = a.scheduledTime,
                                                    dueAt = System.currentTimeMillis() + a.etaMinutes * 60_000L
                                                )
                                                val started = com.example.metrotime.work.BusFollow.start(context, f)
                                                followed = com.example.metrotime.work.BusFollow.all(context)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (started) "Aviso quando o ${a.routeShortName} das $time estiver a ${f.alertMinutes} min daqui."
                                                    else "Já tens ${com.example.metrotime.work.BusFollow.MAX_FOLLOWS} avisos ligados. Desliga um primeiro.",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hora a que o autocarro chega: a do horário se for só previsto, senão agora mais os minutos que faltam. */
private fun arrivalClock(a: com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo): String =
    if (!a.realtime && a.scheduledTime != null) a.scheduledTime
    else java.time.LocalTime.now(java.time.ZoneId.of("Europe/Lisbon")).plusMinutes(a.etaMinutes.toLong())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/**
 * Um autocarro na paragem, como no Google Maps: número e destino; por baixo o estado ("a horas", "atrasado
 * 6 min" com a hora do horário riscada, "previsto · 19:51"); à direita "Agora", os minutos ou, a mais de uma
 * hora, a hora; e o sino para avisar quando este autocarro estiver a chegar.
 */
@Composable
private fun StopArrivalRow(
    a: com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo,
    following: Boolean,
    onClick: () -> Unit,
    onBell: () -> Unit
) {
    val status = a.status
    val color = busStatusColor(status.kind)
    val eta = a.etaMinutes
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BusLineBadge(a.routeShortName, a.color, height = 26.dp, fontSize = 14.sp)
                Text(a.destination, color = Color.White, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status.realtime) Icon(LiveIcon, contentDescription = "Tempo real", tint = color, modifier = Modifier.size(11.dp))
                val late = status.kind == com.example.metrotime.data.bus.BusTiming.Kind.LATE || status.kind == com.example.metrotime.data.bus.BusTiming.Kind.VERY_LATE
                Text(
                    when {
                        !status.realtime && a.scheduledTime != null && eta < 60 -> "${status.text} · ${a.scheduledTime}"
                        late && a.scheduledTime != null -> "${status.text} ·"
                        else -> status.text
                    },
                    color = color, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (late && a.scheduledTime != null) {
                    Text(
                        a.scheduledTime, color = Color(0xFF8E929A), fontSize = 13.sp, lineHeight = 17.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 56.dp)) {
            val timeColor = if (status.realtime) color else Color.White
            when {
                eta <= 0 -> Text("Agora", color = timeColor, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                eta < 60 -> {
                    Text("$eta", color = timeColor, fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                    Text("min", color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 14.sp)
                }
                else -> Text(arrivalClock(a), color = timeColor, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        IconButton(
            onClick = onBell,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (following) MetrotimeBlue.copy(alpha = 0.18f) else Color.Transparent)
        ) {
            Icon(
                if (following) Icons.Filled.Notifications else androidx.compose.material.icons.Icons.Outlined.Notifications,
                contentDescription = if (following) "Cancelar o aviso deste autocarro" else "Avisar quando este autocarro estiver a chegar",
                tint = if (following) MetrotimeBlue else TextSecondaryDark,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Map Layer Helpers (pure non-Compose, called on map thread)
// ──────────────────────────────────────────────────────────────────────────────

private val LINE_NAMES = listOf("Azul", "Amarela", "Verde", "Vermelha")

private fun lineColorInt(lineName: String): Int = when (lineName) {
    "Azul"     -> AndroidColor.parseColor("#0077C8")
    "Amarela"  -> AndroidColor.parseColor("#FFD500")
    "Verde"    -> AndroidColor.parseColor("#009E60")
    "Vermelha" -> AndroidColor.parseColor("#ED1C24")
    else       -> AndroidColor.WHITE
}

private fun createFriendBitmap(initial: String): Bitmap {
    val size = 70
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#3B82F6") // Blue circle
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 2f, border)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 36f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    
    val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2)
    canvas.drawText(initial, size / 2f, yPos, textPaint)

    return bitmap
}

private fun setupMapLayers(context: android.content.Context, style: Style) {
    val coords    = MapConfiguration.STATION_COORDS
    val lineOrder = MapConfiguration.LINE_STATION_ORDER
    val empty     = """{"type":"FeatureCollection","features":[]}"""

    // ── Rede CP (por baixo do metro) ───────────────────────────────────────────
    setupCpBaseLayers(style)

    // ── Metro route lines ──────────────────────────────────────────────────────
    for ((lineName, stationIds) in lineOrder) {
        val coordsArr = JSONArray()
        for (id in stationIds) {
            val c = coords[id] ?: continue
            coordsArr.put(JSONArray().put(c.lon).put(c.lat))
        }
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject()
                .put("type", "LineString")
                .put("coordinates", coordsArr))
            .put("properties", JSONObject())
            .toString()

        if (style.getSource("line-src-$lineName") == null) {
            style.addSource(GeoJsonSource("line-src-$lineName", geoJson))
            style.addLayer(
                LineLayer("line-layer-$lineName", "line-src-$lineName").withProperties(
                    lineColor(lineColorInt(lineName)),
                    lineWidth(4f),
                    lineCap("round"),
                    lineJoin("round")
                )
            )
        }
    }

    // ── Linhas pelo traçado real dos túneis (OpenStreetMap), em vez de retas entre as estações ─────────────
    applyMetroTracks(style, com.example.metrotime.data.metro.MetroTracks.load(context))

    // ── Station dots (as do troço interrompido de uma perturbação ficam a cinzento) ─────────────────────
    val addPorto = style.getSource("stations-src") == null
    if (style.getSource("stations-src") == null) {
        style.addSource(GeoJsonSource("stations-src", metroStationsCollection(emptySet())))
        style.addLayer(
            CircleLayer("stations-layer", "stations-src").withProperties(
                circleRadius(5f),
                circleColor(
                    org.maplibre.android.style.expressions.Expression.switchCase(
                        org.maplibre.android.style.expressions.Expression.eq(get("cut"), org.maplibre.android.style.expressions.Expression.literal(true)),
                        org.maplibre.android.style.expressions.Expression.color(AndroidColor.parseColor("#8A8F98")),
                        org.maplibre.android.style.expressions.Expression.color(AndroidColor.WHITE)
                    )
                ),
                circleStrokeWidth(1.5f),
                circleStrokeColor(AndroidColor.parseColor("#333333"))
            )
        )
    }

    // ── Metro do Porto: linhas e estações iguais às de Lisboa (por baixo dos metros) ─────────────────────
    if (addPorto) setupPortoMetroLayers(style)

    // ── Metros: andam pelo traçado (pontos de longe, carruagens de perto), brilho e etiqueta do último ─────
    setupMetroLayers(style)

    // ── Friends layer ─────────────────────────────────────────────────────────
    if (style.getSource("friends-src") == null) {
        style.addSource(GeoJsonSource("friends-src", empty))
        style.addLayer(
            SymbolLayer("friends-layer", "friends-src").withProperties(
                iconImage(get("imageId")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                textAllowOverlap(true),
                textIgnorePlacement(true),
                symbolAvoidEdges(false),
                iconSize(1.0f),
                iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_VIEWPORT)
            )
        )
    }

    // ── Bus layers ─────────────────────────────────────────────────────────────
    // Percurso da linha aberta (a cor muda para a da linha em updateBusRouteLine)
    if (style.getSource("bus-route-src") == null) {
        style.addSource(GeoJsonSource("bus-route-src", empty))
        style.addLayer(
            LineLayer("bus-route-layer", "bus-route-src").withProperties(
                lineColor(AndroidColor.parseColor(com.example.metrotime.data.bus.BusColors.FALLBACK)),
                lineWidth(4f),
                lineCap("round"),
                lineJoin("round"),
                lineOpacity(0.9f)
            )
        )
    }

    // Paragens da linha aberta: pontos brancos com o contorno na cor da linha
    if (style.getSource("bus-stops-src") == null) {
        style.addSource(GeoJsonSource("bus-stops-src", empty))
        style.addLayer(
            CircleLayer("bus-stops-layer", "bus-stops-src").withProperties(
                circleRadius(4.5f),
                circleColor(AndroidColor.WHITE),
                circleStrokeWidth(2.5f),
                circleStrokeColor(AndroidColor.parseColor(com.example.metrotime.data.bus.BusColors.FALLBACK)),
                circleOpacity(1f)
            )
        )
    }

    // All bus stops
    if (style.getSource("all-stops-src") == null) {
        try {
            // Use clustering so MapLibre handles the 15k points efficiently without lagging the UI
            val opts = org.maplibre.android.style.sources.GeoJsonOptions()
                .withCluster(true)
                .withClusterMaxZoom(15)
                .withClusterRadius(40)
            style.addSource(GeoJsonSource("all-stops-src", empty, opts))
            
            // Small subtle white dots (only unclustered points)
            val stopsLayer = CircleLayer("all-stops-layer", "all-stops-src")
                .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                .withProperties(
                        circleRadius(
                            org.maplibre.android.style.expressions.Expression.interpolate(
                                org.maplibre.android.style.expressions.Expression.linear(),
                                org.maplibre.android.style.expressions.Expression.zoom(),
                                org.maplibre.android.style.expressions.Expression.stop(14f, 3f),
                                org.maplibre.android.style.expressions.Expression.stop(16f, 5f),
                                org.maplibre.android.style.expressions.Expression.stop(18f, 7f)
                            )
                        ),
                        circleColor(AndroidColor.parseColor("#FFFFFF")),
                        circleStrokeWidth(1.5f),
                        circleStrokeColor(AndroidColor.parseColor("#666666")),
                        circleOpacity(
                            org.maplibre.android.style.expressions.Expression.interpolate(
                                org.maplibre.android.style.expressions.Expression.linear(),
                                org.maplibre.android.style.expressions.Expression.zoom(),
                                org.maplibre.android.style.expressions.Expression.stop(13.5f, 0f),
                                org.maplibre.android.style.expressions.Expression.stop(14f, 0.5f),
                                org.maplibre.android.style.expressions.Expression.stop(16f, 0.8f)
                            )
                        ),
                        circleStrokeOpacity(
                            org.maplibre.android.style.expressions.Expression.interpolate(
                                org.maplibre.android.style.expressions.Expression.linear(),
                                org.maplibre.android.style.expressions.Expression.zoom(),
                                org.maplibre.android.style.expressions.Expression.stop(13.5f, 0f),
                                org.maplibre.android.style.expressions.Expression.stop(14f, 0.5f)
                            )
                        )
                    )
            stopsLayer.minZoom = 13.5f
            stopsLayer.maxZoom = 16f
            style.addLayer(stopsLayer)
            // De perto, cada paragem com o sinal "P" (desenhado com a fonte, não à mão)
            if (style.getImage("stop-p") == null) style.addImage("stop-p", busStopSignBitmap(context))
            val stopsSignLayer = SymbolLayer("all-stops-p", "all-stops-src")
                .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                .withProperties(
                    iconImage("stop-p"),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconSize(
                        org.maplibre.android.style.expressions.Expression.interpolate(
                            org.maplibre.android.style.expressions.Expression.linear(),
                            org.maplibre.android.style.expressions.Expression.zoom(),
                            org.maplibre.android.style.expressions.Expression.stop(16f, 0.6f),
                            org.maplibre.android.style.expressions.Expression.stop(18f, 0.9f)
                        )
                    )
                )
            stopsSignLayer.minZoom = 16f
            style.addLayer(stopsSignLayer)
            // Stop name labels (visible at zoom ≥15.5, unclustered only)
            val stopsLabelLayer = SymbolLayer("all-stops-labels", "all-stops-src")
                .withFilter(org.maplibre.android.style.expressions.Expression.not(org.maplibre.android.style.expressions.Expression.has("point_count")))
                .withProperties(
                    textField(get("name")),
                    textSize(
                        org.maplibre.android.style.expressions.Expression.interpolate(
                            org.maplibre.android.style.expressions.Expression.linear(),
                            org.maplibre.android.style.expressions.Expression.zoom(),
                            org.maplibre.android.style.expressions.Expression.stop(15.5f, 9f),
                            org.maplibre.android.style.expressions.Expression.stop(18f, 12f)
                        )
                    ),
                    textColor(AndroidColor.parseColor("#CCCCCC")),
                    textHaloColor(AndroidColor.parseColor("#000000")),
                    textHaloWidth(1.5f),
                    textOffset(arrayOf(0f, 1.2f)),
                    textAllowOverlap(false),
                    textIgnorePlacement(false),
                    textOptional(true),
                    textOpacity(
                        org.maplibre.android.style.expressions.Expression.interpolate(
                            org.maplibre.android.style.expressions.Expression.linear(),
                            org.maplibre.android.style.expressions.Expression.zoom(),
                            org.maplibre.android.style.expressions.Expression.stop(15f, 0f),
                            org.maplibre.android.style.expressions.Expression.stop(15.5f, 0.7f)
                        )
                    )
                )
            stopsLabelLayer.minZoom = 15f
            style.addLayer(stopsLabelLayer)
        } catch (e: Exception) {
            android.util.Log.e("BusMap", "FAILED to create all-stops layers!", e)
        }
    }

    // Autocarros: pílula na cor da linha com contorno branco e um bico no sítio exato. Por cima das
    // paragens (antes ficavam por baixo dos "P") e o escolhido maior
    if (style.getSource("bus-vehicles-src") == null) {
        try {
            style.addSource(GeoJsonSource("bus-vehicles-src", empty))
            style.addLayer(
                SymbolLayer("bus-vehicles-layer", "bus-vehicles-src").withProperties(
                    iconImage(get("icon")),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    // O centro da bolinha é o sítio do autocarro
                    iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
                    symbolSortKey(get("sort")),
                    iconSize(
                        org.maplibre.android.style.expressions.Expression.interpolate(
                            org.maplibre.android.style.expressions.Expression.linear(),
                            org.maplibre.android.style.expressions.Expression.zoom(),
                            org.maplibre.android.style.expressions.Expression.stop(
                                10f,
                                org.maplibre.android.style.expressions.Expression.switchCase(
                                    get("selected"), org.maplibre.android.style.expressions.Expression.literal(1.0f),
                                    org.maplibre.android.style.expressions.Expression.literal(0.75f)
                                )
                            ),
                            org.maplibre.android.style.expressions.Expression.stop(
                                14f,
                                org.maplibre.android.style.expressions.Expression.switchCase(
                                    get("selected"), org.maplibre.android.style.expressions.Expression.literal(1.3f),
                                    org.maplibre.android.style.expressions.Expression.literal(1.0f)
                                )
                            )
                        )
                    )
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("BusMap", "FAILED to create bus layers!", e)
        }
    }

    // ── Outras redes (MobiCascais, barcos, Covilhã, Viseu…), por cima dos autocarros ─────────
    setupRegionalLayers(style)

    // ── Comboios CP e percurso do comboio aberto (por cima de tudo) ────────────
    setupCpTopLayers(context, style)
}

/**
 * Render all Carris bus stops on the map as subtle white dots.
 */
/** A lista de paragens mais recente (as anteriores, ainda a ser preparadas, já não se mostram). */
@Volatile private var allStopsGeneration = 0

private fun updateAllBusStops(map: MapLibreMap, stops: List<com.example.metrotime.data.bus.BusStop>) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>("all-stops-src") ?: return
    val generation = ++allStopsGeneration

    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            val featureList = mutableListOf<org.maplibre.geojson.Feature>()
            for (stop in stops) {
                if (stop.lat == 0.0 && stop.lon == 0.0) continue
                val point = org.maplibre.geojson.Point.fromLngLat(stop.lon, stop.lat)
                val feature = org.maplibre.geojson.Feature.fromGeometry(point)
                feature.addStringProperty("id", stop.id)
                feature.addStringProperty("name", stop.name)
                feature.addNumberProperty("lat", stop.lat)
                feature.addNumberProperty("lon", stop.lon)
                feature.addStringProperty("operator", stop.operator)
                featureList.add(feature)
            }
            if (generation != allStopsGeneration) return@launch
            // Milhares de paragens: o texto faz-se aqui; na thread principal o mapa só o recebe
            val json = org.maplibre.geojson.FeatureCollection.fromFeatures(featureList).toJson()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (generation == allStopsGeneration) source.setGeoJson(json)
            }
        } catch (e: Exception) {
            android.util.Log.e("BusMap", "Error updating all bus stops", e)
        }
    }
}

/** Os serviços de localização do Google demoram a arrancar na primeira vez: arrancam já, fora da thread principal. */
internal fun warmUpLocation(context: android.content.Context) {
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        runCatching { com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context) }
    }
}

private fun updateFriendMarkers(context: android.content.Context, map: MapLibreMap, friends: List<LiveMapViewModel.FriendLocation>) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>("friends-src") ?: return

    val features = JSONArray()
    for (friend in friends) {
        val imageId = if (friend.photoBase64 != null) "b64_${friend.uid}" else friend.photoUrl ?: friend.initial
        
        // Ensure image exists in style
        if (style.getImage(imageId) == null) {
            if (friend.photoBase64 != null) {
                try {
                    val bytes = android.util.Base64.decode(friend.photoBase64, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        // Apply circle crop and border manually
                        val bordered = Bitmap.createBitmap(74, 74, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bordered)
                        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = AndroidColor.WHITE
                            setStyle(Paint.Style.STROKE)
                            strokeWidth = 4f
                        }
                        
                        // Circle mask
                        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                        val circleBmp = Bitmap.createBitmap(70, 70, Bitmap.Config.ARGB_8888)
                        val circleCanvas = Canvas(circleBmp)
                        circleCanvas.drawCircle(35f, 35f, 35f, maskPaint)
                        maskPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                        circleCanvas.drawBitmap(Bitmap.createScaledBitmap(bmp, 70, 70, true), 0f, 0f, maskPaint)
                        
                        canvas.drawBitmap(circleBmp, 2f, 2f, null)
                        canvas.drawCircle(37f, 37f, 35f, borderPaint)
                        
                        style.addImage(imageId, bordered)
                    } else {
                        style.addImage(imageId, createFriendBitmap(friend.initial))
                    }
                } catch (e: Exception) {
                    style.addImage(imageId, createFriendBitmap(friend.initial))
                }
            } else if (friend.photoUrl != null) {
                // Temporary fallback while loading
                style.addImage(imageId, createFriendBitmap(friend.initial))
                
                // Load image async
                val loader = coil.ImageLoader(context)
                val req = coil.request.ImageRequest.Builder(context)
                    .data(friend.photoUrl)
                    .transformations(coil.transform.CircleCropTransformation())
                    .size(70)
                    .target { drawable ->
                        val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            val bordered = Bitmap.createBitmap(74, 74, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bordered)
                            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = AndroidColor.WHITE
                                setStyle(Paint.Style.STROKE)
                                strokeWidth = 4f
                            }
                            canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, 70, 70, true), 2f, 2f, null)
                            canvas.drawCircle(37f, 37f, 35f, borderPaint)
                            
                            style.addImage(imageId, bordered)
                            // The layer will automatically use the new image since the ID is the same
                        }
                    }
                    .build()
                loader.enqueue(req)
            } else {
                style.addImage(imageId, createFriendBitmap(friend.initial))
            }
        }

        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(friend.lon).put(friend.lat)))
                .put("properties", JSONObject()
                    .put("uid", friend.uid)
                    .put("name", friend.name)
                    .put("imageId", imageId)
                    .put("timestamp", friend.timestamp))
        )
    }

    val collection = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
        .toString()

    source.setGeoJson(collection)
}

// ── Bus icon bitmap ───────────────────────────────────────────────────────────

private fun createBusIconBitmap(): Bitmap {
    val size = 80
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // White circle background with subtle shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#40000000")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f + 2f, size / 2f - 3f, shadowPaint)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, bgPaint)

    // Light grey circle border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, borderPaint)

    // Dark bus silhouette (front view)
    val busPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#333333")
        style = Paint.Style.FILL
    }

    // Bus body (rounded rectangle)
    val bodyLeft = 24f
    val bodyTop = 18f
    val bodyRight = 56f
    val bodyBottom = 60f
    val bodyRect = android.graphics.RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
    canvas.drawRoundRect(bodyRect, 6f, 6f, busPaint)

    // Bus roof bar (darker strip on top)
    val roofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#555555")
        style = Paint.Style.FILL
    }
    val roofRect = android.graphics.RectF(30f, 14f, 50f, 20f)
    canvas.drawRoundRect(roofRect, 3f, 3f, roofPaint)

    // Windshield (lighter rectangle)
    val windshieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#B0BEC5")
        style = Paint.Style.FILL
    }
    val wsRect = android.graphics.RectF(28f, 23f, 52f, 38f)
    canvas.drawRoundRect(wsRect, 3f, 3f, windshieldPaint)

    // Two headlights (small circles)
    val headlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFC107")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(30f, 53f, 3.5f, headlightPaint)
    canvas.drawCircle(50f, 53f, 3.5f, headlightPaint)

    // Front bumper line
    val bumperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#222222")
        style = Paint.Style.FILL
    }
    val bumperRect = android.graphics.RectF(26f, 58f, 54f, 62f)
    canvas.drawRoundRect(bumperRect, 2f, 2f, bumperPaint)

    return bitmap
}

// ── Bus marker update functions ──────────────────────────────────────────────

/**
 * Sanitize a float/double for JSON: replace NaN/Infinity with 0.
 * Android's JSONObject.put throws JSONException on NaN/Infinity.
 */
private fun safeDouble(value: Float): Double {
    val d = value.toDouble()
    return if (d.isNaN() || d.isInfinite()) 0.0 else d
}
private fun safeDouble(value: Double): Double {
    return if (value.isNaN() || value.isInfinite()) 0.0 else value
}

/**
 * Sinal de paragem para o mapa: quadrado arredondado branco com um "P" em negrito (fonte do sistema),
 * contorno escuro para se ver sobre o mapa.
 */
private fun busStopSignBitmap(context: android.content.Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (26 * d).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = android.graphics.RectF(1.5f * d, 1.5f * d, size - 1.5f * d, size - 1.5f * d)
    val radius = 6f * d
    canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
    canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#1E1F22"); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#1E1F22")
        textSize = 16f * d
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    // Centro vertical pela altura real do "P" (sem a margem da fonte)
    val bounds = android.graphics.Rect()
    text.getTextBounds("P", 0, 1, bounds)
    canvas.drawText("P", size / 2f, size / 2f + bounds.height() / 2f, text)
    return bitmap
}

/**
 * Autocarro no mapa: pílula na cor oficial da linha com o número, contorno branco e sombra para se ver
 * em qualquer fundo, e um bico em baixo que aponta para onde está o autocarro.
 */
internal fun busPillBitmap(context: android.content.Context, number: String, colorHex: String): Bitmap {
    val d = context.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor(com.example.metrotime.data.bus.BusColors.textOn(colorHex))
        textSize = 15f * d
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val shadow = 4f * d
    val border = 2.5f * d
    val pillH = 30f * d
    val pillW = maxOf(pillH + 10f * d, text.measureText(number) + 24f * d)
    val tail = 7f * d
    val width = (pillW + 2 * shadow).toInt()
    val height = (pillH + tail + 2 * shadow).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val left = shadow
    val top = shadow
    val rect = android.graphics.RectF(left, top, left + pillW, top + pillH)
    val cx = left + pillW / 2f
    // Pílula com o bico (um só caminho, para o contorno e a sombra serem contínuos)
    val path = android.graphics.Path().apply {
        addRoundRect(rect, pillH / 2f, pillH / 2f, android.graphics.Path.Direction.CW)
        val tri = android.graphics.Path().apply {
            moveTo(cx - tail, top + pillH - 2f * d)
            lineTo(cx, top + pillH + tail)
            lineTo(cx + tail, top + pillH - 2f * d)
            close()
        }
        op(tri, android.graphics.Path.Op.UNION)
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        setShadowLayer(shadow * 0.8f, 0f, 1.5f * d, AndroidColor.argb(140, 0, 0, 0))
    })
    // Por dentro do contorno branco, a cor da linha
    val inner = android.graphics.Path().apply {
        val r = android.graphics.RectF(rect.left + border, rect.top + border, rect.right - border, rect.bottom - border)
        addRoundRect(r, (pillH - 2 * border) / 2f, (pillH - 2 * border) / 2f, android.graphics.Path.Direction.CW)
        val tri = android.graphics.Path().apply {
            moveTo(cx - tail + border * 1.4f, top + pillH - 2f * d - border)
            lineTo(cx, top + pillH + tail - border * 1.9f)
            lineTo(cx + tail - border * 1.4f, top + pillH - 2f * d - border)
            close()
        }
        op(tri, android.graphics.Path.Op.UNION)
    }
    canvas.drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor(colorHex) })
    val bounds = android.graphics.Rect()
    text.getTextBounds(number, 0, number.length, bounds)
    canvas.drawText(number, cx, top + pillH / 2f - bounds.exactCenterY(), text)
    return bitmap
}

/**
 * Autocarro no mapa (opção B do design): a bolinha branca de sempre da Carris com o veículo desenhado e, encostada à
 * direita, a etiqueta com o número na cor da linha. O centro da bolinha é o centro da imagem (é ele que fica no sítio
 * do veículo). O aberto leva um aro na cor da linha. Sem setas. [kind]: autocarro, elétrico ou barco.
 */
internal fun busBallBitmap(
    context: android.content.Context,
    number: String?,
    colorHex: String,
    selected: Boolean,
    kind: com.example.metrotime.data.regional.VehicleKind = com.example.metrotime.data.regional.VehicleKind.BUS
): Bitmap {
    val d = context.resources.displayMetrics.density
    val lineColor = runCatching { AndroidColor.parseColor(colorHex) }.getOrDefault(AndroidColor.parseColor("#8C8C99"))
    val label = number?.trim().orEmpty()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor(com.example.metrotime.data.bus.BusColors.textOn(colorHex))
        textSize = 12f * d
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val ballR = 15f * d
    val margin = 20f * d
    val labelH = 22f * d
    val labelW = if (label.isEmpty()) 0f else maxOf(labelH, text.measureText(label) + 14f * d)
    val labelLeft = 12f * d
    val half = maxOf(margin, if (labelW > 0f) labelLeft + labelW + 3f * d else 0f)
    val w = kotlin.math.ceil(2 * half).toInt()
    val h = kotlin.math.ceil(2 * margin).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val cy = h / 2f

    // Sombra (ou, no aberto, o aro na cor da linha)
    if (selected) {
        canvas.drawCircle(cx, cy, 17f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * d
            color = lineColor
        })
    } else {
        canvas.drawCircle(cx, cy + 1f * d, 17f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(89, 0, 0, 0) })
    }
    canvas.drawCircle(cx, cy, ballR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
    canvas.drawCircle(cx, cy, ballR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = AndroidColor.parseColor("#E0E0E0")
    })

    // O veículo de frente (medidas do design, à volta do centro)
    fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float, hex: String) {
        canvas.drawRoundRect(
            android.graphics.RectF(cx + l * d, cy + t * d, cx + r * d, cy + b * d), radius * d, radius * d,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor(hex) }
        )
    }
    fun dot(x: Float, y: Float, r: Float, hex: String) {
        canvas.drawCircle(cx + x * d, cy + y * d, r * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor(hex) })
    }
    when (kind) {
        com.example.metrotime.data.regional.VehicleKind.BOAT -> {
            // Barco: casco e cabine
            val hull = android.graphics.Path().apply {
                moveTo(cx - 9f * d, cy + 1f * d)
                lineTo(cx + 9f * d, cy + 1f * d)
                lineTo(cx + 6f * d, cy + 7f * d)
                lineTo(cx - 6f * d, cy + 7f * d)
                close()
            }
            canvas.drawPath(hull, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#333333") })
            rect(-5f, -6f, 5f, 1.5f, 1.5f, "#333333")
            rect(-3.5f, -4.5f, 3.5f, -1f, 1f, "#B0BEC5")
            rect(-1f, -9.5f, 1f, -6f, 0.5f, "#555555")
            rect(-8f, 8.5f, 8f, 9.8f, 0.6f, "#4FC3F7")
        }
        com.example.metrotime.data.regional.VehicleKind.BUS -> {
            // O autocarro de frente (o símbolo "directions_bus"), centrado na bolinha
            val bus = androidx.core.graphics.PathParser.createPathFromPathData(BUS_SYMBOL_PATH)
            bus.fillType = android.graphics.Path.FillType.EVEN_ODD
            val k = 0.98f * d
            bus.transform(android.graphics.Matrix().apply {
                setTranslate(-12f, -11.5f)
                postScale(k, k)
                postTranslate(cx, cy)
            })
            canvas.drawPath(bus, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#1F1F1F") })
        }
        else -> {
            val tram = kind == com.example.metrotime.data.regional.VehicleKind.TRAM
            rect(-7f, -10f, 7f, 8f, 3f, "#333333")
            if (tram) {
                // Elétrico: o pantógrafo em cima
                val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = AndroidColor.parseColor("#555555")
                    strokeWidth = 1.3f * d
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(cx - 3f * d, cy - 10f * d, cx, cy - 13f * d, pole)
                canvas.drawLine(cx + 3f * d, cy - 10f * d, cx, cy - 13f * d, pole)
                canvas.drawLine(cx - 4f * d, cy - 13f * d, cx + 4f * d, cy - 13f * d, pole)
            } else {
                rect(-4.5f, -11.5f, 4.5f, -8.5f, 1.5f, "#555555")
            }
            rect(-5.5f, -7.5f, 5.5f, -0.5f, 1.5f, "#B0BEC5")
            dot(-4.2f, 4.5f, 1.5f, "#FFC107")
            dot(4.2f, 4.5f, 1.5f, "#FFC107")
            rect(-6f, 7f, 6f, 9f, 1f, "#222222")
        }
    }

    // A etiqueta com o número, encostada à bolinha
    if (labelW > 0f) {
        val r = android.graphics.RectF(cx + labelLeft, cy - labelH / 2f, cx + labelLeft + labelW, cy + labelH / 2f)
        canvas.drawRoundRect(r, labelH / 2f, labelH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            setShadowLayer(2.5f * d, 0f, 1f * d, AndroidColor.argb(128, 0, 0, 0))
        })
        val border = 2f * d
        val inner = android.graphics.RectF(r.left + border, r.top + border, r.right - border, r.bottom - border)
        canvas.drawRoundRect(inner, inner.height() / 2f, inner.height() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor })
        val bounds = android.graphics.Rect()
        text.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(label, r.centerX(), r.centerY() - bounds.exactCenterY(), text)
    }
    return bitmap
}

/** O autocarro de frente (o "directions_bus" do Material, 24×24): carroçaria, para-brisas, faróis e rodas. */
private const val BUS_SYMBOL_PATH =
    "M4,16c0,0.88 0.39,1.67 1,2.22V20c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h8v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1.78" +
        "c0.61,-0.55 1,-1.34 1,-2.22V6c0,-3.5 -3.58,-4 -8,-4s-8,0.5 -8,4v10z" +
        "M7.5,17c-0.83,0 -1.5,-0.67 -1.5,-1.5S6.67,14 7.5,14s1.5,0.67 1.5,1.5S8.33,17 7.5,17z" +
        "M16.5,17c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z" +
        "M18,11H6V6h12v5z"

/** Os elétricos da Carris (12E, 15E, 18E, 24E, 25E, 28E): a bolinha leva um elétrico em vez do autocarro. */
private fun carrisKind(route: String?, operator: String?): com.example.metrotime.data.regional.VehicleKind =
    if (operator.orEmpty().lowercase().let { it.isEmpty() || it == "carris" } && route.orEmpty().matches(Regex("\\d+E")))
        com.example.metrotime.data.regional.VehicleKind.TRAM
    else com.example.metrotime.data.regional.VehicleKind.BUS

private fun updateBusMarkers(map: MapLibreMap, buses: List<LiveBus>, context: android.content.Context, colorHex: String?, selectedId: String? = null) {
    val style = map.style ?: run {
        android.util.Log.e("BusMap", "map.style is NULL!")
        return
    }
    val source = style.getSourceAs<GeoJsonSource>("bus-vehicles-src") ?: run {
        android.util.Log.e("BusMap", "bus-vehicles-src source NOT FOUND!")
        return
    }

    try {
        val featureList = mutableListOf<org.maplibre.geojson.Feature>()
        for (bus in buses) {
            val lat = safeDouble(bus.lat)
            val lon = safeDouble(bus.lon)
            if (lat == 0.0 && lon == 0.0) continue

            val point = org.maplibre.geojson.Point.fromLngLat(lon, lat)
            val feature = org.maplibre.geojson.Feature.fromGeometry(point)
            // Pílula com o número na cor da linha (antes: o mesmo ícone amarelo para todas)
            val color = com.example.metrotime.data.bus.BusColors.normalize(colorHex)
                ?: com.example.metrotime.data.bus.BusColors.forRoute(bus.routeShortName, bus.operator)
            val selected = selectedId != null && bus.vehicleId == selectedId
            val kind = carrisKind(bus.routeShortName, bus.operator)
            val iconId = "bus-ball-${bus.routeShortName}-$color-$kind-$selected"
            if (style.getImage(iconId) == null) style.addImage(iconId, busBallBitmap(context, bus.routeShortName, color, selected, kind))
            feature.addStringProperty("icon", iconId)
            feature.addStringProperty("vehicleId", bus.vehicleId ?: "")
            feature.addBooleanProperty("selected", selected)
            feature.addNumberProperty("sort", if (selected) 1 else 0)
            feature.addStringProperty("routeShortName", bus.routeShortName ?: "")
            feature.addNumberProperty("bearing", safeDouble(bus.bearing))
            feature.addNumberProperty("speed", safeDouble(bus.speed))
            feature.addStringProperty("destination", bus.destination ?: "")
            feature.addStringProperty("status", bus.currentStatus ?: "")
            featureList.add(feature)
        }

        val fc = org.maplibre.geojson.FeatureCollection.fromFeatures(featureList)
        source.setGeoJson(fc)
    } catch (e: Exception) {
        android.util.Log.e("BusMap", "Error updating bus markers", e)
    }
}

private fun updateBusStops(map: MapLibreMap, stops: List<BusStop>, colorHex: String?) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>("bus-stops-src") ?: return
    colorHex?.let { com.example.metrotime.data.bus.BusColors.normalize(it) }?.let { color ->
        style.getLayerAs<CircleLayer>("bus-stops-layer")?.setProperties(circleStrokeColor(AndroidColor.parseColor(color)))
    }

    val features = JSONArray()
    for (stop in stops) {
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(stop.lon).put(stop.lat)))
                .put("properties", JSONObject()
                    .put("id", stop.id)
                    .put("name", stop.name)
                    .put("operator", stop.operator))
        )
    }

    source.setGeoJson(
        JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    )
}

private fun updateBusRouteLine(map: MapLibreMap, shape: List<Pair<Double, Double>>, colorHex: String?) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>("bus-route-src") ?: return
    colorHex?.let { com.example.metrotime.data.bus.BusColors.normalize(it) }?.let { color ->
        style.getLayerAs<LineLayer>("bus-route-layer")?.setProperties(lineColor(AndroidColor.parseColor(color)))
    }

    if (shape.isEmpty()) {
        source.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
        return
    }

    val coordsArr = JSONArray()
    for ((lat, lon) in shape) {
        coordsArr.put(JSONArray().put(lon).put(lat))
    }

    val geoJson = JSONObject()
        .put("type", "Feature")
        .put("geometry", JSONObject()
            .put("type", "LineString")
            .put("coordinates", coordsArr))
        .put("properties", JSONObject())
        .toString()

    source.setGeoJson(geoJson)
}

/**
 * Resolves Portuguese labels for bus directions based on line destinations.
 * Direction 0 = outbound ("Para [last endpoint]"), Direction 1 = inbound ("Para [first endpoint]").
 */
private fun getDirectionLabel(route: com.example.metrotime.data.bus.BusRoute, direction: Int): String {
    if (route.operator == "carris") {
        // Use the PRIMARY variant's long name to derive direction labels
        try {
            val primaryRoute = com.example.metrotime.data.bus.CarrisGtfsData.routes
                .filter { it.value.shortName == route.shortName }
                .values.sortedBy { it.id }.firstOrNull()
            if (primaryRoute != null) {
                val parts = primaryRoute.longName.split(" - ").map { it.trim() }
                if (parts.size >= 2) {
                    return if (direction == 0) "Para ${parts.last()}" else "Para ${parts.first()}"
                }
            }
        } catch (_: Exception) {}
    }

    val parts = route.longName.split(" - ")
    if (parts.size >= 2) {
        return if (direction == 0) "Para ${parts.last().trim()}" else "Para ${parts.first().trim()}"
    }
    return if (direction == 0) "Sentido Ida" else "Sentido Volta"
}
