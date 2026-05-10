package com.example.metrotime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.R
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.data.history.TripStats
import com.example.metrotime.ui.theme.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource

class StatsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FRIEND_UID = "friend_uid"
        const val EXTRA_FRIEND_NAME = "friend_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val friendUid = intent.getStringExtra(EXTRA_FRIEND_UID)
        val friendName = intent.getStringExtra(EXTRA_FRIEND_NAME)
        
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    StatsScreen(this@StatsActivity, friendUid, friendName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(activity: ComponentActivity, friendUid: String?, friendName: String?) {
    var stats by remember { mutableStateOf<TripStats?>(if (friendUid == null) TripHistoryManager.computeStats(activity) else null) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    LaunchedEffect(friendUid) {
        if (friendUid != null) {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
            db.getReference("user_stats").child(friendUid).get().addOnSuccessListener { snap ->
                if (snap.exists()) {
                    try {
                        val topStationsList = mutableListOf<Triple<String, String, Int>>()
                        val stationsSnap = snap.child("topStations")
                        for (s in stationsSnap.children) {
                            val name = s.child("name").getValue(String::class.java) ?: ""
                            val type = s.child("type").getValue(String::class.java) ?: "metro"
                            val count = s.child("count").getValue(Int::class.java) ?: 0
                            topStationsList.add(Triple(name, type, count))
                        }
                        
                        val topLinesList = mutableListOf<Pair<String, Int>>()
                        val linesSnap = snap.child("topLines")
                        for (s in linesSnap.children) {
                            val name = s.child("name").getValue(String::class.java) ?: ""
                            val count = s.child("count").getValue(Int::class.java) ?: 0
                            topLinesList.add(Pair(name, count))
                        }

                        stats = TripStats(
                            totalVisits = snap.child("totalVisits").getValue(Int::class.java) ?: 0,
                            totalDistanceKm = snap.child("totalDistanceKm").getValue(Double::class.java) ?: 0.0,
                            timeSavedMinutes = snap.child("timeSavedMinutes").getValue(Int::class.java) ?: 0,
                            currentStreak = snap.child("currentStreak").getValue(Int::class.java) ?: 0,
                            longestStreak = snap.child("longestStreak").getValue(Int::class.java) ?: 0,
                            topStations = topStationsList,
                            topLines = topLinesList,
                            monthlyVisits = snap.child("monthlyVisits").getValue(Int::class.java) ?: 0,
                            previousMonthVisits = snap.child("previousMonthVisits").getValue(Int::class.java) ?: 0
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        stats = TripHistoryManager.computeStats(activity) // Fallback if parsing fails (should be rare)
                    }
                } else {
                    // Friend has no stats yet, mock empty
                    stats = TripStats(0, 0.0, 0, 0, 0, emptyList(), emptyList(), 0, 0)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (friendName != null) "Estatísticas de $friendName" else "Estatísticas", color = Color.White, fontWeight = FontWeight.Bold) },
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

        if (stats == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3B82F6))
            }
        } else {
            val nonNullStats = stats!!
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Streak Card ──
            item(key = "streak") {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -40 }
                ) {
                    StreakCard(nonNullStats)
                }
            }

            // ── Quick Stats Row ──
            item(key = "quick_stats") {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(600, delayMillis = 150)) + slideInVertically(tween(600, delayMillis = 150)) { -40 }
                ) {
                    QuickStatsRow(nonNullStats)
                }
            }

            // ── Top Linhas ──
            item(key = "top_lines_header") {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 300))
                ) {
                    Text(
                        "🏆 Top Linhas",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            item(key = "top_lines") {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(600, delayMillis = 350)) + slideInVertically(tween(600, delayMillis = 350)) { 40 }
                ) {
                    TopLinesCard(nonNullStats)
                }
            }

            // ── Top Estações ──
            item(key = "top_stations_header") {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 450))
                ) {
                    Text(
                        "📍 Top Estações",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            if (nonNullStats.topStations.isEmpty()) {
                item(key = "no_stations") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(painter = painterResource(id = R.drawable.metro_logo), contentDescription = null, modifier = Modifier.size(32.dp))
                                    Image(painter = painterResource(id = R.drawable.cp_logo), contentDescription = null, modifier = Modifier.size(32.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Ainda sem viagens registadas",
                                    color = Color.Gray,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "As tuas estações vão aparecer aqui automaticamente!",
                                    color = Color.Gray.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(nonNullStats.topStations, key = { i, pair -> "station_$i" }) { index, (name, type, count) ->
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400, delayMillis = 500 + index * 80)) +
                                slideInVertically(tween(400, delayMillis = 500 + index * 80)) { 30 }
                    ) {
                        TopStationItem(index + 1, name, type, count, nonNullStats.totalVisits)
                    }
                }
            }

            // ── Monthly Comparison ──
            item(key = "monthly") {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 700))
                ) {
                    MonthlyComparisonCard(nonNullStats)
                }
            }

                // Bottom spacer
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
fun StreakCard(stats: TripStats) {
    val infiniteTransition = rememberInfiniteTransition(label = "fireGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF6B35).copy(alpha = 0.9f),
                            Color(0xFFFF4444).copy(alpha = 0.85f),
                            Color(0xFFFF1744).copy(alpha = 0.8f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (stats.currentStreak > 0) "🔥" else "❄️",
                    fontSize = 48.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (stats.currentStreak > 0) "${stats.currentStreak} dias de streak!"
                           else "Começa o teu streak!",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                if (stats.longestStreak > stats.currentStreak && stats.longestStreak > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Recorde: ${stats.longestStreak} dias",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp
                    )
                }

                if (stats.currentStreak > 0) {
                    Spacer(Modifier.height(16.dp))
                    // Streak dots
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val dotsToShow = minOf(stats.currentStreak, 7) // show up to 7 dots
                        repeat(dotsToShow) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = glowAlpha))
                            )
                            if (it < dotsToShow - 1) Spacer(Modifier.width(6.dp))
                        }
                        if (stats.currentStreak > 7) {
                            Spacer(Modifier.width(6.dp))
                            Text("+${stats.currentStreak - 7}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickStatsRow(stats: TripStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickStatCard(
            emoji = "⏱️",
            value = formatTimeSaved(stats.timeSavedMinutes),
            label = "poupadas",
            gradient = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
            modifier = Modifier.weight(1f)
        )
        QuickStatCard(
            emoji = "🚇",
            value = "${stats.totalVisits}",
            label = "visitas",
            gradient = listOf(Color(0xFF06D6A0), Color(0xFF118AB2)),
            modifier = Modifier.weight(1f)
        )
        QuickStatCard(
            emoji = "📏",
            value = formatDistanceKm(stats.totalDistanceKm),
            label = "km total",
            gradient = listOf(Color(0xFFFC5C7D), Color(0xFF6A82FB)),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun QuickStatCard(
    emoji: String,
    value: String,
    label: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier
) {
    // Animated counter effect
    val animatedProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "statAnim"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(gradient),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(vertical = 20.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(emoji, fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = label,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun TopLinesCard(stats: TripStats) {
    val totalLineVisits = stats.topLines.sumOf { it.second }.coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (stats.topLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Sem dados de linhas ainda", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                stats.topLines.forEach { (lineName, count) ->
                    val percentage = (count.toFloat() / totalLineVisits)
                    val lineColor = getLineColor(lineName)

                    val animatedWidth by animateFloatAsState(
                        targetValue = percentage,
                        animationSpec = tween(900, easing = FastOutSlowInEasing),
                        label = "lineBar"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Line name
                        Text(
                            text = lineName,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(90.dp)
                        )

                        // Progress bar
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF3A3A3A))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedWidth)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(lineColor, lineColor.copy(alpha = 0.7f))
                                        )
                                    )
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        // Percentage
                        Text(
                            text = "${(percentage * 100).toInt()}%",
                            color = lineColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopStationItem(rank: Int, name: String, type: String, count: Int, totalVisits: Int) {
    val percentage = if (totalVisits > 0) count.toFloat() / totalVisits else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank badge
            val rankColor = when (rank) {
                1 -> Color(0xFFFFD700) // Gold
                2 -> Color(0xFFC0C0C0) // Silver
                3 -> Color(0xFFCD7F32) // Bronze
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(rankColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    color = rankColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            // Logo
            val isCp = type.equals("cp", ignoreCase = true)
            Image(
                painter = painterResource(id = if (isCp) R.drawable.cp_logo else R.drawable.metro_logo),
                contentDescription = if (isCp) "CP" else "Metro",
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                // Mini progress bar
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF3A3A3A))
                ) {
                    val animatedWidth by animateFloatAsState(
                        targetValue = percentage,
                        animationSpec = tween(700),
                        label = "stationBar"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedWidth)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF3B82F6))
                    )
                }
            }

            Text(
                text = "${count}x",
                color = Color(0xFF3B82F6),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun MonthlyComparisonCard(stats: TripStats) {
    val diff = stats.monthlyVisits - stats.previousMonthVisits
    val percentChange = if (stats.previousMonthVisits > 0)
        ((diff.toFloat() / stats.previousMonthVisits) * 100).toInt()
    else if (stats.monthlyVisits > 0) 100 else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "📅 Este mês",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${stats.monthlyVisits} visitas",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                if (percentChange != 0) {
                    val isPositive = percentChange > 0
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isPositive) Color(0xFF10B981).copy(alpha = 0.15f)
                                else Color(0xFFEF4444).copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${if (isPositive) "📈 +" else "📉 "}${percentChange}%",
                            color = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            if (stats.previousMonthVisits > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "vs mês anterior: ${stats.previousMonthVisits} visitas",
                    color = Color.Gray.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────

private fun getLineColor(lineName: String): Color = when {
    lineName.contains("Azul", ignoreCase = true) -> LineAzul
    lineName.contains("Amarela", ignoreCase = true) -> LineAmarela
    lineName.contains("Verde", ignoreCase = true) -> LineVerde
    lineName.contains("Vermelha", ignoreCase = true) -> LineVermelha
    lineName.contains("Alfa", ignoreCase = true) -> CpAlfaPendular
    lineName.contains("Intercidades", ignoreCase = true) -> CpIntercidades
    lineName.contains("Inter", ignoreCase = true) -> CpInterRegional
    lineName.contains("Regional", ignoreCase = true) -> CpRegional
    lineName.contains("Urbano", ignoreCase = true) -> CpUrbano
    else -> Color(0xFF3B82F6)
}

private fun formatTimeSaved(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    val h = minutes / 60
    val m = minutes % 60
    return if (m > 0) "${h}h${m}m" else "${h}h"
}

private fun formatDistanceKm(km: Double): String {
    return if (km < 1) "${(km * 1000).toInt()}m"
    else if (km < 100) String.format("%.1f", km)
    else "${km.toInt()}"
}
