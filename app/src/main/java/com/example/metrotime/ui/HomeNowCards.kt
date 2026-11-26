package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*


import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.R
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.cp.CpBoard
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.delayMinutes
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.data.plan.Place
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────────────────
// Cartões novos do "Agora": comboios da CP a circular (filtrados e ordenados) e "As minhas estações"
// (até 5, uma de cada vez, a deslizar para o lado, com partidas e chegadas).
// ──────────────────────────────────────────────────────────────────────────────

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val LATE = Color(0xFFFFC107)
private val VERY_LATE = Color(0xFFFF7A7A)

private fun delayColor(minutes: Int) = when {
    minutes > 5 -> VERY_LATE
    minutes >= HomeNowConfig.LATE_MIN -> LATE
    else -> GREEN
}

// ── Comboios CP ──────────────────────────────────────────────────────────────

/** Comboios a circular, com o nome da estação onde foram vistos pela última vez. */
private suspend fun loadTrainRows(context: Context): List<HomeNowConfig.TrainRow> {
    val vehicles = CpRepository.getActiveVehicles()
    return withContext(Dispatchers.IO) {
        val names = CpNetwork.stations(context).associate { it.code to formatStationName(it.name) }
        vehicles.map { v ->
            val origin = formatStationName(v.origin?.designation ?: "?")
            val destination = formatStationName(v.destination?.designation ?: "?")
            HomeNowConfig.TrainRow(
                service = CpService.shortName(v.service?.code),
                number = v.trainNumber,
                runDate = v.runDate,
                title = "${v.trainNumber} · $origin → $destination",
                near = v.lastStation?.let { names[it] }?.let { "perto de $it" },
                delay = v.delayMinutes.coerceAtLeast(0)
            )
        }
    }
}

/**
 * Comboios da CP a circular, só dos serviços escolhidos, pelo atraso ou agrupados pelo serviço (o botão no
 * próprio cartão troca e fica guardado). [fill]: no carrossel, ocupa o cartão todo e mostra os comboios que
 * couberem; senão (pré-visualização) mostra [HomeNowConfig.CpCard.count]. [onOpenAll] null = sem "Ver todos".
 */
@Composable
internal fun HomeCpTrainsCard(
    activity: ComponentActivity,
    cfg: HomeNowConfig.CpCard,
    onChange: (HomeNowConfig.CpCard) -> Unit,
    fill: Boolean = false,
    onOpenAll: (() -> Unit)?
) {
    var trains by remember { mutableStateOf<List<HomeNowConfig.TrainRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val rows = attempt { loadTrainRows(activity) }
                if (rows != null) { trains = rows; failed = false } else if (trains == null) failed = true
                delay(60_000)
            }
        }
    }

    Card(
        modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (fill) 16.dp else 14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier).padding(if (fill) 16.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(CpGreen.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.cp_logo), contentDescription = null, modifier = Modifier.size(22.dp), contentScale = ContentScale.Fit)
                }
                Column(Modifier.weight(1f)) {
                    Text("Comboios", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (cfg.services.isEmpty()) "Nenhum serviço escolhido"
                        else HomeNowConfig.SERVICES.filter { it in cfg.services }.joinToString(" · ") + " a circular",
                        color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Segmented(listOf("Atraso", "Serviço"), if (cfg.byService) 1 else 0, { onChange(cfg.copy(byService = it == 1)) }, compact = true)
            }

            if (fill) {
                Box(Modifier.weight(1f).fillMaxWidth()) { TrainsBody(activity, trains, failed, cfg, fill = true) }
            } else {
                TrainsBody(activity, trains, failed, cfg, fill = false)
            }
            if (onOpenAll != null) {
                Text(
                    "Ver todos os comboios ›",
                    color = Color(0xFF60A5FA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenAll).padding(vertical = 4.dp)
                )
            }
        }
    }
}

/** Os comboios do cartão: os que couberem no cartão ([fill]), ou [HomeNowConfig.CpCard.count] na pré-visualização. */
@Composable
private fun TrainsBody(
    activity: ComponentActivity,
    list: List<HomeNowConfig.TrainRow>?,
    failed: Boolean,
    cfg: HomeNowConfig.CpCard,
    fill: Boolean
) {
    when {
        list == null && failed -> Text("Sem ligação aos dados da CP.", color = TextSecondaryDark, fontSize = 13.sp)
        list == null -> Text("A carregar comboios…", color = TextSecondaryDark, fontSize = 13.sp)
        else -> {
            val all = HomeNowConfig.arrange(list, if (fill) cfg.copy(count = Int.MAX_VALUE) else cfg)
            if (all.isEmpty()) {
                Text(
                    if (cfg.onlyLate) "Nenhum comboio atrasado destes serviços agora." else "Nenhum comboio destes serviços a circular agora.",
                    color = TextSecondaryDark, fontSize = 13.sp
                )
                return
            }
            // Os que couberem com a letra do telemóvel; o espaço que sobra fica repartido entre eles
            val rows = all.mapIndexed { i, t -> (cfg.byService && (i == 0 || all[i - 1].service != t.service)) to t }
            if (fill) {
                FitColumn(rows, Modifier.fillMaxSize(), gapBefore = { _, r -> if (r.first) 14.dp else 6.dp }) { (header, t) -> TrainRowView(activity, t, header) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { rows.forEach { (header, t) -> TrainRowView(activity, t, header) } }
            }
        }
    }
}

@Composable
private fun TrainRowView(activity: ComponentActivity, t: HomeNowConfig.TrainRow, header: Boolean) {
    if (header) {
        Text(CpService.longName(t.service), color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { activity.startActivity(CpTripDetailActivity.newIntent(activity, t.number, t.runDate)) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { CpServiceBadge(t.service, getServiceColor(t.service)) }
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.near ?: CpService.longName(t.service), color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (t.delay >= HomeNowConfig.LATE_MIN) "+${t.delay} min" else "a horas",
            color = delayColor(t.delay), fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
    }
}

// ── As minhas estações ───────────────────────────────────────────────────────

/** Uma linha do quadro de uma estação (partida, chegada ou próximo metro). */
private data class BoardRow(
    val time: String,
    val badge: String?,
    /** Hora prevista riscada por baixo da estimada (CP atrasada), como no quadro da estação. */
    val struck: String? = null,
    val timeColor: Color = Color.White,
    val badgeColor: Color,
    val lineColor: Color?,
    val title: String,
    val subtitle: String,
    val status: String,
    val statusColor: Color,
    val open: Intent?
)

private data class Board(val rows: List<BoardRow>, val emptyText: String)

/** Quantas linhas pedir para o cartão de uma estação (mostra as que couberem). */
private const val STATION_ROWS = 12

/** Quadro de uma estação: os próximos metros de cada sentido, ou as próximas partidas/chegadas da CP. null se falhar. */
private suspend fun loadBoard(activity: ComponentActivity, place: Place, departures: Boolean, max: Int): Board? = attempt {
    if (place.kind == Place.Kind.METRO) {
        if (!RemoteArrivalsSource.isMetroOperating()) return@attempt Board(emptyList(), "Fechado · abre às 6:30")
        val data = withContext(Dispatchers.IO) { RemoteArrivalsSource(activity).fetchArrivals(place.id) }
        val rows = MetroLines.groupByLine(place.id, data.arrivals).flatMap { (line, directions) ->
            directions.map { d ->
                val first = d.seconds.firstOrNull() ?: 0
                BoardRow(
                    time = HomeText.metroWait(first),
                    badge = null,
                    badgeColor = Color.Transparent,
                    lineColor = lineColorOf(line),
                    title = d.destination,
                    subtitle = HomeText.laterWaits(d.seconds.drop(1)) ?: "Linha $line",
                    status = if (data.isEstimated) "estimado" else "",
                    statusColor = TextSecondaryDark,
                    open = null
                )
            }
        }.take(max)
        Board(rows, "Sem tempos do Metro neste momento")
    } else {
        val now = CpTime.now()
        val stops = CpRepository.getTimetableWindow(place.id, now).stops
        // As mesmas horas e a mesma ordem do quadro da estação (pela hora a que parte mesmo)
        val rows = CpBoard.board(stops, departures, now).take(max).map { e ->
            val stop = e.stop
            val service = CpService.shortName(stop.trainService?.code)
            val other = if (departures) stop.trainDestination?.designation else stop.trainOrigin?.designation
            val platform = stop.platform?.takeIf { it.isNotBlank() && it != "0" }
            val color = when {
                e.suppressed -> VERY_LATE
                e.late -> if ((e.delayMinutes ?: 0) > 5) VERY_LATE else LATE
                e.delayMinutes == null -> TextSecondaryDark
                else -> GREEN
            }
            BoardRow(
                time = e.shown,
                badge = service,
                struck = e.struck,
                timeColor = when {
                    e.suppressed -> Color.Gray
                    e.struck != null -> color
                    else -> Color.White
                },
                badgeColor = getServiceColor(service),
                lineColor = null,
                title = (if (departures) "" else "de ") + formatStationName(other ?: "?"),
                subtitle = listOfNotNull("${stop.trainNumber}", platform?.let { "Linha $it" }).joinToString(" · "),
                status = listOfNotNull(e.countdown, e.status).joinToString(" · "),
                statusColor = color,
                open = CpTripDetailActivity.newIntent(
                    activity, stop.trainNumber, e.timed.queryDate.toString(),
                    stationCode = place.id, stationIsArrival = !departures
                )
            )
        }
        Board(rows, if (departures) "Sem partidas nas próximas horas" else "Sem chegadas nas próximas horas")
    }
}

/** Abre a estação no ecrã dela (metro ou CP). */
private fun stationIntent(activity: ComponentActivity, place: Place): Intent? =
    if (place.kind == Place.Kind.CP) CpStationActivity.newIntent(activity, place.id, place.name)
    else StationsRepo.loadStations(activity).find { it.apiId == place.id }?.let { StationArrivalsActivity.newIntent(activity, it.id) }

/**
 * Até 5 estações num cartão do carrossel: escolhe-se a estação pelos nomes (deslizar para o lado muda de cartão)
 * e, na CP, partidas ou chegadas; as linhas enchem o cartão. Sem estações, um convite para as escolher ([onEdit]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeMyStationsCard(activity: ComponentActivity, stations: List<Place>, showBoth: Boolean, onEdit: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var departures by rememberSaveable { mutableStateOf(true) }
    val current = stations.getOrNull(selected.coerceAtMost(stations.lastIndex))

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF60A5FA).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(StationIcon, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                }
                Text("As minhas estações", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "Editar", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onEdit).padding(6.dp)
                )
            }
            if (current == null) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onEdit),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(StationIcon, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Escolhe até ${HomeNowConfig.MAX_STATIONS} estações para veres aqui as partidas e chegadas.",
                        color = TextSecondaryDark, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stations.forEachIndexed { i, p ->
                    val on = p == current
                    Box(
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) MetrotimeBlue else Color.Transparent)
                            .border(1.dp, if (on) MetrotimeBlue else DIVIDER, RoundedCornerShape(16.dp))
                            .clickable { selected = i }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            p.name, color = if (on) Color.White else Color(0xFFDADDE2), fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1
                        )
                    }
                }
            }
            if (showBoth && current.kind == Place.Kind.CP) {
                Segmented(listOf("Partidas", "Chegadas"), if (departures) 0 else 1, { departures = it == 0 })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                StationPage(activity, current, departures || !showBoth)
            }
        }
    }
}

@Composable
private fun StationPage(activity: ComponentActivity, place: Place, departures: Boolean) {
    var board by remember(place, departures) { mutableStateOf<Board?>(null) }
    var failed by remember(place, departures) { mutableStateOf(false) }
    LaunchedEffect(place, departures) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                // Mais do que cabem: o cartão mostra os que couberem com a letra do telemóvel
                val b = loadBoard(activity, place, departures, STATION_ROWS)
                if (b != null) { board = b; failed = false } else if (board == null) failed = true
                delay(60_000)
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val b = board
        when {
            b == null && failed -> Text("Sem ligação. Tento outra vez daqui a pouco.", color = TextSecondaryDark, fontSize = 13.sp)
            b == null -> Text("A carregar…", color = TextSecondaryDark, fontSize = 13.sp)
            b.rows.isEmpty() -> Text(b.emptyText, color = TextSecondaryDark, fontSize = 13.sp)
            else -> FitColumn(b.rows, Modifier.weight(1f).fillMaxWidth(), gapBefore = { _, _ -> 6.dp }) { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (r.open != null) Modifier.clickable { activity.startActivity(r.open) } else Modifier)
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.width(62.dp)) {
                        Text(
                            r.time, color = if (r.lineColor != null && r.time == "a chegar") GREEN else r.timeColor,
                            fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1
                        )
                        if (r.struck != null) {
                            Text(r.struck, color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                        }
                    }
                    if (r.badge != null) CpServiceBadge(r.badge, r.badgeColor)
                    if (r.lineColor != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(r.lineColor))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.subtitle, color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (r.status.isNotEmpty()) Text(r.status, color = r.statusColor, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        if (b == null || b.rows.isEmpty()) Spacer(Modifier.weight(1f))
        stationIntent(activity, place)?.let { intent ->
            Text(
                "Ver estação ›",
                color = Color(0xFF60A5FA),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { activity.startActivity(intent) }.padding(vertical = 4.dp)
            )
        }
    }
}
