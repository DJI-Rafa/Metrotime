package com.example.metrotime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.boolOrNull
import com.example.metrotime.data.firebaseSafeKey
import com.example.metrotime.data.longOrNull
import com.example.metrotime.data.stringOrNull
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.boat.BoatArrival
import com.example.metrotime.data.boat.BoatScheduleRepo
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class StationArrivalsActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_STATION_ID = "station_id"
        fun newIntent(context: Context, stationId: String): Intent {
            return Intent(context, StationArrivalsActivity::class.java).apply {
                putExtra(EXTRA_STATION_ID, stationId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val stationId = intent.getStringExtra(EXTRA_STATION_ID) ?: run {
            finish()
            return
        }

        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    StationArrivalsScreen(this@StationArrivalsActivity, stationId)
                }
            }
        }
    }
}

data class StationReport(val issue: String, val timestamp: Long)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StationArrivalsScreen(activity: ComponentActivity, stationId: String) {
    val source = remember { RemoteArrivalsSource(activity) }
    var station by remember { mutableStateOf<Station?>(null) }
    // Por linha e sentido (Linha Verde: para Telheiras, para Cais do Sodré)
    var lineGroups by remember { mutableStateOf<List<Pair<String, List<MetroLines.Direction>>>>(emptyList()) }
    var fetchedAt by remember { mutableLongStateOf(0L) }
    var busArrivals by remember { mutableStateOf<List<com.example.metrotime.data.bus.BusArrival>>(emptyList()) }
    var boatArrivals by remember { mutableStateOf<List<BoatArrival>>(emptyList()) }
    var surfaceMode by remember { mutableStateOf("Autocarros") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isEstimated by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDestinationPrompt by remember { mutableStateOf(false) }
    var destinationPromptShown by remember { mutableStateOf(false) }
    
    var activeReports by remember { mutableStateOf<List<StationReport>>(emptyList()) }
    var showReportDialog by remember { mutableStateOf(false) }
    var customReportText by remember { mutableStateOf("") }
    var globalAlertText by remember { mutableStateOf<String?>(null) }
    var isMaintenanceMode by remember { mutableStateOf(false) }

    // Derive hasBoats from station id so it doesn't recompute inside LazyColumn builder
    val hasBoats by remember(station) {
        derivedStateOf {
            station?.id == "cais_do_sodre" || station?.id == "terreiro_do_paco"
        }
    }

    // Listeners do Firebase removidos ao sair do ecrã (antes cada estação aberta deixava 3 listeners
    // ativos para sempre, a segurar a Activity em memória)
    DisposableEffect(stationId) {
        val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
        val alertRef = db.getReference("global_alert")
        val maintenanceRef = db.getReference("maintenance_mode")
        val reportsRef = db.getReference("station_reports").child(stationId)

        val alertListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").boolOrNull() ?: false
                globalAlertText = if (active) snapshot.child("text").stringOrNull() else null
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        val maintenanceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isMaintenanceMode = snapshot.boolOrNull() ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        val reportsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val reports = mutableListOf<StationReport>()
                val now = System.currentTimeMillis()
                val maxAgeMs = 3 * 24 * 60 * 60 * 1000L // 3 days
                for (issueSnap in snapshot.children) {
                    val issue = issueSnap.key ?: continue
                    val timestamp = issueSnap.longOrNull() ?: continue
                    if (now - timestamp <= maxAgeMs) {
                        reports.add(StationReport(issue, timestamp))
                    } else {
                        // Automatically clean up old report if not confirmed by admin
                        issueSnap.ref.removeValue()
                    }
                }
                activeReports = reports
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        alertRef.addValueEventListener(alertListener)
        maintenanceRef.addValueEventListener(maintenanceListener)
        reportsRef.addValueEventListener(reportsListener)
        onDispose {
            alertRef.removeEventListener(alertListener)
            maintenanceRef.removeEventListener(maintenanceListener)
            reportsRef.removeEventListener(reportsListener)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            currentTime = System.currentTimeMillis()
            // Arrival times are network-refreshed in 15-second intervals; a five-second
            // display tick keeps them useful without recomposing the whole list every second.
            delay(5_000)
        }
    }

    val isAdmin = remember { Prefs.isAdmin(activity) }
    if (isMaintenanceMode && !isAdmin) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF57C00), modifier = Modifier.size(80.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("App em manutenção", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Voltaremos assim que possível. A nossa equipa está a trabalhar para melhorar a tua experiência.", color = Color.Gray, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }

    LaunchedEffect(stationId) {
        station = withContext(Dispatchers.IO) {
            StationsRepo.loadStations(activity).find { it.id == stationId }
        }
        
        while (isActive) {
            station?.let { currentStation ->
                // Metro arrivals — independent
                try {
                    val arrivalsResponse = withContext(Dispatchers.IO) {
                        source.fetchArrivals(currentStation.apiId)
                    }
                    lineGroups = MetroLines.groupByLine(currentStation.apiId, arrivalsResponse.arrivals)
                    fetchedAt = arrivalsResponse.fetchedAtMillis
                    isEstimated = arrivalsResponse.isEstimated
                    errorMsg = null
                    if (!destinationPromptShown && lineGroups.isNotEmpty()) {
                        showDestinationPrompt = true
                        destinationPromptShown = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorMsg = "Erro ao carregar tempos: ${e.javaClass.simpleName}"
                }

                // Bus arrivals — independent
                try {
                    busArrivals = withContext(Dispatchers.IO) {
                        com.example.metrotime.data.bus.GtfsRadarService.getBusesNearStation(
                            activity, currentStation.lat, currentStation.lon
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Boat departures — independent
                try {
                    boatArrivals = withContext(Dispatchers.IO) {
                        BoatScheduleRepo.getNextDepartures(currentStation.id, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            delay(15000)
        }
    }

    val lineState = rememberMetroLines(activity)
    val stationLines = remember(station) { station?.let { MetroLines.linesOf(it.apiId) }.orEmpty() }
    val lineProblems = (lineState as? MetroNow.Lines)?.statuses?.filter { !it.ok && it.line in stationLines }.orEmpty()
    val elapsedSec = if (fetchedAt > 0) ((currentTime - fetchedAt) / 1000).toInt().coerceAtLeast(0) else 0

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                // O nome da estação (antes dizia "Metrotime") e as linhas que lá passam
                Column {
                    Text(
                        station?.name.orEmpty(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (stationLines.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            stationLines.forEach { line ->
                                Box(Modifier.size(8.dp).clip(CircleShape).background(lineColorOf(line)))
                                Text(line, color = TextSecondaryDark, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = Color.White
                    )
                }
            },
            actions = {
                IconButton(onClick = { showReportDialog = true }) {
                    Icon(imageVector = FlagIcon, contentDescription = "Reportar um problema na estação", tint = Color.White)
                }
                var isFav by remember { mutableStateOf(Prefs.getMetroFavorites(activity).contains(stationId)) }
                IconButton(onClick = {
                    Prefs.toggleMetroFavorite(activity, stationId)
                    isFav = !isFav
                }) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = if (isFav) "Remover das favoritas" else "Adicionar às favoritas",
                        tint = if (isFav) Color(0xFFFFCA28) else Color.White.copy(alpha = 0.4f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground
            )
        )

        if (globalAlertText != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE53935))
                    .padding(12.dp)
            ) {
                Text(
                    text = "⚠️ Alerta: $globalAlertText",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Problemas reportados pelos utilizadores (elevador, escadas…)
            if (activeReports.isNotEmpty()) {
                item(key = "reports") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeReports.forEach { ReportRow(it, currentTime) }
                    }
                }
            }
            // Perturbação numa linha desta estação (estado oficial do Metro)
            lineProblems.forEach { problem ->
                item(key = "problem_${problem.line}") { LineProblemCard(problem.line, problem.message) }
            }

            item(key = "header") {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Próximos metros", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (fetchedAt > 0) {
                        Text(
                            if (elapsedSec < 60) "atualizado há $elapsedSec s" else "atualizado há ${elapsedSec / 60} min",
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            when {
                errorMsg != null && lineGroups.isEmpty() -> item(key = "error") {
                    Text(text = errorMsg.orEmpty(), color = Color(0xFFFF8A7D), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
                station != null && lineGroups.isEmpty() -> item(key = "empty_metro") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Text(
                            if (RemoteArrivalsSource.isMetroOperating()) "Sem metros previstos de momento." else "Metro fechado · abre às 6:30",
                            color = TextSecondaryDark,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                else -> {
                    if (isEstimated) {
                        item(key = "estimated_warning") { EstimatedNote() }
                    }
                    items(lineGroups, key = { it.first }) { (line, directions) ->
                        LineArrivalsCard(line, directions, elapsedSec)
                    }
                }
            }

            // Autocarros (e barcos no Cais do Sodré e no Terreiro do Paço)
            if (busArrivals.isNotEmpty() || hasBoats) {
                item(key = "surface_header") {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (hasBoats) {
                        SurfaceModeToggle(currentMode = surfaceMode, onModeSelected = { surfaceMode = it })
                    } else {
                        Text(
                            "Autocarros perto",
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                item(key = "surface_list") {
                    if (!hasBoats || surfaceMode == "Autocarros") {
                        if (busArrivals.isEmpty()) {
                            Text("A procurar autocarros…", color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        } else {
                            BusList(busArrivals)
                        }
                    } else {
                        if (boatArrivals.isEmpty()) {
                            Text("Sem barcos nas próximas horas.", color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                        } else {
                            BoatList(boatArrivals)
                        }
                    }
                }
            }
        }
    }

    if (showDestinationPrompt && station != null) {
        DestinationPromptDialog(
            station = station!!,
            destinations = lineGroups.flatMap { (_, directions) -> directions.map { it.destination } }.distinct(),
            onDestinationSelected = { dest ->
                showDestinationPrompt = false
                if (dest != null) {
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
                    if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                            val lat = loc?.latitude ?: TripHistoryManager.getLastUserLocation(activity)?.first
                            val lon = loc?.longitude ?: TripHistoryManager.getLastUserLocation(activity)?.second

                            if (lat != null && lon != null) {
                                // Tens de estar na estação (antes bastava estar a menos de 50 km de Lisboa)
                                val metres = com.example.metrotime.data.MetroNetwork.haversineKm(lat, lon, station!!.lat, station!!.lon) * 1000
                                if (metres > 300) {
                                    Toast.makeText(activity, "Para iniciar a viagem tens de estar na estação (estás a ${metres.toInt()} m).", Toast.LENGTH_LONG).show()
                                } else {
                                    // Conta com a origem; a próxima estação detetada dá-lhe o destino.
                                    // Uma por estação a cada 30 min (antes cada toque era mais uma viagem)
                                    val trip = TripHistoryManager.startManualTrip(activity, station!!.apiId, dest)
                                    Toast.makeText(
                                        activity,
                                        if (trip != null) "Viagem para $dest registada. Boa viagem!" else "Já tens uma viagem registada daqui há pouco.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                Toast.makeText(activity, "Não conseguimos obter a tua localização! Liga o GPS ou aguarda um bocado.", Toast.LENGTH_SHORT).show()
                            }
                        }.addOnFailureListener {
                            Toast.makeText(activity, "Erro de localização! Não podes iniciar viagem.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(activity, "Precisas de dar permissão de localização para registar a viagem!", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showDestinationPrompt = false }
        )
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Reportar Problema", color = Color.White) },
            text = {
                Column {
                    Text("Selecione o equipamento avariado para avisar os outros utilizadores.", color = Color.LightGray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                            db.getReference("station_reports").child(stationId).child("Elevador Avariado").setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                            showReportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))
                    ) {
                        Text("Elevador Avariado", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                            db.getReference("station_reports").child(stationId).child("Escadas Rolantes Paradas").setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                            showReportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))
                    ) {
                        Text("Escadas Rolantes Paradas", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = customReportText,
                        onValueChange = { customReportText = it },
                        placeholder = { Text("Outro problema...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // ".", "#", "$", "[", "]" fechavam a app e "/" criava um nó aninhado que
                            // fazia crashar quem abrisse esta estação
                            val issueKey = firebaseSafeKey(customReportText)
                            if (issueKey.isNotBlank()) {
                                val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                                db.getReference("station_reports").child(stationId).child(issueKey).setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                                customReportText = ""
                                showReportDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00)),
                        enabled = customReportText.isNotBlank()
                    ) {
                        Text("Reportar Problema", color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Cancelar", color = Color.Gray)
                }
            },
            containerColor = DarkBackground
        )
    }
}

/** Autocarros / Barcos, no mesmo estilo dos separadores Partidas / Chegadas. */
@Composable
fun SurfaceModeToggle(currentMode: String, onModeSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBackground)
            .padding(3.dp)
    ) {
        listOf("Autocarros", "Barcos").forEach { mode ->
            val isSelected = currentMode == mode
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MetrotimeBlue else Color.Transparent,
                animationSpec = tween(200),
                label = "tabBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onModeSelected(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode,
                    color = if (isSelected) Color.White else TextSecondaryDark,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** Uma linha da estação: os sentidos, com o próximo metro em minutos e os seguintes por baixo. */
@Composable
private fun LineArrivalsCard(line: String, directions: List<MetroLines.Direction>, elapsedSec: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(lineColorOf(line)))
            Text("Linha $line", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        directions.forEachIndexed { i, direction ->
            if (i > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
            // Contagem decrescente desde o último pedido (a API é consultada de 15 em 15 s)
            val left = direction.seconds.map { (it - elapsedSec).coerceAtLeast(0) }
            val first = left.firstOrNull()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("para", color = TextSecondaryDark, fontSize = 12.sp)
                    Text(direction.destination, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        first?.let { HomeText.metroWait(it) } ?: "—",
                        color = if (first != null && first <= 30) GREEN else Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    HomeText.laterWaits(left.drop(1))?.let {
                        Text(it, color = TextSecondaryDark, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Problema reportado por utilizadores, numa linha discreta. */
@Composable
private fun ReportRow(report: StationReport, now: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A2E22))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = ORANGE, modifier = Modifier.size(16.dp))
        Text(report.issue, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("reportado ${HomeText.ago(now - report.timestamp)}", color = TextSecondaryDark, fontSize = 12.sp)
    }
}

/** Perturbação oficial numa linha que passa nesta estação. */
@Composable
private fun LineProblemCard(line: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E22)),
        border = androidx.compose.foundation.BorderStroke(1.dp, ORANGE.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = ORANGE, modifier = Modifier.size(18.dp))
                Text("Linha $line com problemas", color = ORANGE, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(message, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** A API do Metro não deu tempos para esta estação: mostram-se tempos estimados. */
@Composable
private fun EstimatedNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A2E22))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
        Text(
            "Tempos estimados (~4 min): o Metro não está a dar tempos em tempo real para esta estação.",
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}

/** Autocarros perto, num só cartão: número da carreira, destino, paragem e tempo. */
@Composable
private fun BusList(buses: List<com.example.metrotime.data.bus.BusArrival>) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        buses.forEachIndexed { i, bus ->
            if (i > 0) Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !bus.vehicleId.isNullOrEmpty()) {
                        // Abre o autocarro no mapa ao vivo, centrado nele
                        val vehicleId = bus.vehicleId ?: return@clickable
                        context.startActivity(LiveMapActivity.busIntent(context, vehicleId, "carris", bus.routeShortName))
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Número na cor oficial da linha (as do radar são todas da Carris)
                BusLineBadge(bus.routeShortName, com.example.metrotime.data.bus.BusColors.carris(bus.routeShortName))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(bus.destination, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Paragem ${bus.stopName}", color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Todos estes vêm do GPS: tempo real, a verde com o sinal (como no mapa e no widget)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(com.example.metrotime.ui.theme.LiveIcon, contentDescription = "Tempo real", tint = Color(0xFF6FCF73), modifier = Modifier.size(12.dp))
                    Text(
                        if (bus.etaMinutes <= 1) "a chegar" else "${bus.etaMinutes} min",
                        color = Color(0xFF6FCF73),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Próximos barcos (Transtejo / Soflusa), no mesmo estilo dos autocarros. */
@Composable
private fun BoatList(boats: List<BoatArrival>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        boats.forEachIndexed { i, boat ->
            if (i > 0) Box(Modifier.padding(start = 38.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
            val brandColor = if (boat.serviceName == "Soflusa") Color(0xFFE94B3C) else Color(0xFFF39200)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(brandColor))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(boat.destination, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${boat.serviceName} · ${boat.scheduledTime}", color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1)
                }
                Text(
                    if (boat.etaMinutes == 0) "a sair" else "${boat.etaMinutes} min",
                    color = if (boat.etaMinutes <= 5) ORANGE else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun DestinationPromptDialog(
    station: Station,
    destinations: List<String>,
    onDestinationSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Para onde vais?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Podemos registar esta viagem para as tuas estatísticas. Qual é o teu destino a partir de ${station.name}?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                
                destinations.forEach { dest ->
                    Button(
                        onClick = { onDestinationSelected(dest) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) {
                        Text("Sentido $dest", color = Color.White)
                    }
                }
                
                OutlinedButton(
                    onClick = { onDestinationSelected(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Não vou viajar agora", color = Color.LightGray)
                }
            }
        },
        confirmButton = {},
        containerColor = DarkBackground
    )
}
