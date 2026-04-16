package com.example.metrotime.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.TrainPosition
import com.example.metrotime.ui.theme.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject

class LiveMapActivity : ComponentActivity() {

    private val viewModel: LiveMapViewModel by viewModels()
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                    LiveMapScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                        onMapViewCreated = { mv -> mapView = mv }
                    )
                }
            }
        }
    }

    override fun onStart()    { super.onStart();    mapView?.onStart() }
    override fun onResume()   { super.onResume();   mapView?.onResume() }
    override fun onPause()    { super.onPause();    mapView?.onPause() }
    override fun onStop()     { super.onStop();     mapView?.onStop() }
    override fun onLowMemory(){ super.onLowMemory();mapView?.onLowMemory() }
    override fun onDestroy()  { super.onDestroy();  mapView?.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Composable UI
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun LiveMapScreen(
    viewModel: LiveMapViewModel,
    onBack: () -> Unit,
    onMapViewCreated: (MapView) -> Unit
) {
    val trains       by viewModel.trains.collectAsStateWithLifecycle()
    val selectedTrain by viewModel.selectedTrain.collectAsStateWithLifecycle()
    val isLoading    by viewModel.isLoading.collectAsStateWithLifecycle()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }

    // Keep a stable reference so the click handler sees the current trains list
    val trainsRef = rememberUpdatedState<List<TrainPosition>>(trains)

    Box(modifier = Modifier.fillMaxSize()) {

        // ── MapView ────────────────────────────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).also { mv ->
                    onMapViewCreated(mv)
                    mv.getMapAsync { map ->
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

                        map.setStyle(
                            Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")
                        ) { style ->
                            styleLoaded = true
                            setupMapLayers(style)
                            // Wire up taps
                            val tapLayerIds: Array<String> = LINE_NAMES.map { "trains-layer-$it" }.toTypedArray()
                            map.addOnMapClickListener { point ->
                                val screen = map.projection.toScreenLocation(point)
                                for (layerId in tapLayerIds) {
                                    val hits = map.queryRenderedFeatures(screen, layerId)
                                    if (hits.isNotEmpty()) {
                                        val id = hits[0].getStringProperty("trainId")
                                        if (id != null) {
                                            val tapped: TrainPosition? = trainsRef.value.find { t -> t.id == id }
                                            viewModel.selectTrain(tapped)
                                            return@addOnMapClickListener true
                                        }
                                    }
                                }
                                false
                            }
                        }
                    }
                }
            }
        )

        // Update train marker positions whenever trains state or style readiness changes
        LaunchedEffect(trains, styleLoaded) {
            val map = mapLibreMap ?: return@LaunchedEffect
            if (styleLoaded) updateTrainMarkers(map, trains)
        }

        // ── Top Bar ────────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
            color = DarkBackground.copy(alpha = 0.92f),
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

        // ── Loading ────────────────────────────────────────────────────────────
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color.White) }
        }

        // ── Bottom Info Sheet ──────────────────────────────────────────────────
        val currentTrain: TrainPosition? = selectedTrain
        AnimatedVisibility(
            visible = currentTrain != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val safeTrain = currentTrain
            if (safeTrain != null) {
                TrainInfoSheet(train = safeTrain, onDismiss = { viewModel.selectTrain(null) })
            }
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
    val minutes = train.secondsToNextStation / 60
    val seconds = train.secondsToNextStation % 60
    val timeStr   = if (train.secondsToNextStation <= 30) "A chegar!" else "%02d:%02d".format(minutes, seconds)
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
            Text("Comboio", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Circle, contentDescription = null, tint = lineColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Linha: ${train.line}", color = lineColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Destino: ${train.destination}",          color = Color.White,     fontSize = 15.sp)
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
// Map Layer Helpers (non-Composable)
// ──────────────────────────────────────────────────────────────────────────────

private val LINE_NAMES = listOf("Azul", "Amarela", "Verde", "Vermelha")

private fun lineColorInt(lineName: String): Int = when (lineName) {
    "Azul"     -> AndroidColor.parseColor("#0077C8")
    "Amarela"  -> AndroidColor.parseColor("#FFD500")
    "Verde"    -> AndroidColor.parseColor("#009E60")
    "Vermelha" -> AndroidColor.parseColor("#ED1C24")
    else       -> AndroidColor.WHITE
}

private fun setupMapLayers(style: Style) {
    val coords    = MapConfiguration.STATION_COORDS
    val lineOrder = MapConfiguration.LINE_STATION_ORDER
    val empty     = """{"type":"FeatureCollection","features":[]}"""

    // ── Metro route lines ──────────────────────────────────────────────────────
    for ((lineName, stationIds) in lineOrder) {
        val coords2 = JSONArray()
        for (id in stationIds) {
            val c = coords[id] ?: continue
            coords2.put(JSONArray().put(c.lon).put(c.lat))
        }
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords2))
            .put("properties", JSONObject())
            .toString()

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

    // ── Station dots ───────────────────────────────────────────────────────────
    val stationFeatures = JSONArray()
    for ((id, coord) in coords) {
        stationFeatures.put(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(coord.lon).put(coord.lat)))
                .put("properties", JSONObject().put("id", id).put("name", coord.name))
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

    // ── Per-line train layers (empty initially) ────────────────────────────────
    for (lineName in LINE_NAMES) {
        style.addSource(GeoJsonSource("trains-src-$lineName", empty))
        style.addLayer(
            CircleLayer("trains-layer-$lineName", "trains-src-$lineName").withProperties(
                circleRadius(9f),
                circleColor(lineColorInt(lineName)),
                circleStrokeWidth(2.5f),
                circleStrokeColor(AndroidColor.WHITE)
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
                        .put("seconds",     train.secondsToNextStation))
            )
        }

        val collection = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()

        source.setGeoJson(collection)
    }
}
