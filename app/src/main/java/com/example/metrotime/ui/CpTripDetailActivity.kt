package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.cp.*
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

import java.util.Calendar
import java.util.TimeZone

fun addMinutesToTime(timeStr: String, delayMin: Int): String {
    if (timeStr.isBlank() || delayMin == 0) return timeStr
    try {
        val parts = timeStr.split(":")
        if (parts.size >= 2) {
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            val totalMins = h * 60 + m + delayMin
            // Handle hours past midnight (e.g. 24, 25)
            val newH = (totalMins / 60) % 24 
            val newM = totalMins % 60
            return String.format("%02d:%02d", newH, newM)
        }
    } catch (e: Exception) {}
    return timeStr
}

fun getNormalizedMinutes(timeStr: String?): Int? {
    if (timeStr.isNullOrBlank()) return null
    return try {
        val parts = timeStr.split(":")
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        val mins = h * 60 + m
        if (mins < 4 * 60) mins + 24 * 60 else mins
    } catch (e: Exception) { null }
}

fun getEffectiveTime(stop: CpTrainStop, isDeparture: Boolean): Int? {
    val arr = getNormalizedMinutes(stop.eta ?: stop.arrival)
    val dep = getNormalizedMinutes(stop.etd ?: stop.departure)
    return if (isDeparture) (dep ?: arr) else (arr ?: dep)
}

fun calculateTrainPosition(
    stops: List<CpTrainStop>,
    currentStopIndex: Int,
    status: String?
): Float {
    if (stops.isEmpty() || currentStopIndex < 0 || currentStopIndex >= stops.size) return -1f

    if (status == VehicleStatus.AT_STATION || status == VehicleStatus.AT_ORIGIN || status == VehicleStatus.COMPLETED) {
        return currentStopIndex.toFloat()
    }

    if (currentStopIndex == stops.lastIndex) {
        return currentStopIndex.toFloat()
    }

    // Transit between currentStopIndex and currentStopIndex + 1
    val dep = getEffectiveTime(stops[currentStopIndex], isDeparture = true)
    val arr = getEffectiveTime(stops[currentStopIndex + 1], isDeparture = false)

    if (dep == null || arr == null || dep >= arr) {
        return currentStopIndex + 0.5f
    }

    val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Lisbon"))
    val nowMinsRaw = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val nowMins = if (nowMinsRaw < 4 * 60) nowMinsRaw + 24 * 60 else nowMinsRaw

    var progress = (nowMins - dep).toFloat() / (arr - dep).toFloat()
    progress = progress.coerceIn(0.1f, 0.9f)
    
    return currentStopIndex + progress
}

class CpTripDetailActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TRAIN_NUMBER = "train_number"

        fun newIntent(context: Context, trainNumber: Int): Intent {
            return Intent(context, CpTripDetailActivity::class.java).apply {
                putExtra(EXTRA_TRAIN_NUMBER, trainNumber)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CpGtfsRepository.initialize(applicationContext)
        val trainNumber = intent.getIntExtra(EXTRA_TRAIN_NUMBER, -1)
        if (trainNumber == -1) { finish(); return }

        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    CpTripDetailScreen(this@CpTripDetailActivity, trainNumber)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpTripDetailScreen(activity: ComponentActivity, trainNumber: Int) {
    var trip by remember { mutableStateOf<CpTripDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Auto-refresh every 15 seconds
    LaunchedEffect(trainNumber) {
        // Wait for GTFS data to finish loading
        CpGtfsRepository.awaitReady()

        // Pre-load station names from the /stations endpoint
        var stationsMap: Map<String, String> = emptyMap()
        try {
            val response = withContext(Dispatchers.IO) { CpApi.service.getStations() }
            stationsMap = response.stations.associate { it.code to it.designation }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        while (isActive) {
            try {
                // ── Step 1: Get live vehicle data from /vehicles ──
                val vehicle = withContext(Dispatchers.IO) {
                    val resp = CpApi.service.getVehicles()
                    resp.vehicles.firstOrNull { it.trainNumber == trainNumber }
                }

                if (vehicle == null) {
                    if (trip == null) errorMsg = "Comboio $trainNumber não encontrado em circulação."
                    isLoading = false
                    delay(60_000)
                    continue
                }

                // Vehicles API delay is in SECONDS → convert to minutes
                val delaySec = vehicle.delay ?: 0
                val delayMin = delaySec / 60

                // Convert lastStation from API format (94-XXXXX) to GTFS format (94_XXXXX)
                val lastStationGtfs = vehicle.lastStation?.replace("-", "_")

                // ── Step 2: Get schedule from GTFS ──
                val schedule = CpGtfsRepository.getTripSchedule(trainNumber, vehicle.gtfs?.tripId)

                val trainStops: List<CpTrainStop> = if (schedule != null && schedule.isNotEmpty()) {
                    // Find the index of lastStation in the schedule
                    val lastStationIdx = if (lastStationGtfs != null) {
                        schedule.indexOfFirst { it.id == lastStationGtfs }
                    } else -1

                    schedule.mapIndexed { idx, gtfsStop ->
                        val designation = CpGtfsRepository.getStopDetails(gtfsStop.id)?.name
                            ?: stationsMap[gtfsStop.id.replace("_", "-")]
                            ?: "Estação ${gtfsStop.id}"

                        val arrBase = gtfsStop.arr.take(5)
                        val depBase = gtfsStop.dep.take(5)

                        // Only apply delay to the current station and future stations
                        // Past stations departed on time (or with whatever delay they had then)
                        val isPast = lastStationIdx >= 0 && idx < lastStationIdx
                        val effectiveDelay = if (isPast) 0 else delayMin
                        val eta = addMinutesToTime(arrBase, effectiveDelay)
                        val etd = addMinutesToTime(depBase, effectiveDelay)

                        CpTrainStop(
                            station = CpGenericEntity(code = gtfsStop.id, designation = designation),
                            arrival = arrBase,
                            departure = depBase,
                            delay = effectiveDelay,
                            eta = if (effectiveDelay > 0) eta else null,
                            etd = if (effectiveDelay > 0) etd else null
                        )
                    }
                } else {
                    // Last resort: use cached route codes
                    val cachedCodes = CpRouteRepository.getRoute(trainNumber)
                    if (!cachedCodes.isNullOrEmpty()) {
                        cachedCodes.map { code ->
                            val designation = stationsMap[code] ?: "Estação $code"
                            CpTrainStop(
                                station = CpGenericEntity(code = code, designation = designation)
                            )
                        }
                    } else {
                        // Extreme fallback: at least show the current position and origin/dest from vehicle
                        val stops = mutableListOf<CpTrainStop>()
                        vehicle.origin?.let { stops.add(CpTrainStop(station = it)) }
                        if (lastStationGtfs != null && lastStationGtfs != vehicle.origin?.code?.replace("-", "_") && lastStationGtfs != vehicle.destination?.code?.replace("-", "_")) {
                            stops.add(CpTrainStop(station = CpGenericEntity(code = lastStationGtfs, designation = stationsMap[lastStationGtfs.replace("_", "-")] ?: "Atual")))
                        }
                        vehicle.destination?.let { stops.add(CpTrainStop(station = it)) }
                        stops
                    }
                }

                // ── Step 3: Build complete CpTripDetail from vehicle + GTFS ──
                val detail = CpTripDetail(
                    trainNumber = trainNumber,
                    serviceCode = CpServiceCode(
                        code = vehicle.service?.code,
                        designation = vehicle.service?.designation
                    ),
                    lastStationCode = lastStationGtfs,
                    delay = delayMin,
                    status = vehicle.status,
                    hasDisruptions = vehicle.hasDisruptions,
                    latitude = vehicle.latitude,
                    longitude = vehicle.longitude,
                    trainStops = trainStops
                )

                trip = detail
                errorMsg = null
            } catch (e: Exception) {
                e.printStackTrace()
                if (trip == null) {
                    errorMsg = if (e.message?.contains("429") == true) {
                        "Demasiados pedidos. Tente novamente mais tarde."
                    } else {
                        "Erro: ${e.javaClass.simpleName}"
                    }
                }
            }
            isLoading = false
            delay(60_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text("Comboio $trainNumber", color = Color.White, fontWeight = FontWeight.Bold)
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
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CpIntercidades)
            }
        } else if (errorMsg != null && trip == null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg.orEmpty(), color = Color.Red)
            }
        } else if (trip != null) {
            val t = trip!!
            
            Column(modifier = Modifier.fillMaxSize()) {
                if (errorMsg != null) {
                    Text(
                        text = errorMsg.orEmpty(),
                        color = Color.Red,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 12.sp
                    )
                }
                
                val stops = t.trainStops ?: emptyList()
                val lastStationCode = t.lastStationCode

                // Find the index of the current/last station
                val currentStopIndex = stops.indexOfLast { stop ->
                    stop.station?.code == lastStationCode
                }

                val trainPosition = calculateTrainPosition(stops, currentStopIndex, t.status)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Header card
                    item(key = "header") {
                        TripHeaderCard(t, trainNumber)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Find the index of the current/last station already done above

                    itemsIndexed(stops, key = { idx, stop -> "${idx}_${stop.station?.code}" }) { index, stop ->
                        val isPast = index < currentStopIndex
                        val isCurrent = index == currentStopIndex
                        val isFirst = index == 0
                        val isLast = index == stops.lastIndex

                        TimelineStopRow(
                            stop = stop,
                            isPast = isPast,
                            isCurrent = isCurrent,
                            isFirst = isFirst,
                            isLast = isLast,
                            serviceColor = getServiceColor(t.serviceCode?.code),
                            index = index,
                            trainPosition = trainPosition
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TripHeaderCard(trip: CpTripDetail, knownTrainNumber: Int) {
    val serviceColor = getServiceColor(trip.serviceCode?.code)
    // Trips API: delay is already in MINUTES (not seconds!)
    val delayMin = trip.delay ?: 0
    val delayColor = when {
        delayMin <= 0 -> Color(0xFF4CAF50)
        delayMin in 1..5 -> Color(0xFFFFC107)
        else -> Color(0xFFFF5252)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(serviceColor)
            )

            Column(modifier = Modifier.padding(16.dp)) {
                // Service + train number
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(serviceColor)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = trip.serviceCode?.designation ?: "?",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Nº ${trip.trainNumber ?: knownTrainNumber}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Origin → Destination
                val stops = trip.trainStops ?: emptyList()
                val origin = stops.firstOrNull()?.station?.designation ?: "?"
                val destination = stops.lastOrNull()?.station?.designation ?: "?"
                Text(
                    text = "$origin  →  $destination",
                    color = Color.LightGray,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Duration + delay
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (trip.duration != null) {
                        Text(text = "Duração: ${trip.duration}", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(delayColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (delayMin <= 0) "Sem atraso" else "+${delayMin} min atraso",
                            color = delayColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                // Status
                val statusText = when (trip.status) {
                    VehicleStatus.IN_TRANSIT -> "Em trânsito"
                    VehicleStatus.AT_STATION -> "Na estação"
                    VehicleStatus.AT_ORIGIN -> "Na origem"
                    VehicleStatus.NEAR_NEXT -> "Próx. estação"
                    VehicleStatus.NOT_STARTED -> "Não iniciado"
                    VehicleStatus.COMPLETED -> "Concluído"
                    VehicleStatus.CANCELLED -> "Cancelado"
                    else -> trip.status ?: ""
                }
                if (statusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = statusText, color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

// Removed PastStationRed to use serviceColor consistently

@Composable
fun TimelineStopRow(
    stop: CpTrainStop,
    isPast: Boolean,
    isCurrent: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    serviceColor: Color,
    index: Int,
    trainPosition: Float
) {
    // Past and current stations use service color. Future uses gray.
    val dotColor = when {
        isPast || isCurrent -> serviceColor
        else -> Color.Gray
    }
    val lineColor = when {
        isPast || isCurrent -> serviceColor
        else -> Color(0xFF3A3A3A)
    }
    val textColor = when {
        isCurrent -> Color.White
        isPast -> Color.Gray
        else -> Color.LightGray
    }

    // Trips API: stop delay is already in MINUTES
    val stopDelay = stop.delay ?: 0
    val hasDelay = stopDelay > 0
    val isSuppressed = stop.supression != null

    val isTrainAtStation = trainPosition == index.toFloat()
    val isTrainOnTopLine = trainPosition > (index - 1f) && trainPosition < index && trainPosition > (index - 0.5f)
    val isTrainOnBottomLine = trainPosition > index && trainPosition < (index + 1f) && trainPosition <= (index + 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Timeline column (dot + connecting lines)
        Column(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top line segment
            if (!isFirst) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (trainPosition >= index) {
                        Box(Modifier.fillMaxSize().background(serviceColor))
                    } else if (isTrainOnTopLine) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().weight(1f).background(serviceColor))
                            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF3A3A3A)))
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF3A3A3A)))
                    }

                    if (isTrainOnTopLine) {
                        Box(
                            modifier = Modifier
                                .requiredSize(22.dp)
                                .background(Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(TrainIcon, contentDescription = null, tint = Color.Black, modifier = Modifier.requiredSize(16.dp))
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Dot
            Box(
                modifier = Modifier
                    .size(if (isTrainAtStation) 22.dp else if (isCurrent) 16.dp else 10.dp)
                    .clip(CircleShape)
                    .background(if (isTrainAtStation) Color.White else dotColor),
                contentAlignment = Alignment.Center
            ) {
                if (isTrainAtStation) {
                    Icon(TrainIcon, contentDescription = null, tint = Color.Black, modifier = Modifier.requiredSize(16.dp))
                }
            }

            // Bottom line segment
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (trainPosition > index + 0.5f) {
                        Box(Modifier.fillMaxSize().background(serviceColor))
                    } else if (isTrainOnBottomLine) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().weight(1f).background(serviceColor))
                            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF3A3A3A)))
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFF3A3A3A)))
                    }

                    if (isTrainOnBottomLine) {
                        Box(
                            modifier = Modifier
                                .requiredSize(22.dp)
                                .background(Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(TrainIcon, contentDescription = null, tint = Color.Black, modifier = Modifier.requiredSize(16.dp))
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Stop info
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
        ) {
            // Station name
            Text(
                text = stop.station?.designation ?: "?",
                color = if (isCurrent) Color.White else textColor,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (isCurrent) 15.sp else 14.sp,
                textDecoration = if (isSuppressed) TextDecoration.LineThrough else null
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Times row
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Scheduled time
                val scheduledArr = stop.arrival
                val scheduledDep = stop.departure
                val scheduledText = buildString {
                    if (scheduledArr != null) append(scheduledArr)
                    if (scheduledDep != null) {
                        if (isNotEmpty()) append(" / ")
                        append(scheduledDep)
                    }
                }
                if (scheduledText.isNotEmpty()) {
                    Text(
                        text = scheduledText,
                        color = if (hasDelay) Color.Gray else textColor,
                        fontSize = 12.sp,
                        textDecoration = if (hasDelay) TextDecoration.LineThrough else null
                    )
                }

                // Real time (ETA/ETD) if delayed
                if (hasDelay) {
                    Spacer(modifier = Modifier.width(8.dp))
                    val realText = buildString {
                        if (stop.eta != null) append(stop.eta)
                        if (stop.etd != null) {
                            if (isNotEmpty()) append(" / ")
                            append(stop.etd)
                        }
                    }
                    if (realText.isNotEmpty()) {
                        Text(
                            text = realText,
                            color = Color(0xFFFF5252),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+${stopDelay}'",
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp
                    )
                }
            }

            // Platform
            if (!stop.platform.isNullOrBlank() && stop.platform != "0") {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Plat. ${stop.platform}",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            // Suppression info
            if (isSuppressed) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "⚠ Suprimido: ${stop.supression?.designation ?: ""}",
                    color = Color(0xFFFF9800),
                    fontSize = 11.sp
                )
            }
        }
    }
}
