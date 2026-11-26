package com.example.metrotime.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.boat.BoatArrival
import com.example.metrotime.data.boat.BoatScheduleRepo
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.bus.BusStop
import com.example.metrotime.data.bus.CarrisGtfsData
import com.example.metrotime.data.cp.CpBoard
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.data.plan.Walk
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * Perto de mim: tudo o que está à tua volta numa só lista (metro, comboios, paragens e barcos), do mais perto
 * para o mais longe a pé, cada um com as próximas partidas. Atualiza a cada minuto.
 */
class NearbyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                    NearbyScreen(this)
                }
            }
        }
    }
}

private const val METRO_RADIUS_M = 1_200.0
private const val CP_RADIUS_M = 1_500.0
private const val BUS_RADIUS_M = 400.0
private const val BOAT_RADIUS_M = 1_200.0
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Os cais com barcos e a estação de metro onde a app já mostra os horários deles. */
private data class Pier(val name: String, val stationId: String, val lat: Double, val lon: Double)

private val PIERS = listOf(
    Pier("Cais do Sodré (Transtejo)", "cais_do_sodre", 38.7058, -9.1449),
    Pier("Terreiro do Paço (Soflusa)", "terreiro_do_paco", 38.7068, -9.1340)
)

/** Uma coisa perto: o que é, a distância e, quando já se sabem, as próximas partidas. */
private sealed interface NearItem {
    val key: String
    val metres: Double

    data class Metro(val station: Station, override val metres: Double, val lines: List<Pair<String, List<MetroLines.Direction>>>? = null, val closed: Boolean = false) : NearItem {
        override val key get() = "m_${station.id}"
    }
    data class Train(val code: String, val name: String, override val metres: Double, val departures: List<CpDeparture>? = null) : NearItem {
        override val key get() = "c_$code"
    }
    data class Bus(val stop: BusStop, override val metres: Double, val arrivals: List<BusMapRepository.StopArrivalInfo>? = null) : NearItem {
        override val key get() = "b_${stop.operator}_${stop.id}"
    }
    data class Boat(val pier: Pier, override val metres: Double, val boats: List<BoatArrival>) : NearItem {
        override val key get() = "p_${pier.stationId}"
    }
}

/** Uma partida como no quadro da estação (CpBoard): hora a mostrar, prevista riscada, contagem e estado. */
private data class CpDeparture(
    val time: String,
    val struck: String?,
    val service: String,
    val destination: String,
    val countdown: String?,
    val status: String,
    val delay: Int?,
    val late: Boolean,
    val suppressed: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyScreen(activity: ComponentActivity) {
    var hasPermission by remember { mutableStateOf(hasLocationPermission(activity)) }
    var items by remember { mutableStateOf<List<NearItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = hasLocationPermission(activity)
    }

    LaunchedEffect(hasPermission, reload) {
        if (!hasPermission) return@LaunchedEffect
        error = null
        val here = currentLocation(activity)
        if (here == null) { error = "Não foi possível saber onde estás. Liga a localização e tenta outra vez."; return@LaunchedEffect }
        val base = withContext(Dispatchers.IO) { nearbyPlaces(activity, here) }
        items = base
        if (base.isEmpty()) return@LaunchedEffect
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                items = withTimes(activity, base)
                delay(60_000)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Perto de mim", color = Color.White, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = { items = null; reload++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Atualizar", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )
        val list = items
        when {
            !hasPermission -> NearbyMessage("📍", "Para veres o que está perto precisamos da tua localização.", "Permitir localização") {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            error != null -> NearbyMessage("⚠️", error.orEmpty(), "Tentar outra vez") { reload++ }
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetrotimeBlue)
            }
            list.isEmpty() -> NearbyMessage("🧭", "Nada de metro, comboios, autocarros ou barcos a poucos minutos a pé daqui.", null) {}
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.key }) { item -> NearCard(activity, item) }
                item(key = "note") {
                    Text(
                        "Metro até ${(METRO_RADIUS_M / 1000).toString().replace('.', ',')} km, comboios até ${(CP_RADIUS_M / 1000).toString().replace('.', ',')} km, " +
                            "paragens até ${BUS_RADIUS_M.toInt()} m. Distâncias em linha reta.",
                        color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyMessage(emoji: String, text: String, button: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(emoji, fontSize = 36.sp)
        Text(text, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (button != null) {
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = MetrotimeBlue)) { Text(button) }
        }
    }
}

/** O que está perto (sem horários), do mais perto para o mais longe. */
private suspend fun nearbyPlaces(activity: ComponentActivity, here: Pair<Double, Double>): List<NearItem> {
    val (lat, lon) = here
    fun m(toLat: Double, toLon: Double) = TripPlanner.metres(lat, lon, toLat, toLon)
    val metro = StationsRepo.loadStations(activity)
        .map { NearItem.Metro(it, m(it.lat, it.lon)) }
        .filter { it.metres <= METRO_RADIUS_M }
        .sortedBy { it.metres }.take(3)
    val cp = CpNetwork.stations(activity)
        .map { NearItem.Train(it.code, formatStationName(it.name), m(it.lat, it.lon)) }
        .filter { it.metres <= CP_RADIUS_M }
        .sortedBy { it.metres }.take(2)
    CarrisGtfsData.ensureLoaded(activity)
    val carris = CarrisGtfsData.stops.map { BusStop(it.id, it.name, it.lat, it.lon, "carris") }
    val cm = attempt { BusMapRepository.getAllCmStops() }.orEmpty()
    val bus = (carris + cm)
        .map { NearItem.Bus(it, m(it.lat, it.lon)) }
        .filter { it.metres <= BUS_RADIUS_M }
        .sortedBy { it.metres }
        .distinctBy { it.stop.name }
        .take(5)
    val now = System.currentTimeMillis()
    val boats = PIERS.map { NearItem.Boat(it, m(it.lat, it.lon), BoatScheduleRepo.getNextDepartures(it.stationId, now)) }
        .filter { it.metres <= BOAT_RADIUS_M && it.boats.isNotEmpty() }
    return (metro + cp + bus + boats).sortedBy { it.metres }
}

/** As próximas partidas de cada sítio, em paralelo; se um pedido falhar, esse fica sem horários. */
private suspend fun withTimes(activity: ComponentActivity, base: List<NearItem>): List<NearItem> = coroutineScope {
    val metroOpen = RemoteArrivalsSource.isMetroOperating()
    base.map { item ->
        async(Dispatchers.IO) {
            when (item) {
                is NearItem.Metro -> if (!metroOpen) item.copy(closed = true) else {
                    val data = attempt { RemoteArrivalsSource(activity).fetchArrivals(item.station.apiId) }
                    item.copy(lines = data?.let { MetroLines.groupByLine(item.station.apiId, it.arrivals) } ?: emptyList())
                }
                is NearItem.Train -> {
                    val now = CpTime.now()
                    val stops = attempt { CpRepository.getTimetableWindow(item.code, now).stops }
                    // As mesmas horas e a mesma ordem do quadro da estação
                    item.copy(departures = stops?.let { list ->
                        CpBoard.board(list, departures = true, now = now).take(4).map { e ->
                            CpDeparture(
                                time = e.shown,
                                struck = e.struck,
                                service = CpService.shortName(e.stop.trainService?.code),
                                destination = formatStationName(e.stop.trainDestination?.designation ?: "?"),
                                countdown = e.countdown,
                                status = e.status,
                                delay = e.delayMinutes,
                                late = e.late,
                                suppressed = e.suppressed
                            )
                        }
                    } ?: emptyList())
                }
                is NearItem.Bus -> item.copy(
                    arrivals = attempt { BusMapRepository.getStopArrivals(activity, item.stop.id, item.stop.operator) }
                        ?.groupBy { it.routeShortName }?.values?.map { it.minBy { a -> a.etaMinutes } }?.sortedBy { it.etaMinutes }
                        ?: emptyList()
                )
                is NearItem.Boat -> item.copy(boats = BoatScheduleRepo.getNextDepartures(item.pier.stationId, System.currentTimeMillis()))
            }
        }
    }.awaitAll()
}

private fun walkLabel(metres: Double) = "${TripPlanner.distanceText(metres)} · ${Walk.minutes(metres).coerceAtLeast(1)} min a pé"

@Composable
private fun NearCard(activity: ComponentActivity, item: NearItem) {
    val (title, kind) = when (item) {
        is NearItem.Metro -> item.station.name to "Metro"
        is NearItem.Train -> item.name to "Comboios"
        is NearItem.Bus -> item.stop.name to if (item.stop.operator == "carris_metropolitana") "Carris Metropolitana" else "Carris"
        is NearItem.Boat -> item.pier.name to "Barcos"
    }
    Card(
        onClick = { activity.startActivity(openIntent(activity, item)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                    when (item) {
                        is NearItem.Metro -> LineDots(lineColorsOf(item.station.line), size = 11.dp)
                        is NearItem.Train -> Icon(TrainIcon, contentDescription = null, tint = CpGreen, modifier = Modifier.size(24.dp))
                        is NearItem.Bus -> BusStopSign(32.dp)
                        is NearItem.Boat -> Icon(BoatIcon, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(26.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$kind · ${walkLabel(item.metres)}", color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.padding(start = 52.dp)) { NearTimes(item) }
        }
    }
}

@Composable
private fun TimesLoading() = Text("A ver as próximas partidas…", color = TextSecondaryDark, fontSize = 12.sp)

@Composable
private fun NearTimes(item: NearItem) {
    when (item) {
        is NearItem.Metro -> when {
            item.closed -> Text("Fechado · abre às 6:30", color = TextSecondaryDark, fontSize = 12.sp)
            item.lines == null -> TimesLoading()
            item.lines.isEmpty() -> Text("Sem tempos do Metro neste momento", color = TextSecondaryDark, fontSize = 12.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.lines.forEach { (line, directions) ->
                    directions.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(lineColorOf(line)))
                            Text(d.destination, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text(
                                d.seconds.firstOrNull()?.let { HomeText.metroWait(it) } ?: "—",
                                color = if ((d.seconds.firstOrNull() ?: 999) <= 60) GREEN else Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        is NearItem.Train -> when {
            item.departures == null -> TimesLoading()
            item.departures.isEmpty() -> Text("Sem partidas nas próximas horas", color = TextSecondaryDark, fontSize = 12.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.departures.forEach { d ->
                    // Como no quadro da estação: a hora estimada a cor (a prevista riscada por baixo), "agora"/"17 min" e o estado
                    val delayColor = when {
                        d.suppressed -> Color(0xFFFF5252)
                        (d.delay ?: 0) <= 0 -> GREEN
                        (d.delay ?: 0) <= 5 -> Color(0xFFFFC107)
                        else -> Color(0xFFFF5252)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.width(46.dp)) {
                            Text(
                                d.time,
                                color = when { d.suppressed -> Color.Gray; d.late && d.struck != null -> delayColor; else -> Color.White },
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                textDecoration = if (d.suppressed) TextDecoration.LineThrough else null
                            )
                            if (d.struck != null) Text(d.struck, color = Color.Gray, fontSize = 11.sp, textDecoration = TextDecoration.LineThrough)
                        }
                        CpServiceBadge(d.service, getServiceColor(d.service))
                        Text(d.destination, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            d.countdown?.let { Text(it, color = if (it == "agora") GREEN else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            Text(
                                d.status,
                                color = when { d.suppressed || d.late -> delayColor; d.status == "Previsto" -> Color.Gray; else -> GREEN },
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
        is NearItem.Bus -> when {
            item.arrivals == null -> TimesLoading()
            item.arrivals.isEmpty() -> Text("Sem autocarros a caminho", color = TextSecondaryDark, fontSize = 12.sp)
            else -> Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item.arrivals.take(5).forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BusLineBadge(a.routeShortName, a.color, height = 22.dp, fontSize = 12.sp)
                        BusEta(a, fontSize = 13.sp, showIcon = true)
                    }
                }
            }
        }
        is NearItem.Boat -> Text(
            item.boats.groupBy { it.destination }.values.map { it.first() }
                .joinToString(" · ") { "${it.destination} ${it.scheduledTime}" },
            color = Color.White, fontSize = 13.sp
        )
    }
}

private fun openIntent(activity: ComponentActivity, item: NearItem): Intent = when (item) {
    is NearItem.Metro -> StationArrivalsActivity.newIntent(activity, item.station.id)
    is NearItem.Train -> CpStationActivity.newIntent(activity, item.code, item.name)
    is NearItem.Bus -> Intent(activity, LiveMapActivity::class.java).putExtra(
        LiveMapActivity.EXTRA_BUS_STOP,
        listOf(item.stop.operator, item.stop.id, item.stop.name.replace("|", "/"), item.stop.lat, item.stop.lon).joinToString("|")
    )
    is NearItem.Boat -> StationArrivalsActivity.newIntent(activity, item.pier.stationId)
}
