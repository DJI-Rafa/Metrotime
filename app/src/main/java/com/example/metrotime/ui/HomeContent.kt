package com.example.metrotime.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.R
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.alerts.MetroLineStatus
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────────────────
// Página inicial: pesquisa e viagens recentes, "Agora" (estado do metro e próximas partidas das
// estações favoritas), Explorar e Atalhos. Mesmas cores e cartões do separador CP e do Metro.
// ──────────────────────────────────────────────────────────────────────────────

internal val GREEN = Color(0xFF4CAF50)
internal val ORANGE = Color(0xFFFFB74D)
private val YELLOW = Color(0xFFFFC107)
private val RED = Color(0xFFFF7A7A)
internal val DIVIDER = Color(0xFF3A3D42)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

internal val METRO_LINES = listOf("Azul" to LineAzul, "Amarela" to LineAmarela, "Verde" to LineVerde, "Vermelha" to LineVermelha)

internal fun lineColorOf(line: String): Color =
    METRO_LINES.firstOrNull { (name, _) -> line.contains(name, ignoreCase = true) }?.second ?: LineAzul

/** Como runCatching, mas sem engolir o cancelamento (sair do ecrã tem de parar os pedidos). */
internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

@Composable
fun HomeContent(activity: ComponentActivity, animVisible: Boolean, animate: Boolean, onOpenCategory: (String) -> Unit) {
    val metro = rememberMetroLines(activity)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StaggeredItem(delayMillis = 0, visible = animVisible, animate = animate) {
            HomeSearch(activity)
        }
        StaggeredItem(delayMillis = 120, visible = animVisible, animate = animate) {
            HomeNowSection(activity, metro, onOpenCategory)
        }
        StaggeredItem(delayMillis = 240, visible = animVisible, animate = animate) {
            HomeSection("Explorar") { HomeExplore(activity, metro, onOpenCategory) }
        }
        StaggeredItem(delayMillis = 360, visible = animVisible, animate = animate) {
            HomeSection("Atalhos") { HomeShortcuts(activity) }
        }
    }
}

@Composable
internal fun HomeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = TextSecondaryDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )
        content()
    }
}

// ── Pesquisa e viagens recentes ──────────────────────────────────────────────

@Composable
private fun HomeSearch(activity: ComponentActivity) {
    var recents by remember { mutableStateOf(Prefs.getRecentPlans(activity)) }
    // Ao voltar do planeador as viagens recentes podem ter mudado
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { recents = Prefs.getRecentPlans(activity) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            onClick = { activity.startActivity(TripPlannerActivity.newIntent(activity)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            border = BorderStroke(1.dp, Color(0xFF3A3A3A))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Para onde vais? Metro, comboio, autocarro", color = TextSecondaryDark, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (recents.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recents.take(4).forEach { entry ->
                    val fromEntry = entry.substringBefore("\t")
                    val toEntry = entry.substringAfter("\t")
                    val label = "${com.example.metrotime.data.plan.Place.decode(fromEntry)?.name ?: "?"} → " +
                        (com.example.metrotime.data.plan.Place.decode(toEntry)?.name ?: "?")
                    // No máximo quase a largura do ecrã, com reticências (em vez de a letra ficar cortada na borda)
                    Row(
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(CardBackground)
                            .clickable {
                                activity.startActivity(TripPlannerActivity.newIntent(activity, from = fromEntry, to = toEntry))
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(ClockIcon, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ── Metro: estado das linhas ─────────────────────────────────────────────────

internal sealed interface MetroNow {
    data object Loading : MetroNow
    data object Closed : MetroNow
    data object Unknown : MetroNow
    data class Lines(val statuses: List<MetroLineStatus>) : MetroNow {
        val disrupted: List<MetroLineStatus> get() = statuses.filter { !it.ok }
    }
}

/** Estado das linhas, de 2 em 2 minutos enquanto a página inicial está visível. */
@Composable
internal fun rememberMetroLines(activity: ComponentActivity): MetroNow {
    var state by remember { mutableStateOf<MetroNow>(MetroNow.Loading) }
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                state = if (!RemoteArrivalsSource.isMetroOperating()) {
                    MetroNow.Closed
                } else {
                    attempt { RemoteArrivalsSource.fetchLineStatus() }?.takeIf { it.isNotEmpty() }
                        ?.also { com.example.metrotime.data.alerts.DisruptionLog.record(activity, it) }
                        ?.let { MetroNow.Lines(it) }
                        ?: (state as? MetroNow.Lines ?: MetroNow.Unknown)
                }
                delay(120_000)
            }
        }
    }
    return state
}

@Composable
internal fun HomeMetroCard(state: MetroNow, onClick: () -> Unit) {
    val disrupted = (state as? MetroNow.Lines)?.disrupted.orEmpty()
    val alert = disrupted.isNotEmpty()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (alert) Color(0xFF3A2E22) else CardBackground),
        border = if (alert) BorderStroke(1.dp, ORANGE.copy(alpha = 0.45f)) else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alert) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = ORANGE, modifier = Modifier.size(18.dp))
                    Text(
                        if (disrupted.size == 1) "Linha ${disrupted[0].line} com problemas" else "${disrupted.size} linhas com problemas",
                        color = ORANGE,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text("Metro", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val (text, color) = when (state) {
                        MetroNow.Loading -> "A verificar…" to TextSecondaryDark
                        MetroNow.Closed -> "Fechado · abre às 6:30" to TextSecondaryDark
                        MetroNow.Unknown -> "Sem informação" to TextSecondaryDark
                        is MetroNow.Lines -> "Circulação normal" to GREEN
                    }
                    if (state is MetroNow.Lines) Box(Modifier.size(8.dp).clip(CircleShape).background(GREEN))
                    Text(text, color = color, fontSize = 13.sp)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
            }
            // A mensagem do Metro, a mesma do alerta
            disrupted.forEach { s ->
                Text(
                    if (disrupted.size > 1) "${s.line}: ${s.message}" else s.message,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                METRO_LINES.forEach { (name, color) ->
                    val bad = disrupted.any { it.line == name }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(if (bad) Modifier.border(2.dp, ORANGE, CircleShape) else Modifier)
                        )
                        Text(
                            name,
                            color = if (bad) ORANGE else TextSecondaryDark,
                            fontSize = 12.sp,
                            fontWeight = if (bad) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (alert && disrupted.size < METRO_LINES.size) {
                Text("As outras linhas circulam normalmente.", color = TextSecondaryDark, fontSize = 12.sp)
            }
        }
    }
}

// ── Favoritas: próximas partidas ─────────────────────────────────────────────

internal data class FavRow(
    val key: String,
    val logo: Int,
    val tint: Color,
    val title: String,
    val subtitle: String,
    val main: String,
    val mainColor: Color = Color.White,
    val struck: String? = null,
    val note: String = "",
    val noteColor: Color = TextSecondaryDark,
    val open: Intent,
    /** Cores das linhas, mostradas em vez do logótipo (separador do metro). */
    val dots: List<Color> = emptyList()
)

@Composable
internal fun HomeFavoritesCard(activity: ComponentActivity, fill: Boolean = false) {
    var rows by remember { mutableStateOf<List<FavRow>?>(null) }
    // Atualiza a cada minuto e sempre que se volta à página inicial (favoritas podem ter mudado)
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                rows = loadFavoriteRows(activity)
                delay(60_000)
            }
        }
    }
    Card(
        modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        val list = rows
        when {
            list == null -> Text(
                "A carregar as tuas estações…",
                color = TextSecondaryDark,
                fontSize = 13.sp,
                modifier = Modifier.padding(14.dp)
            )
            list.isEmpty() -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { activity.startActivity(Intent(activity, FavoritesActivity::class.java)) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Marca estações como favoritas para veres aqui as próximas partidas.",
                    color = TextSecondaryDark,
                    fontSize = 13.sp
                )
            }
            else -> list.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
                FavoriteRowView(row) { activity.startActivity(row.open) }
            }
        }
    }
}

@Composable
internal fun FavoriteRowView(row: FavRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (row.dots.isNotEmpty()) {
            LineDots(row.dots, Modifier.width(36.dp))
        } else {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(row.tint),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(row.logo), contentDescription = null, modifier = Modifier.size(22.dp), contentScale = ContentScale.Fit)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.subtitle, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.struck?.let {
                    Text(it, color = TextSecondaryDark, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                    Spacer(Modifier.width(6.dp))
                }
                Text(row.main, color = row.mainColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            if (row.note.isNotEmpty()) Text(row.note, color = row.noteColor, fontSize = 12.sp)
        }
    }
}

/** Bolas com as cores das linhas de uma estação (Alameda: verde e vermelha). */
@Composable
internal fun LineDots(colors: List<Color>, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
        colors.forEach { Box(Modifier.size(size).clip(CircleShape).background(it)) }
    }
}

/** Cores das linhas de uma estação ("Verde Vermelha"). */
internal fun lineColorsOf(line: String): List<Color> =
    METRO_LINES.filter { (name, _) -> line.contains(name, ignoreCase = true) }.map { it.second }

/** Até duas estações de metro e duas da CP favoritas, cada uma com a próxima partida. */
private suspend fun loadFavoriteRows(activity: ComponentActivity): List<FavRow> = coroutineScope {
    val metroIds = Prefs.getMetroFavorites(activity).sorted().take(2)
    val cpEntries = Prefs.getCpFavorites(activity).sorted().take(2)
    if (metroIds.isEmpty() && cpEntries.isEmpty()) return@coroutineScope emptyList()
    val stations = if (metroIds.isEmpty()) emptyList() else withContext(Dispatchers.IO) { StationsRepo.loadStations(activity) }
    val metro = metroIds.mapNotNull { id -> stations.find { it.id == id } }.map { st -> async { metroRow(activity, st) } }
    val cp = cpEntries.map { entry -> async { cpRow(activity, entry) } }
    (metro + cp).awaitAll()
}

/** Próximo metro de uma estação. [inMetroTab]: sem o "Metro ·" e com as cores das linhas em vez do logótipo. */
internal suspend fun metroRow(activity: ComponentActivity, st: Station, inMetroTab: Boolean = false): FavRow {
    val prefix = if (inMetroTab) "" else "Metro · "
    val base = FavRow(
        key = "m_${st.id}",
        logo = R.drawable.metro_logo,
        tint = lineColorOf(st.line).copy(alpha = 0.22f),
        title = st.name,
        subtitle = if (inMetroTab) st.line.split(" ").joinToString(" · ") else "Metro",
        main = "—",
        open = StationArrivalsActivity.newIntent(activity, st.id),
        dots = if (inMetroTab) lineColorsOf(st.line) else emptyList()
    )
    if (!RemoteArrivalsSource.isMetroOperating()) return base.copy(subtitle = "${prefix}fechado", note = "abre às 6:30")
    val data = attempt { RemoteArrivalsSource(activity).fetchArrivals(st.apiId) }
    val next = data?.arrivals?.minByOrNull { it.minutes } ?: return base.copy(note = "sem dados")
    val after = data.arrivals.filter { it.direction == next.direction && it.minutes > next.minutes }.minByOrNull { it.minutes }
    return base.copy(
        subtitle = "${prefix}para ${next.direction}",
        main = HomeText.metroWait(next.minutes),
        mainColor = if (next.minutes <= 30) GREEN else Color.White,
        note = when {
            after != null -> "depois ${HomeText.metroWait(after.minutes)}"
            data.isEstimated -> "estimado"
            else -> ""
        }
    )
}

private suspend fun cpRow(activity: ComponentActivity, entry: String): FavRow {
    val code = entry.substringBefore("|")
    val rawName = entry.substringAfter("|")
    val base = FavRow(
        key = "c_$code",
        logo = R.drawable.cp_logo,
        tint = CpGreen.copy(alpha = 0.25f),
        title = formatStationName(rawName),
        subtitle = "CP",
        main = "—",
        open = CpStationActivity.newIntent(activity, code, rawName)
    )
    val now = CpTime.now()
    val stops = attempt { CpRepository.getTimetableWindow(code, now).stops } ?: return base.copy(note = "sem ligação")
    // Próxima partida como no quadro da estação: pela hora a que parte mesmo, com a hora estimada pela CP
    val next = com.example.metrotime.data.cp.CpBoard.board(stops, departures = true, now = now)
        .firstOrNull { it.minutesLeft >= 0 } ?: return base.copy(note = "sem partidas próximas")

    val stop = next.stop
    val delay = next.delayMinutes
    val lateColor = if ((delay ?: 0) > 5) RED else YELLOW
    val service = CpService.shortName(stop.trainService?.code)
    val destination = formatStationName(stop.trainDestination?.designation ?: "?")
    val platform = stop.platform?.takeIf { it.isNotBlank() && it != "0" }?.let { " · Linha $it" } ?: ""
    return base.copy(
        subtitle = "$service ${stop.trainNumber} → $destination$platform",
        main = next.shown,
        mainColor = when {
            next.suppressed -> TextSecondaryDark
            next.late && next.struck != null -> lateColor
            else -> Color.White
        },
        struck = next.struck,
        note = next.status,
        noteColor = when {
            next.suppressed -> RED
            next.late -> lateColor
            delay == null -> TextSecondaryDark
            else -> GREEN
        }
    )
}

// ── Explorar e Atalhos ───────────────────────────────────────────────────────

@Composable
private fun HomeExplore(activity: ComponentActivity, metro: MetroNow, onOpenCategory: (String) -> Unit) {
    val disrupted = (metro as? MetroNow.Lines)?.disrupted?.size ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExploreTile(
            logo = R.drawable.metro_logo,
            tint = LineAzul.copy(alpha = 0.22f),
            title = "Metro",
            subtitle = when (disrupted) {
                0 -> "4 linhas"
                1 -> "1 linha com problemas"
                else -> "$disrupted linhas com problemas"
            },
            subtitleColor = if (disrupted > 0) ORANGE else TextSecondaryDark
        ) { onOpenCategory("Metro") }
        ExploreTile(
            logo = R.drawable.cp_logo,
            tint = CpGreen.copy(alpha = 0.25f),
            title = "Comboios",
            subtitle = "CP"
        ) { onOpenCategory("Comboio") }
        ExploreTile(
            logo = R.drawable.bus_symbol,
            tint = Color(0xFFFFC20E).copy(alpha = 0.16f),
            title = "Autocarros",
            subtitle = "Carris"
        ) { onOpenCategory("Autocarro") }
    }
}

@Composable
private fun RowScope.ExploreTile(
    logo: Int,
    tint: Color,
    title: String,
    subtitle: String,
    subtitleColor: Color = TextSecondaryDark,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(24.dp), contentScale = ContentScale.Fit)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = subtitleColor, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun HomeShortcuts(activity: ComponentActivity) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.example.metrotime.ui.caderneta.CadernetaHomeCard(activity)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutTile(MapIcon, GREEN, "Mapa ao vivo") { activity.startActivity(Intent(activity, LiveMapActivity::class.java)) }
            ShortcutTile(ChartIcon, Color(0xFF60A5FA), "Estatísticas") { activity.startActivity(Intent(activity, StatsActivity::class.java)) }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutTile(Icons.Filled.Star, Color(0xFFFFD54F), "Favoritos") { activity.startActivity(Intent(activity, FavoritesActivity::class.java)) }
            ShortcutTile(Icons.Filled.Person, Color(0xFFC4B5FD), "Amigos") { activity.startActivity(Intent(activity, FriendsActivity::class.java)) }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutTile(PinIcon, Color(0xFFFF8A65), "Perto de mim") { activity.startActivity(Intent(activity, NearbyActivity::class.java)) }
            ShortcutTile(RouteIcon, MetrotimeBlue, "Planear viagem") { activity.startActivity(TripPlannerActivity.newIntent(activity)) }
        }
    }
}

@Composable
private fun RowScope.ShortcutTile(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        // Com letra grande o nome passa para 2 linhas (e os dois da fila crescem juntos) em vez de ficar cortado
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
