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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Estado real do comboio quando a API devolve null ou NOT_STARTED apesar de já haver horas reais,
 * atrasos ou paragens passadas. As horas vêm do CpTripClock (datas reais, atravessa a meia-noite).
 */
fun inferTripStatus(trip: CpTripDetail, moments: List<CpTripClock.StopMoment>, now: java.time.LocalDateTime): String {
    val apiStatus = trip.status
    if (apiStatus != null && apiStatus != VehicleStatus.NOT_STARTED) return apiStatus
    val stops = trip.trainStops ?: return apiStatus ?: VehicleStatus.NOT_STARTED

    val hasRealTimeData = stops.any { it.eta != null || it.etd != null }
    val hasDelay = (trip.delay ?: 0) > 0 || stops.any { (it.delay ?: 0) > 0 }
    val hasPassedStops = stops.any { it.passed == true }
    if (!hasRealTimeData && !hasDelay && !hasPassedStops) return VehicleStatus.NOT_STARTED

    val lastArrival = moments.lastOrNull()?.arrivalOrDeparture
    if (lastArrival != null && now.isAfter(lastArrival)) return VehicleStatus.COMPLETED
    return VehicleStatus.IN_TRANSIT
}

/**
 * Posição do comboio em paragens (2.4 = 40 % entre a 3.ª e a 4.ª): junta a posição pelo relógio
 * (horas reais) com a última estação reportada pela CP. Devolve também se o GPS da CP está atrasado.
 */
fun calculateTrainPosition(
    stops: List<CpTrainStop>,
    currentStopIndex: Int,
    status: String?,
    moments: List<CpTripClock.StopMoment>,
    now: java.time.LocalDateTime
): Pair<Float, Boolean> {
    if (stops.isEmpty() || status == VehicleStatus.NOT_STARTED) return -1f to false

    val timePos = CpTripClock.progress(moments, now)

    var apiPos = -1f
    if (currentStopIndex in stops.indices) {
        apiPos = when {
            status == VehicleStatus.AT_STATION || status == VehicleStatus.AT_ORIGIN || status == VehicleStatus.COMPLETED -> currentStopIndex.toFloat()
            currentStopIndex == stops.lastIndex -> currentStopIndex.toFloat()
            else -> currentStopIndex + 0.1f // IN_TRANSIT
        }
    }

    if (apiPos < 0f) return timePos to false
    if (timePos < 0f) return apiPos to false
    // A CP diz que já vai mais à frente do que o relógio: confia na CP
    if (apiPos > timePos) return apiPos to false
    // O relógio vai à frente: o GPS da CP está atrasado se já devia estar a meio do troço seguinte
    return timePos to (timePos >= currentStopIndex + 0.5f)
}

class CpTripDetailActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TRAIN_NUMBER = "train_number"
        private const val EXTRA_RUN_DATE = "run_date"
        private const val EXTRA_FROM = "from_code"
        private const val EXTRA_TO = "to_code"
        private const val EXTRA_STATION = "station_code"
        private const val EXTRA_STATION_ARRIVAL = "station_arrival"

        /**
         * [fromCode]/[toCode]: estações onde vais entrar/sair (viagem A → B), destacadas no percurso.
         * [stationCode]: estação de cujo quadro de partidas/chegadas vieste — só se marca no percurso,
         * não quer dizer que entras ou sais lá.
         */
        fun newIntent(
            context: Context,
            trainNumber: Int,
            runDate: String? = null,
            fromCode: String? = null,
            toCode: String? = null,
            stationCode: String? = null,
            stationIsArrival: Boolean = false
        ): Intent {
            return Intent(context, CpTripDetailActivity::class.java).apply {
                putExtra(EXTRA_TRAIN_NUMBER, trainNumber)
                if (runDate != null) putExtra(EXTRA_RUN_DATE, runDate)
                if (fromCode != null) putExtra(EXTRA_FROM, fromCode)
                if (toCode != null) putExtra(EXTRA_TO, toCode)
                if (stationCode != null) {
                    putExtra(EXTRA_STATION, stationCode)
                    putExtra(EXTRA_STATION_ARRIVAL, stationIsArrival)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trainNumber = intent.getIntExtra(EXTRA_TRAIN_NUMBER, -1)
        val runDate = intent.getStringExtra(EXTRA_RUN_DATE)
        val fromCode = intent.getStringExtra(EXTRA_FROM)
        val toCode = intent.getStringExtra(EXTRA_TO)
        val stationCode = intent.getStringExtra(EXTRA_STATION)
        val stationIsArrival = intent.getBooleanExtra(EXTRA_STATION_ARRIVAL, false)
        if (trainNumber == -1) { finish(); return }

        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    CpTripDetailScreen(this@CpTripDetailActivity, trainNumber, runDate, fromCode, toCode, stationCode, stationIsArrival)
                }
            }
        }
    }
}

private val TRIP_HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpTripDetailScreen(
    activity: ComponentActivity,
    trainNumber: Int,
    runDate: String?,
    fromCode: String? = null,
    toCode: String? = null,
    stationCode: String? = null,
    stationIsArrival: Boolean = false
) {
    var trip by remember { mutableStateOf<CpTripDetail?>(null) }
    var tripDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isMaintenanceMode by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(CpTime.now()) }
    var showFollow by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(com.example.metrotime.work.CpFollow.isFollowing(activity, trainNumber)) }

    // Listener removido ao sair do ecrã
    DisposableEffect(Unit) {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance(CpRepository.FIREBASE_URL).getReference("maintenance_mode")
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                isMaintenanceMode = (snapshot.value as? Boolean) ?: false
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    val isAdmin = remember { com.example.metrotime.prefs.Prefs.isAdmin(activity) }
    if (isMaintenanceMode && !isAdmin) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("🔧", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Text("App em manutenção", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Voltaremos assim que possível. A nossa equipa está a trabalhar para melhorar a tua experiência.", color = Color.Gray, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { activity.finish() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))) {
                    Text("Voltar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    // Atualiza a cada 15 s, só enquanto o ecrã está visível
    LaunchedEffect(trainNumber, runDate) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val (loaded, date) = CpRepository.getTripDetail(trainNumber, runDate)
                    trip = loaded
                    tripDate = date
                    errorMsg = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (trip == null) {
                        errorMsg = "Erro: ${e.javaClass.simpleName}"
                    }
                }
                now = CpTime.now()
                following = com.example.metrotime.work.CpFollow.isFollowing(activity, trainNumber)
                isLoading = false
                delay(15_000)
            }
        }
    }

    val t = trip
    val stops = t?.trainStops ?: emptyList()
    val moments = remember(stops, tripDate) { tripDate?.let { CpTripClock.moments(it, stops) } ?: emptyList() }
    val serviceShort = CpService.shortName(t?.effectiveService?.code)
    val destinationName = stops.lastOrNull()?.station?.designation?.let { formatStationName(it) }

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
            actions = {
                if (t != null && stops.isNotEmpty()) {
                    // Ver no mapa ao vivo
                    IconButton(onClick = {
                        activity.startActivity(
                            Intent(activity, LiveMapActivity::class.java).putExtra(LiveMapActivity.EXTRA_FOCUS_CP_TRAIN, trainNumber)
                        )
                    }) { Icon(MapIcon, contentDescription = "Ver no mapa", tint = Color.White) }
                    // Partilhar
                    IconButton(onClick = {
                        val text = shareText(trainNumber, serviceShort, stops, moments, t, fromCode, toCode)
                        activity.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Partilhar viagem"
                        ))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Partilhar", tint = Color.White) }
                    // Seguir
                    IconButton(onClick = { showFollow = true }) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = if (following) "A seguir" else "Seguir",
                            tint = if (following) Color(0xFF4ADE80) else Color.White
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CpIntercidades)
            }
        } else if (errorMsg != null && t == null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg.orEmpty(), color = Color.Red)
            }
        } else if (t != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (errorMsg != null) {
                    Text(
                        text = errorMsg.orEmpty(),
                        color = Color.Red,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 12.sp
                    )
                }

                val lastStationCode = t.lastStationCode
                val inferredStatus = inferTripStatus(t, moments, now)

                // Find the index of the current/last station
                val currentStopIndex = stops.indexOfLast { stop ->
                    stop.station?.code == lastStationCode
                }

                val (trainPosition, isApiLagging) = calculateTrainPosition(stops, currentStopIndex, inferredStatus, moments, now)

                // Paragens já passadas: pela posição no relógio quando o GPS da CP está atrasado
                val timeBasedIdx = if (trainPosition >= 0f) trainPosition.toInt() else -1
                val effectiveCurrentIdx = when {
                    timeBasedIdx >= 0 && (currentStopIndex < 0 || timeBasedIdx > currentStopIndex) -> timeBasedIdx
                    currentStopIndex >= 0 -> currentStopIndex
                    else -> timeBasedIdx
                }
                val fromIdx = fromCode?.let { c -> stops.indexOfFirst { it.station?.code == c } } ?: -1
                val toIdx = toCode?.let { c -> stops.indexOfLast { it.station?.code == c } } ?: -1
                val stationIdx = stationCode?.let { c ->
                    if (stationIsArrival) stops.indexOfLast { it.station?.code == c } else stops.indexOfFirst { it.station?.code == c }
                } ?: -1

                // Começa com a posição do comboio (ou a tua estação) à vista
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                var scrolled by remember { mutableStateOf(false) }
                LaunchedEffect(stops.size) {
                    if (!scrolled && stops.isNotEmpty()) {
                        scrolled = true
                        val target = when {
                            effectiveCurrentIdx >= 0 -> effectiveCurrentIdx
                            fromIdx >= 0 -> fromIdx
                            stationIdx >= 0 -> stationIdx
                            else -> 0
                        }
                        // O cabeçalho é o primeiro item; deixa duas paragens antes à vista
                        if (target > 2) listState.animateScrollToItem(target - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Header card
                    item(key = "header") {
                        TripHeaderCard(t, trainNumber, inferredStatus)
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (isApiLagging) {
                        item(key = "api_lag_warning") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF4A3500))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📡", fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            "A aguardar GPS da CP",
                                            color = Color(0xFFF59E0B),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            "Localização estimada pelo tempo previsto. O radar da CP tem atraso e ainda não atualizou a última estação.",
                                            color = Color(0xFFD4A44A),
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    itemsIndexed(stops, key = { idx, stop -> "${idx}_${stop.station?.code}" }) { index, stop ->
                        val isPast = index < effectiveCurrentIdx
                        val isCurrent = index == effectiveCurrentIdx
                        val isFirst = index == 0
                        val isLast = index == stops.lastIndex
                        val role = when (index) {
                            fromIdx -> moments.getOrNull(index)?.departureOrArrival?.let { "Entras aqui · parte às ${it.format(TRIP_HM)}" } ?: "Entras aqui"
                            toIdx -> moments.getOrNull(index)?.arrivalOrDeparture?.let { "Sais aqui · chega às ${it.format(TRIP_HM)}" } ?: "Sais aqui"
                            // Vieste do quadro desta estação: diz só quando o comboio lá passa
                            stationIdx -> if (stationIsArrival) {
                                moments.getOrNull(index)?.arrivalOrDeparture?.let { "Chega aqui às ${it.format(TRIP_HM)}" }
                            } else {
                                moments.getOrNull(index)?.departureOrArrival?.let { "Parte daqui às ${it.format(TRIP_HM)}" }
                            }
                            else -> null
                        }

                        TimelineStopRow(
                            stop = stop,
                            isPast = isPast,
                            isCurrent = isCurrent,
                            isFirst = isFirst,
                            isLast = isLast,
                            serviceColor = getServiceColor(t.effectiveService?.code),
                            index = index,
                            trainPosition = trainPosition,
                            role = role,
                            roleColor = if (index == fromIdx || index == toIdx) Color(0xFF4ADE80) else Color(0xFF60A5FA)
                        )
                    }
                }

                if (showFollow) {
                    CpFollowDialog(
                        trainNumber = trainNumber,
                        runDate = (tripDate ?: CpTime.today()).toString(),
                        service = serviceShort,
                        destination = destinationName,
                        stops = stops,
                        fromIndex = when {
                            fromIdx >= 0 -> fromIdx + 1
                            effectiveCurrentIdx >= 0 -> effectiveCurrentIdx + 1
                            else -> 1
                        },
                        boardCode = fromCode,
                        preselectCode = toCode ?: stationCode?.takeIf { stationIsArrival },
                        onDismiss = {
                            showFollow = false
                            following = com.example.metrotime.work.CpFollow.isFollowing(activity, trainNumber)
                        }
                    )
                }
            }
        }
    }
}

/** Texto para partilhar: "Vou no Intercidades 520 (Lisboa → Porto). Chego a Coimbra-B às 14:35." */
private fun shareText(
    trainNumber: Int,
    service: String,
    stops: List<CpTrainStop>,
    moments: List<CpTripClock.StopMoment>,
    trip: CpTripDetail,
    fromCode: String?,
    toCode: String?
): String {
    val origin = formatStationName(stops.firstOrNull()?.station?.designation ?: "")
    val destination = formatStationName(stops.lastOrNull()?.station?.designation ?: "")
    val toIdx = toCode?.let { c -> stops.indexOfLast { it.station?.code == c } }?.takeIf { it >= 0 } ?: stops.lastIndex
    val fromIdx = fromCode?.let { c -> stops.indexOfFirst { it.station?.code == c } }?.takeIf { it >= 0 }
    val arrival = moments.getOrNull(toIdx)?.arrivalOrDeparture?.format(TRIP_HM)
    val delay = (stops.getOrNull(toIdx)?.delay ?: trip.delay)?.takeIf { it > 0 }
    return buildString {
        append("Vou no ${CpService.longName(service)} $trainNumber ($origin → $destination).")
        fromIdx?.let { i ->
            moments.getOrNull(i)?.departureOrArrival?.let {
                append(" Parto de ${formatStationName(stops[i].station?.designation ?: "")} às ${it.format(TRIP_HM)}.")
            }
        }
        if (arrival != null) append(" Chego a ${formatStationName(stops[toIdx].station?.designation ?: "")} às $arrival")
        append(if (delay != null) " (+$delay min de atraso)." else ".")
    }
}

@Composable
fun TripHeaderCard(trip: CpTripDetail, knownTrainNumber: Int, inferredStatus: String = trip.status ?: VehicleStatus.NOT_STARTED) {
    val serviceColor = getServiceColor(trip.effectiveService?.code)
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
                            text = trip.effectiveService?.designation ?: "?",
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
                val origin = formatStationName(stops.firstOrNull()?.station?.designation ?: "?")
                val destination = formatStationName(stops.lastOrNull()?.station?.designation ?: "?")
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

                    val isNotStarted = inferredStatus == VehicleStatus.NOT_STARTED
                    val (chipText, chipColor) = when {
                        isNotStarted && delayMin <= 0 -> "Agendado" to Color.Gray
                        delayMin <= 0 -> "Na hora" to Color(0xFF4CAF50)
                        else -> "+${delayMin} min atraso" to delayColor
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(chipColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = chipText,
                            color = chipColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                // Status
                val statusText = when (inferredStatus) {
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
    trainPosition: Float,
    role: String? = null,
    roleColor: Color = Color(0xFF4ADE80)
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
            // A tua estação (de onde vieste ou onde vais sair)
            if (role != null) {
                Text(role, color = roleColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            // Station name
            Text(
                text = formatStationName(stop.station?.designation ?: "?"),
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
