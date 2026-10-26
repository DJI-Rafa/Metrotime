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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
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
import com.example.metrotime.data.TrainPosition
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.launch
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startLocationTracking()
        }
        // O serviço só arranca depois da resposta: com o tipo "location" sem permissão o Android 14
        // lançava SecurityException e fechava a app
        com.example.metrotime.work.WidgetUpdateService.requestFetch(this, fromUi = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    updateTrainMarkers(map, viewModel.trains.value)
                    updateFriendMarkers(this@LiveMapActivity, map, viewModel.friendLocations.value)
                    val routeColor = viewModel.selectedBusRoute.value?.color
                    updateBusMarkers(map, viewModel.liveBuses.value, this@LiveMapActivity, routeColor, viewModel.selectedLiveBus.value?.bus?.vehicleId)
                    updateBusStops(map, viewModel.busRouteStops.value, routeColor)
                    updateBusRouteLine(map, viewModel.busRouteShape.value, routeColor)
                    updateAllBusStops(map, viewModel.allBusStops.value)
                    updateCpTrains(this@LiveMapActivity, map, viewModel.cpTrains.value, viewModel.selectedCpNumber.value)
                    applyMapFilters(map, viewModel.mapFilters.value)
                    // Rede ferroviária (traçados reais) carregada fora da thread principal
                    lifecycleScope.launch {
                        val network = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { buildCpNetwork(this@LiveMapActivity) }.getOrNull()
                        }
                        if (network != null && styleReady) applyCpNetwork(map, network)
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

                // Wire up train tap
                val tapLayerIds: Array<String> =
                    LINE_NAMES.map { "trains-layer-$it" }.toTypedArray()
                map.addOnMapClickListener { point ->
                    val screen = map.projection.toScreenLocation(point)
                    // Comboios CP primeiro (ficam por cima de tudo); a área de toque é a etiqueta
                    val touch = android.graphics.RectF(screen.x - 24f, screen.y - 24f, screen.x + 24f, screen.y + 24f)
                    val cpHits = map.queryRenderedFeatures(touch, "cp-trains")
                    if (cpHits.isNotEmpty()) {
                        cpHits[0].getStringProperty("number")?.toIntOrNull()?.let { viewModel.selectCpTrain(it) }
                        return@addOnMapClickListener true
                    }
                    for (layerId in tapLayerIds) {
                        val hits = map.queryRenderedFeatures(screen, layerId)
                        if (hits.isNotEmpty()) {
                            val trainId = hits[0].getStringProperty("trainId") ?: continue
                            val tapped = viewModel.trains.value.find { t -> t.id == trainId }
                            viewModel.selectTrain(tapped)
                            return@addOnMapClickListener true
                        }
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
                    val stationHits = map.queryRenderedFeatures(screen, "stations-layer")
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

                    
                    viewModel.selectTrain(null)
                    viewModel.selectStation(null)
                    viewModel.clearSelectedBusStop()
                    viewModel.selectCpTrain(null)
                    false
                }
            }
        }

        // 4. Observe trains, friends, and buses — update map markers
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var lastTrainMarkerUpdateAt = 0L
                    var lastTrainMarkerSignature = ""
                    viewModel.trains.collect { trains ->
                        if (styleReady) {
                            val now = SystemClock.elapsedRealtime()
                            val signature = trains.joinToString("|") { "${it.id}:${it.nextStationId}:${it.isGhost}" }
                            if (signature != lastTrainMarkerSignature || now - lastTrainMarkerUpdateAt >= 3_000L) {
                                lastTrainMarkerSignature = signature
                                lastTrainMarkerUpdateAt = now
                                mapLibreMap?.let { updateTrainMarkers(it, trains) }
                            }
                        }
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
                    viewModel.allBusStops.collect { allStops ->
                        if (styleReady) {
                            mapLibreMap?.let { updateAllBusStops(it, allStops) }
                        }
                    }
                }
                // Comboios CP: etiquetas, percurso do comboio aberto, filtros e pedidos de câmara
                launch {
                    kotlinx.coroutines.flow.combine(viewModel.cpTrains, viewModel.selectedCpNumber) { t, s -> t to s }
                        .collect { (trains, selected) ->
                            if (styleReady) mapLibreMap?.let { updateCpTrains(this@LiveMapActivity, it, trains, selected) }
                        }
                }
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
    override fun onResume()   { super.onResume();   mapView.onResume(); viewModel.startLocationTracking() }
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
    val selectedTrain by viewModel.selectedTrain.collectAsState()
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
                // Filtros: escolher o que aparece no mapa
                MapFilterBar(
                    filters = mapFilters,
                    cpServices = cpServicesPresent,
                    cpCount = cpTrains.size,
                    onChange = { viewModel.updateMapFilters(it) }
                )
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
                        if (arrival.realtime && vehicleId != null) {
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

        // ── Train / Station info bottom sheets ─────────────────────────────────
        val safeTrain: TrainPosition? = selectedTrain
        val safeStation = selectedStation

        AnimatedVisibility(
            visible = safeTrain != null && !isBusMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (safeTrain != null) {
                TrainInfoSheet(
                    train = safeTrain,
                    onDismiss = { viewModel.selectTrain(null) }
                )
            }
        }

        AnimatedVisibility(
            visible = safeStation != null && !isBusMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (safeStation != null) {
                StationInfoSheet(
                    stationState = safeStation,
                    onDismiss = { viewModel.selectStation(null) }
                )
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

@Composable
private fun LiveBusDetailSheet(
    state: LiveMapViewModel.SelectedLiveBusState,
    routeColor: String?,
    onDismiss: () -> Unit
) {
    val bus = state.bus
    val lineHex = routeColor ?: com.example.metrotime.data.bus.BusColors.forRoute(bus.routeShortName, bus.operator)
    val color = busColor(lineHex) // destaques do painel na cor da linha (antes: amarelo fixo)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .navigationBarsPadding(),
        color = Color(0xFF1C1C20),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(40.dp).height(4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))

            // Bus header
            Row(verticalAlignment = Alignment.CenterVertically) {
                BusLineBadge(bus.routeShortName, lineHex, height = 32.dp, fontSize = 17.sp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        bus.destination.ifEmpty { "Autocarro" },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // Só o número ("[BNA17]1252" → "1252")
                            "Veículo ${bus.vehicleId.substringAfterLast("]").substringAfter("|")}",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                        val statusText = when (bus.currentStatus) {
                            "STOPPED_AT" -> " • Parado"
                            "INCOMING_AT" -> " • A chegar"
                            "IN_TRANSIT_TO" -> " • Em trânsito"
                            else -> ""
                        }
                        if (statusText.isNotEmpty()) {
                            Text(statusText, color = color, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = color, modifier = Modifier.size(24.dp))
                }
            } else if (state.schedule.isEmpty()) {
                Text(
                    "Horário não disponível para este autocarro.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Text(
                    "Próximas paragens",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))

                // Find the index of the current stop
                val currentIdx = state.schedule.indexOfFirst { !it.isPassed }

                // Scrollable list of stops
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.schedule.forEachIndexed { index, stop ->
                        val isCurrent = index == currentIdx
                        val isPassed = stop.isPassed

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min) // Force minimum intrinsic height so child Column can fill it
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Timeline indicator
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(24.dp)
                                    .fillMaxHeight()
                            ) {
                                // Upper line
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .weight(1f)
                                        .background(
                                            if (index == 0) Color.Transparent
                                            else if (isPassed || isCurrent) Color.Gray.copy(alpha = 0.4f)
                                            else color
                                        )
                                )
                                // Circle dot
                                Box(
                                    modifier = Modifier
                                        .size(if (isCurrent) 14.dp else 10.dp)
                                        .background(
                                            when {
                                                isCurrent -> color
                                                isPassed -> Color.Gray.copy(alpha = 0.5f)
                                                else -> color
                                            },
                                            RoundedCornerShape(50)
                                        )
                                )
                                // Lower line
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .weight(1f)
                                        .background(
                                            if (index == state.schedule.size - 1) Color.Transparent
                                            else if (isPassed) Color.Gray.copy(alpha = 0.4f)
                                            else color
                                        )
                                )
                            }

                            Spacer(Modifier.width(10.dp))

                            // Stop name
                            Text(
                                stop.stopName,
                                color = when {
                                    isCurrent -> Color.White
                                    isPassed -> Color.Gray.copy(alpha = 0.5f)
                                    else -> Color.White.copy(alpha = 0.9f)
                                },
                                fontSize = if (isCurrent) 16.sp else 15.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Arrival time
                            Text(
                                stop.arrivalTime,
                                color = when {
                                    isCurrent -> color
                                    isPassed -> Color.Gray.copy(alpha = 0.4f)
                                    else -> Color.LightGray
                                },
                                fontSize = if (isCurrent) 16.sp else 15.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
            ) { Text("Voltar") }
            Spacer(Modifier.height(8.dp))
        }
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
    var selected by remember(stop.id) { mutableStateOf(setOf<String>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); now = System.currentTimeMillis() } }

    val lines = remember(state.arrivals) {
        state.arrivals.map { it.routeShortName to it.color }.distinctBy { it.first }
            .sortedWith(compareBy({ it.first.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it.first }))
    }
    // Uma linha por (número, destino): a próxima chegada e, por baixo, a seguinte
    val groups = remember(state.arrivals, selected) {
        state.arrivals
            .filter { selected.isEmpty() || it.routeShortName in selected }
            .groupBy { it.routeShortName to it.destination }
            .values.map { it.sortedBy { a -> a.etaMinutes } }
            .sortedBy { it.first().etaMinutes }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .navigationBarsPadding(),
        color = DarkBackground,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp).height(4.dp)
                    .background(Color(0xFF4A4D53), RoundedCornerShape(2.dp))
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BusStopSign(40.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stop.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (stop.operator == "carris_metropolitana") "Paragem da Carris Metropolitana" else "Paragem da Carris",
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

            if (lines.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mostrar linhas", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val allOn = selected.isEmpty()
                        Box(
                            modifier = Modifier
                                .height(34.dp)
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
                                    .height(34.dp)
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
                    groups.isEmpty() -> Text(
                        "Nenhum autocarro a caminho desta paragem.",
                        color = TextSecondaryDark,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    else -> Surface(color = CardBackground, shape = RoundedCornerShape(14.dp)) {
                        Column {
                            groups.forEachIndexed { i, group ->
                                val next = group.first()
                                if (i > 0) Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(1.dp).background(Color(0xFF3A3D42)))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onArrivalSelected(next) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BusLineBadge(next.routeShortName, next.color)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("para", color = TextSecondaryDark, fontSize = 12.sp)
                                        Text(next.destination, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // Tempo real (minutos, sinal de GPS e atraso) ou previsto (pelo horário)
                                    Column(horizontalAlignment = Alignment.End) {
                                        BusEta(next)
                                        BusStatusLine(next)
                                    }
                                }
                            }
                        }
                    }
                }
                if (groups.isNotEmpty()) {
                    Text("Toca num autocarro para ver onde está.", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}


@Composable
private fun StationInfoSheet(stationState: LiveMapViewModel.SelectedStationState, onDismiss: () -> Unit) {
    val stationLines = remember(stationState.id) {
        MapConfiguration.LINE_STATION_ORDER.filter { it.value.contains(stationState.id) }.keys.toList()
    }
    var selectedTab by remember(stationState.id) { mutableStateOf(stationLines.firstOrNull() ?: "") }

    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = Color(0xFF1C1C20),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Box(
                modifier = Modifier
                    .width(40.dp).height(4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            
            Text(stationState.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            
            if (stationLines.size > 1) {
                // Interface station: render clickable chips
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stationLines.forEach { line ->
                        val lineColor = when (line) {
                            "Azul"     -> LineAzul
                            "Amarela"  -> LineAmarela
                            "Verde"    -> LineVerde
                            "Vermelha" -> LineVermelha
                            else       -> Color.Gray
                        }
                        val isSelected = selectedTab == line
                        Box(
                            modifier = Modifier
                                .background(if (isSelected) lineColor else Color.Transparent, RoundedCornerShape(16.dp))
                                .border(1.dp, lineColor, RoundedCornerShape(16.dp))
                                .clickable { selectedTab = line }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Linha $line", color = if (isSelected) Color.White else lineColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            } else if (stationLines.isNotEmpty()) {
                // Single line station
                val line = stationLines.first()
                val lineColor = when (line) {
                    "Azul"     -> LineAzul
                    "Amarela"  -> LineAmarela
                    "Verde"    -> LineVerde
                    "Vermelha" -> LineVermelha
                    else       -> Color.Gray
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(CircleIcon, contentDescription = null, tint = lineColor, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Linha $line", color = lineColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            
            if (stationState.loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
            } else {
                val arrivals = stationState.arrivals?.arrivals?.filter { it.line == selectedTab || selectedTab.isEmpty() } ?: emptyList()
                val isEstimated = stationState.arrivals?.isEstimated ?: false
                
                if (isEstimated) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(Color(0xFF4A3500), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Tempos estimados (API indisponível)",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (arrivals.isEmpty()) {
                    Text("Não há comboios previstos para esta linha.", color = Color.LightGray, fontSize = 14.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        arrivals.sortedBy { it.minutes }.take(4).forEach { arr ->
                            val mins = arr.minutes / 60
                            val secs = arr.minutes % 60
                            val timeStr = when {
                                arr.minutes <= 15 -> "Na estação."
                                arr.minutes <= 30 -> "A chegar!"
                                else -> "%02d:%02d".format(mins, secs)
                            }
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2A2A30), RoundedCornerShape(12.dp))
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Sentido", color = Color.Gray, fontSize = 12.sp)
                                    Text(arr.direction, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    text = timeStr,
                                    color = if (arr.minutes <= 60) Color(0xFFFF5252) else Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Fechar") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TrainInfoSheet(train: TrainPosition, onDismiss: () -> Unit) {
    val lineColor = when (train.line) {
        "Azul"     -> LineAzul
        "Amarela"  -> LineAmarela
        "Verde"    -> LineVerde
        "Vermelha" -> LineVermelha
        else       -> Color.Gray
    }
    
    val minutes   = train.secondsToNextStation / 60
    val seconds   = train.secondsToNextStation % 60
    
    val timeStr = when {
        train.secondsToNextStation <= 15 -> "Na estação."
        train.secondsToNextStation <= 30 -> "A chegar!"
        else -> "%02d:%02d".format(minutes, seconds)
    }
    
    val timeColor = if (train.secondsToNextStation <= 60) Color(0xFFFF5252) else Color.White

    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = Color(0xFF1C1C20),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Box(
                modifier = Modifier
                    .width(40.dp).height(4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            val topTitle = if (train.isGhost) "Comboio (Sem Sinal): ${train.id}" else "Comboio: ${train.id}"
            Text(topTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CircleIcon, contentDescription = null, tint = lineColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Linha: ${train.line}", color = lineColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Destino: ${train.destination}",             color = Color.White,     fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("Próxima Estação: ${train.nextStationName}", color = Color.LightGray, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Tempo Restante: $timeStr", color = timeColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Fechar") }
            Spacer(Modifier.height(8.dp))
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

private fun createArrowBitmap(color: Int, isGhost: Boolean = false): Bitmap {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
        if (isGhost) alpha = 150 // Semi-transparent for ghost
    }
    
    // Draw an arrow pointing UP (0 degrees bearing)
    val path = Path().apply {
        moveTo(size / 2f, 0f)                   // Top tip
        lineTo(size.toFloat() - 10f, size.toFloat())  // Bottom right
        lineTo(size / 2f, size * 0.7f)          // Bottom center intent
        lineTo(10f, size.toFloat())             // Bottom left
        close()
    }
    canvas.drawPath(path, paint)

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (isGhost) AndroidColor.RED else AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, strokePaint)

    if (isGhost) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = AndroidColor.RED
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("?", size / 2f, size * 0.6f, textPaint)
    }

    return bitmap
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

    // ── Station dots ───────────────────────────────────────────────────────────
    if (style.getSource("stations-src") == null) {
        val stationFeatures = JSONArray()
        for ((id, coord) in coords) {
            stationFeatures.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(coord.lon).put(coord.lat)))
                    .put("properties", JSONObject()
                        .put("id", id)
                        .put("name", coord.name))
            )
        }
        val stationGeoJson = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", stationFeatures)
            .toString()

        style.addSource(GeoJsonSource("stations-src", stationGeoJson))
        style.addLayer(
            CircleLayer("stations-layer", "stations-src").withProperties(
                circleRadius(5f),
                circleColor(AndroidColor.WHITE),
                circleStrokeWidth(1.5f),
                circleStrokeColor(AndroidColor.parseColor("#333333"))
            )
        )
    }

    // Add generic white arrows
    style.addImage("arrow-white", createArrowBitmap(AndroidColor.WHITE, false))
    style.addImage("arrow-ghost", createArrowBitmap(AndroidColor.WHITE, true))

    // ── Per-line train layers (empty initially) ────────────────────────────────
    for (lineName in LINE_NAMES) {
        if (style.getSource("trains-src-$lineName") == null) {
            style.addSource(GeoJsonSource("trains-src-$lineName", empty))
            style.addLayer(
                SymbolLayer("trains-layer-$lineName", "trains-src-$lineName").withProperties(
                    iconImage(
                        org.maplibre.android.style.expressions.Expression.switchCase(
                            org.maplibre.android.style.expressions.Expression.eq(get("isGhost"), org.maplibre.android.style.expressions.Expression.literal(true)),
                            org.maplibre.android.style.expressions.Expression.literal("arrow-ghost"),
                            org.maplibre.android.style.expressions.Expression.literal("arrow-white")
                        )
                    ),
                    iconRotate(get("bearing")),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconSize(
                        org.maplibre.android.style.expressions.Expression.interpolate(
                            org.maplibre.android.style.expressions.Expression.linear(),
                            org.maplibre.android.style.expressions.Expression.zoom(),
                            org.maplibre.android.style.expressions.Expression.stop(10f, 0.4f),
                            org.maplibre.android.style.expressions.Expression.stop(14f, 1.0f)
                        )
                    )
                )
            )
        }
    }

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
                    iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM),
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

    // ── Comboios CP e percurso do comboio aberto (por cima de tudo) ────────────
    setupCpTopLayers(context, style)
}

/**
 * Render all Carris bus stops on the map as subtle white dots.
 */
private fun updateAllBusStops(map: MapLibreMap, stops: List<com.example.metrotime.data.bus.BusStop>) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>("all-stops-src") ?: return

    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            android.util.Log.d("BusMap", "updateAllBusStops called with ${stops.size} stops")
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
            val fc = org.maplibre.geojson.FeatureCollection.fromFeatures(featureList)
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                source.setGeoJson(fc)
                android.util.Log.d("BusMap", "updateAllBusStops: setGeoJson with ${featureList.size} features")
            }
        } catch (e: Exception) {
            android.util.Log.e("BusMap", "Error updating all bus stops", e)
        }
    }
}

private fun updateTrainMarkers(map: MapLibreMap, trains: List<TrainPosition>) {
    val style  = map.style ?: return
    val byLine = trains.groupBy { it.line }

    for (lineName in LINE_NAMES) {
        val source     = style.getSourceAs<GeoJsonSource>("trains-src-$lineName") ?: continue
        val lineTrains = byLine[lineName] ?: emptyList()

        val features = JSONArray()
        for (train in lineTrains) {
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(train.lon).put(train.lat)))
                    .put("properties", JSONObject()
                        .put("trainId",     train.id)
                        .put("line",        train.line)
                        .put("destination", train.destination)
                        .put("nextStation", train.nextStationName)
                        .put("bearing",     train.bearing)
                        .put("seconds",     train.secondsToNextStation)
                        .put("isGhost",     train.isGhost))
            )
        }

        val collection = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()

        source.setGeoJson(collection)
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
private fun busPillBitmap(context: android.content.Context, number: String, colorHex: String): Bitmap {
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
            val iconId = "bus-pin-${bus.routeShortName}-$color"
            if (style.getImage(iconId) == null) style.addImage(iconId, busPillBitmap(context, bus.routeShortName, color))
            feature.addStringProperty("icon", iconId)
            feature.addStringProperty("vehicleId", bus.vehicleId ?: "")
            val selected = selectedId != null && bus.vehicleId == selectedId
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
