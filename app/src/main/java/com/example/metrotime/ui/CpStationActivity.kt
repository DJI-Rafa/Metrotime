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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.example.metrotime.data.cp.*
import com.example.metrotime.ui.theme.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
        
        CpApi.prefetchConfig()

        setContent {
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
    var timedStops by remember { mutableStateOf<List<CpTimedStop>>(emptyList()) }
    var stationMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    var stationInfo by remember { mutableStateOf<CpStationInfo?>(null) }
    var notices by remember { mutableStateOf<List<CpNotice>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var filterMode by remember { mutableStateOf("Todos") }
    var directionFilter by remember { mutableStateOf<String?>(null) }
    var tabMode by remember { mutableStateOf("Partidas") } // "Partidas" or "Chegadas"
    var isMaintenanceMode by remember { mutableStateOf(false) }
    var suppressedTrains by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var now by remember { mutableStateOf(CpTime.now()) }
    var reloadRequest by remember { mutableIntStateOf(0) }
    var lastUpdated by remember { mutableLongStateOf(0L) }
    var refreshFailed by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(CpTime.now()) }
    var clockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pullState = rememberPullToRefreshState()

    // Listeners do Firebase — removidos quando se sai do ecrã (antes ficavam ativos para sempre)
    DisposableEffect(Unit) {
        val db = FirebaseDatabase.getInstance(CpRepository.FIREBASE_URL)
        val maintenanceRef = db.getReference("maintenance_mode")
        val suppressionsRef = db.getReference("cp_suppressions/trains")
        val maintenanceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isMaintenanceMode = (snapshot.value as? Boolean) ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        val suppressionsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val trains = mutableSetOf<Int>()
                for (child in snapshot.children) {
                    val tNum = (child.child("trainNumber").value as? Number)?.toInt()
                    if (tNum != null) trains.add(tNum)
                }
                suppressedTrains = trains
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        maintenanceRef.addValueEventListener(maintenanceListener)
        suppressionsRef.addValueEventListener(suppressionsListener)
        onDispose {
            maintenanceRef.removeEventListener(maintenanceListener)
            suppressionsRef.removeEventListener(suppressionsListener)
        }
    }

    val isAdmin = remember { com.example.metrotime.prefs.Prefs.isAdmin(activity) }
    if (isMaintenanceMode && !isAdmin) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("🔧", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Text("App em manutenção", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Voltaremos assim que possível.", color = Color.Gray, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { activity.finish() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))) {
                    Text("Voltar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    // Informação da estação (linha, acessibilidade, serviços) e avisos da CP que lhe dizem respeito
    LaunchedEffect(stationCode) {
        stationInfo = CpRepository.getStationInfo(stationCode)
        val all = runCatching { CpRepository.getNotices() }.getOrDefault(emptyList())
        notices = all.filter { it.concerns(stationName, stationInfo?.trainLine) }
    }

    // Relógio da contagem decrescente ("em 5 min")
    LaunchedEffect(Unit) {
        while (isActive) {
            clock = CpTime.now()
            clockMillis = System.currentTimeMillis()
            delay(15_000)
        }
    }

    // Puxar para atualizar: reinicia o ciclo de carregamento
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) reloadRequest++
    }

    // Horários: atualiza a cada 60 s, mas só enquanto o ecrã está visível
    LaunchedEffect(stationCode, reloadRequest) {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = CpTime.now()
                if (timedStops.isEmpty()) isLoading = true
                try {
                    val result = CpRepository.getTimetableWindow(stationCode, now)
                    timedStops = result.stops
                    stationMessages = result.messages
                    errorMsg = null
                    refreshFailed = false
                    lastUpdated = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    refreshFailed = true
                    if (timedStops.isEmpty()) {
                        errorMsg = "Erro ao carregar horários: ${e.javaClass.simpleName}"
                    }
                }
                isLoading = false
                if (pullState.isRefreshing) pullState.endRefresh()
                delay(60_000)
            }
        }
    }

    // Paragens do separador atual, na janela [agora − 5 min, agora + 5 h], com datas reais
    val today = now.toLocalDate()
    val tabStops = remember(timedStops, tabMode, now) {
        timedStops.mapNotNull { timed ->
            val stop = timed.stop
            // Partidas: só comboios que partem daqui; Chegadas: só os que chegam aqui
            val timeStr = if (tabMode == "Partidas") stop.departureTime else stop.arrivalTime
            val at = timed.dateTimeOf(timeStr) ?: return@mapNotNull null
            if (!CpTimetableWindow.isInWindow(at.toLocalDate(), at.toLocalTime(), stop.delay, now)) return@mapNotNull null
            Pair(timed, at)
        }.sortedBy { it.second }
    }
    // Filtros: só os serviços e sentidos que existem nesta estação
    val servicesHere = remember(tabStops) {
        val present = tabStops.map { getServiceShortName(it.first.stop.trainService?.code) }.toSet()
        CpService.ORDER.filter { it in present }
    }
    fun directionOf(stop: CpStationStop): String = formatStationName(
        (if (tabMode == "Partidas") stop.trainDestination?.designation else stop.trainOrigin?.designation) ?: "?"
    )
    val directionsHere = remember(tabStops, tabMode) {
        tabStops.groupingBy { directionOf(it.first.stop) }.eachCount().entries
            .sortedByDescending { it.value }.take(8).map { it.key }
    }
    LaunchedEffect(servicesHere) { if (filterMode != "Todos" && filterMode !in servicesHere) filterMode = "Todos" }
    LaunchedEffect(directionsHere) { if (directionFilter != null && directionFilter !in directionsHere) directionFilter = null }
    val filteredStops = remember(tabStops, filterMode, directionFilter) {
        tabStops.filter { (timed, _) ->
            (filterMode == "Todos" || getServiceShortName(timed.stop.trainService?.code) == filterMode) &&
                (directionFilter == null || directionOf(timed.stop) == directionFilter)
        }
    }

    // A API devolve "Lisboa Santa Apolonia"; no ecrã mostra-se "Lisboa - St. Apolónia"
    val displayName = remember(stationName) { formatStationName(stationName) }
    var menuOpen by remember { mutableStateOf(false) }
    var showServices by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.getCpFavorites(activity).contains("$stationCode|$stationName")) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        displayName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = stationInfo?.let { info ->
                        listOfNotNull(
                            info.trainLine?.takeIf { it.isNotBlank() },
                            if (info.mobilityAccess?.equals("Sim", ignoreCase = true) == true) "♿ Acessível" else null
                        ).joinToString(" · ")
                    }
                    if (!subtitle.isNullOrEmpty()) {
                        Text(subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                // Ações menos usadas ficam no menu, para o cabeçalho não ficar cheio
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(CardBackground)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Planear viagem daqui", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                activity.startActivity(CpJourneyActivity.newIntent(activity, from = "$stationCode|$displayName"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mostrar no widget", color = Color.White) },
                            onClick = {
                                menuOpen = false
                                com.example.metrotime.prefs.Prefs.setCpWidgetStation(activity, "$stationCode|$stationName")
                                val manager = android.appwidget.AppWidgetManager.getInstance(activity)
                                if (com.example.metrotime.work.CpWidgetUpdater.hasWidgets(activity)) {
                                    CpWidgetProvider.refresh(activity)
                                    android.widget.Toast.makeText(activity, "O widget passa a mostrar $displayName.", android.widget.Toast.LENGTH_SHORT).show()
                                } else if (manager.isRequestPinAppWidgetSupported) {
                                    manager.requestPinAppWidget(android.content.ComponentName(activity, CpWidgetProvider::class.java), null, null)
                                } else {
                                    android.widget.Toast.makeText(activity, "Adiciona o widget \"Comboios CP\" no ecrã inicial.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                        if (!stationInfo?.services.isNullOrEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Serviços da estação", color = Color.White) },
                                onClick = { menuOpen = false; showServices = true }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        // ── Partidas / Chegadas ──
        DepartureArrivalToggle(currentTab = tabMode, onTabSelected = { tabMode = it; directionFilter = null })

        // ── Filtros numa só linha: serviço e sentido (só quando há por onde escolher) ──
        val showServiceFilter = servicesHere.size > 1
        val showDirectionFilter = directionsHere.size > 1
        if (showServiceFilter || showDirectionFilter) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showServiceFilter) {
                    (listOf("Todos") + servicesHere).forEach { filter ->
                        CpFilterChip(
                            text = filter,
                            selected = filterMode == filter,
                            selectedColor = getServiceColor(filter),
                            onClick = { filterMode = filter }
                        )
                    }
                }
                if (showServiceFilter && showDirectionFilter) {
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .width(1.dp)
                            .height(18.dp)
                            .background(Color.White.copy(alpha = 0.12f))
                    )
                }
                if (showDirectionFilter) {
                    CpDirectionDropdown(
                        label = if (tabMode == "Partidas") "Para" else "De",
                        directions = directionsHere,
                        current = directionFilter,
                        onSelected = { directionFilter = it }
                    )
                }
            }
        }

        if (isLoading && timedStops.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetrotimeBlue)
            }
        } else {
            // clipToBounds: sem isto o indicador de atualizar aparecia por cima do texto acima da lista
            Box(Modifier.fillMaxSize().clipToBounds().nestedScroll(pullState.nestedScrollConnection)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Um só item fixo no topo (avisos + "Atualizado há…"): o que chega mais tarde cresce
                    // dentro dele em vez de ser inserido por cima da lista, que assim não salta
                    item(key = "header", contentType = "header") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (stationMessages.isNotEmpty() || notices.isNotEmpty()) {
                                CpAlertsCard(activity, stationMessages, notices)
                            }
                            if (lastUpdated > 0) {
                                val seconds = ((maxOf(clockMillis, lastUpdated) - lastUpdated) / 1000).coerceAtLeast(0)
                                val age = if (seconds < 60) "há ${seconds}s" else "há ${seconds / 60} min"
                                Text(
                                    if (refreshFailed) "⚠ Sem ligação — horários de $age" else "Atualizado $age",
                                    color = if (refreshFailed) Color(0xFFFFB74D) else Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }

                    when {
                        errorMsg != null && timedStops.isEmpty() -> item(key = "error") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(errorMsg.orEmpty(), color = Color.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { reloadRequest++ },
                                    colors = ButtonDefaults.buttonColors(containerColor = MetrotimeBlue)
                                ) {
                                    Text("Tentar novamente", color = Color.White)
                                }
                            }
                        }
                        filteredStops.isEmpty() -> item(key = "empty") {
                            Text(
                                "Sem comboios nas próximas 5 horas.",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(24.dp)
                            )
                        }
                        else -> items(
                            filteredStops,
                            key = { (timed, at) -> "${at}_${timed.stop.trainNumber}_${timed.stop.departureTime}_${timed.stop.arrivalTime}" },
                            contentType = { "train" }
                        ) { (timed, at) ->
                            val stop = timed.stop
                            // As supressões do Firebase são do dia de hoje; não marcar comboios de amanhã
                            val isSuppressed = at.toLocalDate() == today && suppressedTrains.contains(stop.trainNumber)
                            // Contagem decrescente pela hora efetiva (prevista + atraso)
                            val effective = at.plusMinutes((stop.delay ?: 0).coerceAtLeast(0).toLong())
                            val minutes = java.time.Duration.between(clock, effective).toMinutes()
                            val countdown = when {
                                isSuppressed || stop.supression != null -> null
                                minutes <= 0 -> "agora"
                                minutes < 60 -> "$minutes min"
                                else -> null
                            }
                            CpTimetableCard(
                                stop = stop,
                                isSuppressed = isSuppressed,
                                isDeparture = tabMode == "Partidas",
                                dayLabel = if (at.toLocalDate().isAfter(today)) "amanhã" else null,
                                countdown = countdown,
                                onClick = {
                                    activity.startActivity(
                                        // Tal como no site da CP: a data do pedido de horários. Esta estação só fica
                                        // marcada no percurso — ver o quadro não quer dizer que entras ou sais aqui
                                        CpTripDetailActivity.newIntent(
                                            activity, stop.trainNumber, timed.queryDate.toString(),
                                            stationCode = stationCode,
                                            stationIsArrival = tabMode == "Chegadas"
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
                PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }

    if (showServices) {
        AlertDialog(
            onDismissRequest = { showServices = false },
            title = { Text("Serviços da estação", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stationInfo?.services.orEmpty().forEach { code ->
                        Text("•  ${CpText.serviceLabel(code)}", color = Color.LightGray, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showServices = false }) { Text("Fechar", color = MetrotimeBlue) }
            },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ──────────────────────────────────────────────
// Avisos (estação + CP), recolhidos por omissão
// ──────────────────────────────────────────────

@Composable
fun CpAlertsCard(activity: ComponentActivity, messages: List<String>, notices: List<CpNotice>) {
    var expanded by remember { mutableStateOf(false) }
    val shownNotices = notices.take(4)
    val count = messages.size + shownNotices.size
    val preview = messages.firstOrNull() ?: shownNotices.firstOrNull()?.title.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF57C00).copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠️", fontSize = 15.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 aviso" else "$count avisos",
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (!expanded && preview.isNotBlank()) {
                    Text(preview, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Recolher" else "Expandir",
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                messages.forEach { msg ->
                    Text(msg, color = Color.White, fontSize = 13.sp)
                }
                shownNotices.forEach { n ->
                    Column(Modifier.fillMaxWidth().clickable {
                        runCatching {
                            val url = n.link.ifBlank { "https://www.cp.pt/pt/avisos" }
                            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    }) {
                        Text(n.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val dates = listOfNotNull(n.start, n.end).distinct().joinToString(" a ") {
                            it.format(java.time.format.DateTimeFormatter.ofPattern("d/M"))
                        }
                        val sub = listOf(n.summary, dates).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Filtros
// ──────────────────────────────────────────────

@Composable
fun CpFilterChip(text: String, selected: Boolean, selectedColor: Color, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) selectedColor else CardBackground,
        animationSpec = tween(200),
        label = "filterBg"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.LightGray,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
    }
}

/** Sentido num menu, em vez de uma segunda fila de chips. */
@Composable
fun CpDirectionDropdown(label: String, directions: List<String>, current: String?, onSelected: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = current != null
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) MetrotimeBlue else CardBackground)
                .clickable { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (current == null) "Todos os sentidos" else "$label $current",
                color = if (selected) Color.White else Color.LightGray,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (selected) Color.White else Color.Gray,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(CardBackground)
        ) {
            (listOf<String?>(null) + directions).forEach { d ->
                DropdownMenuItem(
                    text = {
                        Text(
                            d ?: "Todos os sentidos",
                            color = if (d == current) MetrotimeBlue else Color.White,
                            fontWeight = if (d == current) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = { open = false; onSelected(d) }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Partidas / Chegadas toggle (mesmo estilo dos separadores do resto da app)
// ──────────────────────────────────────────────

@Composable
fun DepartureArrivalToggle(currentTab: String, onTabSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardBackground)
            .padding(3.dp)
    ) {
        listOf("Partidas", "Chegadas").forEach { tab ->
            val isSelected = currentTab == tab
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
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Timetable card
// ──────────────────────────────────────────────

/**
 * Faixa de cor à esquerda de um cartão. Desenhada por trás do conteúdo em vez de uma Box com
 * fillMaxHeight + IntrinsicSize.Min, que obrigava a medir cada cartão duas vezes ao fazer scroll.
 */
fun Modifier.cpStrip(color: Color, width: Dp = 4.dp): Modifier = drawBehind {
    drawRect(color, size = Size(width.toPx(), size.height))
}

/** Etiqueta do serviço (AP, IC, R, U…) na cor da CP. */
@Composable
fun CpServiceBadge(shortName: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(shortName, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
    }
}

/** Hora | destino + comboio | contagem + estado. */
@Composable
fun CpTimetableCard(
    stop: CpStationStop,
    isSuppressed: Boolean,
    isDeparture: Boolean,
    dayLabel: String? = null,
    countdown: String? = null,
    onClick: () -> Unit
) {
    val serviceCode = stop.trainService?.code
    val serviceColor = getServiceColor(serviceCode)
    val shortName = getServiceShortName(serviceCode)
    val delayMin = stop.delay ?: 0
    val isActuallySuppressed = isSuppressed || stop.supression != null
    // null = sem dados em tempo real ("Previsto"); 0 = confirmado a horas
    val delayState = CpDelay.stateFromMinutes(stop.delay, isActuallySuppressed)

    val scheduledTime = if (isDeparture) stop.departureTime else stop.arrivalTime
    val estimatedTime = if (isDeparture) stop.etd else stop.eta
    val scheduledDisplay = scheduledTime?.take(5) ?: "--:--"
    val isDelayed = !isActuallySuppressed && delayMin > 0 && estimatedTime != null

    val delayColor = when {
        isActuallySuppressed -> Color(0xFFFF5252)
        delayMin <= 0 -> Color(0xFF4CAF50)
        delayMin in 1..5 -> Color(0xFFFFC107)
        else -> Color(0xFFFF5252)
    }
    val (statusText, statusColor) = when {
        isActuallySuppressed -> "Suprimido" to Color(0xFFFF5252)
        delayMin > 0 -> "+$delayMin min" to delayColor
        delayState == CpDelayState.SCHEDULED -> "Previsto" to Color.Gray
        else -> "Na hora" to Color(0xFF4CAF50)
    }

    val rawPlace = if (isDeparture) stop.trainDestination?.designation else stop.trainOrigin?.designation
    val placeName = remember(rawPlace) { formatStationName(rawPlace ?: "?") }
    val platform = stop.platform?.takeIf { it.isNotBlank() && it != "0" }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .cpStrip(if (isActuallySuppressed) Color(0xFFFF5252) else serviceColor)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hora (efetiva, com a prevista riscada por baixo quando há atraso)
            Column(modifier = Modifier.widthIn(min = 56.dp)) {
                Text(
                    text = if (isDelayed) estimatedTime.orEmpty().take(5) else scheduledDisplay,
                    color = when {
                        isActuallySuppressed -> Color.Gray
                        isDelayed -> delayColor
                        else -> Color.White
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (isActuallySuppressed) TextDecoration.LineThrough else TextDecoration.None
                )
                if (isDelayed) {
                    Text(
                        text = scheduledDisplay,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Destino (ou origem, nas chegadas) e comboio
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        if (!isDeparture) withStyle(SpanStyle(color = Color.Gray, fontWeight = FontWeight.Normal)) { append("de ") }
                        append(placeName)
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isActuallySuppressed) TextDecoration.LineThrough else TextDecoration.None
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpServiceBadge(shortName, serviceColor)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = listOfNotNull("${stop.trainNumber}", platform?.let { "Linha $it" }).joinToString(" · "),
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Contagem decrescente e estado
            Column(horizontalAlignment = Alignment.End) {
                (countdown ?: dayLabel)?.let { top ->
                    Text(
                        text = top,
                        color = when (top) {
                            "agora" -> Color(0xFF4ADE80)
                            dayLabel -> Color.Gray
                            else -> Color.White
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Utility functions (used by CpVehicleCard in SettingsActivity too)
// ──────────────────────────────────────────────

// Nome curto do serviço (AP, IC, IR, R, U, IN): os códigos estão num só sítio, CpService
fun getServiceShortName(serviceCode: String?): String = CpService.shortName(serviceCode)

// Utility to map CP service codes / filter names to colors
fun getServiceColor(codeOrFilter: String?): Color = when (codeOrFilter) {
    "1", "AP" -> CpAlfaPendular
    "2", "IC" -> CpIntercidades
    "3", "IR" -> CpInterRegional
    "4", "40", "R" -> CpRegional
    "5", "45", "55", "U" -> CpUrbano
    "12", "IN" -> CpIntercidades
    "UF", "FT" -> CpFertagus
    "Todos" -> Color(0xFF3B82F6)
    else -> Color.Gray
}
