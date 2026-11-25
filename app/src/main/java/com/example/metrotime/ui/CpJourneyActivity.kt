package com.example.metrotime.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.cp.*
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Viagem A → B: comboios diretos entre duas estações, com horas reais, duração, atraso e linha.
 * Cruza os horários das duas estações (CpRepository.findJourneys), sem endpoint próprio.
 */
class CpJourneyActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"

        /** [from]/[to] no formato "código|nome". */
        fun newIntent(context: Context, from: String? = null, to: String? = null): Intent =
            Intent(context, CpJourneyActivity::class.java).apply {
                from?.let { putExtra(EXTRA_FROM, it) }
                to?.let { putExtra(EXTRA_TO, it) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CpApi.prefetchConfig()
        val from = intent.getStringExtra(EXTRA_FROM)
        val to = intent.getStringExtra(EXTRA_TO)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                    CpJourneyScreen(this, from, to)
                }
            }
        }
    }
}

private data class StationPick(val code: String, val name: String) {
    val entry get() = "$code|$name"
    companion object {
        /** Os nomes guardados podem vir crus da API ("Porto Campanha"): mostram-se sempre formatados. */
        fun parse(entry: String?): StationPick? = entry?.takeIf { it.contains("|") }
            ?.let { StationPick(it.substringBefore("|"), formatStationName(it.substringAfter("|"))) }
    }
}

private enum class JourneyField { FROM, TO }

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CpJourneyScreen(activity: ComponentActivity, fromEntry: String?, toEntry: String?) {
    var stations by remember { mutableStateOf<List<CpStation>>(emptyList()) }
    var from by remember { mutableStateOf(StationPick.parse(fromEntry)) }
    var to by remember { mutableStateOf(StationPick.parse(toEntry)) }
    var fromQuery by remember { mutableStateOf(from?.name.orEmpty()) }
    var toQuery by remember { mutableStateOf(to?.name.orEmpty()) }
    var activeField by remember { mutableStateOf<JourneyField?>(null) }
    var departAt by remember { mutableStateOf<LocalDateTime?>(null) } // null = agora
    var results by remember { mutableStateOf<List<CpJourneyOption>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(Prefs.getRecentJourneys(activity)) }
    val favorites = remember { Prefs.getCpFavorites(activity).mapNotNull { StationPick.parse(it) }.sortedBy { it.name } }
    val focusManager = LocalFocusManager.current
    val toFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        stations = runCatching { CpRepository.getStations(activity) }.getOrDefault(emptyList())
    }

    // Pesquisa automática quando as duas estações estão escolhidas
    LaunchedEffect(from, to, departAt) {
        val a = from
        val b = to
        results = null
        error = null
        if (a == null || b == null) return@LaunchedEffect
        if (a.code == b.code) { error = "Escolhe duas estações diferentes."; return@LaunchedEffect }
        loading = true
        try {
            results = CpRepository.findJourneys(a.code, b.code, departAt ?: CpTime.now())
            Prefs.addRecentJourney(activity, "${a.entry}|${b.entry}")
            recents = Prefs.getRecentJourneys(activity)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            error = "Não foi possível obter os horários (${e.javaClass.simpleName})."
        }
        loading = false
    }

    // Sugestões do campo em edição: favoritas com o campo vazio, estações a partir de 2 letras
    val query = when (activeField) { JourneyField.FROM -> fromQuery; JourneyField.TO -> toQuery; null -> "" }
    val picked = when (activeField) { JourneyField.FROM -> from; JourneyField.TO -> to; null -> null }
    val suggestions = remember(activeField, query, picked, stations, favorites) {
        when {
            activeField == null -> emptyList()
            query.isBlank() -> favorites
            query.length < 2 || query == picked?.name -> emptyList()
            else -> stations.filter { CpText.matches(it.designation, query) }
                .sortedBy { if (CpText.normalize(it.designation).startsWith(CpText.normalize(query))) 0 else 1 }
                .take(8)
                .map { StationPick(it.code, formatStationName(it.designation)) }
        }
    }

    fun pick(field: JourneyField, station: StationPick) {
        if (field == JourneyField.FROM) {
            from = station; fromQuery = station.name
        } else {
            to = station; toQuery = station.name
        }
        // Escolhida a partida, passa logo para a chegada
        if (field == JourneyField.FROM && to == null) toFocus.requestFocus() else focusManager.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Planear viagem", color = Color.White, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        // ── De / Para ──
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RouteIndicator()
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    StationInput(
                        value = fromQuery,
                        onValueChange = { fromQuery = it },
                        placeholder = "Estação de partida",
                        modifier = Modifier.onFocusChanged {
                            if (it.isFocused) activeField = JourneyField.FROM
                            else if (activeField == JourneyField.FROM) activeField = null
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    StationInput(
                        value = toQuery,
                        onValueChange = { toQuery = it },
                        placeholder = "Estação de chegada",
                        modifier = Modifier
                            .focusRequester(toFocus)
                            .onFocusChanged {
                                if (it.isFocused) activeField = JourneyField.TO
                                else if (activeField == JourneyField.TO) activeField = null
                            }
                    )
                }
                IconButton(onClick = {
                    val a = from; from = to; to = a
                    val q = fromQuery; fromQuery = toQuery; toQuery = q
                }) {
                    Icon(SwapVertIcon, contentDescription = "Trocar estações", tint = Color.Gray, modifier = Modifier.size(22.dp))
                }
            }
        }

        // ── Quando ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .padding(3.dp)
        ) {
            val now = CpTime.now()
            SegmentButton("Partir agora", selected = departAt == null) { departAt = null }
            val at = departAt
            SegmentButton(
                text = if (at == null) "Escolher hora"
                else (if (at.toLocalDate().isAfter(now.toLocalDate())) "Amanhã · " else "Hoje · ") + at.format(HM),
                selected = at != null
            ) {
                val base = departAt ?: now
                TimePickerDialog(activity, { _, h, m ->
                    var chosen = LocalDateTime.of(now.toLocalDate(), LocalTime.of(h, m))
                    // Uma hora já passada hoje quer dizer amanhã
                    if (chosen.isBefore(now.minusMinutes(30))) chosen = chosen.plusDays(1)
                    departAt = chosen
                }, base.hour, base.minute, true).show()
            }
        }

        val listPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
        when {
            suggestions.isNotEmpty() -> {
                val field = activeField
                LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (query.isBlank()) item(key = "fav_header") { CpSectionHeader("Favoritas") }
                    items(suggestions, key = { "s_" + it.code }) { station ->
                        StationSuggestionCard(station) { if (field != null) pick(field, station) }
                    }
                }
            }
            loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetrotimeBlue)
            }
            error != null -> JourneyMessage("⚠️", error.orEmpty())
            results != null -> {
                val list = results.orEmpty()
                if (list.isEmpty()) {
                    JourneyMessage(
                        "🚉",
                        "Sem comboios diretos nas próximas horas. Pode haver ligação com transbordo — experimenta uma estação intermédia."
                    )
                } else {
                    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "results_header") {
                            CpSectionHeader(if (list.size == 1) "1 comboio direto" else "${list.size} comboios diretos")
                        }
                        items(list, key = { "${it.trainNumber}_${it.departure}" }) { option ->
                            JourneyCard(option) {
                                activity.startActivity(
                                    CpTripDetailActivity.newIntent(
                                        activity, option.trainNumber, option.runDate.toString(),
                                        fromCode = from?.code, toCode = to?.code
                                    )
                                )
                            }
                        }
                    }
                }
            }
            recents.isNotEmpty() -> LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "recent_header") { CpSectionHeader("Pesquisas recentes") }
                items(recents, key = { it }) { entry ->
                    val parts = entry.split("|")
                    val a = StationPick(parts[0], formatStationName(parts[1]))
                    val b = StationPick(parts[2], formatStationName(parts[3]))
                    RecentJourneyCard(a.name, b.name) {
                        from = a; fromQuery = a.name
                        to = b; toQuery = b.name
                        focusManager.clearFocus()
                    }
                }
            }
            else -> JourneyMessage("🧭", "Escolhe as estações de partida e de chegada para ver os comboios diretos.")
        }
    }
}

/** Ponto de partida, linha e ponto de chegada, alinhados com os dois campos. */
@Composable
private fun RouteIndicator() {
    Column(
        modifier = Modifier.height(105.dp).padding(vertical = 21.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(10.dp).border(2.dp, MetrotimeBlue, CircleShape))
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .width(2.dp)
                .background(Color.White.copy(alpha = 0.15f))
        )
        Box(Modifier.size(10.dp).clip(CircleShape).background(MetrotimeBlue))
    }
}

@Composable
private fun StationInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(MetrotimeBlue),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = Color.Gray, fontSize = 15.sp)
                    innerTextField()
                }
                if (focused && value.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Limpar",
                        tint = Color.Gray,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { onValueChange("") }
                    )
                }
            }
        }
    )
}

@Composable
private fun RowScope.SegmentButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) MetrotimeBlue else Color.Transparent,
        animationSpec = tween(200),
        label = "segmentBg"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.Gray,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun StationSuggestionCard(station: StationPick, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TrainIcon, contentDescription = null, tint = CpIntercidades, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                station.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecentJourneyCard(fromName: String, toName: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TrainIcon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(fromName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("→ $toName", color = Color.LightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun JourneyMessage(emoji: String, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 36.sp)
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun JourneyCard(option: CpJourneyOption, onClick: () -> Unit) {
    val service = CpService.shortName(option.serviceCode)
    val serviceColor = getServiceColor(service)
    val delay = option.delayMinutes
    val today = CpTime.today()
    val strike = if (option.suppressed) TextDecoration.LineThrough else null
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .cpStrip(if (option.suppressed) Color(0xFFFF5252) else serviceColor)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
        ) {
            // Como no quadro da estação: a hora real em grande (a cor do atraso) e a prevista riscada
            val dep = option.realDeparture
            val arr = option.realArrival
            val late = !option.suppressed && dep != option.departure
            val lateColor = if ((delay ?: 0) > 5) Color(0xFFFF5252) else Color(0xFFFFC107)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dep.format(HM), color = if (late) lateColor else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textDecoration = strike)
                if (late) Text(" " + option.departure.format(HM), color = Color.Gray, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                Text("  →  ", color = Color.Gray, fontSize = 14.sp)
                Text(arr.format(HM), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textDecoration = strike)
                if (dep.toLocalDate().isAfter(today)) {
                    Text("  · amanhã", color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                val minutes = java.time.Duration.between(dep, arr).toMinutes()
                Text(if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "$minutes min", color = Color.Gray, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CpServiceBadge(service, serviceColor)
                Spacer(Modifier.width(6.dp))
                val platform = option.departureStop.platform?.takeIf { it.isNotBlank() && it != "0" }
                Text(
                    listOfNotNull("${option.trainNumber}", platform?.let { "Linha $it" }).joinToString(" · "),
                    color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val (status, color) = when {
                    option.suppressed -> "Suprimido" to Color(0xFFFF5252)
                    delay == null -> "Previsto" to Color.Gray
                    delay < 2 -> "Na hora" to Color(0xFF4CAF50)
                    delay <= 5 -> "+$delay min" to Color(0xFFFFC107)
                    else -> "+$delay min" to Color(0xFFFF5252)
                }
                Text(status, color = color, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            }
        }
    }
}
