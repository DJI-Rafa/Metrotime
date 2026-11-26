package com.example.metrotime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.cp.CpLive
import com.example.metrotime.data.cp.CpLiveTrain
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpRouteGeometry
import com.example.metrotime.data.cp.CpSmoother
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.CpTone
import com.example.metrotime.data.cp.CpTrack
import com.example.metrotime.data.cp.CpTrainNow
import com.example.metrotime.data.cp.CpTrainStop
import com.example.metrotime.data.cp.CpTripClock
import com.example.metrotime.data.cp.VehicleStatus
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.prefs.Prefs
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────────────────
// Comboios CP no Mapa ao Vivo — porta do mapa do CP Bot (assets/map/map.js) para o MapLibre nativo.
// Nas etiquetas, em vez do número do comboio aparece o tipo de serviço (AP, IC, IR, R, U).
// ──────────────────────────────────────────────────────────────────────────────

/** Um comboio CP em circulação, como aparece no mapa. */
data class CpMapTrain(
    val number: Int,
    val lat: Double,
    val lon: Double,
    val bearing: Double?,
    val service: String,
    val serviceName: String?,
    val delaySeconds: Int?,
    val status: String?,
    val origin: String?,
    val destination: String?,
    val lastStationCode: String?,
    val runDate: String?,
    /** O GPS tal como veio (lat/lon acima podem estar na estação ou agarrados ao carril) e a hora dele (ms). */
    val gpsLat: Double = lat,
    val gpsLon: Double = lon,
    val gpsAt: Long? = null,
    /** A hora dos dados (ms): a da Cloud Function, ou a da leitura direta. */
    val dataAt: Long = 0L,
    /** A última passagem registada pela CP: estação, hora "HH:mm:ss" e se já saiu de lá. */
    val passCode: String? = null,
    val passTime: String? = null,
    val passLeft: Boolean? = null
) {
    val tone: CpTone get() = CpTone.ofSeconds(delaySeconds, hasRealtime = status != VehicleStatus.NOT_STARTED)
    val moving: Boolean get() = bearing != null && status != VehicleStatus.AT_STATION && status != VehicleStatus.AT_ORIGIN
}

private val CP_GREEN = Color(0xFF4ADE80)
private const val CP_GREEN_HEX = "#4ade80"
private val SEA = Color(0xFF0E0E0E)
private val PANEL = Color(0xFF161817)
private val PANEL_LINE = Color(0xFF2A2E2C)
private val MUTED = Color(0xFF8E9893)

fun CpTone.color(): Color = when (this) {
    CpTone.ON_TIME -> Color(0xFF4FA862)
    CpTone.LATE -> Color(0xFFE0A43A)
    CpTone.VERY_LATE -> Color(0xFFFF8A7D)
    CpTone.IDLE -> Color(0xFF6F7C76)
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

// ── Camadas ──────────────────────────────────────────────────────────────────

private val CP_LAYERS = listOf(
    "cp-network", "cp-stations", "cp-route-ahead", "cp-route-done", "cp-route-stops",
    "cp-route-names", "cp-train-heading", "cp-trains", "cp-train-dots"
)

/** Abaixo deste zoom os comboios são pontos (verde a horas, vermelho com 5 min ou mais) em vez das etiquetas. */
const val CP_DOT_MAX_ZOOM = 9f
/** A partir de quanto atraso o ponto fica vermelho. */
private const val CP_DOT_LATE_S = 5 * 60
private const val CP_DOT_GREEN = "#4ADE80"
private const val CP_DOT_RED = "#FF5252"
private const val CP_DOT_IDLE = "#8E9893"

/** Cor do ponto de um comboio visto de longe: verde até 5 min de atraso, vermelho daí para cima. */
internal fun cpDotColor(delaySeconds: Int?, started: Boolean): String = when {
    !started || delaySeconds == null -> CP_DOT_IDLE
    delaySeconds >= CP_DOT_LATE_S -> CP_DOT_RED
    else -> CP_DOT_GREEN
}

/** Rede e estações, por baixo das linhas do metro. */
fun setupCpBaseLayers(style: Style) {
    if (style.getSource("cp-network-src") != null) return
    val empty = FeatureCollection.fromFeatures(emptyList())
    style.addSource(GeoJsonSource("cp-network-src", empty))
    style.addLayer(
        LineLayer("cp-network", "cp-network-src").withProperties(
            lineColor("#3d5a48"),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(4f, 0.8f), Expression.stop(8f, 1.6f), Expression.stop(12f, 2.4f))),
            lineOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(12f, 0.9f), Expression.stop(14f, 0.35f)))
        )
    )
    style.addSource(GeoJsonSource("cp-stations-src", empty))
    style.addLayer(
        CircleLayer("cp-stations", "cp-stations-src").withProperties(
            circleRadius(2.5f),
            circleColor("#5a7364")
        ).also { it.minZoom = 8f }
    )
}

/** Percurso do comboio aberto e os comboios, por cima de tudo. */
fun setupCpTopLayers(context: Context, style: Style) {
    if (style.getSource("cp-route-src") != null) return
    val empty = FeatureCollection.fromFeatures(emptyList())
    style.addSource(GeoJsonSource("cp-route-src", empty))
    style.addLayer(
        LineLayer("cp-route-ahead", "cp-route-src").withFilter(Expression.eq(Expression.get("part"), "ahead")).withProperties(
            lineColor(CP_GREEN_HEX), lineWidth(4f), lineOpacity(0.75f), lineJoin(Property.LINE_JOIN_ROUND),
            lineDasharray(arrayOf(1.6f, 1.4f))
        )
    )
    style.addLayer(
        LineLayer("cp-route-done", "cp-route-src").withFilter(Expression.eq(Expression.get("part"), "done")).withProperties(
            lineColor(CP_GREEN_HEX), lineWidth(5f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    style.addLayer(
        CircleLayer("cp-route-stops", "cp-route-src").withFilter(Expression.eq(Expression.get("part"), "stop")).withProperties(
            circleRadius(Expression.match(Expression.get("state"), Expression.literal(5f), Expression.stop("current", 6.5f))),
            circleColor(Expression.match(Expression.get("state"), Expression.literal("#ffffff"), Expression.stop("ahead", "#0e0e0e"))),
            circleStrokeColor(CP_GREEN_HEX),
            circleStrokeWidth(Expression.match(Expression.get("state"), Expression.literal(2.5f), Expression.stop("passed", 0f)))
        )
    )
    style.addLayer(
        SymbolLayer("cp-route-names", "cp-route-src").withFilter(Expression.eq(Expression.get("part"), "stop")).withProperties(
            textField(Expression.get("label")),
            textFont(arrayOf("Montserrat Medium")),
            textSize(11f),
            textAnchor(Property.TEXT_ANCHOR_LEFT),
            textOffset(arrayOf(0.9f, 0f)),
            textOptional(true),
            textColor("#d9e4de"),
            textHaloColor("#0e0e0e"),
            textHaloWidth(1.4f)
        ).also { it.minZoom = 7.5f }
    )

    style.addSource(GeoJsonSource("cp-trains-src", empty))
    // De longe: um ponto por comboio, verde a horas e vermelho com 5 min ou mais de atraso (o aberto, maior)
    style.addLayer(
        CircleLayer("cp-train-dots", "cp-trains-src").withProperties(
            circleColor(Expression.toColor(Expression.get("dot"))),
            circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(5f, Expression.switchCase(Expression.eq(Expression.get("z"), 2), Expression.literal(6f), Expression.literal(3.5f))),
                    Expression.stop(CP_DOT_MAX_ZOOM, Expression.switchCase(Expression.eq(Expression.get("z"), 2), Expression.literal(8f), Expression.literal(5.5f)))
                )
            ),
            circleStrokeColor("#0E0E0E"),
            circleStrokeWidth(1.5f)
        ).also { it.maxZoom = CP_DOT_MAX_ZOOM }
    )
    // Seta de direção: um triângulo a espreitar da etiqueta para onde o comboio vai
    style.addLayer(
        SymbolLayer("cp-train-heading", "cp-trains-src").withFilter(Expression.eq(Expression.get("moving"), true)).withProperties(
            iconImage(Expression.get("arrow")),
            iconRotate(Expression.get("bearing")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            symbolSortKey(Expression.get("z"))
        ).also { it.minZoom = CP_DOT_MAX_ZOOM }
    )
    style.addLayer(
        SymbolLayer("cp-trains", "cp-trains-src").withProperties(
            iconImage(Expression.get("icon")),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            symbolSortKey(Expression.get("z"))
        ).also { it.minZoom = CP_DOT_MAX_ZOOM }
    )
}

/**
 * Desenha a rede (troços e estações) — pesado, por isso a Activity chama isto fora da thread principal, e devolve já
 * o texto GeoJSON (passar os objetos ao mapa convertia-os na thread principal: ~1 s ao abrir o mapa).
 */
fun buildCpNetwork(context: Context): Pair<String, String> {
    val lines = CpNetwork.networkLines(context).map { line -> line.map { Point.fromLngLat(it.second, it.first) } }
    val network = Feature.fromGeometry(MultiLineString.fromLngLats(lines))
    val stations = FeatureCollection.fromFeatures(CpNetwork.stations(context).map { s ->
        Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply { addStringProperty("name", s.name) }
    })
    return network.toJson() to stations.toJson()
}

fun applyCpNetwork(map: MapLibreMap, network: Pair<String, String>) {
    val style = map.style ?: return
    style.getSourceAs<GeoJsonSource>("cp-network-src")?.setGeoJson(network.first)
    style.getSourceAs<GeoJsonSource>("cp-stations-src")?.setGeoJson(network.second)
}

/** Mostra/esconde grupos de camadas conforme os filtros. */
fun applyMapFilters(map: MapLibreMap, f: Prefs.MapFilters) {
    val style = map.style ?: return
    fun show(layer: Layer?, visible: Boolean) {
        layer?.setProperties(visibility(if (visible) Property.VISIBLE else Property.NONE))
    }
    style.layers.forEach { layer ->
        val id = layer.id
        when {
            id.startsWith("line-layer-") || id == "stations-layer" -> show(layer, f.metro)
            id == PORTO_LINES_LAYER || id == PORTO_STATIONS_LAYER -> show(layer, portoMetroShown(f))
            // Os metros das duas cidades partilham as camadas (cada linha filtra-se ao desenhar)
            id.startsWith("metro-") -> show(layer, f.metro || portoMetroShown(f))
            id in CP_LAYERS -> show(layer, f.cp)
            id == "friends-layer" -> show(layer, f.friends)
            id.startsWith("regional-") -> show(layer, f.regional)
        }
    }
}

// ── Etiquetas e setas (bitmaps) ───────────────────────────────────────────────

private fun toneArgb(tone: CpTone): Int = when (tone) {
    CpTone.ON_TIME -> 0xFF4FA862.toInt()
    CpTone.LATE -> 0xFFE0A43A.toInt()
    CpTone.VERY_LATE -> 0xFFFF8A7D.toInt()
    CpTone.IDLE -> 0xFF6F7C76.toInt()
}

private fun pillBitmap(context: Context, label: String, tone: CpTone, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B100E.toInt()
        textSize = (if (selected) 12f else 11f) * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val h = (if (selected) 22f else 20f) * d
    val w = maxOf((if (selected) 40f else 34f) * d, textPaint.measureText(label) + 12f * d)
    val ring = 2f * d
    val bmp = Bitmap.createBitmap((w + 2 * ring).toInt() + 2, (h + 2 * ring).toInt() + 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val outer = RectF(1f, 1f, w + 2 * ring + 1f, h + 2 * ring + 1f)
    canvas.drawRoundRect(outer, outer.height() / 2, outer.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) 0xFF4ADE80.toInt() else 0xFF0E0E0E.toInt()
    })
    val inner = RectF(outer.left + ring, outer.top + ring, outer.right - ring, outer.bottom - ring)
    canvas.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) 0xFFFFFFFF.toInt() else toneArgb(tone)
    })
    val y = inner.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(label, inner.centerX(), y, textPaint)
    return bmp
}

private fun arrowBitmap(context: Context, tone: CpTone, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (64 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = size / 2f
    val path = Path().apply {
        moveTo(c, c - 26 * d)
        lineTo(c + 6 * d, c - 17 * d)
        lineTo(c - 6 * d, c - 17 * d)
        close()
    }
    Canvas(bmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) 0xFFFFFFFF.toInt() else toneArgb(tone)
    })
    return bmp
}

/** As imagens das etiquetas já postas em cada estilo do mapa (perguntar ao mapa a cada imagem do ecrã sai caro). */
private val cpImagesAdded = java.util.WeakHashMap<Style, MutableSet<String>>()

private fun ensureCpImage(style: Style, name: String, make: () -> android.graphics.Bitmap) {
    val added = cpImagesAdded.getOrPut(style) { HashSet() }
    if (name in added) return
    if (style.getImage(name) == null) style.addImage(name, make())
    added += name
}

/** Um comboio no mapa: etiqueta com o tipo de serviço na cor do atraso e a seta de direção. */
internal fun cpFeature(
    context: Context, style: Style, number: Int, service: String, tone: CpTone,
    lat: Double, lon: Double, bearing: Double?, moving: Boolean, isSelected: Boolean,
    /** Cor do ponto de longe ([cpDotColor]). */
    dot: String = CP_DOT_IDLE,
    /** Comboios que não são da CP (Fertagus): a chave do veículo, para abrir o painel certo. */
    key: String? = null
): Feature {
    val icon = "cp-pill-$service-${tone.name}-$isSelected"
    ensureCpImage(style, icon) { pillBitmap(context, service, tone, isSelected) }
    val arrow = "cp-arrow-${tone.name}-$isSelected"
    ensureCpImage(style, arrow) { arrowBitmap(context, tone, isSelected) }
    return Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
        addStringProperty("number", number.toString())
        key?.let { addStringProperty("key", it) }
        addStringProperty("icon", icon)
        addStringProperty("arrow", arrow)
        addStringProperty("dot", dot)
        addNumberProperty("bearing", bearing ?: 0.0)
        addBooleanProperty("moving", moving && bearing != null)
        addNumberProperty("z", if (isSelected) 2 else 1)
    }
}

/**
 * Um comboio que não é da CP (a Fertagus, do GO Hub da TML) desenhado como os da CP: etiqueta "FT" na cor do atraso
 * e, de longe, o ponto verde ou vermelho. Abre pelo [key] (o painel das outras redes, com as paragens do horário).
 */
data class ExtraTrain(
    val key: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val delaySeconds: Int?,
    val selected: Boolean
) {
    fun feature(context: Context, style: Style): Feature {
        val known = delaySeconds != null
        val tone = if (known) CpTone.ofSeconds(delaySeconds, hasRealtime = true) else CpTone.ON_TIME
        return cpFeature(
            context, style, 0, label, tone, lat, lon, null, false, selected,
            dot = if (known) cpDotColor(delaySeconds, true) else CP_DOT_GREEN, key = key
        )
    }
}

/** Atualiza os comboios no mapa de uma vez, onde os dados os põem (a primeira imagem, antes de o desenhador arrancar). */
fun updateCpTrains(context: Context, map: MapLibreMap, trains: List<CpMapTrain>, selected: Int?) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>("cp-trains-src") ?: return
    val features = trains.map { t ->
        cpFeature(
            context, style, t.number, t.service, t.tone, t.lat, t.lon, t.bearing, t.moving, t.number == selected,
            dot = cpDotColor(t.delaySeconds, t.status != VehicleStatus.NOT_STARTED)
        )
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

/**
 * Desenha os comboios CP a cada imagem. Os que têm percurso ([CpLiveTrain]) andam pelo traçado com a mesma conta do
 * painel: entre atualizações continuam a andar ao ritmo do horário, a partir do último sítio conhecido, e nunca para
 * trás nem aos saltos ([CpSmoother]). Os outros ficam no GPS, agarrado ao carril.
 */
class CpTrainsRenderer {
    private val smoother = CpSmoother()
    private val lastModel = HashMap<Int, CpLiveTrain>()
    private var drewEmpty = false
    /** O último desenho (posições arredondadas e etiquetas): igual, não se envia outra vez. */
    private var lastSignature = 0L

    fun render(
        context: Context, map: MapLibreMap, trains: List<CpMapTrain>, live: Map<Int, CpLiveTrain>,
        selected: Int?, nowMs: Long, dtSec: Double,
        /** Comboios de outros operadores (Fertagus) com a mesma etiqueta. */
        extra: List<ExtraTrain> = emptyList()
    ) {
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>("cp-trains-src") ?: return
        if (trains.isEmpty() && extra.isEmpty()) {
            if (!drewEmpty) source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            drewEmpty = true
            smoother.retain(emptySet())
            lastModel.clear()
            return
        }
        drewEmpty = false
        val now = CpLive.lisbon(nowMs)
        val features = ArrayList<Feature>(trains.size)
        // Só os que estão à vista (com margem): de perto são poucos dos ~200 que andam no país
        val box = map.projection.visibleRegion.latLngBounds.let { b ->
            val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.6
            val dLon = (b.longitudeEast - b.longitudeWest) * 0.6
            org.maplibre.android.geometry.LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
        }
        var signature = 17L
        fun sign(number: Int, lat: Double, lon: Double, extra: Int) {
            signature = signature * 31 + number
            signature = signature * 31 + Math.round(lat * 20_000)
            signature = signature * 31 + Math.round(lon * 20_000)
            signature = signature * 31 + extra
        }
        for (t in trains) {
            if (t.number != selected && !box.contains(org.maplibre.android.geometry.LatLng(t.lat, t.lon))) continue
            var lat = t.lat
            var lon = t.lon
            var bearing = t.bearing
            var moving = t.moving
            var tone = t.tone
            var dot = cpDotColor(t.delaySeconds, t.status != VehicleStatus.NOT_STARTED)
            val model = live[t.number]
            val n = model?.now(now)
            val s = if (model != null && n != null) model.metersOf(n) else null
            if (model != null && n != null && s != null) {
                val newData = lastModel[t.number] !== model
                lastModel[t.number] = model
                val shown = smoother.step(t.number, s, dtSec, newData)
                val track = model.geometry.track
                val p = track.pointAt(shown)
                lat = p.first
                lon = p.second
                val still = n.atStation || n.finished
                bearing = if (still) null else track.bearingAt(shown)
                moving = !still
                // A cor do atraso: a do painel (o do próximo acontecimento)
                tone = CpTone.ofSeconds(n.delayMinutes?.times(60) ?: t.delaySeconds, hasRealtime = n.started)
                dot = cpDotColor(n.delayMinutes?.times(60) ?: t.delaySeconds, n.started)
            } else {
                smoother.forget(t.number)
                lastModel.remove(t.number)
            }
            features += cpFeature(context, style, t.number, t.service, tone, lat, lon, bearing, moving, t.number == selected, dot = dot)
            sign(t.number, lat, lon, (tone.ordinal * 7 + dot.hashCode()) * 31 + (bearing?.toInt() ?: -1) + (if (t.number == selected) 1_000 else 0))
        }
        // Fertagus: mesma etiqueta e o mesmo ponto, com "FT"
        for (f in extra) {
            if (!f.selected && !box.contains(org.maplibre.android.geometry.LatLng(f.lat, f.lon))) continue
            features += f.feature(context, style)
            sign(f.key.hashCode(), f.lat, f.lon, (f.delaySeconds ?: -1) + if (f.selected) 1_000_000 else 0)
        }
        val keys = trains.mapTo(HashSet()) { it.number }
        smoother.retain(keys)
        lastModel.keys.retainAll(keys)
        signature = signature * 31 + features.size
        if (signature == lastSignature) return
        lastSignature = signature
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}

// ── Percurso do comboio aberto ───────────────────────────────────────────────

/** Coordenadas de uma paragem: as da CP ou, se faltarem, as da rede GTFS. */
private fun stopPoint(context: Context, stop: CpTrainStop): Pair<Double, Double>? {
    val lat = stop.latitude?.toDoubleOrNull()
    val lon = stop.longitude?.toDoubleOrNull()
    if (lat != null && lon != null && lat != 0.0) return lat to lon
    val code = stop.station?.code ?: return null
    return CpNetwork.stations(context).find { it.code == code }?.let { it.lat to it.lon }
}

/**
 * Percurso de um comboio: o traçado real (GTFS, como no CP Bot) ou, sem ele, linhas retas entre as paragens, e onde
 * cada paragem cai nesse traçado. Calculado fora da thread principal.
 */
fun cpRouteGeometry(context: Context, number: Int, stops: List<CpTrainStop>): CpRouteGeometry? {
    val points = stops.map { stopPoint(context, it) }
    val track = runCatching { CpNetwork.trackForTrain(context, number) }.getOrNull()
    return CpRouteGeometry.of(track, points)
}

/** Coordenadas de uma estação pela rede GTFS (já carregada). */
fun cpStationPoint(code: String): Pair<Double, Double>? = CpNetwork.stationsByCode[code]?.let { it.lat to it.lon }

/**
 * O comboio aberto com o percurso da CP (as horas reais e as previstas de cada paragem) e o que vem com ele no mapa
 * (GPS com a hora, última passagem). O painel e o mapa usam este mesmo comboio: dizem sempre o mesmo.
 */
fun cpLiveOf(state: LiveMapViewModel.CpTripState?, train: CpMapTrain?): CpLiveTrain? {
    val trip = state?.trip ?: return null
    val stops = trip.trainStops.orEmpty()
    val runDate = state.runDate ?: return null
    val geometry = state.geometry ?: return null
    if (stops.size < 2) return null
    return CpLive.build(
        number = state.number,
        stops = stops,
        runDate = runDate,
        geometry = geometry,
        lastStationCode = trip.lastStationCode ?: train?.lastStationCode,
        status = cpStatusOf(trip.status, train),
        gpsPoint = train?.let { it.gpsLat to it.gpsLon },
        gpsAt = train?.gpsAt?.let { CpLive.lisbon(it) },
        passCode = train?.passCode,
        passTime = train?.passTime,
        passLeft = train?.passLeft,
        dataAt = train?.dataAt?.takeIf { it > 0 }?.let { CpLive.lisbon(it) } ?: CpTime.now(),
        stationPoint = ::cpStationPoint
    )
}

/** Suprimido pela CP manda sobre o estado do GPS. */
private fun cpStatusOf(tripStatus: String?, train: CpMapTrain?): String? =
    if (tripStatus == VehicleStatus.CANCELLED) VehicleStatus.CANCELLED else train?.status ?: tripStatus

/**
 * Onde está o comboio aberto e quanto vai atrasado: a mesma conta do ecrã do comboio e dos avisos
 * ([CpTrainNow]: a última estação da CP, as horas das paragens e o GPS só para o ponto do troço).
 */
fun cpNow(state: LiveMapViewModel.CpTripState?, train: CpMapTrain?, now: LocalDateTime = CpTime.now()): CpTrainNow? {
    cpLiveOf(state, train)?.let { return it.now(now) }
    // Sem traçado: só as horas e a última estação
    val trip = state?.trip ?: return null
    val stops = trip.trainStops.orEmpty()
    val runDate = state.runDate ?: return null
    if (stops.isEmpty()) return null
    return CpTrainNow.of(
        stops, CpTripClock.moments(runDate, stops), trip.lastStationCode ?: train?.lastStationCode,
        cpStatusOf(trip.status, train), null, now
    )
}

/** A posição de [cpNow] em paragens (3.4 = 40 % entre a 4.ª e a 5.ª; -1 = ainda não saiu). */
fun cpProgress(state: LiveMapViewModel.CpTripState?, train: CpMapTrain?, now: LocalDateTime = CpTime.now()): Float =
    cpNow(state, train, now)?.position ?: -1f


fun buildCpRoute(context: Context, state: LiveMapViewModel.CpTripState?, train: CpMapTrain?): FeatureCollection {
    val trip = state?.trip
    val stops = trip?.trainStops.orEmpty()
    if (state == null || stops.isEmpty()) return FeatureCollection.fromFeatures(emptyList())
    val pos = cpProgress(state, train)
    val points = stops.map { stopPoint(context, it) }
    val features = mutableListOf<Feature>()
    fun line(part: String, coords: List<Pair<Double, Double>>) {
        if (coords.size > 1) features.add(Feature.fromGeometry(LineString.fromLngLats(coords.map { Point.fromLngLat(it.second, it.first) })).apply {
            addStringProperty("part", part)
        })
    }

    // Já percorrido a cheio até onde o comboio está; o resto tracejado
    val geometry = state.geometry
    if (geometry != null) {
        val track = geometry.track
        val at = geometry.stopAt
        val known = at.filterNotNull()
        val start = known.first()
        val end = known.last()
        // Corta onde o comboio está (o mesmo ponto onde o mapa o desenha)
        val here: Double? = if (pos >= 0) geometry.metersAt(pos) else null
        val split = if (pos < 0) start else (here ?: start).coerceIn(start, end)
        line("done", track.slice(start, split))
        line("ahead", track.slice(split, end))
    }

    val runDate = state.runDate
    val moments = if (runDate != null) CpTripClock.moments(runDate, stops) else null
    stops.forEachIndexed { i, stop ->
        val p = points[i] ?: return@forEachIndexed
        val stopState = when {
            pos < 0 -> "ahead"
            kotlin.math.abs(i - pos) < 1e-4 -> "current"
            i < pos -> "passed"
            else -> "ahead"
        }
        val time = moments?.get(i)?.departureOrArrival?.format(HM)
        val name = formatStationName(stop.station?.designation ?: "")
        features.add(Feature.fromGeometry(Point.fromLngLat(p.second, p.first)).apply {
            addStringProperty("part", "stop")
            addStringProperty("state", stopState)
            addStringProperty("label", if (time != null) "$name · $time" else name)
        })
    }
    return FeatureCollection.fromFeatures(features)
}

fun applyCpRoute(map: MapLibreMap, route: FeatureCollection) {
    map.style?.getSourceAs<GeoJsonSource>("cp-route-src")?.setGeoJson(route)
}

// ── Painel do comboio aberto ─────────────────────────────────────────────────

private fun whereText(train: CpMapTrain?, stops: List<CpTrainStop>, now: CpTrainNow?): String {
    fun name(i: Int) = stops.getOrNull(i)?.station?.designation?.let { formatStationName(it) }
    if (now == null) return if (train == null) "Já não está em circulação." else ""
    return when {
        now.cancelled -> "Suprimido: não se realiza hoje."
        now.finished -> name(now.stopIndex)?.let { "Chegou a $it." } ?: ""
        !now.started -> "Ainda não saiu de ${name(0) ?: train?.origin ?: "?"}."
        now.atStation && now.arriving -> name(now.stopIndex)?.let { "A chegar a $it." } ?: ""
        now.atStation -> name(now.stopIndex)?.let { "Parado em $it." } ?: ""
        else -> {
            val a = name(now.stopIndex)
            val b = name(now.stopIndex + 1)
            if (a != null && b != null) "Entre $a e $b." else ""
        }
    }
}

/** O atraso do painel: o do próximo acontecimento (o mesmo da lista), ou o do GPS se não houver horas. */
private fun delayBadge(train: CpMapTrain?, now: CpTrainNow?): Pair<String, CpTone> {
    val minutes = now?.delayMinutes ?: train?.delaySeconds?.let { Math.round(it / 60.0).toInt() }
    val tone = if (now?.cancelled == true) CpTone.VERY_LATE
    else if (train == null && now?.finished != false) CpTone.IDLE
    else CpTone.ofSeconds(minutes?.times(60), hasRealtime = now?.started ?: (train?.status != VehicleStatus.NOT_STARTED))
    val text = when {
        now?.cancelled == true -> "Suprimido"
        train == null && (now == null || now.finished) -> "Terminado"
        now != null && !now.started && (minutes ?: 0) <= 0 -> "Programado"
        minutes == null -> "Sem dados"
        minutes < 2 -> "A horas"
        else -> "+$minutes min"
    }
    return text to tone
}

@Composable
fun CpTrainSheet(
    number: Int,
    train: CpMapTrain?,
    state: LiveMapViewModel.CpTripState?,
    onClose: () -> Unit,
    onDetails: (runDate: String?) -> Unit,
    onFollow: (runDate: String, stops: List<CpTrainStop>) -> Unit,
    /** A cor do painel: o verde da CP; o azul da Fertagus nos comboios dela. */
    accent: Color = CP_GREEN,
    /** "Detalhes" e "Seguir comboio" (usam a API da CP: na Fertagus não há). */
    actions: Boolean = true,
    /** De onde vêm os dados, em baixo (a Fertagus vem do GO Hub da TML, com o horário). */
    footnote: String? = null
) {
    val trip = state?.trip
    val stops = trip?.trainStops.orEmpty()
    var now by remember { mutableStateOf(CpTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(5_000); now = CpTime.now() }
    }
    val trainNow = remember(state, train, now) { cpNow(state, train, now) }
    val pos = trainNow?.position ?: -1f
    val moments = remember(state) { state?.runDate?.let { CpTripClock.moments(it, stops) } }
    val service = train?.service ?: CpService.shortName(trip?.effectiveService?.code)
    val (badgeText, tone) = delayBadge(train, trainNow)
    val destination = train?.destination ?: stops.lastOrNull()?.station?.designation?.let { formatStationName(it) } ?: "?"
    val origin = train?.origin ?: stops.firstOrNull()?.station?.designation?.let { formatStationName(it) } ?: "?"

    val drag = rememberSheetDrag(key = number)
    val density = LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).sheetDrag(drag, density),
        color = PANEL,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PANEL_LINE)
    ) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp).navigationBarsPadding()) {
            // Pega: puxar para baixo fecha
            SheetHandle(drag, onClose, color = PANEL_LINE, height = 24.dp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${CpService.longName(service)} → $destination",
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("Comboio $number · $origin → $destination", color = MUTED, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(tone.color())
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(badgeText, color = Color(0xFF0B100E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White) }
            }
            Text(whereText(train, stops, trainNow), color = MUTED, fontSize = 14.sp)
            if (trip?.offline == true) {
                Text("A CP não responde: atrasos pelo GPS do comboio.", color = Color(0xFFFFB74D), fontSize = 12.sp)
            }
            // Próxima paragem, com a mesma hora da lista (a partida; no fim do percurso, a chegada) e a linha
            val nextIdx = trainNow?.nextIndex ?: 0
            val next = stops.getOrNull(nextIdx)
            if (next != null && moments != null && trainNow?.finished != true && trainNow?.cancelled != true) {
                val m = moments[nextIdx]
                val last = nextIdx == stops.lastIndex
                val at = (if (last) m.arrivalOrDeparture else m.departureOrArrival)?.format(HM)?.let { if (m.estimated) "~$it" else it }
                val name = formatStationName(next.station?.designation ?: "")
                val label = when {
                    trainNow == null || !trainNow.started || trainNow.atStation -> "Parte de $name às $at"
                    last -> "Chega a $name às $at"
                    else -> "Próxima: $name às $at"
                }
                val platform = next.platform?.takeIf { it.isNotBlank() && it != "0" }?.let { " · Linha $it" } ?: ""
                Text(label + platform, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }

            if (actions) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDetails(state?.runDate?.toString() ?: train?.runDate) }) {
                        Text("Detalhes", color = Color.White)
                    }
                    val runDate = state?.runDate
                    if (runDate != null && stops.isNotEmpty()) {
                        Button(
                            onClick = { onFollow(runDate.toString(), stops) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) { Text("Seguir comboio", color = Color(0xFF0B100E), fontWeight = FontWeight.Bold) }
                    }
                }
            }

            if (stops.isEmpty()) {
                Text(
                    if (state?.loading != false) "A carregar as paragens…" else "Sem paragens disponíveis.",
                    color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp)
                )
            } else {
                Spacer(Modifier.height(10.dp))
                CpRailStops(stops = stops, moments = moments, pos = if (train == null) -1f else pos, badge = service, accent = accent)
            }
            footnote?.let { Text(it, color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

private val ROW_HEIGHT = 52.dp

/**
 * Paragens com uma linha verde por onde o comboio já passou, tracejada por onde vai passar,
 * e o comboio (etiqueta com o tipo de serviço) em cima dela — como no CP Bot.
 */
@Composable
fun CpRailStops(
    stops: List<CpTrainStop>, moments: List<CpTripClock.StopMoment>?, pos: Float, badge: String,
    /** A cor da linha e da etiqueta do comboio (verde da CP; azul da Fertagus). */
    accent: Color = CP_GREEN
) {
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Centro de cada linha (px), medido: com letra grande as linhas crescem e o comboio tem de as acompanhar
    val centers = remember(stops.size) { mutableStateListOf<Float?>().apply { repeat(stops.size) { add(null) } } }
    fun centerAt(p: Float): Float? {
        val i = p.toInt().coerceIn(0, stops.lastIndex)
        val a = centers.getOrNull(i) ?: return null
        val b = centers.getOrNull(minOf(stops.lastIndex, i + 1)) ?: return a
        return a + (b - a) * (p - i)
    }
    // Começa com o comboio à vista
    val measured = centers.all { it != null }
    LaunchedEffect(pos >= 0, stops.size, measured) {
        if (pos > 1 && measured) centerAt(pos - 1f)?.let { scroll.scrollTo((it - with(density) { ROW_HEIGHT.toPx() } / 2).toInt().coerceAtLeast(0)) }
    }
    Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(scroll)) {
        Column {
            stops.forEachIndexed { i, stop ->
                val state = when {
                    pos < 0 -> "ahead"
                    kotlin.math.abs(i - pos) < 1e-4 -> "current"
                    i < pos -> "passed"
                    else -> "ahead"
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).height(IntrinsicSize.Min)
                        .onGloballyPositioned { centers[i] = it.positionInParent().y + it.size.height / 2f },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        RailSegment(i, stops.lastIndex, pos, accent)
                        val size = if (state == "current") 16.dp else 12.dp
                        Box(
                            Modifier.size(size).clip(CircleShape)
                                .background(if (state == "ahead") PANEL else Color.White)
                                .border(2.dp, if (state == "ahead") accent.copy(alpha = 0.55f) else Color.White, CircleShape)
                        )
                    }
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(
                            formatStationName(stop.station?.designation ?: "?"),
                            color = when (state) { "passed" -> MUTED; "current" -> Color.White; else -> Color(0xFFE8ECEA) },
                            fontWeight = if (state == "current") FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp,
                            textDecoration = if (stop.supression != null) TextDecoration.LineThrough else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val meta = listOfNotNull(
                            stop.platform?.takeIf { it.isNotBlank() && it != "0" }?.let { "Linha $it" },
                            if (stop.supression != null) "Suprimida" else null
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = MUTED, fontSize = 12.sp)
                    }
                    val m = moments?.getOrNull(i)
                    val planned = m?.scheduledDeparture ?: m?.scheduledArrival
                    // Sem hora real da CP a hora esperada é uma estimativa (atraso das vizinhas):
                    // mostra-se com "~" e nunca nas paragens já passadas
                    val guessed = m?.estimated == true
                    val shown = if (guessed && state == "passed") planned else m?.departure ?: m?.arrival ?: planned
                    val late = planned != null && shown != null && Duration.between(planned, shown).toMinutes() >= 2
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (late) {
                            Text(planned!!.format(HM), color = MUTED, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
                            Spacer(Modifier.width(6.dp))
                            Text((if (guessed) "~" else "") + shown!!.format(HM), color = CpTone.VERY_LATE.color(), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        } else {
                            Text(shown?.format(HM) ?: "", color = if (state == "passed") MUTED else Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (pos >= 0) {
            // A etiqueta do comboio, em cima da linha, onde ele está (nas posições medidas das linhas)
            val y = centerAt(pos)?.let { with(density) { it.toDp() } } ?: (ROW_HEIGHT * pos + ROW_HEIGHT / 2)
            Box(
                Modifier
                    .offset(x = 28.dp - 20.dp, y = y - 11.dp)
                    .width(40.dp).height(22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .border(2.dp, accent, RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(badge, color = Color(0xFF0B0F0D), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }
    }
}

/** Metade de cima e metade de baixo do segmento de linha de uma paragem. */
@Composable
private fun RailSegment(index: Int, lastIndex: Int, pos: Float, accent: Color = CP_GREEN) {
    Canvas(Modifier.fillMaxSize()) {
        val x = size.width / 2
        val mid = size.height / 2
        val stroke = 3.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))
        fun draw(fromY: Float, toY: Float, fromPos: Float, toPos: Float) {
            // Parte já percorrida (até pos) a cheio; o resto tracejado
            val cut = when {
                pos < 0 || pos <= fromPos -> fromY
                pos >= toPos -> toY
                else -> fromY + (toY - fromY) * (pos - fromPos) / (toPos - fromPos)
            }
            if (cut > fromY) drawLine(accent, Offset(x, fromY), Offset(x, cut), stroke)
            if (toY > cut) drawLine(accent.copy(alpha = 0.75f), Offset(x, cut), Offset(x, toY), stroke, pathEffect = dash)
        }
        if (index > 0) draw(0f, mid, index - 0.5f, index.toFloat())
        if (index < lastIndex) draw(mid, size.height, index.toFloat(), index + 0.5f)
    }
}
