package com.example.metrotime.ui

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.example.metrotime.R
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FavoritesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    FavoritesScreen(this@FavoritesActivity)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(activity: ComponentActivity) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Metro", "Comboio")

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Favoritos", color = Color.White, fontWeight = FontWeight.Bold) },
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

        // Custom tab row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBackground)
                .padding(4.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
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
                        .clickable { selectedTab = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> MetroFavoritesTab(activity)
            1 -> CpFavoritesTab(activity)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroFavoritesTab(activity: ComponentActivity) {
    var metroFavIds by remember { mutableStateOf(Prefs.getMetroFavorites(activity).toList()) }
    var stationNames by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }

    // Load station names for favorites
    LaunchedEffect(metroFavIds) {
        val allStations = withContext(Dispatchers.IO) {
            StationsRepo.loadStations(activity)
        }
        stationNames = allStations.associate { it.id to Pair(it.name, it.line) }
    }

    if (metroFavIds.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.metro_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Sem estações de metro favoritas",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Abre uma estação e carrega na ⭐ para a adicionar aqui!",
                    color = Color.Gray.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(metroFavIds, key = { it }) { stationId ->
                val info = stationNames[stationId]
                val name = info?.first ?: stationId
                val line = info?.second ?: ""

                val lineColor = when {
                    line.contains("Azul", ignoreCase = true) -> LineAzul
                    line.contains("Amarela", ignoreCase = true) -> LineAmarela
                    line.contains("Verde", ignoreCase = true) -> LineVerde
                    line.contains("Vermelha", ignoreCase = true) -> LineVermelha
                    else -> Color.Gray
                }

                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            Prefs.toggleMetroFavorite(activity, stationId)
                            metroFavIds = Prefs.getMetroFavorites(activity).toList()
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFEF4444)),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                "Remover",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 24.dp)
                            )
                        }
                    },
                    content = {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activity.startActivity(
                                        StationArrivalsActivity.newIntent(activity, stationId)
                                    )
                                },
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                            ) {
                                // Line color strip
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                                        .background(lineColor)
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = TrainIcon,
                                        contentDescription = null,
                                        tint = lineColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            name,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (line.isNotEmpty()) {
                                            Text(
                                                "Linha $line",
                                                color = lineColor,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                    Text(
                                        "›",
                                        color = Color.Gray,
                                        fontSize = 24.sp
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpFavoritesTab(activity: ComponentActivity) {
    var cpFavs by remember { mutableStateOf(Prefs.getCpFavorites(activity).toList()) }

    if (cpFavs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.cp_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Sem estações CP favoritas",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Abre uma estação de comboio e carrega na ⭐ para a adicionar aqui!",
                    color = Color.Gray.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(cpFavs, key = { it }) { favEntry ->
                // Format is "stationCode|stationName"
                val parts = favEntry.split("|", limit = 2)
                val code = parts.getOrElse(0) { favEntry }
                val name = parts.getOrElse(1) { code }

                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            Prefs.toggleCpFavorite(activity, favEntry)
                            cpFavs = Prefs.getCpFavorites(activity).toList()
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFEF4444)),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                "Remover",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 24.dp)
                            )
                        }
                    },
                    content = {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activity.startActivity(
                                        CpStationActivity.newIntent(activity, code, name)
                                    )
                                },
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                            ) {
                                // CP brand color strip
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                                        .background(CpIntercidades)
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(CpIntercidades.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.cp_logo),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            name,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Estação CP",
                                            color = CpIntercidades,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        "›",
                                        color = Color.Gray,
                                        fontSize = 24.sp
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
