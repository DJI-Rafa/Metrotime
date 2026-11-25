package com.example.metrotime.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.R
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.bus.BusStop
import com.example.metrotime.data.bus.CarrisGtfsData
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

// ──────────────────────────────────────────────────────────────────────────────
// "Agora" em carrossel: um cartão de cada vez, todos do mesmo tamanho, a deslizar para o lado. Os cartões
// ao lado espreitam mais pequenos; por cima, os separadores; por baixo, os pontos.
// ──────────────────────────────────────────────────────────────────────────────

/** Altura de todos os cartões do carrossel. */
internal val NOW_CARD_HEIGHT = 400.dp

/**
 * Coluna que mostra só os itens que cabem inteiros na altura que tem (seja qual for o tamanho de letra do
 * telemóvel) e, quando ficaram itens de fora, reparte o espaço que sobra entre os que se veem, para não ficar
 * um buraco no fim. [rank]: os de rank mais baixo entram primeiro (por omissão, pela ordem da lista); [gapBefore]:
 * o espaço antes de cada item. Sem limite de altura, mostra-os todos.
 */
@Composable
internal fun <T> FitColumn(
    items: List<T>,
    modifier: Modifier = Modifier,
    rank: (Int, T) -> Int = { i, _ -> i },
    gapBefore: (Int, T) -> Dp = { _, _ -> 10.dp },
    item: @Composable (T) -> Unit
) {
    SubcomposeLayout(modifier) { constraints ->
        val bounded = constraints.hasBoundedHeight
        // Num cartão nunca cabem mais de uns 15; não vale a pena compor os 100 comboios a circular (ficam os
        // de rank mais baixo, pela ordem da lista)
        val candidates = if (!bounded || items.size <= MAX_FIT_ITEMS) items else {
            items.indices.sortedWith(compareBy({ rank(it, items[it]) }, { it })).take(MAX_FIT_ITEMS).sorted().map { items[it] }
        }
        val loose = Constraints(minWidth = constraints.minWidth, maxWidth = constraints.maxWidth)
        val measured: List<Placeable> = candidates.mapIndexed { i, t ->
            subcompose(i) { Column { item(t) } }.first().measure(loose)
        }
        val gaps = candidates.mapIndexed { i, t -> gapBefore(i, t).roundToPx() }

        // Entram por rank (e, no mesmo rank, pela ordem da lista) enquanto couberem
        val chosen = sortedSetOf<Int>()
        fun heightOf(set: Collection<Int>) = set.sumOf { measured[it].height } +
            set.sorted().drop(1).sumOf { gaps[it] }
        for (i in candidates.indices.sortedWith(compareBy({ rank(it, candidates[it]) }, { it }))) {
            if (bounded && heightOf(chosen + i) > constraints.maxHeight) break
            chosen += i
        }
        val shown = chosen.toList()
        val used = heightOf(shown)
        val cut = shown.size < items.size
        val extra = if (bounded && cut && shown.size > 1) (constraints.maxHeight - used) / (shown.size - 1) else 0
        val height = if (bounded && cut) constraints.maxHeight else used.coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = (shown.maxOfOrNull { measured[it].width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            var y = 0
            shown.forEachIndexed { k, i ->
                if (k > 0) y += gaps[i] + extra
                measured[i].place(0, y)
                y += measured[i].height
            }
        }
    }
}

private const val MAX_FIT_ITEMS = 24

/** Ocupa a largura toda do ecrã dentro de uma coluna com [padding] dos lados (os cartões ao lado espreitam). */
private fun Modifier.fullBleed(padding: Dp) = layout { measurable, constraints ->
    val extra = (padding * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-padding.roundToPx(), 0) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeNowCarousel(cards: List<HomeNowConfig.Card>, content: @Composable (HomeNowConfig.Card) -> Unit) {
    if (cards.isEmpty()) return
    val pager = rememberPagerState(pageCount = { cards.size })
    val scope = rememberCoroutineScope()
    val tabs = rememberLazyListState()
    // O separador do cartão à vista fica sempre visível
    LaunchedEffect(pager.currentPage) { tabs.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(
            state = tabs,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fullBleed(16.dp)
        ) {
            itemsIndexed(cards, key = { _, c -> c.id }) { i, c ->
                val on = i == pager.currentPage
                val bg by animateColorAsState(if (on) MetrotimeBlue else Color.Transparent, label = "tab")
                Box(
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(bg)
                        .border(1.dp, if (on) MetrotimeBlue else Color(0xFF4A4D53), RoundedCornerShape(17.dp))
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(c.tab, color = if (on) Color.White else Color(0xFFDADDE2), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 24.dp),
            pageSpacing = 10.dp,
            key = { cards[it].id },
            modifier = Modifier.fullBleed(16.dp).height(NOW_CARD_HEIGHT)
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Os de lado mais pequenos e esbatidos; crescem ao chegar ao centro
                        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                        val s = 1f - 0.06f * offset
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.45f * offset
                    }
            ) {
                content(cards[page])
            }
        }

        if (cards.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                cards.indices.forEach { i ->
                    val on = i == pager.currentPage
                    val w by androidx.compose.animation.core.animateDpAsState(if (on) 18.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = w, height = 6.dp)
                            .clip(CircleShape)
                            .background(if (on) Color.White else Color(0xFF5A5D63))
                    )
                }
            }
        }
    }
}

// ── Metro ────────────────────────────────────────────────────────────────────

/** Estado das linhas e os próximos metros da estação mais perto (ou da primeira favorita), a encher o cartão. */
@Composable
internal fun NowMetroCard(activity: ComponentActivity, state: MetroNow, onOpen: () -> Unit) {
    var station by remember { mutableStateOf<Pair<Station, Double?>?>(null) }
    var located by remember { mutableStateOf<Boolean?>(null) }
    var dirs by remember { mutableStateOf<List<Triple<String, String, List<Int>>>?>(null) }
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val stations = StationsRepo.loadStations(activity)
        val here = currentLocation(activity)
        located = here != null
        station = here?.let { h -> stations.map { it to TripPlanner.metres(h.first, h.second, it.lat, it.lon) }.minByOrNull { it.second } }
            ?.takeIf { it.second <= 3_000 }?.let { it.first to it.second }
            ?: Prefs.getMetroFavorites(activity).sorted().firstNotNullOfOrNull { id -> stations.find { it.id == id } }?.let { it to null }
        val st = station?.first ?: return@LaunchedEffect
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (!RemoteArrivalsSource.isMetroOperating()) {
                    closed = true
                    dirs = emptyList()
                } else {
                    closed = false
                    attempt { withContext(Dispatchers.IO) { RemoteArrivalsSource(activity).fetchArrivals(st.apiId) } }?.let { data ->
                        dirs = MetroLines.groupByLine(st.apiId, data.arrivals).flatMap { (line, ds) -> ds.map { Triple(line, it.destination, it.seconds) } }
                    }
                }
                delay(30_000)
            }
        }
    }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(LineAzul.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.metro_logo), contentDescription = null, modifier = Modifier.size(22.dp), contentScale = ContentScale.Fit)
                }
                val disrupted = (state as? MetroNow.Lines)?.disrupted.orEmpty()
                val (text, color) = when {
                    state is MetroNow.Closed -> "Fechado" to TextSecondaryDark
                    disrupted.size == 1 -> "Linha ${disrupted[0].line} com problemas" to ORANGE
                    disrupted.size > 1 -> "${disrupted.size} linhas com problemas" to ORANGE
                    state is MetroNow.Lines -> "Circulação normal" to GREEN
                    state is MetroNow.Loading -> "A verificar…" to TextSecondaryDark
                    else -> "Sem informação" to TextSecondaryDark
                }
                // O estado por baixo do título: ao lado, com perturbações, "Metro" partia-se em "Me / tro"
                Column(Modifier.weight(1f)) {
                    Text("Metro", color = Color.White, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Text(text, color = color, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // As quatro linhas, cada uma com o estado
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                METRO_LINES.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (line, lineColor) ->
                            val ok = (state as? MetroNow.Lines)?.statuses?.firstOrNull { it.line == line }?.ok
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(lineColor))
                                Text(line, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                val (t, c) = when {
                                    state is MetroNow.Closed -> "Fechado" to TextSecondaryDark
                                    ok == true -> "Normal" to GREEN
                                    ok == false -> "Problemas" to ORANGE
                                    else -> "—" to TextSecondaryDark
                                }
                                Text(t, color = c, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = DIVIDER)
            val st = station
            Text(
                when {
                    st == null && located == false -> "Liga a localização para veres o metro mais perto"
                    st == null -> "À procura da estação mais perto…"
                    st.second == null -> "${st.first.name} · favorita"
                    else -> "Perto de ti · ${st.first.name} · ${TripPlanner.distanceText(st.second!!)}"
                },
                color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = dirs
                when {
                    st == null -> Unit
                    closed -> Text("Fechado · abre às 6:30", color = TextSecondaryDark, fontSize = 13.sp)
                    list == null -> Text("A carregar…", color = TextSecondaryDark, fontSize = 13.sp)
                    list.isEmpty() -> Text("Sem tempos do Metro neste momento", color = TextSecondaryDark, fontSize = 13.sp)
                    else -> FitColumn(list, Modifier.fillMaxSize()) { (line, dest, secs) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(lineColorOf(line)))
                                Text(dest, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                val first = secs.firstOrNull() ?: 0
                                Text(HomeText.metroWait(first), color = if (first <= 60) GREEN else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    HomeText.laterWaits(secs.drop(1)).orEmpty(), color = TextSecondaryDark, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(92.dp)
                                )
                            }
                        }
                    }
                }
            Text("Abrir o metro ›", color = Color(0xFF60A5FA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Autocarros ───────────────────────────────────────────────────────────────

private fun stopIntent(activity: ComponentActivity, stop: BusStop): Intent =
    Intent(activity, LiveMapActivity::class.java).putExtra(
        LiveMapActivity.EXTRA_BUS_STOP,
        listOf(stop.operator, stop.id, stop.name.replace("|", "/"), stop.lat, stop.lon).joinToString("|")
    )

/**
 * As paragens favoritas (até 3) ou, sem favoritas, as duas mais perto, com as próximas chegadas a encher o
 * cartão (o espaço repartido pelas paragens). Tocar numa paragem ou num autocarro abre-o no mapa.
 */
@Composable
internal fun NowBusCard(activity: ComponentActivity, onOpen: () -> Unit) {
    var stops by remember { mutableStateOf<List<Pair<BusStop, String>>?>(null) }
    var favorites by remember { mutableStateOf(true) }
    var arrivals by remember { mutableStateOf<Map<String, List<BusMapRepository.StopArrivalInfo>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val favs = Prefs.getFavoriteBusStops(activity).mapNotNull { entry ->
            entry.split("|").takeIf { it.size == 5 }?.let { (op, id, name, lat, lon) ->
                BusStop(id, name, lat.toDoubleOrNull() ?: return@let null, lon.toDoubleOrNull() ?: return@let null, op)
            }
        }.sortedBy { it.name }
        val list = if (favs.isNotEmpty()) {
            favs.take(3).map { it to if (it.operator == "carris_metropolitana") "Carris Metropolitana" else "Carris" }
        } else {
            favorites = false
            val here = currentLocation(activity)
            if (here == null) emptyList() else withContext(Dispatchers.IO) {
                CarrisGtfsData.ensureLoaded(activity)
                val carris = CarrisGtfsData.stops.map { BusStop(it.id, it.name, it.lat, it.lon, "carris") }
                val cm = attempt { BusMapRepository.getAllCmStops() }.orEmpty()
                (carris + cm)
                    .map { it to TripPlanner.metres(here.first, here.second, it.lat, it.lon) }
                    .filter { it.second < 500 }
                    .sortedBy { it.second }
                    .distinctBy { it.first.name }
                    .take(2)
                    .map { (s, m) -> s to walkText(m) }
            }
        }
        stops = list
        if (list.isEmpty()) return@LaunchedEffect
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                arrivals = coroutineScope {
                    list.map { (s, _) ->
                        async {
                            "${s.operator}|${s.id}" to (attempt { BusMapRepository.getStopArrivals(activity, s.id, s.operator, perLine = 4) }.orEmpty())
                        }
                    }.awaitAll().toMap()
                }
                delay(30_000)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFC20E).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.bus_symbol), contentDescription = null, modifier = Modifier.size(22.dp), contentScale = ContentScale.Fit)
                }
                Column(Modifier.weight(1f)) {
                    Text("Autocarros", color = Color.White, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(if (favorites) "As tuas paragens" else "Perto de ti", color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                }
            }
            val list = stops
            when {
                list == null -> Text("À procura das paragens…", color = TextSecondaryDark, fontSize = 13.sp)
                list.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    BusStopSign(40.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Marca paragens com a estrela no mapa (ou liga a localização) para veres aqui os autocarros.",
                        color = TextSecondaryDark, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
                else -> {
                    // Um item por paragem e um por chegada: entram as paragens, depois a 1.ª chegada de cada, a 2.ª…
                    val items = list.flatMap { (stop, detail) ->
                        val arr = arrivals["${stop.operator}|${stop.id}"]
                        listOf(BusCardItem(stop, detail, null, 0, arr)) +
                            arr.orEmpty().mapIndexed { j, a -> BusCardItem(stop, detail, a, j + 1, arr) }
                    }
                    FitColumn(
                        items,
                        Modifier.weight(1f).fillMaxWidth(),
                        rank = { _, it -> it.rank },
                        gapBefore = { _, it -> if (it.arrival == null) 14.dp else 4.dp }
                    ) { it ->
                        val stop = it.stop
                        val a = it.arrival
                        if (a == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).clickable { activity.startActivity(stopIntent(activity, stop)) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                BusStopSign(30.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(stop.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(it.detail, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                            when {
                                it.all == null -> Text("A ver as chegadas…", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(start = 40.dp, top = 4.dp))
                                it.all.isEmpty() -> Text("Sem autocarros a caminho", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(start = 40.dp, top = 4.dp))
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val v = a.vehicleId
                                        activity.startActivity(
                                            if (a.realtime && v != null) LiveMapActivity.busIntent(activity, v, a.operator, a.routeShortName)
                                            else stopIntent(activity, stop)
                                        )
                                    }
                                    .padding(start = 40.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                BusLineBadge(a.routeShortName, a.color, height = 22.dp, fontSize = 12.sp)
                                Text(a.destination, color = Color(0xFFDADDE2), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                BusEta(a, fontSize = 14.sp, showIcon = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Uma linha do cartão dos autocarros: o título da paragem ([arrival] null) ou uma chegada. */
private data class BusCardItem(
    val stop: BusStop,
    val detail: String,
    val arrival: BusMapRepository.StopArrivalInfo?,
    val rank: Int,
    val all: List<BusMapRepository.StopArrivalInfo>?
)
