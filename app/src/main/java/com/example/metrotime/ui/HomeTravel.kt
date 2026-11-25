package com.example.metrotime.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
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
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.plan.LastTrip
import com.example.metrotime.data.plan.MetroTrip
import com.example.metrotime.data.plan.Place
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────────────────
// Página inicial: "Para casa" (último metro e último comboio de onde estás para casa) e a escolha da casa.
// ──────────────────────────────────────────────────────────────────────────────

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private const val NEAR_HOME_M = 800.0

private data class GoHome(
    val located: Boolean,
    val nearHome: Boolean = false,
    val metro: LastTrip.Metro? = null,
    val train: LastTrip.Train? = null
)

@Composable
internal fun HomeGoHomeCard(activity: ComponentActivity, fill: Boolean = false) {
    var home by remember { mutableStateOf(LastTrip.home(activity)) }
    var state by remember { mutableStateOf<GoHome?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(Prefs.isLastTripAlerts(activity)) }

    LaunchedEffect(home) {
        state = null
        val h = home ?: return@LaunchedEffect
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val here = currentLocation(activity)
                state = if (here == null) GoHome(located = false) else {
                    val p = Place(Place.Kind.HERE, "", "Aqui", here.first, here.second)
                    if (TripPlanner.metres(here.first, here.second, h.lat, h.lon) < NEAR_HOME_M) GoHome(located = true, nearHome = true)
                    // A rede da CP (estação mais perto) lê-se de um ficheiro: fora da thread do ecrã
                    else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        GoHome(
                            located = true,
                            metro = LastTrip.lastMetro(p, h),
                            train = attempt { LastTrip.lastTrain(activity, p, h, CpTime.now()) }
                        )
                    }
                }
                delay(5 * 60_000L)
            }
        }
    }

    Card(
        modifier = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (fill) 16.dp else 14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        val h = home
        if (h == null) {
            Row(
                modifier = Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier).clickable { showPicker = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(22.dp))
                Text(
                    "Define a tua casa para veres o último metro e o último comboio para lá.",
                    color = TextSecondaryDark, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().then(if (fill) Modifier.fillMaxHeight() else Modifier).padding(if (fill) 16.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Home, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Para casa", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(h.name, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { activity.startActivity(TripPlannerActivity.newIntent(activity, to = h.encode())) }) {
                        Text("Ir agora", color = MetrotimeBlue, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showPicker = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Mudar a casa", tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
                    }
                }
                GoHomeLines(state)
                if (fill) Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Avisar-me à noite antes do último", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = alerts,
                        onCheckedChange = { alerts = it; Prefs.setLastTripAlerts(activity, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue),
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }

    if (showPicker) {
        HomePickerDialog(
            activity = activity,
            current = home,
            onPicked = { p ->
                Prefs.setHome(activity, p?.encode())
                home = p
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun GoHomeLines(state: GoHome?) {
    val muted = TextSecondaryDark
    when {
        state == null -> Text("A ver o último metro e comboio…", color = muted, fontSize = 13.sp)
        !state.located -> Text("Liga a localização para saberes o último metro e comboio daqui para casa.", color = muted, fontSize = 13.sp)
        state.nearHome -> Text("Estás perto de casa.", color = muted, fontSize = 13.sp)
        state.metro == null && state.train == null -> Text("Sem metro nem comboio direto daqui para casa.", color = muted, fontSize = 13.sp)
        else -> {
            val now = CpTime.now()
            val nowMin = MetroTrip.serviceMinutes(now.hour, now.minute)
            state.metro?.let { m ->
                val left = (m.serviceMinutes - nowMin).toLong()
                LastLine(
                    "Último metro",
                    if (left < 0) "já passou · o metro reabre às 6:30" else "${m.clock} em ${m.fromName}",
                    left.takeIf { it >= 0 },
                    "estimado"
                )
            }
            state.train?.let { t ->
                val left = Duration.between(now, t.departure).toMinutes()
                LastLine("Último comboio", "${t.label} · ${t.departure.format(HM)} em ${t.fromName}", left.takeIf { it >= 0 }, null)
            }
        }
    }
}

@Composable
private fun LastLine(title: String, text: String, minutesLeft: Long?, note: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title + (note?.let { " ($it)" } ?: ""), color = TextSecondaryDark, fontSize = 11.sp)
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (minutesLeft != null && minutesLeft <= 120) {
            Text(
                if (minutesLeft < 60) "daqui a $minutesLeft min" else "daqui a ${minutesLeft / 60} h ${minutesLeft % 60} min",
                color = if (minutesLeft <= 30) ORANGE else GREEN,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Escolher a casa: uma estação, uma paragem ou a localização atual; também a remove. */
@Composable
internal fun HomePickerDialog(activity: ComponentActivity, current: Place?, onPicked: (Place?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<Place>>(emptyList()) }
    LaunchedEffect(Unit) { favorites = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { favoritePlaces(activity) } }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        delay(200)
        results = TripPlanner.searchPlaces(activity, query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkBackground,
        title = { Text("Onde é a tua casa?", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Estação ou paragem…", color = TextSecondaryDark) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondaryDark) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MetrotimeBlue,
                        unfocusedBorderColor = Color(0xFF3A3A3A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                val list = if (query.isBlank()) favorites else results
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (query.isBlank()) {
                        item(key = "here") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        scope.launch {
                                            val here = currentLocation(activity)
                                            if (here == null) {
                                                Toast.makeText(activity, "Não foi possível saber onde estás.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                onPicked(Place(Place.Kind.POINT, "home", "Casa (localização guardada)", here.first, here.second))
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MetrotimeBlue, modifier = Modifier.size(22.dp))
                                Text("Estou em casa agora (usar a localização)", color = Color.White, fontSize = 14.sp)
                            }
                        }
                        if (favorites.isNotEmpty()) item(key = "fav") { Text("Favoritas", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                    items(list, key = { "${it.kind}_${it.id}" }) { p -> PlaceRow(p) { onPicked(p) } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar", color = Color.LightGray) }
        },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { onPicked(null) }) { Text("Remover casa", color = Color(0xFFFF8A7D)) }
            }
        }
    )
}
