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
import com.example.metrotime.data.cp.*
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class CpAviso(val titulo: String = "", val link: String = "")

class CpStationActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_STATION_CODE = "station_code"
        private const val EXTRA_STATION_NAME = "station_name"

        fun newIntent(context: Context, stationCode: String, stationName: String): Intent {
            return Intent(context, CpStationActivity::class.java).apply {
                putExtra(EXTRA_STATION_CODE, stationCode)
                putExtra(EXTRA_STATION_NAME, stationName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stationCode = intent.getStringExtra(EXTRA_STATION_CODE) ?: run { finish(); return }
        val stationName = intent.getStringExtra(EXTRA_STATION_NAME) ?: "Estação"

        setContent {
            // Ensure repository is initialized
            CpRouteRepository.initialize(applicationContext)
            
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    CpStationScreen(this@CpStationActivity, stationCode, stationName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpStationScreen(activity: ComponentActivity, stationCode: String, stationName: String) {
    var vehicles by remember { mutableStateOf<List<CpVehicle>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var filterMode by remember { mutableStateOf("Todos") }

    // Auto-refresh vehicles every 15 seconds
    LaunchedEffect(stationCode) {
        CpGtfsRepository.awaitReady()
        while (isActive) {
            try {
                val response = withContext(Dispatchers.IO) {
                    CpApi.service.getVehicles()
                }
                val activeVehicles = response.vehicles.filter { 
                    it.status != VehicleStatus.COMPLETED && it.status != VehicleStatus.CANCELLED 
                }
                
                CpRouteRepository.queueUnknownVehicles(activeVehicles)

                // Filter vehicles using Route Cache + Fallback
                vehicles = activeVehicles.filter { v ->
                    val status = v.status
                    val schedule = CpGtfsRepository.getTripSchedule(v.trainNumber, v.gtfs?.tripId)
                    val routeFromGtfs = schedule?.map { it.id.replace("_", "-") }
                    val routeFromCache = CpRouteRepository.getRoute(v.trainNumber)
                    
                    val route = routeFromGtfs ?: routeFromCache

                    if (!route.isNullOrEmpty()) {
                        val targetIndex = route.indexOf(stationCode)
                        if (targetIndex != -1) {
                            val currentIndex = route.indexOf(v.lastStation)
                            targetIndex >= currentIndex
                        } else {
                            false
                        }
                    } else {
                        if (v.destination?.code == stationCode) {
                            true
                        } else if (v.origin?.code == stationCode) {
                            status == VehicleStatus.NOT_STARTED ||
                            status == VehicleStatus.AT_ORIGIN ||
                            status == VehicleStatus.AT_STATION
                        } else if (v.lastStation == stationCode) {
                            status == VehicleStatus.AT_STATION
                        } else {
                            false
                        }
                    }
                }
                errorMsg = null
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = "Erro ao carregar comboios: ${e.javaClass.simpleName}"
            }
            isLoading = false
            delay(60_000)
        }
    }

    // Apply service type filter
    val filteredVehicles = remember(vehicles, filterMode) {
        if (filterMode == "Todos") vehicles
        else vehicles.filter { getServiceShortName(it.service?.code) == filterMode }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stationName, color = Color.White, fontWeight = FontWeight.Bold) },
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
                var isFav by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.getCpFavorites(activity).contains("$stationCode|$stationName")) }
                IconButton(onClick = {
                    com.example.metrotime.prefs.Prefs.toggleCpFavorite(activity, "$stationCode|$stationName")
                    isFav = !isFav
                }) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorito",
                        tint = if (isFav) Color(0xFFFFCA28) else Color.White.copy(alpha = 0.4f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        // Service type filter chips
        CpServiceFilterRow(currentFilter = filterMode, onFilterSelected = { filterMode = it })

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CpIntercidades)
            }
        } else if (errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg.orEmpty(), color = Color.Red, fontSize = 14.sp)
            }
        } else if (filteredVehicles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Sem comboios nesta estação de momento.", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredVehicles, key = { it.trainNumber }) { vehicle ->
                    CpVehicleCard(vehicle = vehicle) {
                        activity.startActivity(
                            CpTripDetailActivity.newIntent(activity, vehicle.trainNumber)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CpServiceFilterRow(currentFilter: String, onFilterSelected: (String) -> Unit) {
    val filters = listOf("Todos", "AP", "IC", "IR", "R", "U")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            val isSelected = currentFilter == filter
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) getServiceColor(filter) else CardBackground,
                animationSpec = tween(200),
                label = "filterBg"
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .clickable { onFilterSelected(filter) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = filter,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun CpVehicleCard(vehicle: CpVehicle, onClick: () -> Unit) {
    val serviceColor = getServiceColor(vehicle.service?.code)
    // Vehicles API: delay is in SECONDS
    val delayMin = (vehicle.delay ?: 0) / 60
    val delayColor = when {
        delayMin <= 0 -> Color(0xFF4CAF50) // green — on time or ahead
        delayMin in 1..5 -> Color(0xFFFFC107) // yellow — slight delay
        else -> Color(0xFFFF5252) // red — significant delay
    }
    val statusText = when (vehicle.status) {
        VehicleStatus.IN_TRANSIT -> "Em trânsito"
        VehicleStatus.AT_STATION -> "Na estação"
        VehicleStatus.AT_ORIGIN -> "Na origem"
        VehicleStatus.NEAR_NEXT -> "Próx. estação"
        VehicleStatus.NOT_STARTED -> "Não iniciado"
        VehicleStatus.COMPLETED -> "Concluído"
        VehicleStatus.CANCELLED -> "Cancelado"
        else -> vehicle.status ?: ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Service color strip
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(serviceColor)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Row 1: Service badge + train number + delay
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Service badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(serviceColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = getServiceShortName(vehicle.service?.code),
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "${vehicle.trainNumber}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Delay chip
                    if (vehicle.status != VehicleStatus.NOT_STARTED && vehicle.status != VehicleStatus.CANCELLED) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(delayColor.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (delayMin <= 0) "Sem atraso" else "+${delayMin} min",
                                color = delayColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Origin → Destination
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = vehicle.origin?.designation ?: "?",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = " → ",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Text(
                        text = vehicle.destination?.designation ?: "?",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 3: Status + service name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusText,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = vehicle.service?.designation ?: "",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// Utility to map CP service codes to short display names
fun getServiceShortName(serviceCode: String?): String = when (serviceCode) {
    "1" -> "AP"
    "2" -> "IC"
    "3" -> "IR"
    "4" -> "R"
    "5" -> "U"
    "40" -> "R"
    "45" -> "U"
    "55" -> "U"
    else -> serviceCode ?: "?"
}

// Utility to map CP service codes / filter names to colors
fun getServiceColor(codeOrFilter: String?): Color = when (codeOrFilter) {
    "1", "AP" -> CpAlfaPendular
    "2", "IC" -> CpIntercidades
    "3", "IR" -> CpInterRegional
    "4", "40", "R" -> CpRegional
    "5", "45", "55", "U" -> CpUrbano
    "Todos" -> Color(0xFF3B82F6)
    else -> Color.Gray
}
