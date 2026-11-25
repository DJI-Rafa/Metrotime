package com.example.metrotime.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.plan.Place
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Os cartões do "Agora": quais aparecem, por que ordem e as definições deles. Sem dependências Android (testado em app/src/test). */
object HomeNowConfig {

    /** Pela ordem por omissão (a do carrossel antes de se mudar no Personalizar); [tab] = nome no separador. */
    enum class Card(val id: String, val title: String, val tab: String) {
        METRO("metro", "Estado do metro", "Metro"),
        CP_TRAINS("cp_trains", "Comboios CP", "Comboios"),
        BUS_STOPS("bus_stops", "Autocarros", "Autocarros"),
        MY_STATIONS("stations", "As minhas estações", "Estações"),
        HOME("home", "Para casa", "Casa"),
        FAVORITES("favorites", "Favoritas", "Favoritas")
    }

    data class Entry(val card: Card, val visible: Boolean)

    val DEFAULT: List<Entry> = Card.entries.map { Entry(it, true) }

    /** "metro:1,home:0,…" → cartões pela ordem guardada; os que faltarem (novos) vão para o fim, visíveis. */
    fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return DEFAULT
        val saved = raw.split(",").mapNotNull { part ->
            val card = Card.entries.firstOrNull { it.id == part.substringBefore(":").trim() } ?: return@mapNotNull null
            Entry(card, part.substringAfter(":", "1").trim() != "0")
        }.distinctBy { it.card }
        return saved + Card.entries.filter { c -> saved.none { it.card == c } }.map { Entry(it, true) }
    }

    fun encode(entries: List<Entry>): String = entries.joinToString(",") { "${it.card.id}:${if (it.visible) 1 else 0}" }

    /** Leva o elemento da posição [from] para [to] (o resto encosta). Fora dos limites fica igual. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    // ── Cartão "Comboios CP" ────────────────────────────────────────────────────

    /** Serviços pela ordem em que aparecem (e em que se agrupam). */
    val SERVICES = listOf("AP", "IC", "IR", "R", "U", "IN", "FT")

    data class CpCard(
        val services: Set<String> = setOf("AP", "IC", "IR", "U"),
        val byService: Boolean = false,
        val onlyLate: Boolean = false,
        val count: Int = 5
    ) {
        fun encode(): String =
            "${SERVICES.filter { it in services }.joinToString(",")};${if (byService) "service" else "delay"};${if (onlyLate) 1 else 0};$count"

        companion object {
            const val MIN_COUNT = 3
            const val MAX_COUNT = 10

            fun parse(raw: String?): CpCard {
                val p = raw?.split(";")?.takeIf { it.size == 4 } ?: return CpCard()
                return CpCard(
                    services = p[0].split(",").map { it.trim() }.filter { it in SERVICES }.toSet(),
                    byService = p[1] == "service",
                    onlyLate = p[2] == "1",
                    count = p[3].toIntOrNull()?.coerceIn(MIN_COUNT, MAX_COUNT) ?: 5
                )
            }
        }
    }

    /** Um comboio a circular, já com o serviço curto ("IC") e o atraso em minutos. */
    data class TrainRow(
        val service: String,
        val number: Int,
        val runDate: String?,
        val title: String,
        val near: String?,
        val delay: Int
    )

    /** Atrasado a sério: 2 minutos ou mais (como nos horários). */
    const val LATE_MIN = 2

    /**
     * Os comboios a mostrar: só dos serviços escolhidos (e só os atrasados, se pedido), ordenados pelo atraso
     * (o mais atrasado primeiro) ou agrupados pelo serviço (AP, IC, IR…) e dentro de cada grupo pelo atraso.
     */
    fun arrange(rows: List<TrainRow>, cfg: CpCard): List<TrainRow> {
        val filtered = rows.filter { it.service in cfg.services && (!cfg.onlyLate || it.delay >= LATE_MIN) }
        val rank = { s: String -> SERVICES.indexOf(s).let { if (it < 0) SERVICES.size else it } }
        val sorted = if (cfg.byService) {
            filtered.sortedWith(compareBy<TrainRow>({ rank(it.service) }, { -it.delay }, { it.number }))
        } else {
            filtered.sortedWith(compareBy<TrainRow>({ -it.delay }, { it.number }))
        }
        return sorted.take(cfg.count)
    }

    // ── "As minhas estações" ────────────────────────────────────────────────────

    const val MAX_STATIONS = 5

    /** Junta uma estação ao fim, se ainda não estiver e houver lugar. */
    fun addStation(list: List<String>, entry: String): List<String> =
        if (entry in list || list.size >= MAX_STATIONS) list else list + entry
}

private enum class EditorView { LIST, CP, STATIONS }

// ── Secção "Agora" ───────────────────────────────────────────────────────────

/** Secção "Agora" da página inicial: os cartões escolhidos e o botão Editar, que abre o painel a deslizar. */
@Composable
internal fun HomeNowSection(activity: ComponentActivity, metro: MetroNow, onOpenCategory: (String) -> Unit) {
    var entries by remember { mutableStateOf(HomeNowConfig.parse(Prefs.getHomeNowCards(activity))) }
    var cpCard by remember { mutableStateOf(HomeNowConfig.CpCard.parse(Prefs.getNowCpCard(activity))) }
    var stations by remember { mutableStateOf(Prefs.getNowStations(activity).mapNotNull { Place.decode(it) }) }
    var showBoth by remember { mutableStateOf(Prefs.isNowStationsBoth(activity)) }
    var editor by remember { mutableStateOf<EditorView?>(null) }

    fun saveEntries(list: List<HomeNowConfig.Entry>) { entries = list; Prefs.setHomeNowCards(activity, HomeNowConfig.encode(list)) }
    fun saveCp(cfg: HomeNowConfig.CpCard) { cpCard = cfg; Prefs.setNowCpCard(activity, cfg.encode()) }
    fun saveStations(list: List<Place>) { stations = list; Prefs.setNowStations(activity, list.map { it.encode() }) }
    fun saveShowBoth(both: Boolean) { showBoth = both; Prefs.setNowStationsBoth(activity, both) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Agora",
                color = TextSecondaryDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp).weight(1f)
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { editor = EditorView.LIST }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(14.dp))
                Text("Editar", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        val visible = entries.filter { it.visible }
        if (visible.isEmpty()) {
            Text(
                "Não escolheste nenhum cartão. Toca em Editar para escolher o que aparece aqui.",
                color = TextSecondaryDark,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        // Um cartão de cada vez, a deslizar para o lado (todos do mesmo tamanho)
        HomeNowCarousel(visible.map { it.card }) { card ->
            when (card) {
                HomeNowConfig.Card.METRO -> NowMetroCard(activity, metro) { onOpenCategory("Metro") }
                HomeNowConfig.Card.FAVORITES -> HomeFavoritesCard(activity, fill = true)
                HomeNowConfig.Card.HOME -> HomeGoHomeCard(activity, fill = true)
                HomeNowConfig.Card.BUS_STOPS -> NowBusCard(activity) { onOpenCategory("Autocarro") }
                HomeNowConfig.Card.CP_TRAINS -> HomeCpTrainsCard(activity, cpCard, ::saveCp, fill = true) { onOpenCategory("Comboio") }
                HomeNowConfig.Card.MY_STATIONS -> HomeMyStationsCard(activity, stations, showBoth) { editor = EditorView.STATIONS }
            }
        }
    }

    editor?.let { start ->
        HomeNowEditor(
            activity = activity,
            start = start,
            entries = entries,
            onEntries = ::saveEntries,
            cpCard = cpCard,
            onCpCard = ::saveCp,
            stations = stations,
            onStations = ::saveStations,
            showBoth = showBoth,
            onShowBoth = ::saveShowBoth,
            onClose = { editor = null }
        )
    }
}

// ── Painel "Personalizar" (entra a deslizar da direita) ──────────────────────

@Composable
private fun HomeNowEditor(
    activity: ComponentActivity,
    start: EditorView,
    entries: List<HomeNowConfig.Entry>,
    onEntries: (List<HomeNowConfig.Entry>) -> Unit,
    cpCard: HomeNowConfig.CpCard,
    onCpCard: (HomeNowConfig.CpCard) -> Unit,
    stations: List<Place>,
    onStations: (List<Place>) -> Unit,
    showBoth: Boolean,
    onShowBoth: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var view by remember { mutableStateOf(start) }
    // Depois de sair a deslizar, fecha de vez
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState && !shown.targetState) onClose()
    }
    fun close() { shown.targetState = false }
    fun back() { if (view != EditorView.LIST) view = EditorView.LIST else close() }

    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInHorizontally(tween(320)) { it },
            exit = slideOutHorizontally(tween(260)) { it }
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                AnimatedContent(
                    targetState = view,
                    // Lado a lado, à mesma velocidade: o painel novo entra exatamente à medida que o outro sai
                    // (antes o de trás só recuava um quarto e viam-se os dois ao mesmo tempo)
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
                            slideInHorizontally(tween(320)) { it } togetherWith slideOutHorizontally(tween(320)) { -it }
                        } else {
                            slideInHorizontally(tween(320)) { -it } togetherWith slideOutHorizontally(tween(320)) { it }
                        }
                    },
                    label = "editor",
                    modifier = Modifier.fillMaxSize().systemBarsPadding()
                ) { v ->
                    when (v) {
                        EditorView.LIST -> EditorListPanel(
                            entries, cpCard, stations, showBoth, onEntries,
                            onOpen = { view = it },
                            onBack = ::close
                        )
                        EditorView.CP -> CpCardPanel(activity, cpCard, onCpCard, onBack = { view = EditorView.LIST })
                        EditorView.STATIONS -> StationsPanel(activity, stations, onStations, showBoth, onShowBoth, onBack = { view = EditorView.LIST })
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
        }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun PanelLabel(text: String) =
    Text(text, color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))

/** Interruptor com as cores da app. */
@Composable
private fun BlueSwitch(checked: Boolean, onChange: (Boolean) -> Unit) =
    Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue))

/** Dois botões lado a lado, um escolhido (azul). [compact]: pequeno, para dentro de um cartão. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 8.dp else 10.dp))
            .background(if (compact) DarkBackground else CardBackground)
            .padding(if (compact) 2.dp else 3.dp)
    ) {
        options.forEachIndexed { i, text ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .then(if (compact) Modifier else Modifier.weight(1f))
                    .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
                    .background(if (on) MetrotimeBlue else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = if (compact) 9.dp else 4.dp, vertical = if (compact) 5.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text, color = if (on) Color.White else Color(0xFF9E9E9E), fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Resumo de cada cartão por baixo do nome, no painel. */
private fun cardDetail(card: HomeNowConfig.Card, cp: HomeNowConfig.CpCard, stations: List<Place>, showBoth: Boolean): String = when (card) {
    HomeNowConfig.Card.METRO -> "Circulação e linhas com problemas"
    HomeNowConfig.Card.FAVORITES -> "Próximo metro e comboio das tuas favoritas"
    HomeNowConfig.Card.HOME -> "Último metro e comboio para casa"
    HomeNowConfig.Card.BUS_STOPS -> "As tuas paragens, ou as mais perto"
    HomeNowConfig.Card.CP_TRAINS ->
        if (cp.services.isEmpty()) "Nenhum serviço escolhido"
        else HomeNowConfig.SERVICES.filter { it in cp.services }.joinToString(" · ") + if (cp.byService) ", por serviço" else ", por atraso"
    HomeNowConfig.Card.MY_STATIONS -> when (stations.size) {
        0 -> "Ainda sem estações"
        1 -> "1 estação"
        else -> "${stations.size} estações"
    } + if (stations.isEmpty()) "" else if (showBoth) " · partidas e chegadas" else " · só partidas"
}

@Composable
private fun EditorListPanel(
    entries: List<HomeNowConfig.Entry>,
    cpCard: HomeNowConfig.CpCard,
    stations: List<Place>,
    showBoth: Boolean,
    onEntries: (List<HomeNowConfig.Entry>) -> Unit,
    onOpen: (EditorView) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(DarkBackground)) {
        PanelHeader("Personalizar \"Agora\"", onBack) {
            TextButton(onClick = { onEntries(HomeNowConfig.DEFAULT) }) { Text("Repor", color = Color(0xFFD3D3D3)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                "Arrasta pela pega para mudar a ordem. Toca num cartão com seta para o configurar.",
                color = TextSecondaryDark, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
            )
            ReorderableList(
                items = entries,
                keyOf = { it.card },
                onMove = { from, to -> onEntries(HomeNowConfig.move(entries, from, to)) }
            ) { entry, dragging, handle ->
                val panel = when (entry.card) {
                    HomeNowConfig.Card.CP_TRAINS -> EditorView.CP
                    HomeNowConfig.Card.MY_STATIONS -> EditorView.STATIONS
                    else -> null
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(if (dragging) 12.dp else 0.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) Color(0xFF35383E) else CardBackground)
                        .then(if (panel != null) Modifier.clickable { onOpen(panel) } else Modifier)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(handle.size(width = 44.dp, height = 56.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            DragHandleIcon,
                            contentDescription = "Arrastar ${entry.card.title}",
                            tint = if (dragging) Color.White else Color(0xFF8C8F96),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(Modifier.weight(1f).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.card.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            cardDetail(entry.card, cpCard, stations, showBoth),
                            color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (panel != null) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Configurar", tint = TextSecondaryDark, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    BlueSwitch(entry.visible) { on ->
                        onEntries(entries.map { if (it.card == entry.card) it.copy(visible = on) else it })
                    }
                }
            }
        }
    }
}

/**
 * Lista que se ordena a arrastar pela pega ([row] recebe o Modifier da pega): a linha arrastada segue o dedo
 * e as outras afastam-se com animação; ao largar, encaixa no lugar.
 */
@Composable
private fun <T> ReorderableList(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    rowHeight: Dp = 68.dp,
    spacing: Dp = 8.dp,
    row: @Composable (item: T, dragging: Boolean, handle: Modifier) -> Unit
) {
    val stepPx = with(LocalDensity.current) { (rowHeight + spacing).toPx() }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val count = items.size
    Box(Modifier.fillMaxWidth().height(rowHeight * count + spacing * (count - 1).coerceAtLeast(0))) {
        items.forEachIndexed { index, item ->
            val k = keyOf(item)
            key(k) {
                val isDragging = draggingKey == k
                val target = index * stepPx
                val y = remember { Animatable(target) }
                LaunchedEffect(target, isDragging) { if (!isDragging) y.animateTo(target, tween(220)) }
                LaunchedEffect(isDragging) {
                    if (isDragging) snapshotFlow { dragY }.collect { y.snapTo(it) }
                }
                val handle = Modifier.pointerInput(k) {
                    detectDragGestures(
                        onDragStart = {
                            dragY = currentItems.indexOfFirst { keyOf(it) == k }.coerceAtLeast(0) * stepPx
                            draggingKey = k
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val last = (currentItems.size - 1).coerceAtLeast(0)
                            dragY = (dragY + amount.y).coerceIn(-stepPx / 2, last * stepPx + stepPx / 2)
                            val from = currentItems.indexOfFirst { keyOf(it) == k }
                            val to = (dragY / stepPx).roundToInt().coerceIn(0, last)
                            if (from >= 0 && to != from) currentOnMove(from, to)
                        },
                        onDragEnd = { draggingKey = null },
                        onDragCancel = { draggingKey = null }
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .offset { IntOffset(0, y.value.roundToInt()) }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            val s = if (isDragging) 1.03f else 1f
                            scaleX = s
                            scaleY = s
                        }
                ) {
                    row(item, isDragging, handle)
                }
            }
        }
    }
}

// ── Definições do cartão "Comboios CP" ───────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CpCardPanel(activity: ComponentActivity, cfg: HomeNowConfig.CpCard, onChange: (HomeNowConfig.CpCard) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(DarkBackground)) {
        PanelHeader("Comboios CP", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelLabel("Serviços a mostrar")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeNowConfig.SERVICES.forEach { code ->
                        val on = code in cfg.services
                        val color = getServiceColor(code)
                        Box(
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (on) color else Color.Transparent)
                                .border(1.dp, if (on) color else Color(0xFF5A5D63), RoundedCornerShape(18.dp))
                                .clickable { onChange(cfg.copy(services = if (on) cfg.services - code else cfg.services + code)) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                code,
                                color = if (!on) Color(0xFFDADDE2) else if (code == CpService.R) Color(0xFF171717) else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    "AP Alfa Pendular · IC Intercidades · IR InterRegional · R Regional · U Urbanos · IN Internacional · FT Fertagus",
                    color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelLabel("Organizar por")
                Segmented(listOf("Atraso", "Serviço"), if (cfg.byService) 1 else 0, { onChange(cfg.copy(byService = it == 1)) })
                Text(
                    if (cfg.byService) "Agrupados por serviço (AP, IC, IR…), cada grupo pelo atraso." else "Os mais atrasados primeiro.",
                    color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBackground).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Só comboios atrasados", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("${HomeNowConfig.LATE_MIN} min ou mais", color = TextSecondaryDark, fontSize = 12.sp)
                    }
                    BlueSwitch(cfg.onlyLate) { onChange(cfg.copy(onlyLate = it)) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelLabel("Como fica")
                HomeCpTrainsCard(activity, cfg, onChange, onOpenAll = null)
            }
        }
    }
}

// ── Definições de "As minhas estações" ───────────────────────────────────────

@Composable
private fun StationsPanel(
    activity: ComponentActivity,
    stations: List<Place>,
    onChange: (List<Place>) -> Unit,
    showBoth: Boolean,
    onShowBoth: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    val full = stations.size >= HomeNowConfig.MAX_STATIONS
    LaunchedEffect(query) {
        delay(200)
        results = TripPlanner.searchPlaces(activity, query, max = 15)
            .filter { it.kind == Place.Kind.METRO || it.kind == Place.Kind.CP }
            .filter { p -> stations.none { it.kind == p.kind && it.id == p.id } }
            .take(8)
    }

    Column(Modifier.fillMaxSize().background(DarkBackground)) {
        PanelHeader("As minhas estações", onBack) {
            Text("${stations.size} de ${HomeNowConfig.MAX_STATIONS}", color = TextSecondaryDark, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Até ${HomeNowConfig.MAX_STATIONS} estações de metro ou da CP. No cartão, desliza para o lado para passar de uma para a outra.",
                color = TextSecondaryDark, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
            if (stations.isNotEmpty()) {
                ReorderableList(
                    items = stations,
                    keyOf = { "${it.kind}_${it.id}" },
                    onMove = { from, to -> onChange(HomeNowConfig.move(stations, from, to)) },
                    rowHeight = 60.dp
                ) { place, dragging, handle ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(if (dragging) 12.dp else 0.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dragging) Color(0xFF35383E) else CardBackground),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(handle.size(width = 40.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                            Icon(DragHandleIcon, contentDescription = "Arrastar ${place.name}", tint = if (dragging) Color.White else Color(0xFF8C8F96), modifier = Modifier.size(16.dp))
                        }
                        PlaceIcon(place, size = 28.dp)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(place.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (place.kind == Place.Kind.CP) "Comboios · CP" else "Metro · " + MetroLines.linesOf(place.id).joinToString(" · "),
                                color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1
                            )
                        }
                        IconButton(onClick = { onChange(stations.filterNot { it.kind == place.kind && it.id == place.id }) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remover ${place.name}", tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (adding && !full) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Estação de metro ou da CP…", color = TextSecondaryDark) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondaryDark) },
                    trailingIcon = {
                        IconButton(onClick = { adding = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = TextSecondaryDark)
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MetrotimeBlue,
                        unfocusedBorderColor = Color(0xFF3A3A3A),
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.forEach { p ->
                        PlaceRow(p) {
                            onChange((stations + p).take(HomeNowConfig.MAX_STATIONS))
                            adding = false
                            query = ""
                        }
                    }
                    if (query.trim().length >= 2 && results.isEmpty()) {
                        Text("Nenhuma estação com esse nome.", color = TextSecondaryDark, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            } else {
                val color = if (full) Color(0xFF6B6E74) else Color(0xFF60A5FA)
                val border = if (full) Color(0xFF3A3D42) else MetrotimeBlue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = border,
                                cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                            )
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !full) { adding = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (full) "Máximo de ${HomeNowConfig.MAX_STATIONS} estações" else "Adicionar estação", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelLabel("Mostrar no cartão")
                Segmented(listOf("Partidas e chegadas", "Só partidas"), if (showBoth) 0 else 1, { onShowBoth(it == 0) })
                Text("No metro aparecem os próximos metros de cada sentido.", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}
