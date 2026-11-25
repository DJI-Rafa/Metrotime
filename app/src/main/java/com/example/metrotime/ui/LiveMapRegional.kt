package com.example.metrotime.ui

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.regional.RegionalParsers
import com.example.metrotime.data.regional.RegionalSource
import com.example.metrotime.data.regional.RegionalStop
import com.example.metrotime.data.regional.RegionalTrip
import com.example.metrotime.data.regional.RegionalVehicle
import com.example.metrotime.data.regional.VehicleKind
import com.example.metrotime.ui.theme.TextSecondaryDark
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

// ──────────────────────────────────────────────────────────────────────────────
// Outras redes no Mapa ao Vivo: MobiCascais, TCB, MTS, Fertagus, barcos (GO Hub da TML) e as redes da Eleven
// Systems (Covilhã, Viseu, Guimarães…). Dados em data/regional. Como os metros: pontos de longe, pílulas de perto.
// O painel segue o do metro (canvas "Metrotime · Novo mapa do metro" no Claude Design).
// ──────────────────────────────────────────────────────────────────────────────

const val REGIONAL_VEHICLES_LAYER = "regional-vehicles-layer"
const val REGIONAL_DOTS_LAYER = "regional-dots-layer"
private const val REGIONAL_VEHICLES_SRC = "regional-vehicles-src"
private const val REGIONAL_ROUTE_SRC = "regional-route-src"
private const val REGIONAL_ROUTE_LAYER = "regional-route-layer"
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
/** Abaixo deste zoom os veículos são pontos (o país todo cabe no ecrã sem pílulas umas em cima das outras). */
/** Daqui para cima, as bolinhas com o número; abaixo, pontos (com a Carris e a CM há centenas de autocarros). */
private const val PILL_MIN_ZOOM = 13f

/**
 * As imagens das bolinhas (uma por linha, cor e tipo) só se fazem para os veículos à vista e guardam-se no máximo
 * [MAX] no estilo do mapa (as mais antigas saem), para a memória não crescer com centenas de linhas.
 */
private object RegionalIcons {
    private const val MAX = 160
    private val byStyle = java.util.WeakHashMap<Style, LinkedHashMap<String, Boolean>>()

    fun ensure(style: Style, id: String, make: () -> android.graphics.Bitmap) {
        val lru = byStyle.getOrPut(style) { LinkedHashMap(64, 0.75f, true) }
        if (lru[id] != null) return
        if (style.getImage(id) == null) style.addImage(id, make())
        lru[id] = true
    }

    fun trim(style: Style, keep: Set<String>) {
        val lru = byStyle[style] ?: return
        val it = lru.keys.iterator()
        while (lru.size > MAX && it.hasNext()) {
            val k = it.next()
            if (k in keep) continue
            it.remove()
            runCatching { style.removeImage(k) }
        }
    }
}

/** O último desenho (para refazer as bolinhas dos que entram à vista quando o mapa pára). */
private var lastRegionalDraw: Pair<List<RegionalVehicle>, String?>? = null

/** O mapa parou: as bolinhas dos veículos que entraram à vista. */
fun refreshRegionalVehicles(map: MapLibreMap, context: Context) {
    lastRegionalDraw?.let { (list, key) -> updateRegionalVehicles(map, context, list, key) }
}


/** O percurso do veículo aberto, os pontos e as pílulas (tudo começa por "regional-": o filtro esconde-as). */
fun setupRegionalLayers(style: Style) {
    if (style.getSource(REGIONAL_ROUTE_SRC) == null) {
        style.addSource(GeoJsonSource(REGIONAL_ROUTE_SRC, EMPTY_FC))
        style.addLayer(
            LineLayer(REGIONAL_ROUTE_LAYER, REGIONAL_ROUTE_SRC).withProperties(
                lineColor(Expression.toColor(Expression.get("color"))),
                lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(0.9f)
            )
        )
    }
    if (style.getSource(REGIONAL_VEHICLES_SRC) == null) {
        style.addSource(GeoJsonSource(REGIONAL_VEHICLES_SRC, EMPTY_FC))
        // De longe: um ponto na cor da linha com aro branco (o aberto, maior)
        style.addLayer(
            CircleLayer(REGIONAL_DOTS_LAYER, REGIONAL_VEHICLES_SRC).withProperties(
                circleColor(Expression.toColor(Expression.get("color"))),
                circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6f, Expression.switchCase(Expression.get("selected"), Expression.literal(6f), Expression.literal(3f))),
                        Expression.stop(PILL_MIN_ZOOM, Expression.switchCase(Expression.get("selected"), Expression.literal(8f), Expression.literal(5f)))
                    )
                ),
                circleStrokeColor(android.graphics.Color.WHITE),
                circleStrokeWidth(1.5f)
            ).also { it.maxZoom = PILL_MIN_ZOOM }
        )
        // De perto: a mesma bolinha dos autocarros da Carris, com o número na cor da linha (o centro é o sítio exato)
        style.addLayer(
            SymbolLayer(REGIONAL_VEHICLES_LAYER, REGIONAL_VEHICLES_SRC).withProperties(
                iconImage(Expression.get("icon")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                symbolSortKey(Expression.get("sort")),
                iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(PILL_MIN_ZOOM, Expression.switchCase(Expression.get("selected"), Expression.literal(1.0f), Expression.literal(0.75f))),
                        Expression.stop(14f, Expression.switchCase(Expression.get("selected"), Expression.literal(1.3f), Expression.literal(1.0f)))
                    )
                )
            ).also { it.minZoom = PILL_MIN_ZOOM }
        )
    }
}

fun updateRegionalVehicles(map: MapLibreMap, context: Context, vehicles: List<RegionalVehicle>, selectedKey: String?) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(REGIONAL_VEHICLES_SRC) ?: return
    lastRegionalDraw = vehicles to selectedKey
    try {
        // Só os que estão à vista (com margem) levam bolinha; os outros ficam só com o ponto
        val near = map.cameraPosition.zoom >= PILL_MIN_ZOOM - 0.75
        val box = if (near) map.projection.visibleRegion.latLngBounds.let { b ->
            val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.5
            val dLon = (b.longitudeEast - b.longitudeWest) * 0.5
            org.maplibre.android.geometry.LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
        } else null
        val used = HashSet<String>()
        val features = vehicles.map { v ->
            val selected = v.key == selectedKey
            val kind = v.operator.kind
            val iconId = if (box != null && (selected || box.contains(org.maplibre.android.geometry.LatLng(v.lat, v.lon)))) {
                "regional-ball-${v.label}-${v.color}-$kind-$selected".also { id ->
                    RegionalIcons.ensure(style, id) { busBallBitmap(context, v.label, v.color, selected, kind) }
                    used += id
                }
            } else null
            Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).apply {
                if (iconId != null) addStringProperty("icon", iconId)
                addStringProperty("key", v.key)
                addStringProperty("color", v.color)
                addBooleanProperty("selected", selected)
                addNumberProperty("sort", if (selected) 1 else 0)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        RegionalIcons.trim(style, used)
    } catch (e: Exception) {
        android.util.Log.e("RegionalMap", "Erro a desenhar os veículos", e)
    }
}

/** O traçado da viagem aberta (só a Eleven o dá); sem viagem, limpa. */
fun updateRegionalRoute(map: MapLibreMap, trip: RegionalTrip?, fallbackColor: String?) {
    val source = map.style?.getSourceAs<GeoJsonSource>(REGIONAL_ROUTE_SRC) ?: return
    val shape = trip?.shape.orEmpty()
    if (shape.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val line = LineString.fromLngLats(shape.map { Point.fromLngLat(it.second, it.first) })
    val feature = Feature.fromGeometry(line).apply {
        addStringProperty("color", trip?.color ?: fallbackColor ?: "#8C8C99")
    }
    source.setGeoJson(FeatureCollection.fromFeature(feature))
}

// ── Fertagus no painel dos comboios da CP ────────────────────────────────────

/** O azul da Fertagus (o do painel; o verde fica para a CP). */
internal val FERTAGUS_BLUE = com.example.metrotime.ui.theme.CpFertagus

/**
 * A viagem de um comboio da Fertagus no formato da CP, para abrir no mesmo painel dos comboios da CP: as estações com
 * as horas do horário e as previstas com o atraso, a última estação por onde passou e o estado. null sem horário.
 */
internal fun fertagusCpState(v: RegionalVehicle): Pair<LiveMapViewModel.CpTripState, CpMapTrain>? {
    val code = v.operator.pack ?: return null
    val pack = com.example.metrotime.data.regional.RegionalRepository.pack(code) ?: return null
    val tripId = v.tripId ?: return null
    val (p, t) = pack.findTrip(tripId) ?: return null
    val pattern = pack.patterns[p]
    val number = tripId.replace(Regex("^(\\[[^]]*])+"), "").toIntOrNull() ?: return null
    val runDate = v.serviceDate?.let { java.time.LocalDate.of(it / 10_000, it / 100 % 100, it % 100) }
        ?: java.time.LocalDate.now(java.time.ZoneId.of("Europe/Lisbon"))
    val delayMin = v.delaySeconds?.let { com.example.metrotime.data.bus.BusTiming.roundMinutes(it) }
    val pos = com.example.metrotime.data.regional.TransitLive.positionOf(pack, pattern, v.stopId)
    val stopped = v.status == "STOPPED_AT"
    fun hm(s: Int) = "%02d:%02d".format((s / 3600) % 24, (s / 60) % 60)
    val stops = pattern.stops.indices.map { i ->
        val st = pack.stops[pattern.stops[i]]
        val sched = pattern.timeAt(t, i)
        val expected = delayMin?.let { hm(sched + it * 60) }
        com.example.metrotime.data.cp.CpTrainStop(
            station = com.example.metrotime.data.cp.CpGenericEntity(st.id, st.name),
            arrival = hm(sched),
            departure = hm(sched),
            latitude = st.lat.toString(),
            longitude = st.lon.toString(),
            delay = delayMin,
            eta = expected,
            etd = expected,
            passed = pos != null && i < pos
        )
    }
    // A última estação por onde passou (ou onde está parado)
    val lastIdx = when {
        pos == null -> null
        stopped -> pos
        pos > 0 -> pos - 1
        else -> null
    }
    val lastCode = lastIdx?.let { pack.stops[pattern.stops[it]].id }
    val status = when {
        pos == null -> com.example.metrotime.data.cp.VehicleStatus.IN_TRANSIT
        stopped && pos == 0 -> com.example.metrotime.data.cp.VehicleStatus.AT_ORIGIN
        stopped -> com.example.metrotime.data.cp.VehicleStatus.AT_STATION
        else -> com.example.metrotime.data.cp.VehicleStatus.IN_TRANSIT
    }
    val trip = com.example.metrotime.data.cp.CpTripDetail(
        trainNumber = number.toString(),
        serviceCode = com.example.metrotime.data.cp.CpServiceCode("UF", "Fertagus"),
        lastStationCode = lastCode,
        delay = delayMin,
        status = status,
        trainStops = stops
    )
    val state = LiveMapViewModel.CpTripState(number, trip, runDate, loading = false, geometry = null)
    val train = CpMapTrain(
        number = number,
        lat = v.lat,
        lon = v.lon,
        bearing = null,
        service = com.example.metrotime.data.cp.CpService.FT,
        serviceName = "Fertagus",
        delaySeconds = v.delaySeconds,
        status = status,
        origin = pack.stops[pattern.stops.first()].name,
        destination = RegionalParsers.prettyName(pattern.headsign),
        lastStationCode = lastCode,
        runDate = runDate.toString(),
        dataAt = v.observedAt
    )
    return state to train
}

// ── Painel do veículo aberto ─────────────────────────────────────────────────

private val SHEET = Color(0xFF1C1C20)
private val PASSED_RAIL = Color(0xFF3A3D42)
private val PASSED_DOT = Color(0xFF4A4D53)
private const val RAIL_COLUMN_DP = 36
/** Paragens já passadas que ficam à vista por cima da próxima. */
private const val PASSED_SHOWN = 2

private enum class RegionalStopKind { PASSED, NEXT, AHEAD, END }

private fun kindName(kind: VehicleKind): String = when (kind) {
    VehicleKind.BOAT -> "Barco"
    VehicleKind.TRAM -> "Metro de superfície"
    VehicleKind.TRAIN -> "Comboio"
    VehicleKind.SUBWAY -> "Metro"
    VehicleKind.BUS -> "Autocarro"
}

/** "agora", "4 min", e a partir de uma hora a hora do horário. */
private fun etaText(stop: RegionalStop): String {
    val due = stop.dueMinutes ?: return stop.clock.orEmpty()
    return when {
        due <= 0 -> "agora"
        due < 60 -> "$due min"
        else -> stop.clock ?: "$due min"
    }
}

/**
 * Linha e destino, onde está, e a viagem desenhada como no painel do metro: cinzento por onde já passou, na cor da
 * linha daí para a frente, a próxima paragem a pulsar e o veículo em cima do traço. Tocar numa paragem à frente
 * liga o aviso antes de sair (o mesmo dos autocarros). No GO Hub só há a posição: o painel diz isso.
 */
@Composable
internal fun RegionalVehicleSheet(
    state: LiveMapViewModel.RegionalSelection,
    now: Long,
    onClose: () -> Unit,
    /** Onde fica uma paragem sem coordenadas (as da Carris procuram-se só quando se escolhe sair nela). */
    locate: (suspend (RegionalStop) -> Pair<Double, Double>?)? = null
) {
    val v = state.vehicle
    val trip = state.trip
    val lineHex = trip?.color ?: v.color
    val lineColor = busColor(lineHex)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exitTarget by remember(v.key) { mutableStateOf<RegionalStop?>(null) }

    exitTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { exitTarget = null },
            containerColor = SHEET,
            title = { Text("Avisar antes de sair?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Aviso quando estiveres a ${com.example.metrotime.work.ExitAlarm.ALERT_DISTANCE_M.toInt()} m de ${target.name}.",
                    color = TextSecondaryDark
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exitTarget = null
                    scope.launch {
                        val at = if (!target.lat.isNaN()) target.lat to target.lon else locate?.invoke(target)
                        val ok = at != null &&
                            com.example.metrotime.work.ExitAlarm.startGps(context, trip?.line ?: v.label, target.name, at.first, at.second)
                        android.widget.Toast.makeText(
                            context,
                            when {
                                ok -> "Aviso antes de ${target.name}."
                                at == null -> "Não encontrei a localização desta paragem."
                                else -> "Para avisar antes de sair é preciso acesso à localização."
                            },
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("Avisar", color = lineColor, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { exitTarget = null }) { Text("Cancelar", color = TextSecondaryDark) } }
        )
    }

    val stops = trip?.stops.orEmpty()
    val nextIdx = stops.indexOfFirst { !it.passed }
    val firstShown = if (nextIdx < 0) 0 else (nextIdx - PASSED_SHOWN).coerceAtLeast(0)
    val shown = if (nextIdx < 0) emptyList() else stops.subList(firstShown, stops.size)
    val nextRow = nextIdx - firstShown
    val next = stops.getOrNull(nextIdx)
    val toEnd = stops.lastOrNull()?.dueMinutes?.takeIf { it > 0 && nextIdx >= 0 }

    val subtitle = buildString {
        append(v.operator.name)
        when {
            next != null && (next.dueMinutes ?: 1) <= 0 -> append(" · Em ${next.name}")
            next != null -> append(" · A caminho de ${next.name}")
            v.status == "STOPPED_AT" -> append(" · Parado")
            v.status == "INCOMING_AT" -> append(" · A chegar à paragem")
            v.status == "IN_TRANSIT_TO" -> append(" · Em andamento")
        }
        toEnd?.let { append(" · $it min até ao fim") }
    }

    val drag = rememberSheetDrag(key = v.key)
    val density = LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).sheetDrag(drag, density).navigationBarsPadding(),
        color = SHEET,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp)) {
            SheetHandle(drag, onClose, color = Color.Gray.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BusLineBadge(trip?.line ?: v.label, lineHex, height = 30.dp, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    trip?.destination?.let { "→ $it" } ?: trip?.lineName ?: kindName(v.operator.kind),
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondaryDark) }
            }
            Text(subtitle, color = TextSecondaryDark, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // O estado como nas paragens e nos widgets: "atrasado 4 min" (laranja/vermelho), "a horas", "adiantado 2 min"
            v.delaySeconds?.let { s ->
                val status = com.example.metrotime.data.bus.BusTiming.status(true, com.example.metrotime.data.bus.BusTiming.roundMinutes(s))
                val color = busStatusColor(status.kind)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(com.example.metrotime.ui.theme.LiveIcon, contentDescription = "Tempo real", tint = color, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(status.text.replaceFirstChar { it.uppercase() }, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))

            when {
                state.loading && trip == null -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = lineColor, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
                shown.isNotEmpty() -> RegionalTripStops(shown, nextRow, lineColor, v.key) { exitTarget = it }
                else -> Text(
                    "Posição em direto. Não encontrei esta viagem no horário, por isso não há paragens.",
                    color = TextSecondaryDark, fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(10.dp))
            val age = if (v.observedAt > 0) ((now - v.observedAt) / 60_000).coerceAtLeast(0) else 0
            Text(
                when (v.operator.source) {
                    RegionalSource.TML -> "Dados: TML · GO Hub (CC BY 4.0)"
                    RegionalSource.ELEVEN -> "Dados: ${v.operator.name} · Eleven Systems"
                    RegionalSource.STCP -> "Dados: STCP · Porto Digital · minutos estimados"
                    RegionalSource.SCHEDULE -> "Posição pelo horário oficial: o ${v.operator.name} não publica posições em direto"
                    RegionalSource.CITY -> if (v.operator.key == com.example.metrotime.data.regional.RegionalOperators.CM.key)
                        "Dados: Carris Metropolitana · API aberta" else "Dados: Carris · GTFS em tempo real"
                    RegionalSource.NONE -> "Dados: ${v.operator.name}"
                } + if (age >= 1) " · posição de há $age min" else "",
                color = TextSecondaryDark.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
    }
}

/** A viagem com o traço por trás e o veículo por cima; abre já na próxima, com as duas anteriores à vista. */
@Composable
private fun ColumnScope.RegionalTripStops(
    stops: List<RegionalStop>,
    nextRow: Int,
    lineColor: Color,
    key: String,
    onPick: (RegionalStop) -> Unit
) {
    val centers = remember(key) { mutableStateMapOf<Int, Float>() }
    val scroll = rememberScrollState()
    val next = stops.getOrNull(nextRow)
    // Na paragem (minutos a zero) o veículo fica em cima dela; senão, a meio caminho da anterior
    val atStop = (next?.dueMinutes ?: 1) <= 0 || nextRow == 0
    Box(Modifier.weight(1f, fill = false)) {
        Column(
            Modifier.verticalScroll(scroll).padding(top = 6.dp, bottom = 10.dp).drawWithRail(
                rows = stops.size, centers = centers, nextRow = nextRow, atStop = atStop, lineColor = lineColor
            )
        ) {
            stops.forEachIndexed { i, stop ->
                val kind = when {
                    i < nextRow -> RegionalStopKind.PASSED
                    i == nextRow -> RegionalStopKind.NEXT
                    i == stops.lastIndex -> RegionalStopKind.END
                    else -> RegionalStopKind.AHEAD
                }
                RegionalStopRow(
                    stop, kind, lineColor,
                    modifier = Modifier
                        .onGloballyPositioned { c -> centers[i] = c.positionInParent().y + c.size.height / 2f }
                        .then(if (kind == RegionalStopKind.PASSED) Modifier else Modifier.clickable { onPick(stop) })
                )
            }
        }
        // Esbate nas pontas: há mais paragens para cima e para baixo
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(14.dp)
                .background(Brush.verticalGradient(listOf(SHEET, SHEET.copy(alpha = 0f))))
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                .background(Brush.verticalGradient(listOf(SHEET.copy(alpha = 0f), SHEET)))
        )
    }
    if (stops.size > 1) {
        Text("Toca na paragem onde vais sair para te avisarmos antes.", color = TextSecondaryDark, fontSize = 12.sp)
    }
}

/** O traço (cinzento até ao veículo, na cor da linha depois) por trás das linhas, e o veículo por cima. */
private fun Modifier.drawWithRail(
    rows: Int,
    centers: Map<Int, Float>,
    nextRow: Int,
    atStop: Boolean,
    lineColor: Color
): Modifier = this.then(
    Modifier.drawWithContent {
        val ys = (0 until rows).map { centers[it] }
        val x = RAIL_COLUMN_DP.dp.toPx() / 2
        val vehicleY: Float? = run {
            val yNext = ys.getOrNull(nextRow) ?: return@run null
            val yPrev = ys.getOrNull(nextRow - 1)
            if (atStop || yPrev == null) yNext else (yPrev + yNext) / 2
        }
        if (ys.size > 1 && ys.all { it != null }) {
            val first = ys.first()!!
            val last = ys.last()!!
            val split = (vehicleY ?: first).coerceIn(first, last)
            val w = 6.dp.toPx()
            if (split > first) drawLine(PASSED_RAIL, Offset(x, first), Offset(x, split), w)
            if (split < last) drawLine(lineColor, Offset(x, split), Offset(x, last), w)
        }
        drawContent()
        if (vehicleY != null) {
            // O veículo: um bloco na cor da linha com o miolo branco, como a carruagem do metro
            val cw = 14.dp.toPx()
            val ch = 22.dp.toPx()
            val top = vehicleY - ch / 2
            drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x - cw / 2, top + 2.dp.toPx()), Size(cw, ch), CornerRadius(5.dp.toPx()))
            drawRoundRect(lineColor, Offset(x - cw / 2, top), Size(cw, ch), CornerRadius(5.dp.toPx()))
            val b = 2.dp.toPx()
            drawRoundRect(Color.White, Offset(x - cw / 2 + b, top + b), Size(cw - 2 * b, ch - 2 * b), CornerRadius(3.5.dp.toPx()))
        }
    }
)

/** Uma paragem: círculo branco com aro na cor da linha, a próxima maior e a pulsar, o fim em quadrado, as passadas a cinzento. */
@Composable
private fun RegionalStopRow(stop: RegionalStop, kind: RegionalStopKind, lineColor: Color, modifier: Modifier = Modifier) {
    val passed = kind == RegionalStopKind.PASSED
    val next = kind == RegionalStopKind.NEXT
    val pulse by rememberInfiniteTransition(label = "paragem").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "anel"
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = (if (next) 52 else 44).dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.width(RAIL_COLUMN_DP.dp).fillMaxHeight()) {
            val c = Offset(size.width / 2, size.height / 2)
            when (kind) {
                RegionalStopKind.PASSED -> drawCircle(PASSED_DOT, 5.dp.toPx(), c)
                RegionalStopKind.NEXT -> {
                    drawCircle(lineColor.copy(alpha = 0.55f * (1 - pulse)), 16.dp.toPx() * (1 + 0.55f * pulse), c)
                    drawCircle(lineColor, 10.dp.toPx(), c)
                    drawCircle(Color.White, 6.dp.toPx(), c)
                }
                RegionalStopKind.AHEAD -> {
                    drawCircle(lineColor, 7.dp.toPx(), c)
                    drawCircle(Color.White, 4.dp.toPx(), c)
                }
                RegionalStopKind.END -> {
                    val side = 18.dp.toPx()
                    val inner = 10.dp.toPx()
                    drawRoundRect(lineColor, Offset(c.x - side / 2, c.y - side / 2), Size(side, side), CornerRadius(5.dp.toPx()))
                    drawRoundRect(Color.White, Offset(c.x - inner / 2, c.y - inner / 2), Size(inner, inner), CornerRadius(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stop.name,
            color = when {
                passed -> TextSecondaryDark.copy(alpha = 0.6f)
                next -> Color.White
                else -> Color.White.copy(alpha = 0.9f)
            },
            fontSize = if (next) 16.sp else 15.sp,
            fontWeight = if (next) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (passed) stop.clock.orEmpty() else etaText(stop),
            color = when {
                passed -> TextSecondaryDark.copy(alpha = 0.6f)
                next -> lineColor
                else -> TextSecondaryDark
            },
            fontSize = if (next) 16.sp else 14.sp,
            fontWeight = if (next) FontWeight.Bold else FontWeight.Medium
        )
    }
}
