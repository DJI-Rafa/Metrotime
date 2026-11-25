package com.example.metrotime.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.bus.BusRoute
import com.example.metrotime.data.bus.BusStop
import com.example.metrotime.data.bus.CarrisGtfsData
import com.example.metrotime.data.cp.CpTrack
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ──────────────────────────────────────────────────────────────────────────────
// Separador dos autocarros: pesquisa de linha ou paragem, as tuas paragens e as que estão perto
// com as próximas chegadas, e as linhas que abriste há pouco. Números na cor oficial de cada linha.
// ──────────────────────────────────────────────────────────────────────────────

/** Uma paragem com as próximas chegadas (uma por linha, a mais próxima). */
private data class StopRow(val stop: BusStop, val detail: String, val arrivals: List<BusMapRepository.StopArrivalInfo>?)

@Composable
fun BusTabContent(activity: ComponentActivity) {
    var query by remember { mutableStateOf("") }

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
            placeholder = { Text("Linha, destino ou paragem…", color = TextSecondaryDark) },
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
            BusSearchResults(activity, query)
        } else {
            BusMapCard(activity)
            BusFavoriteStops(activity)
            BusNearbyStops(activity)
            BusRecentLines(activity)
        }
    }
}

private fun openRoute(activity: ComponentActivity, shortName: String) =
    activity.startActivity(Intent(activity, LiveMapActivity::class.java).putExtra(LiveMapActivity.EXTRA_BUS_ROUTE, shortName))

private fun openStop(context: android.content.Context, stop: BusStop) =
    context.startActivity(
        Intent(context, LiveMapActivity::class.java)
            .putExtra(LiveMapActivity.EXTRA_BUS_STOP, listOf(stop.operator, stop.id, stop.name, stop.lat, stop.lon).joinToString("|"))
    )

/** Um autocarro com GPS abre o mapa centrado nele; um previsto (sem GPS) abre a paragem. */
private fun openArrival(context: android.content.Context, a: BusMapRepository.StopArrivalInfo, stop: BusStop) {
    val vehicleId = a.vehicleId
    if (a.realtime && vehicleId != null) {
        context.startActivity(LiveMapActivity.busIntent(context, vehicleId, a.operator, a.routeShortName))
    } else {
        openStop(context, stop)
    }
}

private fun operatorName(operator: String) = if (operator == "carris_metropolitana") "Carris Metropolitana" else "Carris"

// ── Pesquisa ─────────────────────────────────────────────────────────────────

@Composable
private fun BusSearchResults(activity: ComponentActivity, query: String) {
    var routes by remember { mutableStateOf<List<BusRoute>?>(null) }
    var stops by remember { mutableStateOf<List<BusStop>>(emptyList()) }
    LaunchedEffect(query) {
        delay(300) // espera que se acabe de escrever
        val q = MetroLines.normalize(query)
        stops = withContext(Dispatchers.IO) {
            CarrisGtfsData.ensureLoaded(activity)
            CarrisGtfsData.stops
                .filter { MetroLines.normalize(it.name).contains(q) }
                .sortedBy { if (MetroLines.normalize(it.name).startsWith(q)) 0 else 1 }
                .distinctBy { it.name }
                .take(6)
                .map { BusStop(it.id, it.name, it.lat, it.lon, "carris") }
        }
        routes = runCatching { BusMapRepository.searchRoutes(activity, query.trim()) }.getOrDefault(emptyList()).take(12)
    }

    val found = routes
    if (found == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
        }
        return
    }
    if (found.isEmpty() && stops.isEmpty()) {
        Text("Nenhuma linha ou paragem com esse nome.", color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
        return
    }
    if (found.isNotEmpty()) {
        HomeSection("Linhas") {
            BusCard {
                found.forEachIndexed { i, route ->
                    if (i > 0) BusDivider()
                    BusLineRow(route.shortName, route.color, route.longName, operatorName(route.operator)) { openRoute(activity, route.shortName) }
                }
            }
        }
    }
    if (stops.isNotEmpty()) {
        HomeSection("Paragens") {
            BusCard {
                stops.forEachIndexed { i, stop ->
                    if (i > 0) BusDivider()
                    BusStopRowView(StopRow(stop, operatorName(stop.operator), null)) { openStop(activity, stop) }
                }
            }
        }
    }
}

// ── Mapa ─────────────────────────────────────────────────────────────────────

@Composable
private fun BusMapCard(activity: ComponentActivity) {
    Card(
        onClick = { activity.startActivity(Intent(activity, LiveMapActivity::class.java).putExtra("extra_open_bus_search", true)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MetrotimeBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(MapIcon, contentDescription = null, tint = MetrotimeBlue, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Ver no mapa", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Autocarros em direto, linha a linha", color = TextSecondaryDark, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Paragens: favoritas e perto de ti ────────────────────────────────────────

/** Próximas chegadas de cada paragem, em paralelo; null se falhar (mostra a paragem sem tempos). */
private suspend fun withArrivals(activity: ComponentActivity, rows: List<Pair<BusStop, String>>): List<StopRow> = coroutineScope {
    rows.map { (stop, detail) ->
        async {
            val arrivals = runCatching {
                BusMapRepository.getStopArrivals(activity, stop.id, stop.operator)
                    .groupBy { it.routeShortName }.values.map { it.minBy { a -> a.etaMinutes } }
                    .sortedBy { it.etaMinutes }
            }.getOrNull()
            StopRow(stop, detail, arrivals)
        }
    }.awaitAll()
}

/** Paragens favoritas com as próximas chegadas; [title] null = só o cartão (no "Agora" da página inicial). */
@Composable
internal fun BusFavoriteStops(activity: ComponentActivity, title: String? = "As tuas paragens") {
    var rows by remember { mutableStateOf<List<StopRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val favs = Prefs.getFavoriteBusStops(activity)
                    .mapNotNull { entry ->
                        entry.split("|").takeIf { it.size == 5 }?.let { (operator, id, name, lat, lon) ->
                            BusStop(id, name, lat.toDoubleOrNull() ?: return@let null, lon.toDoubleOrNull() ?: return@let null, operator)
                        }
                    }
                    .sortedBy { it.name }
                // Primeiro sem tempos (aparece logo), depois com as chegadas
                if (rows.map { it.stop } != favs) rows = favs.map { StopRow(it, operatorName(it.operator), null) }
                rows = withArrivals(activity, favs.map { it to operatorName(it.operator) })
                delay(60_000)
            }
        }
    }
    if (rows.isEmpty()) return
    val card: @Composable () -> Unit = {
        BusCard {
            rows.forEachIndexed { i, row ->
                if (i > 0) BusDivider()
                BusStopRowView(row) { openStop(activity, row.stop) }
            }
        }
    }
    if (title == null) card() else HomeSection(title) { card() }
}

/** Até três paragens a menos de 500 m (uma por nome), pela última localização conhecida. */
@Composable
private fun BusNearbyStops(activity: ComponentActivity) {
    var rows by remember { mutableStateOf<List<StopRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        val here = runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(activity)
                    .lastLocation.await()?.let { it.latitude to it.longitude }
            } else null
        }.getOrNull() ?: TripHistoryManager.getLastUserLocation(activity) ?: return@LaunchedEffect

        val nearby = withContext(Dispatchers.IO) {
            CarrisGtfsData.ensureLoaded(activity)
            val carris = CarrisGtfsData.stops.map { BusStop(it.id, it.name, it.lat, it.lon, "carris") }
            val cm = runCatching { BusMapRepository.getAllCmStops() }.getOrDefault(emptyList())
            (carris + cm)
                .map { it to CpTrack.metres(here, it.lat to it.lon) }
                .filter { it.second < 500 }
                .sortedBy { it.second }
                .distinctBy { it.first.name }
                .take(3)
        }
        if (nearby.isEmpty()) return@LaunchedEffect
        val withDetail = nearby.map { (stop, metres) -> stop to walkText(metres) }
        rows = withDetail.map { (stop, detail) -> StopRow(stop, detail, null) }
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                rows = withArrivals(activity, withDetail)
                delay(60_000)
            }
        }
    }
    if (rows.isEmpty()) return
    HomeSection("Paragens perto de ti") {
        BusCard {
            rows.forEachIndexed { i, row ->
                if (i > 0) BusDivider()
                BusStopRowView(row) { openStop(activity, row.stop) }
            }
        }
    }
}

// ── Linhas recentes ──────────────────────────────────────────────────────────

@Composable
private fun BusRecentLines(activity: ComponentActivity) {
    var recents by remember { mutableStateOf(Prefs.getRecentBusLines(activity)) }
    // Ao voltar do mapa pode haver uma linha nova
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { recents = Prefs.getRecentBusLines(activity) }
    }
    if (recents.isEmpty()) return
    HomeSection("Linhas recentes") {
        BusCard {
            recents.forEachIndexed { i, entry ->
                val (operator, number, name, color) = entry.split("|")
                if (i > 0) BusDivider()
                BusLineRow(number, color, name, operatorName(operator)) { openRoute(activity, number) }
            }
        }
    }
}

// ── Peças ────────────────────────────────────────────────────────────────────

@Composable
private fun BusCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        content = content
    )
}

@Composable
private fun BusDivider() = Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(1.dp).background(DIVIDER))

@Composable
private fun BusLineRow(number: String, color: String, longName: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.width(50.dp), contentAlignment = Alignment.CenterStart) { BusLineBadge(number, color) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(longName.replace(" - ", " ↔ "), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, color = TextSecondaryDark, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
    }
}

/** Paragem: sinal "P", nome, detalhe e, por baixo, as próximas linhas com os minutos. */
@Composable
private fun BusStopRowView(row: StopRow, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(50.dp), contentAlignment = Alignment.CenterStart) { BusStopSign(36.dp) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.stop.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.detail, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
        }
        val arrivals = row.arrivals ?: return@Column
        if (arrivals.isEmpty()) {
            Text("Sem autocarros a caminho", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(start = 62.dp))
        } else {
            Row(
                modifier = Modifier.padding(start = 62.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                arrivals.take(5).forEach { a ->
                    // Tocar num autocarro mostra-o no mapa
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { openArrival(context, a, row.stop) }
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BusLineBadge(a.routeShortName, a.color, height = 22.dp, fontSize = 12.sp)
                        // Tempo real a verde com o sinal (laranja/vermelho se atrasado); previsto, a hora
                        BusEta(a, fontSize = 13.sp, showIcon = true)
                    }
                }
            }
        }
    }
}
