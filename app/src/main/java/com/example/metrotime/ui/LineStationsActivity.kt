package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LineStationsActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_LINE_NAME = "line_name"
        fun newIntent(context: Context, lineName: String): Intent {
            return Intent(context, LineStationsActivity::class.java).apply {
                putExtra(EXTRA_LINE_NAME, lineName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val lineName = intent.getStringExtra(EXTRA_LINE_NAME) ?: run {
            finish()
            return
        }

        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    LineStationsScreen(this@LineStationsActivity, lineName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineStationsScreen(activity: ComponentActivity, lineName: String) {
    var stations by remember { mutableStateOf<List<Station>>(emptyList()) }

    LaunchedEffect(lineName) {
        val allStations = withContext(Dispatchers.IO) {
            StationsRepo.loadStations(activity)
        }
        stations = allStations.filter { station ->
            station.line.contains(lineName, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Linha $lineName", color = Color.White, fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(stations) { station ->
                StationCard(station = station, currentLineName = lineName) {
                    activity.startActivity(StationArrivalsActivity.newIntent(activity, station.id))
                }
            }
        }
    }
}

@Composable
fun StationCard(station: Station, currentLineName: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = station.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            // Display intersection dots
            val intersectionColors = getIntersectionColors(station.line, currentLineName)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                intersectionColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Icon(
                imageVector = Icons.Outlined.Train,
                contentDescription = "Train",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun getIntersectionColors(stationLineProperty: String, currentLineName: String): List<Color> {
    val linesInStation = stationLineProperty.split(" ").map { it.trim() }
    val intersectionLines = linesInStation.filter { !it.equals(currentLineName, ignoreCase = true) }
    
    val colors = mutableListOf<Color>()
    for (line in intersectionLines) {
        when {
            line.contains("Azul", ignoreCase = true) -> colors.add(LineAzul)
            line.contains("Amarela", ignoreCase = true) -> colors.add(LineAmarela)
            line.contains("Verde", ignoreCase = true) -> colors.add(LineVerde)
            line.contains("Vermelha", ignoreCase = true) -> colors.add(LineVermelha)
        }
    }
    return colors
}
