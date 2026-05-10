package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.Arrival
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.boat.BoatArrival
import com.example.metrotime.data.boat.BoatScheduleRepo
import com.example.metrotime.data.history.StationVisit
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.ui.theme.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import androidx.compose.material.icons.filled.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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

data class ArrivalGroup(
    val destination: String,
    val arrivalTimesSec: List<Int>,
    val fetchedAtMillis: Long,
    val lineColors: List<Color>
)

data class StationReport(val issue: String, val timestamp: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationArrivalsScreen(activity: ComponentActivity, stationId: String) {
    val source = remember { RemoteArrivalsSource(activity) }
    var station by remember { mutableStateOf<Station?>(null) }
    var arrivalGroups by remember { mutableStateOf<List<ArrivalGroup>>(emptyList()) }
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

    // Derive hasBoats from station id so it doesn't recompute inside LazyColumn builder
    val hasBoats by remember(station) {
        derivedStateOf {
            station?.id == "cais_do_sodre" || station?.id == "terreiro_do_paco"
        }
    }

    LaunchedEffect(Unit) {
        val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        db.getReference("global_alert").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                if (active) {
                    globalAlertText = snapshot.child("text").getValue(String::class.java)
                } else {
                    globalAlertText = null
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        while (isActive) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(stationId) {
        val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        val reportsRef = db.getReference("station_reports").child(stationId)
        reportsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val reports = mutableListOf<StationReport>()
                val now = System.currentTimeMillis()
                val maxAgeMs = 3 * 24 * 60 * 60 * 1000L // 3 days
                for (issueSnap in snapshot.children) {
                    val issue = issueSnap.key ?: continue
                    val timestamp = issueSnap.getValue(Long::class.java) ?: continue
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
        })
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
                    arrivalGroups = groupAndFormatArrivals(arrivalsResponse, activity)
                    isEstimated = arrivalsResponse.isEstimated
                    errorMsg = null
                    if (!destinationPromptShown && arrivalGroups.isNotEmpty()) {
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Metrotime", color = Color.White, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            actions = {
                IconButton(onClick = { showReportDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Reportar",
                        tint = Color(0xFFF57C00)
                    )
                }
                var isFav by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.getMetroFavorites(activity).contains(stationId)) }
                IconButton(onClick = { 
                    com.example.metrotime.prefs.Prefs.toggleMetroFavorite(activity, stationId)
                    isFav = !isFav
                }) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorito",
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
                    fontSize = 14.sp
                )
            }
        }

        if (activeReports.isNotEmpty()) {
            val issues = activeReports.map { it.issue }.joinToString(", ")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF57C00))
                    .padding(12.dp)
            ) {
                Text(
                    text = "⚠️ Problemas reportados: $issues",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header card
            station?.let { st ->
                item(key = "header") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = TrainIcon,
                                contentDescription = "Train",
                                tint = Color.LightGray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Estação: ${st.name}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            
            if (errorMsg != null) {
                item(key = "error") {
                    Text(text = errorMsg.orEmpty(), color = Color.Red, modifier = Modifier.padding(16.dp))
                }
            } else if (arrivalGroups.isEmpty() && station != null) {
                item(key = "empty_metro") {
                    Text(text = "Sem comboios próximos", color = Color.LightGray, modifier = Modifier.padding(16.dp))
                }
            } else {
                if (isEstimated) {
                    item(key = "estimated_warning") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF4A3500))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚠️", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Tempos estimados",
                                        color = Color(0xFFF59E0B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "A API do Metro não está a fornecer dados em tempo real para esta linha. Os tempos apresentados são estimados (~4 min).",
                                        color = Color(0xFFD4A44A),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
                items(arrivalGroups, key = { it.destination }) { group ->
                    ArrivalCard(group, currentTime)
                }
            }

            // SURFACE TRANSPORT SECTION (Buses / Boats)
            if (busArrivals.isNotEmpty() || hasBoats) {
                item(key = "surface_toggle") {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (hasBoats) {
                        SurfaceModeToggle(
                            currentMode = surfaceMode, 
                            onModeSelected = { surfaceMode = it }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Text(
                            text = "Autocarros Próximos (< 1km)",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                    }
                }

                if (!hasBoats || surfaceMode == "Autocarros") {
                    if (busArrivals.isEmpty()) {
                        item(key = "empty_bus") {
                            Text("A procurar autocarros...", color = Color.Gray, modifier = Modifier.padding(16.dp))
                        }
                    } else {
                        items(busArrivals) { bus ->
                            BusCard(bus)
                        }
                    }
                } else {
                    if (boatArrivals.isEmpty()) {
                        item(key = "empty_boat") {
                            Text("Sem barcos disponíveis próximos.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                        }
                    } else {
                        items(boatArrivals) { boat ->
                            BoatCard(boat)
                        }
                    }
                }
            }
        }
    }
    
    if (showDestinationPrompt && station != null) {
        DestinationPromptDialog(
            station = station!!,
            destinations = arrivalGroups.map { it.destination }.distinct(),
            onDestinationSelected = { dest ->
                showDestinationPrompt = false
                if (dest != null) {
                    val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(activity)
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                            val lat = loc?.latitude ?: TripHistoryManager.getLastUserLocation(activity)?.first
                            val lon = loc?.longitude ?: TripHistoryManager.getLastUserLocation(activity)?.second

                            if (lat != null && lon != null) {
                                if (!TripHistoryManager.isLocationNearLisbon(lat, lon)) {
                                    android.widget.Toast.makeText(activity, "Apanhado! Estás a mais de 50km de Lisboa! \uD83D\uDD75\uFE0F\u200D\u2642\uFE0F Grande telescópio que tu tens aí para ver o metro...", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    val visit = StationVisit(
                                        stationId = station!!.id,
                                        stationName = station!!.name,
                                        type = "metro",
                                        lineName = station!!.line,
                                        lat = station!!.lat,
                                        lon = station!!.lon,
                                        timestampMs = System.currentTimeMillis(),
                                        confirmed = true
                                    )
                                    TripHistoryManager.addConfirmedVisit(activity, visit)
                                    android.widget.Toast.makeText(activity, "Viagem para $dest registada! Boa viagem \uD83D\uDE87", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                android.widget.Toast.makeText(activity, "Não conseguimos obter a tua localização! Liga o GPS ou aguarda um bocado.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }.addOnFailureListener {
                            android.widget.Toast.makeText(activity, "Erro de localização! Não podes iniciar viagem.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(activity, "Precisas de dar permissão de localização para registar a viagem!", android.widget.Toast.LENGTH_SHORT).show()
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
                            db.getReference("station_reports").child(stationId).child("Elevador Avariado").setValue(System.currentTimeMillis())
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
                            db.getReference("station_reports").child(stationId).child("Escadas Rolantes Paradas").setValue(System.currentTimeMillis())
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
                            if (customReportText.isNotBlank()) {
                                val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                                db.getReference("station_reports").child(stationId).child(customReportText.trim()).setValue(System.currentTimeMillis())
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

@Composable
fun SurfaceModeToggle(currentMode: String, onModeSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(4.dp)
    ) {
        val modes = listOf("Autocarros", "Barcos")
        modes.forEach { mode ->
            val isSelected = currentMode == mode
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFF3B82F6) else Color.Transparent,
                animationSpec = tween(200),
                label = "tabBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onModeSelected(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun BoatCard(boat: BoatArrival) {
    val isSoflusa = boat.serviceName == "Soflusa"
    val brandColor = if (isSoflusa) Color(0xFFE94B3C) else Color(0xFFF39200)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Brand colored strip
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(brandColor)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Info column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Para: ${boat.destination}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = boat.serviceName,
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }

                // ETA
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = boat.scheduledTime,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (boat.etaMinutes == 0) "A zarpar" else "em ${boat.etaMinutes} min",
                        color = if (boat.etaMinutes <= 5) Color(0xFFFF5252) else Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun BusCard(bus: com.example.metrotime.data.bus.BusArrival) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Carris yellow strip
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(Color(0xFFFFCA28))
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Route badge
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFCA28)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bus.routeShortName,
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (bus.routeShortName.length > 3) 12.sp else 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                // Info column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bus.destination,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Paragem: ${bus.stopName}",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                // ETA
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (bus.etaMinutes <= 1) "A chegar" else "~${bus.etaMinutes}",
                        color = if (bus.etaMinutes <= 2) Color(0xFFFF5252) else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (bus.etaMinutes > 1) {
                        Text(
                            text = "min",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ArrivalCard(group: ArrivalGroup, currentTimeMillis: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Colored strip
            Column(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
            ) {
                if (group.lineColors.size > 1) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(group.lineColors[0]))
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(group.lineColors[1]))
                } else {
                    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().background(group.lineColors.firstOrNull() ?: Color.Gray))
                }
            }
            
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TrainIcon,
                    contentDescription = "Train",
                    tint = Color.LightGray,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = "Destino: ${group.destination}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val elapsedSec = ((currentTimeMillis - group.fetchedAtMillis) / 1000).toInt()
                    val timesStr = group.arrivalTimesSec.map { maxOf(0, it - elapsedSec) }.joinToString(", ") { formatTime(it) }

                    Text(
                        text = "Tempos: $timesStr",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

private fun formatTime(totalSeconds: Int): String {
    if (totalSeconds <= 30) return "A chegar!"
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds.toLong())
    val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
    return String.format("%02d:%02d", minutes, seconds)
}

private suspend fun groupAndFormatArrivals(stationArrivals: com.example.metrotime.data.StationArrivals, context: Context): List<ArrivalGroup> {
    if (stationArrivals.arrivals.isEmpty()) return emptyList()

    val allStations = StationsRepo.loadStations(context)

    return stationArrivals.arrivals
        .groupBy { it.direction }
        .map { (direction, arrivalsForDirection) ->
            val arrivalTimesSec = arrivalsForDirection
                .sortedBy { it.minutes }
                .take(3)
                .map { it.minutes }
                
            val destinationStation = allStations.find { it.name.equals(direction, ignoreCase = true) }
            val linesStr = destinationStation?.line?.split(" ") ?: listOf()
            
            val colors = linesStr.map { lineStr ->
                when {
                    lineStr.equals("Azul", ignoreCase = true) -> LineAzul
                    lineStr.equals("Amarela", ignoreCase = true) -> LineAmarela
                    lineStr.equals("Verde", ignoreCase = true) -> LineVerde
                    lineStr.equals("Vermelha", ignoreCase = true) -> LineVermelha
                    else -> Color.Gray
                }
            }.ifEmpty { listOf(Color.Gray) }

            ArrivalGroup(direction, arrivalTimesSec, stationArrivals.fetchedAtMillis, colors)
        }
}

@Composable
fun DestinationPromptDialog(
    station: Station,
    destinations: List<String>,
    onDestinationSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                "Para onde vais?",
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.Text(
                    "Podemos registar esta viagem para as tuas estatísticas. Qual é o teu destino a partir de ${station.name}?",
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    fontSize = 14.sp
                )
                
                destinations.forEach { dest ->
                    androidx.compose.material3.Button(
                        onClick = { onDestinationSelected(dest) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF3B82F6))
                    ) {
                        androidx.compose.material3.Text("Sentido $dest", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
                
                androidx.compose.material3.OutlinedButton(
                    onClick = { onDestinationSelected(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Text("Não vou viajar agora", color = androidx.compose.ui.graphics.Color.LightGray)
                }
            }
        },
        confirmButton = {},
        containerColor = com.example.metrotime.ui.theme.DarkBackground
    )
}
