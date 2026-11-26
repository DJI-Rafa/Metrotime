package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.data.plan.WalkRouter
import com.example.metrotime.data.plan.WalkStep
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A pé até à paragem ou estação: o caminho pelas ruas desde onde estás (ou desde [EXTRA_FROM_LAT]), com a tua
 * posição a andar no mapa, quanto tempo demora e cada viragem com o nome da rua.
 */
class WalkRouteActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TO_LAT = "to_lat"
        private const val EXTRA_TO_LON = "to_lon"
        private const val EXTRA_TO_NAME = "to_name"
        private const val EXTRA_FROM_LAT = "from_lat"
        private const val EXTRA_FROM_LON = "from_lon"
        private const val EXTRA_NOTE = "note"
        private const val STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

        /** [note]: o que vem a seguir ("A Linha Verde parte às 08:09"). Sem partida, usa a tua localização. */
        fun newIntent(
            context: Context, toLat: Double, toLon: Double, toName: String,
            fromLat: Double? = null, fromLon: Double? = null, note: String? = null
        ): Intent = Intent(context, WalkRouteActivity::class.java).apply {
            putExtra(EXTRA_TO_LAT, toLat)
            putExtra(EXTRA_TO_LON, toLon)
            putExtra(EXTRA_TO_NAME, toName)
            if (fromLat != null && fromLon != null) {
                putExtra(EXTRA_FROM_LAT, fromLat)
                putExtra(EXTRA_FROM_LON, fromLon)
            }
            note?.let { putExtra(EXTRA_NOTE, it) }
        }
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val route = mutableStateOf<WalkRouter.Route?>(null)
    private val loading = mutableStateOf(true)
    private val problem = mutableStateOf<String?>(null)

    private val to by lazy { intent.getDoubleExtra(EXTRA_TO_LAT, 0.0) to intent.getDoubleExtra(EXTRA_TO_LON, 0.0) }
    private val toName by lazy { intent.getStringExtra(EXTRA_TO_NAME) ?: "o destino" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warmUpLocation(applicationContext)
        MapLibre.getInstance(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(to.first, to.second)).zoom(15.5).build()
            m.setStyle(Style.Builder().fromUri(STYLE)) { s ->
                style = s
                setupLayers(s)
                showMyLocation(m, s)
                route.value?.let { draw(it) }
            }
        }
        val note = intent.getStringExtra(EXTRA_NOTE)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(DarkBackground)) {
                    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                    WalkTopBar(toName, note, onBack = { finish() })
                    WalkPanel(
                        route = route.value, loading = loading.value, problem = problem.value,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onRefresh = { load() }
                    )
                }
            }
        }
        load()
    }

    /** O caminho desde a partida dada ou desde onde estás agora. */
    private fun load() {
        loading.value = true
        problem.value = null
        lifecycleScope.launch {
            val from = if (intent.hasExtra(EXTRA_FROM_LAT)) {
                intent.getDoubleExtra(EXTRA_FROM_LAT, 0.0) to intent.getDoubleExtra(EXTRA_FROM_LON, 0.0)
            } else currentLocation(this@WalkRouteActivity)
            if (from == null) {
                problem.value = "Não foi possível saber onde estás. Liga a localização para ver o caminho."
                loading.value = false
                return@launch
            }
            val r = WalkRouter.route(from, to, toName)
            route.value = r
            loading.value = false
            draw(r)
        }
    }

    private fun setupLayers(s: Style) {
        if (s.getSource("walk-route-src") != null) return
        s.addSource(GeoJsonSource("walk-route-src", FeatureCollection.fromFeatures(emptyList())))
        s.addLayer(
            LineLayer("walk-route-casing", "walk-route-src").withProperties(
                lineColor(AndroidColor.parseColor("#0B2E66")),
                lineWidth(9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.55f)
            )
        )
        s.addLayer(
            LineLayer("walk-route-line", "walk-route-src").withProperties(
                lineColor(AndroidColor.parseColor("#3B82F6")),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineDasharray(arrayOf(0.2f, 1.8f))
            )
        )
        s.addSource(GeoJsonSource("walk-dest-src", Feature.fromGeometry(Point.fromLngLat(to.second, to.first))))
        s.addLayer(
            CircleLayer("walk-dest-layer", "walk-dest-src").withProperties(
                circleRadius(9f),
                circleColor(AndroidColor.WHITE),
                circleStrokeWidth(2.5f),
                circleStrokeColor(AndroidColor.parseColor("#333333"))
            )
        )
    }

    private fun draw(r: WalkRouter.Route) {
        val s = style ?: return
        val m = map ?: return
        val line = LineString.fromLngLats(r.path.map { Point.fromLngLat(it.second, it.first) })
        s.getSourceAs<GeoJsonSource>("walk-route-src")?.setGeoJson(Feature.fromGeometry(line))
        if (r.path.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(r.path.map { LatLng(it.first, it.second) }).build()
            val d = resources.displayMetrics.density
            val bottom = (resources.displayMetrics.heightPixels * 0.42).toInt()
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * d).toInt(), (110 * d).toInt(), (40 * d).toInt(), bottom), 700)
        }
    }

    /** O ponto azul (a tua posição a andar), como no mapa em direto. */
    private fun showMyLocation(m: MapLibreMap, s: Style) {
        if (!hasLocationPermission(this)) return
        try {
            val c = m.locationComponent
            if (!c.isLocationComponentActivated) {
                val blue = 0xFF1A73E8.toInt()
                val options = org.maplibre.android.location.LocationComponentOptions.builder(this)
                    .foregroundTintColor(blue)
                    .backgroundTintColor(AndroidColor.WHITE)
                    .accuracyColor(blue)
                    .accuracyAlpha(0.15f)
                    .pulseEnabled(false)
                    .build()
                c.activateLocationComponent(
                    org.maplibre.android.location.LocationComponentActivationOptions.Builder(this, s)
                        .locationComponentOptions(options)
                        .useDefaultLocationEngine(true)
                        .useSpecializedLocationLayer(true)
                        .build()
                )
            }
            c.isLocationComponentEnabled = true
            c.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}

@Composable
private fun WalkTopBar(toName: String, note: String?, onBack: () -> Unit) {
    Row(
        Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Color(0xF01E1F23)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text("A pé até $toName", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun WalkPanel(route: WalkRouter.Route?, loading: Boolean, problem: String?, modifier: Modifier, onRefresh: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = 420.dp),
        color = Color(0xFF1C1C20),
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray.copy(alpha = 0.5f)))
            }
            when {
                problem != null -> Text(problem, color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp))
                loading && route == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MetrotimeBlue, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                }
                route != null -> {
                    val minutes = ((route.seconds + 59) / 60).coerceAtLeast(1)
                    val arrive = LocalTime.now(ZoneId.of("Europe/Lisbon")).plusSeconds(route.seconds.toLong()).format(HM)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MetrotimeBlue.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(WalkIcon, contentDescription = null, tint = MetrotimeBlue, modifier = Modifier.size(22.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$minutes min · ${TripPlanner.distanceText(route.metres)}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Chegas às $arrive" + if (!route.streets) " · em linha reta" else "", color = Color(0xFF6FCF73), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Atualizar desde onde estás", tint = TextSecondaryDark)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        route.steps.forEach { step -> WalkStepRow(step) }
                    }
                    Text(
                        if (route.streets) "Caminho pelas ruas do OpenStreetMap · a tua posição atualiza-se no mapa"
                        else "Sem o caminho pelas ruas: a direção em linha reta",
                        color = TextSecondaryDark.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, end = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkStepRow(step: WalkStep) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF2A2C31)), contentAlignment = Alignment.Center) {
            when (step.turn) {
                WalkStep.Turn.ARRIVE -> Icon(Icons.Filled.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                WalkStep.Turn.ROUNDABOUT -> Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                else -> Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(18.dp).rotate(
                        when (step.turn) {
                            WalkStep.Turn.RIGHT -> 0f
                            WalkStep.Turn.SLIGHT_RIGHT -> -45f
                            WalkStep.Turn.SHARP_RIGHT -> 45f
                            WalkStep.Turn.LEFT -> 180f
                            WalkStep.Turn.SLIGHT_LEFT -> -135f
                            WalkStep.Turn.SHARP_LEFT -> 135f
                            WalkStep.Turn.UTURN -> 90f
                            else -> -90f
                        }
                    )
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(step.text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (step.metres >= 1) Text(TripPlanner.distanceText(step.metres), color = TextSecondaryDark, fontSize = 12.sp)
        }
    }
}
