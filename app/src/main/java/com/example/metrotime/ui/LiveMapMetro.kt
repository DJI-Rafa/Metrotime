package com.example.metrotime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.alerts.MetroLineStatus
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.metro.MetroLineTrack
import com.example.metrotime.data.metro.MetroMotion
import com.example.metrotime.data.metro.MetroNight
import com.example.metrotime.data.metro.MetroSmoother
import com.example.metrotime.data.metro.MetroTrain
import com.example.metrotime.data.plan.MetroTrip
import com.example.metrotime.ui.theme.CardBackground
import com.example.metrotime.ui.theme.LineAmarela
import com.example.metrotime.ui.theme.LineAzul
import com.example.metrotime.ui.theme.LineVerde
import com.example.metrotime.ui.theme.LineVermelha
import com.example.metrotime.ui.theme.MetrotimeBlue
import com.example.metrotime.ui.theme.TextOnYellow
import com.example.metrotime.ui.theme.TextSecondaryDark
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

// ──────────────────────────────────────────────────────────────────────────────
// Metro no Mapa ao Vivo: os metros andam pelo traçado real (data/metro), de longe como pontos e de perto como
// carruagens; seletor de tempo, perturbações em cima da linha, modo a bordo e último metro à noite.
// Desenho: canvas "Metrotime · Novo mapa do metro" no Claude Design.
// ──────────────────────────────────────────────────────────────────────────────

private val SHEET = Color(0xFF1C1C20)
private val OUTLINE = Color(0xFF3A3B40)
private val MUTED_TEXT = Color(0xFF9AA0A6)
private val AMBER = Color(0xFFF59E0B)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

internal fun metroLineColor(line: String): Color = when (line) {
    "Azul" -> LineAzul
    "Amarela" -> LineAmarela
    "Verde" -> LineVerde
    "Vermelha" -> LineVermelha
    else -> com.example.metrotime.data.metro.PortoMetro.colorOf(line)?.let { busColor(it) } ?: Color.Gray
}

internal fun metroLineHex(line: String): String = when (line) {
    "Azul" -> "#0077C8"
    "Amarela" -> "#FFD500"
    "Verde" -> "#009E60"
    "Vermelha" -> "#ED1C24"
    else -> com.example.metrotime.data.metro.PortoMetro.colorOf(line) ?: "#FFFFFF"
}

/** O nome a mostrar de uma linha: "Azul" em Lisboa; "A", "Bx"… no Porto. */
internal fun metroLineName(line: String): String =
    if (com.example.metrotime.data.metro.PortoMetro.isPorto(line)) com.example.metrotime.data.metro.PortoMetro.label(line) else line

/** A cor do texto em cima da cor da linha (escuro na Amarela e na D do Porto). */
private fun metroLineTextColor(line: String): Color =
    busColor(com.example.metrotime.data.bus.BusColors.textOn(metroLineHex(line)))

private fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(CpTime.LISBON).format(HM)

private fun serviceMinute(ms: Long): Int {
    val z = Instant.ofEpochMilli(ms).atZone(CpTime.LISBON)
    return MetroTrip.serviceMinutes(z.hour, z.minute)
}

/** Minutos do dia de serviço do metro (até às 4h conta como o dia anterior: 0:30 → 24:30). */
internal fun metroServiceMinute(ms: Long): Int = serviceMinute(ms)

/** A estação de metro mais perto de [p] (lat, lon), até [maxM] metros. */
internal fun nearestMetroStation(p: Pair<Double, Double>, maxM: Double = 1_500.0): String? =
    MapConfiguration.STATION_COORDS.values
        .map { it.id to com.example.metrotime.data.cp.CpTrack.metres(p, it.lat to it.lon) }
        .filter { it.second <= maxM }
        .minByOrNull { it.second }?.first

/** Onde fica uma estação de metro (Lisboa ou Porto). */
internal fun metroStationCoords(id: String): Pair<Double, Double>? =
    MapConfiguration.STATION_COORDS[id]?.let { it.lat to it.lon }
        ?: com.example.metrotime.data.metro.PortoMetro.network?.coords?.get(id)

/** "agora", "<1 min", "4 min". */
private fun etaText(arriveMs: Long, now: Long): String {
    val s = ((arriveMs - now) / 1000).toInt()
    return when {
        s <= 20 -> "agora"
        s < 60 -> "<1 min"
        else -> "${(s + 30) / 60} min"
    }
}

// ── Camadas ──────────────────────────────────────────────────────────────────

/** Comprimento de um metro de 6 carruagens. */
private const val CARS = 6
private const val CAR_M = 16.5
private const val CAR_GAP_M = 1.6
private const val TRAIN_M = CARS * CAR_M + (CARS - 1) * CAR_GAP_M

/** No Porto andam composições duplas de metro ligeiro (mais curtas que os metros de 6 carruagens de Lisboa). */
private const val PORTO_CARS = 4

private fun carsOf(line: String): Int = if (com.example.metrotime.data.metro.PortoMetro.isPorto(line)) PORTO_CARS else CARS

private fun trainMetres(line: String): Double = carsOf(line).let { it * CAR_M + (it - 1) * CAR_GAP_M }

/** O Metro do Porto está à vista (no painel dos filtros é uma das empresas do Porto). */
internal fun portoMetroShown(f: com.example.metrotime.prefs.Prefs.MapFilters): Boolean =
    f.regional && com.example.metrotime.data.regional.RegionalOperators.MDP.key !in f.hiddenOperators

/** Que metros se desenham: os de Lisboa com o "Metro de Lisboa" ligado; os do Porto com o Metro do Porto (e as linhas escolhidas). */
internal fun metroLineShown(f: com.example.metrotime.prefs.Prefs.MapFilters, line: String): Boolean =
    if (com.example.metrotime.data.metro.PortoMetro.isPorto(line))
        portoMetroShown(f) && f.linesOf(com.example.metrotime.data.regional.RegionalOperators.MDP.key).let { it.isEmpty() || com.example.metrotime.data.metro.PortoMetro.label(line) in it }
    else f.metro

// ── Metro do Porto: linhas e estações iguais às de Lisboa ─────────────────────

internal const val PORTO_LINES_LAYER = "porto-lines-layer"
internal const val PORTO_STATIONS_LAYER = "porto-stations-layer"

/** As camadas das linhas e das estações do Porto, com o mesmo aspeto das de Lisboa (por baixo dos metros). */
internal fun setupPortoMetroLayers(style: Style) {
    if (style.getSource("porto-lines-src") != null) return
    val empty = FeatureCollection.fromFeatures(emptyList())
    style.addSource(GeoJsonSource("porto-lines-src", empty))
    style.addSource(GeoJsonSource("porto-stations-src", empty))
    val lines = LineLayer(PORTO_LINES_LAYER, "porto-lines-src").withProperties(
        lineColor(Expression.toColor(Expression.get("color"))),
        lineWidth(4f),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND)
    )
    val stations = CircleLayer(PORTO_STATIONS_LAYER, "porto-stations-src").withProperties(
        circleRadius(5f),
        circleColor(android.graphics.Color.WHITE),
        circleStrokeWidth(1.5f),
        circleStrokeColor(android.graphics.Color.parseColor("#333333"))
    )
    if (style.getLayer("stations-layer") != null) {
        style.addLayerBelow(lines, "stations-layer")
        style.addLayerBelow(stations, "stations-layer")
    } else {
        style.addLayer(lines)
        style.addLayer(stations)
    }
}

/** As linhas (traçado de cada uma, na sua cor; a A por cima nos troços partilhados) e as estações do Porto. */
internal fun buildPortoMetroFeatures(net: com.example.metrotime.data.metro.PortoMetro.Network): Pair<FeatureCollection, FeatureCollection> {
    val lines = net.tracks.entries.sortedByDescending { it.key }.map { (line, t) ->
        lineFeature(t.track.line).apply { addStringProperty("color", net.colors[line] ?: "#888888") }
    }
    val stations = net.linesByStation.keys.mapNotNull { id ->
        val c = net.coords[id] ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(c.second, c.first)).apply {
            addStringProperty("id", id)
            addStringProperty("name", net.names[id] ?: id)
        }
    }
    return FeatureCollection.fromFeatures(lines) to FeatureCollection.fromFeatures(stations)
}

internal fun applyPortoMetro(map: MapLibreMap, features: Pair<FeatureCollection, FeatureCollection>) {
    val style = map.style ?: return
    style.getSourceAs<GeoJsonSource>("porto-lines-src")?.setGeoJson(features.first)
    style.getSourceAs<GeoJsonSource>("porto-stations-src")?.setGeoJson(features.second)
}

/** A partir deste zoom os metros passam de pontos a carruagens (com uma troca suave à volta dele). */
internal const val METRO_CARS_ZOOM = 13.8

/** As estações (pontos brancos), as do troço interrompido a cinzento. */
internal fun metroStationsCollection(cut: Set<String>): FeatureCollection = FeatureCollection.fromFeatures(
    MapConfiguration.STATION_COORDS.values.map { c ->
        Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply {
            addStringProperty("id", c.id)
            addStringProperty("name", c.name)
            addBooleanProperty("cut", c.id in cut)
        }
    }
)

private fun lineFeature(points: List<Pair<Double, Double>>): Feature =
    Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) }))

/** Linhas do metro pelo traçado real (antes eram retas entre as estações). */
internal fun applyMetroTracks(style: Style, tracks: Map<String, MetroLineTrack>) {
    for ((line, track) in tracks) {
        style.getSourceAs<GeoJsonSource>("line-src-$line")?.setGeoJson(lineFeature(track.track.line))
    }
}

/** Fontes e camadas dos metros: brilho, carruagens (com contorno), pontos e etiquetas do último metro. */
internal fun setupMetroLayers(style: Style) {
    if (style.getSource("metro-dots-src") != null) return
    val empty = FeatureCollection.fromFeatures(emptyList())

    // Troço interrompido: tracejado cinzento por cima da linha e por baixo das estações
    style.addSource(GeoJsonSource("metro-cut-src", empty))
    val cut = LineLayer("metro-cut-layer", "metro-cut-src").withProperties(
        lineColor("#6B7078"),
        lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11f, 4f), Expression.stop(15f, 7f))),
        lineDasharray(arrayOf(1.3f, 1.0f)),
        lineOpacity(0.9f)
    )
    if (style.getLayer("stations-layer") != null) style.addLayerBelow(cut, "stations-layer") else style.addLayer(cut)

    style.addSource(GeoJsonSource("metro-glow-src", empty))
    style.addLayer(
        CircleLayer("metro-glow-layer", "metro-glow-src").withProperties(
            circleRadius(Expression.get("r")),
            circleColor(Expression.get("color")),
            circleOpacity(Expression.get("o")),
            circleBlur(0.6f)
        )
    )

    style.addSource(GeoJsonSource("metro-cars-src", empty))
    val carsOpacity = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(METRO_CARS_ZOOM - 0.5f, 0f),
        Expression.stop(METRO_CARS_ZOOM + 0.4f, Expression.get("alpha"))
    )
    style.addLayer(
        LineLayer("metro-cars-outline", "metro-cars-src").withProperties(
            lineColor("#FFFFFF"),
            lineWidth(Expression.interpolate(Expression.exponential(1.6f), Expression.zoom(),
                Expression.stop(13f, 3.6f), Expression.stop(15f, 7.5f), Expression.stop(17f, 15f), Expression.stop(19f, 32f), Expression.stop(21f, 74f))),
            lineOpacity(carsOpacity),
            lineCap(Property.LINE_CAP_BUTT),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    style.addLayer(
        LineLayer("metro-cars-layer", "metro-cars-src").withProperties(
            lineColor(Expression.get("color")),
            lineWidth(Expression.interpolate(Expression.exponential(1.6f), Expression.zoom(),
                Expression.stop(13f, 2f), Expression.stop(15f, 5f), Expression.stop(17f, 11f), Expression.stop(19f, 25f), Expression.stop(21f, 62f))),
            lineOpacity(carsOpacity),
            lineCap(Property.LINE_CAP_BUTT),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
    )

    style.addSource(GeoJsonSource("metro-dots-src", empty))
    val dotsOpacity = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(METRO_CARS_ZOOM - 0.5f, Expression.get("alpha")),
        Expression.stop(METRO_CARS_ZOOM + 0.4f, 0f)
    )
    style.addLayer(
        CircleLayer("metro-dots-layer", "metro-dots-src").withProperties(
            circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10f, 3.5f), Expression.stop(13f, 5.5f), Expression.stop(14.5f, 6.5f))),
            circleColor(Expression.get("color")),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(1.8f),
            circleOpacity(dotsOpacity),
            circleStrokeOpacity(dotsOpacity)
        )
    )

    // Setas de direção: de longe junto ao ponto, de perto à frente da primeira carruagem
    style.addSource(GeoJsonSource("metro-arrows-src", empty))
    style.addLayer(
        SymbolLayer("metro-arrows-layer", "metro-arrows-src").withProperties(
            iconImage(Expression.get("arrow")),
            iconRotate(Expression.get("bearing")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconOpacity(Expression.get("alpha")),
            iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10f, 0.7f), Expression.stop(13f, 1f), Expression.stop(16f, 1.1f), Expression.stop(18f, 1.4f)))
        )
    )

    style.addSource(GeoJsonSource("metro-labels-src", empty))
    style.addLayer(
        SymbolLayer("metro-labels-layer", "metro-labels-src").withProperties(
            textField(Expression.get("label")),
            textFont(arrayOf("Montserrat Medium")),
            textSize(11f),
            textColor("#FFFFFF"),
            textHaloColor("#1C1C20"),
            textHaloWidth(2.2f),
            textOffset(arrayOf(0f, -1.9f)),
            textAllowOverlap(true),
            textIgnorePlacement(true)
        )
    )
}

/**
 * Perturbações: o troço que a mensagem do Metro refere fica tracejado a cinzento e as estações dele também.
 * true se há algum troço desenhado (o renderer só o faz pulsar nesse caso).
 */
internal fun applyMetroDisruptions(map: MapLibreMap, statuses: List<MetroLineStatus>, tracks: Map<String, MetroLineTrack>): Boolean {
    val style = map.style ?: return false
    val features = ArrayList<Feature>()
    val cutStations = HashSet<String>()
    for (st in statuses) {
        if (st.ok) continue
        val track = tracks[st.line] ?: continue
        val range = MetroLines.affectedRange(st.line, st.message) ?: continue
        val points = track.slice(track.stationAt[range.first], track.stationAt[range.last])
        if (points.size >= 2) features += lineFeature(points)
        range.forEach { i -> track.stations.getOrNull(i)?.let { cutStations += it } }
    }
    style.getSourceAs<GeoJsonSource>("metro-cut-src")?.setGeoJson(FeatureCollection.fromFeatures(features))
    style.getSourceAs<GeoJsonSource>("stations-src")?.setGeoJson(metroStationsCollection(cutStations))
    return features.isNotEmpty()
}

/**
 * Seta de direção na cor da linha, com contorno branco, a apontar para cima (o mapa roda-a pelo rumo). [far]: de longe,
 * a seta fica afastada do centro (por fora do ponto); de perto fica no centro (posta à frente do metro).
 */
private fun metroArrowBitmap(hex: String, far: Boolean): android.graphics.Bitmap {
    val d = android.content.res.Resources.getSystem().displayMetrics.density
    val size = ((if (far) 40 else 16) * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = size / 2f
    val tipY = if (far) c - 17 * d else c - 5 * d
    val baseY = if (far) c - 10.5f * d else c + 4 * d
    val half = if (far) 4.5f * d else 5f * d
    val path = android.graphics.Path().apply {
        moveTo(c, tipY)
        lineTo(c + half, baseY)
        lineTo(c - half, baseY)
        close()
    }
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    })
    canvas.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor(hex)
    })
    return bmp
}

/**
 * Desenha os metros a cada imagem: onde o modelo diz que estão (agora ou à hora do seletor), sem saltos
 * ([MetroSmoother]), só os que se veem. De longe são pontos; de perto, 6 carruagens que seguem as curvas.
 */
internal class MetroRenderer {
    private val smoother = MetroSmoother()
    /** Há um troço interrompido desenhado (só então pulsa). */
    var cutActive = false
    private var lastOffset = 0L
    private var offsetChangedAt = 0L
    private val sizes = HashMap<String, Int>()
    /** As setas já postas no estilo do mapa (perguntar ao mapa a cada imagem sai caro). */
    private val arrowImages = java.util.WeakHashMap<Style, MutableSet<String>>()

    private fun arrowIcon(style: Style, hex: String, far: Boolean): String {
        val name = "metro-arrow-${if (far) "far" else "near"}-$hex"
        val added = arrowImages.getOrPut(style) { HashSet() }
        if (name !in added) {
            if (style.getImage(name) == null) style.addImage(name, metroArrowBitmap(hex, far))
            added += name
        }
        return name
    }

    /** O sítio do metro seguido (para a câmara) e se as carruagens estão à vista (para o ritmo das imagens). */
    class Frame(val follow: Pair<Double, Double>?, val detailed: Boolean, val animating: Boolean = false)

    private class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(p: Pair<Double, Double>) = p.first in south..north && p.second in west..east
    }

    private fun padded(b: LatLngBounds): Box {
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.25
        val dLon = (b.longitudeEast - b.longitudeWest) * 0.25
        return Box(b.latitudeSouth - dLat, b.longitudeWest - dLon, b.latitudeNorth + dLat, b.longitudeEast + dLon)
    }

    private fun feature(p: Pair<Double, Double>, color: String, key: String, alpha: Double) =
        Feature.fromGeometry(Point.fromLngLat(p.second, p.first)).apply {
            addStringProperty("key", key)
            addStringProperty("color", color)
            addNumberProperty("alpha", alpha)
        }

    private fun cars(track: MetroLineTrack, center: Double, dir: Int, color: String, key: String, alpha: Double, out: MutableList<Feature>) {
        val head = center + dir * trainMetres(track.line) / 2
        for (c in 0 until carsOf(track.line)) {
            val a = head - dir * c * (CAR_M + CAR_GAP_M)
            val points = track.slice(a, a - dir * CAR_M)
            if (points.size < 2) continue
            out += lineFeature(points).apply {
                addStringProperty("key", key)
                addStringProperty("color", color)
                addNumberProperty("alpha", alpha)
            }
        }
    }

    /** Põe [features] na fonte [id] (vazia para vazia não se volta a enviar). */
    private fun put(style: Style, id: String, features: List<Feature>) {
        if (features.isEmpty() && sizes[id] == 0) return
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
        sizes[id] = features.size
    }

    fun render(
        map: MapLibreMap,
        trains: List<MetroTrain>,
        tracks: Map<String, MetroLineTrack>,
        nowMs: Long,
        offsetMs: Long,
        selected: String?,
        onboard: String?,
        followKey: String?,
        dtSec: Double,
        /** Que linhas se desenham (os filtros: Metro de Lisboa, Metro do Porto e as linhas escolhidas). */
        show: (line: String) -> Boolean
    ): Frame {
        val style = map.style ?: return Frame(null, false)
        if (offsetMs != lastOffset) {
            lastOffset = offsetMs
            offsetChangedAt = nowMs
        }
        // Ao mexer no seletor de tempo, os metros correm até ao sítio novo (e podem andar para trás)
        val fast = nowMs - offsetChangedAt < 900L
        val t = nowMs + offsetMs
        val detailed = map.cameraPosition.zoom >= METRO_CARS_ZOOM - 0.5
        val box = padded(map.projection.visibleRegion.latLngBounds)
        val pulse = 0.5 + 0.5 * sin(nowMs / 1000.0 * 2 * PI / 1.8)

        val places = trains.mapNotNull { tr -> tracks[tr.line]?.let { tr.placeAt(t, it) } }
        // O "último metro" é só de Lisboa (no Porto só se conhecem os metros dos próximos minutos)
        val last = MetroNight.lastTrains(places.filterNot { com.example.metrotime.data.metro.PortoMetro.isPorto(it.line) }, serviceMinute(t))
        val arrows = ArrayList<Feature>()
        val dots = ArrayList<Feature>()
        val carFeatures = ArrayList<Feature>()
        val glows = ArrayList<Feature>()
        val labels = ArrayList<Feature>()
        val keys = HashSet<String>()
        var follow: Pair<Double, Double>? = null
        for (place in places) {
            val track = tracks[place.line] ?: continue
            val key = place.key
            keys += key
            val s = smoother.step(key, place.s, place.dir, dtSec, fast)
            // O metro inteiro cabe na linha (no terminal não fica meio fora)
            val half = minOf(trainMetres(place.line) / 2, track.length / 2)
            val drawn = s.coerceIn(half, track.length - half)
            val center = track.point(drawn)
            if (key == followKey) follow = center
            if (!show(place.line) || !box.contains(center)) continue
            val color = metroLineHex(place.line)
            dots += feature(center, color, key, place.alpha)
            if (detailed) cars(track, drawn, place.dir, color, key, place.alpha, carFeatures)
            // A seta: para onde vai (parado numa estação também, para se saber o sentido)
            val bearing = track.bearing(drawn, place.dir)
            val arrowAt = if (detailed) track.point(drawn + place.dir * (trainMetres(place.line) / 2 + 7.0)) else center
            arrows += feature(arrowAt, color, key, place.alpha).apply {
                addStringProperty("arrow", arrowIcon(style, color, far = !detailed))
                addNumberProperty("bearing", bearing)
            }
            when {
                key == selected || key == onboard -> glows += feature(center, color, key, 1.0).apply {
                    addNumberProperty("r", 20.0 + 12.0 * pulse)
                    addNumberProperty("o", 0.55 - 0.35 * pulse)
                }
                key in last -> glows += feature(center, color, key, 1.0).apply {
                    addNumberProperty("r", 16.0 + 9.0 * pulse)
                    addNumberProperty("o", 0.5 - 0.3 * pulse)
                }
            }
            if (key in last) labels += feature(center, color, key, 1.0).apply { addStringProperty("label", "Último · ${place.destinationName}") }
        }
        smoother.retain(keys)

        // Com o seletor à frente, onde estão agora fica transparente (para se ver quanto andam)
        if (offsetMs >= 20_000L) {
            for (tr in trains) {
                if (!show(tr.line)) continue
                val track = tracks[tr.line] ?: continue
                val p = tr.placeAt(nowMs, track) ?: continue
                val half = minOf(trainMetres(p.line) / 2, track.length / 2)
                val drawn = p.s.coerceIn(half, track.length - half)
                val c = track.point(drawn)
                if (!box.contains(c)) continue
                val color = metroLineHex(p.line)
                dots += feature(c, color, "ghost|${p.key}", 0.28 * p.alpha)
                if (detailed) cars(track, drawn, p.dir, color, "ghost|${p.key}", 0.28 * p.alpha, carFeatures)
            }
        }

        put(style, "metro-dots-src", dots)
        put(style, "metro-cars-src", carFeatures)
        put(style, "metro-glow-src", glows)
        put(style, "metro-labels-src", labels)
        put(style, "metro-arrows-src", arrows)
        // O troço interrompido pulsa devagar
        if (cutActive) style.getLayer("metro-cut-layer")?.setProperties(lineOpacity((0.45 + 0.45 * pulse).toFloat()))
        return Frame(follow, detailed, fast)
    }
}

// ── Componentes ──────────────────────────────────────────────────────────────

@Composable
internal fun MetroLinePill(line: String) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(metroLineColor(line)).padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(metroLineName(line), color = metroLineTextColor(line), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Entra a deslizar um pouco de baixo, uns a seguir aos outros. */
@Composable
private fun Modifier.staggerIn(index: Int, key: Any?): Modifier {
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(index * 45L)
        anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    val dy = with(LocalDensity.current) { 10.dp.toPx() }
    return this.graphicsLayer {
        alpha = anim.value
        translationY = (1 - anim.value) * dy
    }
}

@Composable
private fun ClockGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.11f
        drawCircle(color, radius = w / 2 - stroke, style = Stroke(stroke))
        val c = center
        drawLine(color, c, Offset(c.x, c.y - w * 0.24f), stroke, StrokeCap.Round)
        drawLine(color, c, Offset(c.x + w * 0.17f, c.y + w * 0.1f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun MoonGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val full = Path().apply { addOval(Rect(Offset(w * 0.1f, w * 0.1f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.8f))) }
        val bite = Path().apply { addOval(Rect(Offset(w * 0.32f, w * 0.0f), androidx.compose.ui.geometry.Size(w * 0.72f, w * 0.72f))) }
        drawPath(Path.combine(PathOperation.Difference, full, bite), color)
    }
}

/**
 * O seletor de tempo: "Agora / +5 / +10"; o relógio abre o deslizador (até 15 min), contínuo: os metros andam com o
 * dedo, e ao largar acerta ao minuto. O tempo é lido aqui dentro ([offsetFlow]): arrastar só redesenha este cartão.
 */
@Composable
internal fun MetroTimeBar(offsetFlow: kotlinx.coroutines.flow.StateFlow<Long>, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    val offset = offsetFlow.collectAsState()
    // Os textos e os botões só mudam ao minuto (o deslizador, a cada movimento do dedo)
    val minutes by remember { derivedStateOf { ((offset.value + 30_000L) / 60_000L).toInt() } }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    // A animação do tamanho fica dentro do cartão: por fora cortava a sombra num retângulo (cantos escuros)
    Surface(
        modifier = modifier,
        color = SHEET.copy(alpha = 0.97f),
        shape = RoundedCornerShape(if (expanded) 22.dp else 28.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2E2C)),
        shadowElevation = 10.dp
    ) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))) {
            AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.width(300.dp).padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedContent(
                            targetState = minutes,
                            transitionSpec = {
                                val up = targetState > initialState
                                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                            },
                            label = "tempo"
                        ) { m ->
                            Text(if (m == 0) "Agora" else "Daqui a $m min", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(clockOf(System.currentTimeMillis() + minutes * 60_000L), color = TextSecondaryDark, fontSize = 15.sp)
                    }
                    Text(
                        "Os metros mostram onde vão estar, pelas horas do Metro. A posição de agora fica transparente.",
                        color = MUTED_TEXT, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                    Slider(
                        value = offset.value / 60_000f,
                        onValueChange = { v ->
                            val ms = (v * 60_000f).toLong()
                            // Um toque ao passar os 5, 10 e 15 min
                            if (ms / 300_000L != offset.value / 300_000L) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(ms)
                        },
                        // Ao largar, acerta ao minuto (os metros deslizam até lá)
                        onValueChangeFinished = { onChange(((offset.value + 30_000L) / 60_000L) * 60_000L) },
                        valueRange = 0f..MetroMotion.MAX_OFFSET_MIN.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MetrotimeBlue,
                            inactiveTrackColor = OUTLINE
                        )
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("Agora", "5 min", "10 min", "15 min").forEach { Text(it, color = MUTED_TEXT, fontSize = 11.sp) }
                    }
                }
            }
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center
                ) { ClockGlyph(if (expanded) MetrotimeBlue else TextSecondaryDark) }
                listOf(0, 5, 10).forEach { v ->
                    val on = minutes == v
                    val bg by animateColorAsState(if (on) MetrotimeBlue else Color.Transparent, label = "fundo")
                    val fg by animateColorAsState(if (on) Color.White else TextSecondaryDark, label = "texto")
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable { onChange(v * 60_000L) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (v == 0) "Agora" else "+$v min", color = fg, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Avisos das linhas com perturbações: entram a deslizar de cima; tocar abre a mensagem do Metro inteira. */
@Composable
internal fun MetroDisruptionBanner(statuses: List<MetroLineStatus>, modifier: Modifier = Modifier) {
    val disrupted = statuses.filter { !it.ok }
    AnimatedVisibility(
        visible = disrupted.isNotEmpty(),
        enter = slideInVertically { -it / 2 } + fadeIn(),
        exit = slideOutVertically { -it / 2 } + fadeOut(),
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            disrupted.forEach { DisruptionCard(it) }
        }
    }
}

@Composable
private fun DisruptionCard(st: MetroLineStatus) {
    var open by remember(st.line, st.message) { mutableStateOf(false) }
    val range = remember(st.line, st.message) { MetroLines.affectedRange(st.line, st.message) }
    val cut = MetroLines.normalize(st.message).contains("interromp")
    val stops = MetroLines.stops(st.line)
    val summary = range?.let { "Entre ${MetroLines.nameOf(stops[it.first])} e ${MetroLines.nameOf(stops[it.last])}. O resto da linha circula." }
        ?: st.message
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "seta")
    Surface(
        onClick = { open = !open },
        color = CardBackground,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.animateContentSize().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(AMBER.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = AMBER, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(metroLineColor(st.line)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Linha ${st.line} · ${if (cut) "interrompida" else "com perturbações"}",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    if (open) st.message else summary,
                    color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = if (open) "Fechar o aviso" else "Ver o aviso do Metro",
                tint = TextSecondaryDark, modifier = Modifier.size(22.dp).rotate(turn)
            )
        }
    }
}

/** À noite: quando passa o último metro de cada sentido na estação mais perto de ti. */
@Composable
internal fun MetroNightCard(stationName: String, passes: List<MetroNight.LastPass>, modifier: Modifier = Modifier) {
    Surface(color = CardBackground, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0x2493C5FD)), contentAlignment = Alignment.Center) {
                    MoonGlyph(Color(0xFF93C5FD))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Últimos metros", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Em $stationName, a estação mais perto · estimado", color = TextSecondaryDark, fontSize = 12.sp)
                }
            }
            passes.forEachIndexed { i, p ->
                HorizontalDivider(color = OUTLINE, thickness = 1.dp)
                Row(Modifier.height(34.dp).staggerIn(i, p.line + p.towardsName), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (p.passed) Color(0xFF4A4D53) else metroLineColor(p.line)))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${p.line} → ${p.towardsName}", modifier = Modifier.weight(1f),
                        color = if (p.passed) MUTED_TEXT else Color.White, fontSize = 14.sp,
                        textDecoration = if (p.passed) TextDecoration.LineThrough else null
                    )
                    Text(
                        if (p.passed) "já passou" else MetroTrip.clock(p.minute),
                        color = if (p.passed) MUTED_TEXT else Color.White,
                        fontSize = if (p.passed) 13.sp else 15.sp, fontWeight = if (p.passed) FontWeight.Normal else FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** "A seguir o metro" (com um ponto a piscar); depois de mexeres no mapa, toca para voltar a seguir. */
@Composable
internal fun MetroFollowChip(paused: Boolean, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition(label = "piscar").animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ponto"
    )
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = SHEET.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color(0xFF2A2E2C)),
        shadowElevation = 6.dp,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = paused, onClick = onResume)
    ) {
        Row(Modifier.height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).graphicsLayer { alpha = if (paused) 1f else blink }.clip(CircleShape)
                    .background(if (paused) TextSecondaryDark else MetrotimeBlue)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (paused) "Seguir o metro" else "A seguir o metro", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private enum class StopKind { PASSED, NEXT, AHEAD, END }

private val PASSED_RAIL = Color(0xFF3A3D42)
private val PASSED_DOT = Color(0xFF4A4D53)
private const val STOP_ROW_DP = 44
private const val NEXT_ROW_DP = 52

/** Etiqueta pequena de uma linha (correspondência), na cor dela; [faded] nas estações por onde já passou. */
@Composable
private fun LineChip(line: String, faded: Boolean) {
    val c = metroLineColor(line)
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(if (faded) c.copy(alpha = 0.35f) else c)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            metroLineName(line), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = when {
                faded -> Color(0xFFC9CDD3)
                line == "Amarela" || metroLineTextColor(line) != Color.White -> TextOnYellow
                else -> Color.White
            }
        )
    }
}

/** Largura da coluna do traço; o traço passa no meio. */
private const val RAIL_COLUMN_DP = 36

/**
 * Uma estação na viagem, desenhada como num diagrama de metro: a estação em círculo branco com aro, a próxima maior e
 * a pulsar, o fim em quadrado, as já passadas a cinzento. Numa correspondência, uma barra na cor da outra linha
 * atravessa a estação e a etiqueta diz qual. O traço e o metro são desenhados pela lista, por trás e por cima.
 * [stoppedHere]: o metro está parado nesta estação.
 */
@Composable
private fun StopRow(
    line: String, stationId: String, name: String, arriveAt: Long?, now: Long, kind: StopKind,
    stoppedHere: Boolean, modifier: Modifier = Modifier
) {
    val lineColor = metroLineColor(line)
    val others = remember(stationId, line) { MetroLines.linesOf(stationId).filter { it != line } }
    val passed = kind == StopKind.PASSED
    val next = kind == StopKind.NEXT
    val pulse by rememberInfiniteTransition(label = "estação").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "anel"
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = (if (next) NEXT_ROW_DP else STOP_ROW_DP).dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.width(RAIL_COLUMN_DP.dp).fillMaxHeight()) {
            val x = size.width / 2
            val y = size.height / 2
            // Correspondência: a outra linha atravessa a estação
            others.forEachIndexed { i, other ->
                val w = 30.dp.toPx()
                val h = 10.dp.toPx()
                val dy = (i - (others.size - 1) / 2f) * (h + 2.dp.toPx())
                drawRoundRect(
                    metroLineColor(other).copy(alpha = if (passed) 0.45f else 1f),
                    topLeft = Offset(x - w / 2, y - h / 2 + dy),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
                )
            }
            val interchange = others.isNotEmpty()
            when (kind) {
                StopKind.PASSED -> {
                    if (interchange) drawCircle(SHEET, 8.dp.toPx(), Offset(x, y))
                    drawCircle(PASSED_DOT, (if (interchange) 6 else 5).dp.toPx(), Offset(x, y))
                }
                StopKind.NEXT -> {
                    drawCircle(lineColor.copy(alpha = 0.55f * (1 - pulse)), 16.dp.toPx() * (1 + 0.55f * pulse), Offset(x, y))
                    drawCircle(lineColor, 10.dp.toPx(), Offset(x, y))
                    drawCircle(Color.White, 6.dp.toPx(), Offset(x, y))
                }
                StopKind.AHEAD -> {
                    if (interchange) drawCircle(SHEET, 10.dp.toPx(), Offset(x, y))
                    drawCircle(lineColor, (if (interchange) 8 else 7).dp.toPx(), Offset(x, y))
                    drawCircle(Color.White, (if (interchange) 5 else 4).dp.toPx(), Offset(x, y))
                }
                StopKind.END -> {
                    val side = 18.dp.toPx()
                    val inner = 10.dp.toPx()
                    drawRoundRect(lineColor, Offset(x - side / 2, y - side / 2), androidx.compose.ui.geometry.Size(side, side), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    drawRoundRect(Color.White, Offset(x - inner / 2, y - inner / 2), androidx.compose.ui.geometry.Size(inner, inner), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    color = if (passed) Color(0xFF8A8F98) else Color.White,
                    fontSize = if (next) 16.sp else 15.sp,
                    fontWeight = when {
                        next -> FontWeight.Bold
                        kind == StopKind.END -> FontWeight.Medium
                        else -> FontWeight.Normal
                    }
                )
                others.forEach { LineChip(it, faded = passed) }
                if (kind == StopKind.END) Text("fim", color = Color(0xFF8A8F98), fontSize = 11.sp)
            }
            if (next || stoppedHere) {
                val soon = arriveAt != null && arriveAt - now <= 60_000
                Text(
                    when {
                        stoppedHere -> "o metro está aqui"
                        soon -> "próxima · a chegar"
                        else -> "próxima"
                    },
                    color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
        }
        if (!passed && arriveAt != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    etaText(arriveAt, now),
                    color = if (next) Color.White else TextSecondaryDark,
                    fontSize = if (next) 16.sp else 14.sp, fontWeight = if (next) FontWeight.Bold else FontWeight.Normal
                )
                Text(clockOf(arriveAt), color = Color(0xFF8A8F98), fontSize = 11.sp)
            }
        }
    }
}

/**
 * O metro escolhido: linha, destino e as estações seguintes com as horas da API. "Vou neste metro" pergunta onde
 * sais (a lista desliza para o lado) e passa ao modo a bordo; "Seguir" põe a câmara atrás dele.
 */
@Composable
internal fun MetroTrainSheet(
    train: MetroTrain,
    track: MetroLineTrack?,
    now: Long,
    following: Boolean,
    onBoard: (exitStation: String, alarm: Boolean) -> Unit,
    onFollow: () -> Unit,
    onClose: () -> Unit
) {
    val upcoming = train.upcoming(now).filter { it.second > now - 30_000 }
    val place = track?.let { train.placeAt(now, it) }
    val destination = train.destinationAt(now) ?: "?"
    var picking by remember(train.key) { mutableStateOf(false) }
    var alarm by remember(train.key) { mutableStateOf(true) }
    val stationName = { idx: Int -> track?.stations?.getOrNull(idx)?.let { MetroLines.nameOf(it) } ?: "?" }
    val toEnd = upcoming.lastOrNull()?.second?.let { ceil((it - now) / 60_000.0).toInt() }?.takeIf { it > 0 }
    val subtitle = when {
        place?.atStation != null -> "Parado em ${stationName(place.atStation)}"
        place?.nextStation != null -> "A chegar a ${stationName(place.nextStation)}"
        else -> "Linha ${metroLineName(train.line)}"
    } + (toEnd?.let { " · $it min até ao fim" } ?: "")

    // A seguir o metro: o painel encolhe para uma barra, para se ver o metro no mapa; tocar nela abre as paragens
    var expanded by remember(train.key, following) { mutableStateOf(!following) }
    val next = upcoming.firstOrNull { place?.atStation == null || it.first != place.atStation }
    val nextEta = next?.let { (_, at) ->
        val ms = at - now
        when {
            ms <= 20_000 -> "agora"
            ms < 60_000 -> "<1 min"
            else -> "${ceil(ms / 60_000.0).toInt()} min"
        }
    }
    val followText = when {
        place?.atStation != null && next != null -> "Parado em ${stationName(place.atStation)} · ${stationName(next.first)} $nextEta"
        next != null -> "A chegar a ${stationName(next.first)} · $nextEta"
        else -> subtitle
    }

    val drag = rememberSheetDrag(key = train.key)
    val density = LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).sheetDrag(drag, density).navigationBarsPadding(),
        color = SHEET,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        if (following && !expanded) {
            Column(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Ver as paragens") { expanded = true }
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)
            ) {
                SheetHandle(drag, onClose, color = Color.Gray.copy(alpha = 0.5f), height = 18.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetroLinePill(train.line)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("→ $destination", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(followText, color = TextSecondaryDark, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Ver as paragens", tint = TextSecondaryDark)
                    }
                    IconButton(onClick = { onFollow(); onClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Deixar de seguir", tint = TextSecondaryDark)
                    }
                }
            }
        } else Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            SheetHandle(drag, onClose, color = Color.Gray.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetroLinePill(train.line)
                Spacer(Modifier.width(10.dp))
                Text("→ $destination", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondaryDark) }
            }
            Text(subtitle, color = TextSecondaryDark, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = picking,
                transitionSpec = {
                    val forward = targetState
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                },
                label = "painel"
            ) { isPicking ->
                if (!isPicking) {
                    Column {
                        if (upcoming.isEmpty()) {
                            Text("Sem horas para as próximas estações.", color = TextSecondaryDark, fontSize = 14.sp)
                        }
                        // A viagem toda, com scroll: as estações por onde já passou (desde o terminal), a seguinte e
                        // as que faltam com a hora; abre já na seguinte, com duas das anteriores à vista
                        val dir = place?.dir ?: 1
                        val firstAhead = upcoming.firstOrNull()?.first
                        val passed = if (track != null && firstAhead != null) {
                            generateSequence(if (dir > 0) 0 else track.stations.lastIndex) { it + dir }
                                .takeWhile { it in track.stations.indices && it != firstAhead }
                                .toList()
                        } else emptyList()
                        val rows = passed.map { Triple(it, null as Long?, StopKind.PASSED) } +
                            upcoming.mapIndexed { i, (idx, at) ->
                                val end = track != null && (idx == 0 || idx == track.stations.lastIndex)
                                Triple(idx, at, if (i == 0) StopKind.NEXT else if (end) StopKind.END else StopKind.AHEAD)
                            }
                        val scroll = rememberScrollState()
                        // O meio de cada linha da lista (px, na coluna), para desenhar o traço e pôr o metro
                        val centers = remember(train.key) { mutableStateMapOf<Int, Float>() }
                        val nextRow = passed.size
                        val rowOf = { station: Int -> rows.indexOfFirst { it.first == station }.takeIf { it >= 0 } }
                        // Onde está o metro na lista: na estação onde está parado, ou entre a anterior e a seguinte,
                        // na proporção do caminho já feito
                        val target: Float? = run {
                            val p = place ?: return@run null
                            val tr = track ?: return@run null
                            p.atStation?.let { st -> return@run rowOf(st)?.let { centers[it] } }
                            val nextSt = p.nextStation ?: return@run null
                            val r = rowOf(nextSt) ?: return@run null
                            val yTo = centers[r] ?: return@run null
                            val yFrom = centers[r - 1] ?: return@run yTo
                            val from = tr.stationAt.getOrNull(nextSt - p.dir) ?: return@run yTo
                            val to = tr.stationAt[nextSt]
                            val u = if (to == from) 1.0 else ((p.s - from) / (to - from)).coerceIn(0.0, 1.0)
                            yFrom + (yTo - yFrom) * u.toFloat()
                        }
                        val trainY = remember(train.key) { Animatable(0f) }
                        var trainShown by remember(train.key) { mutableStateOf(false) }
                        LaunchedEffect(target) {
                            val t = target ?: return@LaunchedEffect
                            // As horas mudam ao segundo: desliza esse segundo a velocidade constante
                            if (!trainShown) { trainY.snapTo(t); trainShown = true }
                            else trainY.animateTo(t, tween(1000, easing = androidx.compose.animation.core.LinearEasing))
                        }
                        // Abre já na seguinte, com duas das anteriores à vista
                        LaunchedEffect(train.key) {
                            val y = snapshotFlow { centers[nextRow] }.filterNotNull().first()
                            val to = (y - with(density) { 110.dp.toPx() }).roundToInt()
                            if (to > 0) scroll.animateScrollTo(to, tween(450, easing = FastOutSlowInEasing))
                        }
                        val lineColor = metroLineColor(train.line)
                        Box(Modifier.weight(1f, fill = false)) {
                            Column(
                                Modifier.verticalScroll(scroll).padding(top = 8.dp, bottom = 10.dp).drawWithContent {
                                    val ys = rows.indices.map { centers[it] }
                                    if (ys.size > 1 && ys.all { it != null }) {
                                        val first = ys.first()!!
                                        val last = ys.last()!!
                                        val x = RAIL_COLUMN_DP.dp.toPx() / 2
                                        val w = 6.dp.toPx()
                                        // Cinzento por onde o metro já passou, na cor da linha daí para a frente
                                        val split = (if (trainShown) trainY.value else ys[(nextRow - 1).coerceAtLeast(0)]!!)
                                            .coerceIn(first, last)
                                        if (split > first) drawLine(PASSED_RAIL, Offset(x, first), Offset(x, split), w)
                                        if (split < last) drawLine(lineColor, Offset(x, split), Offset(x, last), w)
                                    }
                                    drawContent()
                                    // O metro: uma carruagem em cima do traço
                                    if (trainShown) {
                                        val x = RAIL_COLUMN_DP.dp.toPx() / 2
                                        val cw = 14.dp.toPx()
                                        val ch = 24.dp.toPx()
                                        val top = trainY.value - ch / 2
                                        val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                                        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x - cw / 2, top + 2.dp.toPx()), androidx.compose.ui.geometry.Size(cw, ch), r)
                                        drawRoundRect(lineColor, Offset(x - cw / 2, top), androidx.compose.ui.geometry.Size(cw, ch), r)
                                        val b = 2.dp.toPx()
                                        drawRoundRect(
                                            Color.White, Offset(x - cw / 2 + b, top + b), androidx.compose.ui.geometry.Size(cw - 2 * b, ch - 2 * b),
                                            androidx.compose.ui.geometry.CornerRadius(3.5.dp.toPx())
                                        )
                                    }
                                }
                            ) {
                                rows.forEachIndexed { i, (idx, at, kind) ->
                                    val id = track?.stations?.getOrNull(idx) ?: ""
                                    StopRow(
                                        train.line, id, stationName(idx), at, now, kind,
                                        stoppedHere = place?.atStation == idx,
                                        modifier = Modifier
                                            .onGloballyPositioned { c -> centers[i] = c.positionInParent().y + c.size.height / 2f }
                                            .then(if (kind == StopKind.PASSED) Modifier else Modifier.staggerIn((i - passed.size).coerceIn(0, 8), train.key))
                                    )
                                }
                            }
                            // Esbate nas pontas: há mais estações para cima e para baixo
                            Box(
                                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(14.dp)
                                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(SHEET, SHEET.copy(alpha = 0f))))
                            )
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(SHEET.copy(alpha = 0f), SHEET)))
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { picking = true },
                                enabled = upcoming.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MetrotimeBlue)
                            ) { Text("Vou neste metro", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = onFollow,
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (following) MetrotimeBlue else OUTLINE)
                            ) { Text(if (following) "A seguir" else "Seguir", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                } else {
                    Column {
                        Text("Onde sais?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            upcoming.forEachIndexed { i, (idx, at) ->
                                Row(
                                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp))
                                        .clickable { track?.stations?.getOrNull(idx)?.let { onBoard(it, alarm) } }
                                        .padding(horizontal = 6.dp)
                                        .staggerIn(i, "sair"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).border(2.dp, metroLineColor(train.line), CircleShape))
                                    Spacer(Modifier.width(12.dp))
                                    Text(stationName(idx), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    Text("chegas às ${clockOf(at)}", color = TextSecondaryDark, fontSize = 13.sp)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Avisar uma estação antes", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = alarm, onCheckedChange = { alarm = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue, checkedThumbColor = Color.White)
                            )
                        }
                        TextButton(onClick = { picking = false }) { Text("Voltar", color = TextSecondaryDark) }
                    }
                }
            }
        }
    }
}

/** A bordo: onde sais, quanto falta (a descer ao segundo), o progresso estação a estação e o aviso antes de sair. */
@Composable
internal fun MetroOnboardCard(
    board: LiveMapViewModel.Onboard,
    train: MetroTrain?,
    track: MetroLineTrack?,
    now: Long,
    onAlarm: (Boolean) -> Unit,
    onStop: () -> Unit
) {
    val exitIdx = track?.indexOf(board.exitStation) ?: -1
    val fromIdx = track?.indexOf(board.fromStation) ?: -1
    val exitName = MetroLines.nameOf(board.exitStation)
    val arrive = train?.upcoming(now)?.firstOrNull { it.first == exitIdx }?.second
    val place = if (train != null && track != null) train.placeAt(now, track) else null
    // Chegou: já está parado na estação, ou já passou dela
    val arrived = (arrive != null && arrive <= now) ||
        (train != null && arrive == null && place != null && exitIdx >= 0 && (place.s - track!!.stationAt[exitIdx]) * place.dir > -60)
    // Chegaste: o modo a bordo acaba sozinho passado um minuto
    if (arrived) LaunchedEffect(board.key) { delay(60_000); onStop() }

    val fraction = if (track != null && place != null && exitIdx >= 0 && fromIdx >= 0 && exitIdx != fromIdx) {
        ((place.s - track.stationAt[fromIdx]) / (track.stationAt[exitIdx] - track.stationAt[fromIdx])).coerceIn(0.0, 1.0)
    } else if (arrived) 1.0 else 0.0
    val progress by animateFloatAsState(fraction.toFloat(), tween(900, easing = FastOutSlowInEasing), label = "progresso")
    val between = if (exitIdx >= 0 && fromIdx >= 0) kotlin.math.abs(exitIdx - fromIdx) - 1 else 0

    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = SHEET,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A BORDO", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Spacer(Modifier.width(8.dp))
                MetroLinePill(board.line)
                Spacer(Modifier.width(8.dp))
                Text("→ ${train?.destinationAt(now) ?: "…"}", color = TextSecondaryDark, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(if (arrived) "Chegaste a" else "Sais em", color = TextSecondaryDark, fontSize = 14.sp)
                    Text(exitName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    val big = when {
                        train == null -> "…"
                        arrived -> "✓"
                        arrive == null -> "…"
                        arrive - now <= 30_000 -> "a chegar"
                        else -> "${ceil((arrive - now) / 60_000.0).toInt()} min"
                    }
                    AnimatedContent(
                        targetState = big,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "contagem"
                    ) { text ->
                        Text(text, color = Color.White, fontSize = if (text.length > 6) 28.sp else 40.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        when {
                            train == null -> "A procurar o teu metro…"
                            arrived -> "boa viagem"
                            arrive != null -> "chegas às ${clockOf(arrive)}"
                            else -> ""
                        },
                        color = TextSecondaryDark, fontSize = 13.sp
                    )
                }
            }
            Column {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(CardBackground)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(metroLineColor(board.line)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(MetroLines.nameOf(board.fromStation), color = MUTED_TEXT, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (between > 0) Text(if (between == 1) "1 estação pelo meio" else "$between estações pelo meio", color = MUTED_TEXT, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(exitName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avisar uma estação antes", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = board.alarm, onCheckedChange = onAlarm,
                    colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue, checkedThumbColor = Color.White)
                )
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, OUTLINE)
            ) { Text("Terminar viagem", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── Estação ──────────────────────────────────────────────────────────────────

/** "agora" (até 30 s) ou "4 min", a partir dos segundos da API. */
private fun secondsText(s: Int): String = if (s <= 30) "agora" else "${(s + 30) / 60} min"

/** Separadores das linhas de uma estação, cada um na cor da sua linha (a cor desliza ao trocar). */
@Composable
private fun LineTabs(lines: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBackground).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lines.forEach { line ->
            val on = line == selected
            val bg by animateColorAsState(if (on) metroLineColor(line) else Color.Transparent, label = "separador")
            val fg by animateColorAsState(
                if (on) metroLineTextColor(line) else TextSecondaryDark, label = "texto"
            )
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable { onSelect(line) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!on) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(metroLineColor(line)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(metroLineName(line), color = fg, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A faixa de um sentido: as 3 estações antes desta e os metros a chegar, na posição real (os mesmos do mapa), a
 * deslizar. A estação fica à direita e pulsa quando o metro está a chegar.
 */
@Composable
private fun ApproachStrip(
    line: String, stationId: String, destinationName: String, trains: List<MetroTrain>,
    track: MetroLineTrack?, now: Long, arrivingNow: Boolean
) {
    if (track == null) return
    val here = track.indexOf(stationId)
    val dest = MetroLines.idByName(destinationName)?.let { track.indexOf(it) }?.takeIf { it >= 0 }
    if (here < 0 || dest == null || dest == here) return
    val dir = if (dest > here) 1 else -1
    // As estações antes desta, no sentido da viagem (a mais longe primeiro), e esta no fim
    val before = (1..3).map { here - dir * it }.filter { it in track.stations.indices }.reversed()
    if (before.isEmpty()) return
    val stops = before + here
    // Onde cai um metro na faixa (0 = a estação mais longe, 1 = esta), com as estações igualmente espaçadas
    fun fractionOf(s: Double): Float? {
        for (k in 0 until stops.size - 1) {
            val a = track.stationAt[stops[k]]
            val b = track.stationAt[stops[k + 1]]
            if (a == b) continue
            val t = (s - a) / (b - a)
            if (t in -0.001..1.001) return ((k + t.coerceIn(0.0, 1.0)) / (stops.size - 1)).toFloat()
        }
        return null
    }
    val approaching = trains.filter { it.line == line }.mapNotNull { tr ->
        val p = tr.placeAt(now, track) ?: return@mapNotNull null
        if (p.dir != dir) return@mapNotNull null
        fractionOf(p.s)?.let { tr.key to it }
    }
    val color = metroLineColor(line)
    val pulse by rememberInfiniteTransition(label = "chegada").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "anel"
    )
    // Cada metro desliza para a posição nova (as horas mudam a cada segundo)
    val xs = ArrayList<Float>()
    approaching.forEach { (k, f) ->
        key(k) {
            val v by animateFloatAsState(f, tween(1000, easing = androidx.compose.animation.core.LinearEasing), label = "metro")
            xs += v
        }
    }
    Column(Modifier.padding(top = 10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val pad = 12.dp.toPx()
            val w = size.width - 2 * pad
            val y = size.height / 2
            drawLine(color, Offset(pad, y), Offset(pad + w, y), 6.dp.toPx(), StrokeCap.Round)
            for (k in 0 until stops.size - 1) {
                val x = pad + w * k / (stops.size - 1)
                drawCircle(color, 6.5f * density, Offset(x, y))
                drawCircle(Color.White, 3.5f * density, Offset(x, y))
            }
            val hx = pad + w
            if (arrivingNow) drawCircle(color.copy(alpha = 0.5f * (1 - pulse)), 12.dp.toPx() * (1 + 0.5f * pulse), Offset(hx, y))
            drawCircle(color, 10.dp.toPx(), Offset(hx, y))
            drawCircle(Color.White, 6.dp.toPx(), Offset(hx, y))
            xs.forEach { f ->
                val x = pad + w * f
                val cw = 30.dp.toPx()
                val ch = 14.dp.toPx()
                val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x - cw / 2, y - ch / 2 + 2.dp.toPx()), androidx.compose.ui.geometry.Size(cw, ch), r)
                drawRoundRect(color, Offset(x - cw / 2, y - ch / 2), androidx.compose.ui.geometry.Size(cw, ch), r)
                val b = 2.dp.toPx()
                drawRoundRect(
                    Color.White, Offset(x - cw / 2 + b, y - ch / 2 + b), androidx.compose.ui.geometry.Size(cw - 2 * b, ch - 2 * b),
                    androidx.compose.ui.geometry.CornerRadius(3.5f * density)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            before.forEach { idx ->
                Text(
                    MetroLines.nameOf(track.stations[idx]), color = Color(0xFF8A8F98), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 4.dp)
                )
            }
            Spacer(Modifier.width(20.dp))
        }
    }
}

/** Um sentido: o próximo metro em grande (verde quando já está a chegar), a faixa e os seguintes. */
@Composable
private fun DirectionCard(
    line: String, stationId: String, direction: MetroLines.Direction, trains: List<MetroTrain>,
    track: MetroLineTrack?, now: Long, modifier: Modifier = Modifier
) {
    val first = direction.seconds.firstOrNull()
    Surface(color = CardBackground, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "→ ${direction.destination}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (first != null) {
                    AnimatedContent(
                        targetState = secondsText(first),
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "tempo"
                    ) { text ->
                        Text(
                            text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = if (first <= 60) Color(0xFF4ADE80) else Color.White
                        )
                    }
                }
            }
            ApproachStrip(line, stationId, direction.destination, trains, track, now, arrivingNow = first != null && first <= 60)
            val later = direction.seconds.drop(1)
            if (later.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Depois", color = TextSecondaryDark, fontSize = 13.sp)
                    later.forEach { s ->
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(SHEET).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(secondsText(s), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A estação aberta no mapa: o nome, as linhas em separadores (numa correspondência) e um cartão por sentido com o
 * próximo metro, a faixa dos que estão a chegar e os seguintes. Desenho: ecrã "Estação" do canvas no Claude Design.
 */
@Composable
internal fun MetroStationSheet(
    state: LiveMapViewModel.SelectedStationState,
    trains: List<MetroTrain>,
    tracks: Map<String, MetroLineTrack>,
    now: Long,
    onClose: () -> Unit
) {
    val lines = remember(state.id) { MetroLines.linesOf(state.id) }
    var selected by remember(state.id) { mutableStateOf(lines.firstOrNull() ?: "") }
    val groups = remember(state.arrivals) {
        state.arrivals?.let { MetroLines.groupByLine(state.id, it.arrivals) }.orEmpty()
    }
    val subtitle = when {
        lines.size > 1 -> "Correspondência · ${lines.dropLast(1).joinToString(", ") { metroLineName(it) }} e ${metroLineName(lines.last())}"
        lines.size == 1 -> "Linha ${metroLineName(lines.first())}"
        else -> ""
    }
    val drag = rememberSheetDrag(key = state.id)
    val density = LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).sheetDrag(drag, density).navigationBarsPadding(),
        color = SHEET,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.animateContentSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp)) {
            SheetHandle(drag, onClose, color = Color.Gray.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) Text(subtitle, color = TextSecondaryDark, fontSize = 13.sp)
                }
                // O caminho a pé até à estação, desde onde estás
                val walkContext = androidx.compose.ui.platform.LocalContext.current
                val stationAt = remember(state.id) { metroStationCoords(state.id) }
                if (stationAt != null) {
                    IconButton(onClick = {
                        walkContext.startActivity(WalkRouteActivity.newIntent(walkContext, stationAt.first, stationAt.second, state.name))
                    }) { Icon(com.example.metrotime.ui.theme.WalkIcon, contentDescription = "A pé até aqui", tint = MetrotimeBlue) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondaryDark) }
            }
            if (lines.size > 1) {
                Spacer(Modifier.height(12.dp))
                LineTabs(lines, selected) { selected = it }
            }
            if (com.example.metrotime.data.metro.PortoMetro.isPorto(state.id)) {
                Text(
                    "Pelo horário oficial · o Metro do Porto não publica tempos em direto",
                    color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (state.arrivals?.isEstimated == true) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF4A3500))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = AMBER, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tempos estimados (a API do Metro não responde)", color = AMBER, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.loading && state.arrivals == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            } else {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        val forward = lines.indexOf(targetState) > lines.indexOf(initialState)
                        (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                    },
                    label = "linha"
                ) { line ->
                    val directions = groups.firstOrNull { it.first == line }?.second.orEmpty()
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (directions.isEmpty()) {
                            Text(
                                "Não há metros previstos nesta linha.", color = TextSecondaryDark, fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        directions.forEachIndexed { i, d ->
                            DirectionCard(line, state.id, d, trains, tracks[line], now, modifier = Modifier.staggerIn(i, state.id + line))
                        }
                    }
                }
            }
        }
    }
}
