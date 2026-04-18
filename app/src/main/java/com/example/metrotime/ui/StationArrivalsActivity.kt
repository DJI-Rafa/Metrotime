package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Train
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
import com.example.metrotime.ui.theme.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationArrivalsScreen(activity: ComponentActivity, stationId: String) {
    val source = remember { RemoteArrivalsSource(activity) }
    var station by remember { mutableStateOf<Station?>(null) }
    var arrivalGroups by remember { mutableStateOf<List<ArrivalGroup>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(stationId) {
        station = withContext(Dispatchers.IO) {
            StationsRepo.loadStations(activity).find { it.id == stationId }
        }
        
        while (isActive) {
            station?.let { currentStation ->
                try {
                    val arrivalsResponse = source.fetchArrivals(currentStation.apiId)
                    arrivalGroups = groupAndFormatArrivals(arrivalsResponse, activity)
                    errorMsg = null
                } catch (e: Exception) {
                    errorMsg = "Erro ao carregar tempos: ${e.javaClass.simpleName}"
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header card
            station?.let { st ->
                item {
                    val nameStr = "Estação: ${st.name}"
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
                                imageVector = Icons.Outlined.Train,
                                contentDescription = "Train",
                                tint = Color.LightGray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = nameStr, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            
            if (errorMsg != null) {
                item {
                    Text(text = errorMsg.orEmpty(), color = Color.Red, modifier = Modifier.padding(16.dp))
                }
            } else if (arrivalGroups.isEmpty() && station != null) {
                item {
                    Text(text = "Sem comboios próximos", color = Color.LightGray, modifier = Modifier.padding(16.dp))
                }
            } else {
                items(arrivalGroups) { group ->
                    ArrivalCard(group, currentTime)
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
                    imageVector = Icons.Outlined.Train,
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
