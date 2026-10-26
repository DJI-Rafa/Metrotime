package com.example.metrotime.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.cp.CpTrack
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await

// ──────────────────────────────────────────────────────────────────────────────
// Separador do metro: pesquisa de estação, linhas (terminais e estado), favoritas com o próximo
// metro e as estações perto de ti. Mesmo estilo do separador CP e da página inicial.
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun MetroTabContent(activity: ComponentActivity) {
    val stations = remember { StationsRepo.loadStations(activity) }
    var query by remember { mutableStateOf("") }
    val status = rememberMetroLines(activity)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Pesquisar estação…", color = TextSecondaryDark) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondaryDark) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MetrotimeBlue,
                unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedContainerColor = CardBackground,
                unfocusedContainerColor = CardBackground,
                cursorColor = Color.White,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        if (query.isNotBlank()) {
            // Sem acentos nem hífens: "sao sebastiao" e "baixa chiado" encontram as estações
            val q = MetroLines.normalize(query)
            val results = stations
                .filter { MetroLines.normalize(it.name).contains(q) }
                .sortedBy { if (MetroLines.normalize(it.name).startsWith(q)) 0 else 1 }
            HomeSection("Estações") {
                if (results.isEmpty()) {
                    Text("Nenhuma estação com esse nome.", color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                } else {
                    MetroStationList(results.map { it to it.line.split(" ").joinToString(" · ") }) { st ->
                        activity.startActivity(StationArrivalsActivity.newIntent(activity, st.id))
                    }
                }
            }
        } else {
            HomeSection("Linhas") {
                METRO_LINES.forEach { (line, color) ->
                    val lineStatus = (status as? MetroNow.Lines)?.statuses?.firstOrNull { it.line == line }
                    MetroLineRow(line, color, status, lineStatus?.ok) {
                        activity.startActivity(LineStationsActivity.newIntent(activity, line))
                    }
                }
            }
            MetroFavorites(activity, stations)
            MetroNearby(activity, stations)
        }
    }
}

@Composable
private fun MetroLineRow(line: String, color: Color, state: MetroNow, ok: Boolean?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Linha $line", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                MetroLines.terminals(line)?.let {
                    Text(it, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val (text, textColor) = when {
                state is MetroNow.Closed -> "Fechado" to TextSecondaryDark
                ok == true -> "Normal" to GREEN
                ok == false -> "Problemas" to ORANGE
                else -> null to TextSecondaryDark
            }
            if (text != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(textColor))
                    Text(text, color = textColor, fontSize = 12.sp)
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
        }
    }
}

/** Estações num só cartão: cores das linhas, nome e uma linha de detalhe. */
@Composable
private fun MetroStationList(rows: List<Pair<Station, String>>, onOpen: (Station) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        rows.forEachIndexed { i, (st, detail) ->
            if (i > 0) Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(st) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LineDots(lineColorsOf(st.line), Modifier.width(36.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(st.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Favoritas do metro com o próximo metro; atualiza a cada 30 s e ao voltar ao separador. */
@Composable
private fun MetroFavorites(activity: ComponentActivity, stations: List<Station>) {
    var rows by remember { mutableStateOf<List<FavRow>?>(null) }
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val favs = Prefs.getMetroFavorites(activity)
                val favStations = stations.filter { it.id in favs }.sortedBy { it.name }
                rows = coroutineScope { favStations.map { st -> async { metroRow(activity, st, inMetroTab = true) } }.awaitAll() }
                delay(30_000)
            }
        }
    }
    val list = rows
    if (list.isNullOrEmpty()) return
    HomeSection("Favoritas") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            list.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
                FavoriteRowView(row) { activity.startActivity(row.open) }
            }
        }
    }
}

/** As duas estações mais perto (até 2 km), pela última localização conhecida. */
@Composable
private fun MetroNearby(activity: ComponentActivity, stations: List<Station>) {
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) {
        here = runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(activity)
                    .lastLocation.await()?.let { it.latitude to it.longitude }
            } else null
        }.getOrNull() ?: TripHistoryManager.getLastUserLocation(activity)
    }
    val nearby = remember(here) {
        val at = here ?: return@remember emptyList()
        stations.map { it to CpTrack.metres(at, it.lat to it.lon) }
            .filter { it.second < 2_000 }
            .sortedBy { it.second }
            .take(2)
    }
    if (nearby.isEmpty()) return
    HomeSection("Perto de ti") {
        MetroStationList(nearby.map { (st, metres) -> st to walkText(metres) }) { st ->
            activity.startActivity(StationArrivalsActivity.newIntent(activity, st.id))
        }
    }
}

/** "350 m · 5 min a pé" (a ~80 m por minuto). */
internal fun walkText(metres: Double): String {
    val minutes = kotlin.math.ceil(metres / 80).toInt().coerceAtLeast(1)
    val distance = if (metres < 1000) "${(metres / 10).toInt() * 10} m" else "%.1f km".format(metres / 1000).replace('.', ',')
    return "$distance · $minutes min a pé"
}
