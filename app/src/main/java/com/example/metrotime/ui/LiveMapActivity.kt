package com.example.metrotime.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start background service to ensure notifications and location sharing work without widgets
        com.example.metrotime.work.WidgetUpdateService.requestFetch(this)

        requestPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)

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
                    setupMapLayers(style)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Wire up train tap
                val tapLayerIds: Array<String> =
                    LINE_NAMES.map { "trains-layer-$it" }.toTypedArray()
                map.addOnMapClickListener { point ->
                    val screen = map.projection.toScreenLocation(point)
                    for (layerId in tapLayerIds) {
                        val hits = map.queryRenderedFeatures(screen, layerId)
                        if (hits.isNotEmpty()) {
                            val trainId = hits[0].getStringProperty("trainId") ?: continue
                            val tapped = viewModel.trains.value.find { t -> t.id == trainId }
                            viewModel.selectTrain(tapped)
                            return@addOnMapClickListener true
                        }
                    }
                    
                    val stationHits = map.queryRenderedFeatures(screen, "stations-layer")
                    if (stationHits.isNotEmpty()) {
                        val stationId = stationHits[0].getStringProperty("id") ?: return@addOnMapClickListener false
                        val stationName = stationHits[0].getStringProperty("name") ?: stationId
                        viewModel.selectStation(stationId, stationName)
                        return@addOnMapClickListener true
                    }
                    
                    viewModel.selectTrain(null)
                    viewModel.selectStation(null)
                    false
                }
            }
        }

        // 4. Observe trains and update map markers
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.trains.collect { trains ->
                        if (styleReady) {
                            mapLibreMap?.let { updateTrainMarkers(it, trains) }
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
            }
        }

        // 5. Set Compose content — Compose just provides the overlay UI
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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

    override fun onStart()    { super.onStart();    mapView.onStart() }
    override fun onResume()   { super.onResume();   mapView.onResume(); viewModel.startLocationTracking() }
    override fun onPause()    { super.onPause();    mapView.onPause(); viewModel.stopLocationTracking() }
    override fun onStop()     { super.onStop();     mapView.onStop() }
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

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Top Bar ────────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
            color = DarkBackground.copy(alpha = 0.90f),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("Live Map", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Loading spinner ────────────────────────────────────────────────────
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // ── Train / Station info bottom sheets ─────────────────────────────────
        val safeTrain: TrainPosition? = selectedTrain
        val safeStation = selectedStation

        AnimatedVisibility(
            visible = safeTrain != null,
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
            visible = safeStation != null,
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
                    Text(
                        "⚠️ Tempos estimados (API indisponível)",
                        color = Color(0xFFF59E0B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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

private fun setupMapLayers(style: Style) {
    val coords    = MapConfiguration.STATION_COORDS
    val lineOrder = MapConfiguration.LINE_STATION_ORDER
    val empty     = """{"type":"FeatureCollection","features":[]}"""

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
