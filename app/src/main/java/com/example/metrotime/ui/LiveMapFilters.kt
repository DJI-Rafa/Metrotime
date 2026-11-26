package com.example.metrotime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.regional.RegionalArea
import com.example.metrotime.data.regional.RegionalOperator
import com.example.metrotime.data.regional.RegionalOperators
import com.example.metrotime.data.regional.RegionalRepository
import com.example.metrotime.data.regional.RegionalSource
import com.example.metrotime.data.regional.RegionalVehicle
import com.example.metrotime.data.regional.VehicleKind
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.MetrotimeBlue
import com.example.metrotime.ui.theme.TextSecondaryDark

// ──────────────────────────────────────────────────────────────────────────────
// Filtros do mapa: o botão "Filtros" na barra, os chips dos filtros ativos e o painel "O que ver no mapa"
// (região → empresas → linhas). Desenho: canvas "Metrotime · Filtros do mapa" no Claude Design.
// ──────────────────────────────────────────────────────────────────────────────

private val SHEET = Color(0xFF1C1C20)
private val FIELD = Color(0xFF2B2D30)
private val CHIP_OFF = Color(0xFF26272C)
private val OUTLINE = Color(0xFF3A3B40)
private val SECTION = Color(0xFF9AA0A6)

/** As empresas das outras redes no painel (a Fertagus também: anda com os comboios CP). */
private val FILTER_OPERATORS: List<RegionalOperator> = RegionalOperators.ALL

private const val FERTAGUS = "tml:7NTB1"
private const val CP_PAGE = "cp"

/**
 * Quantos filtros escolhidos há, o número no botão: metro ou comboios desligados, tipos de comboio escolhidos,
 * outras redes desligadas, empresas escondidas e empresas com linhas escolhidas.
 */
fun regionalFilterCount(f: Prefs.MapFilters): Int {
    val withLines = f.shownLines.map { it.substringBefore("|") }.toSet()
    return (if (f.metro) 0 else 1) + (if (f.cp) 0 else 1) +
        (if (f.cp && (f.shownCpServices.isNotEmpty() || f.cpOnlyDelayed)) 1 else 0) +
        (if (f.regional) 0 else 1) +
        f.hiddenOperators.count { k -> FILTER_OPERATORS.any { it.key == k } } + withLines.size
}

/** O botão azul "Filtros" com o número de filtros escolhidos. */
@Composable
fun FiltersButton(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MetrotimeBlue)
            .clickable(onClickLabel = "Abrir os filtros", onClick = onClick)
            .padding(start = 10.dp, end = if (count > 0) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TuneGlyph()
        Text("Filtros", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (count > 0) {
            Box(
                Modifier.defaultMinSize(minWidth = 18.dp).height(18.dp).clip(CircleShape).background(Color.White).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Sem a margem de cima da letra (puxava o número para baixo), centrado ao meio do círculo
                Text(
                    "$count",
                    color = Color(0xFF1E40AF),
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                        )
                    )
                )
            }
        }
    }
}

/** Três traços com botões (o ícone de "afinar"), desenhado à mão como os outros ícones do mapa. */
@Composable
private fun TuneGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val s = 2.2.dp.toPx()
        val r = 2.dp.toPx()
        fun line(y: Float, knob: Float) {
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.12f, y), androidx.compose.ui.geometry.Offset(w * 0.88f, y), s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(MetrotimeBlue, r + s, androidx.compose.ui.geometry.Offset(w * knob, y))
            drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(w * knob, y), style = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.9f))
        }
        line(size.height * 0.22f, 0.66f)
        line(size.height * 0.5f, 0.38f)
        line(size.height * 0.78f, 0.74f)
    }
}

/**
 * O painel "O que ver no mapa": a região num toque, as empresas (interruptor e quantos veículos há agora) e, ao tocar
 * no nome, as linhas dessa empresa. Carris e Carris Metropolitana abrem a pesquisa de linhas que já existe.
 */
@Composable
fun MapFiltersSheet(
    filters: Prefs.MapFilters,
    vehicles: List<RegionalVehicle>,
    /** Metros e comboios CP agora (para as contas). */
    metroCount: Int,
    cpCount: Int,
    /** Metros do Porto a andar agora (pelo horário). */
    portoMetroCount: Int = 0,
    /** Tipos de comboio presentes (AP, IC, IR, R, U, FT). */
    cpServices: List<String>,
    /** Mudar os filtros que não são de uma empresa (metro, comboios, tipos, paragens, amigos). */
    onChange: ((Prefs.MapFilters) -> Prefs.MapFilters) -> Unit,
    onClose: () -> Unit,
    onShown: (operatorKey: String, shown: Boolean) -> Unit,
    onArea: (RegionalArea?) -> Unit,
    onLines: (operatorKey: String, lines: Set<String>) -> Unit,
    onReset: () -> Unit,
    onSearchCarris: () -> Unit
) {
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val liveByOp = remember(vehicles) { vehicles.groupBy { it.operator.key } }
    // Scrim: tocar fora fecha
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.9f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            color = SHEET,
            shadowElevation = 12.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray.copy(alpha = 0.5f)))
                }
                val op = openKey?.let { k -> FILTER_OPERATORS.firstOrNull { it.key == k } }
                if (openKey == CP_PAGE) {
                    CpTypesPage(filters, cpServices, cpCount, onBack = { openKey = null }, onChange = onChange, onClose = onClose)
                } else if (op == null) {
                    OperatorsPage(filters, liveByOp, metroCount, cpCount, portoMetroCount, onChange, onClose, onShown, onArea, onReset, onSearchCarris) { openKey = it }
                } else {
                    LinesPage(op, filters.linesOf(op.key), liveByOp[op.key].orEmpty(), onBack = { openKey = null }, onLines = { onLines(op.key, it) }, onClose = onClose)
                }
            }
        }
    }
}

/** A região que os filtros mostram agora: a que tem todas as empresas à vista e as outras escondidas (null = todas). */
private fun currentArea(f: Prefs.MapFilters): Pair<Boolean, RegionalArea?> {
    val hidden = f.hiddenOperators.filter { k -> FILTER_OPERATORS.any { it.key == k } }.toSet()
    if (hidden.isEmpty()) return true to null
    for (area in RegionalArea.entries) {
        val others = FILTER_OPERATORS.filter { it.area != area }.mapTo(HashSet()) { it.key }
        if (hidden == others) return true to area
    }
    return false to null
}

@Composable
private fun ColumnScope.OperatorsPage(
    filters: Prefs.MapFilters,
    liveByOp: Map<String, List<RegionalVehicle>>,
    metroCount: Int,
    cpCount: Int,
    portoMetroCount: Int,
    onChange: ((Prefs.MapFilters) -> Prefs.MapFilters) -> Unit,
    onClose: () -> Unit,
    onShown: (String, Boolean) -> Unit,
    onArea: (RegionalArea?) -> Unit,
    onReset: () -> Unit,
    onSearchCarris: () -> Unit,
    onOpen: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val (exact, area) = currentArea(filters)
    Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("O que ver no mapa", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onReset) { Text("Repor", color = TextSecondaryDark, fontSize = 14.sp) }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = TextSecondaryDark) }
    }
    SearchField(query, "Procurar linha ou empresa") { query = it }

    SectionTitle("REGIÃO")
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RegionChip("Todas", exact && area == null) { onArea(null) }
        RegionalArea.entries.forEach { a -> RegionChip(a.label, exact && area == a) { onArea(a) } }
    }

    val q = query.trim()
    val matches = { name: String -> q.isEmpty() || name.contains(q, ignoreCase = true) }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
        // Comboios CP: em todo o país (a região não os esconde); tocar no nome escolhe os tipos
        if (matches("Comboios CP")) {
            item(key = "h-nacional") { SectionTitle("NACIONAL") }
            item(key = "cp") {
                val types = when {
                    filters.shownCpServices.isEmpty() && !filters.cpOnlyDelayed -> "todos os tipos"
                    filters.shownCpServices.isEmpty() -> "só atrasados"
                    else -> filters.shownCpServices.joinToString(", ") + if (filters.cpOnlyDelayed) " · só atrasados" else ""
                }
                OperatorRow(
                    "CP", "#4ADE80", "Comboios CP", "$cpCount em circulação · $types", filters.cp,
                    onOpen = { onOpen(CP_PAGE) }, onToggle = { onChange { it.copy(cp = !it.cp) } }
                )
            }
        }
        RegionalArea.entries.forEach { region ->
            val ops = FILTER_OPERATORS.filter { it.area == region }
                .filter { op ->
                    matches(op.name) || liveByOp[op.key].orEmpty().any { it.line?.startsWith(q, ignoreCase = true) == true }
                }
            val lisboa = region == RegionalArea.LISBOA
            val metro = lisboa && matches("Metro de Lisboa")
            if (ops.isEmpty() && !metro) return@forEach
            item(key = "h-$region") { SectionTitle(region.label.uppercase()) }
            if (metro) {
                item(key = "metro") {
                    OperatorRow(
                        "M", "#0077C8", "Metro de Lisboa", if (metroCount == 1) "1 metro agora" else "$metroCount metros agora", filters.metro,
                        onOpen = { onChange { it.copy(metro = !it.metro) } }, onToggle = { onChange { it.copy(metro = !it.metro) } }
                    )
                }
            }
            items(ops, key = { it.key }) { op ->
                val live = liveByOp[op.key].orEmpty()
                val chosen = filters.linesOf(op.key)
                // A Fertagus anda com os comboios: à vista só com os comboios ligados
                val fertagus = op.key == FERTAGUS
                val on = if (fertagus) filters.cp && op.key !in filters.hiddenOperators
                else filters.regional && op.key !in filters.hiddenOperators
                val sub = when {
                    op.source == RegionalSource.NONE -> "Estações e linhas"
                    op.source == RegionalSource.SCHEDULE -> {
                        val n = if (portoMetroCount == 1) "1 metro agora" else if (portoMetroCount == 0) "Sem metros agora" else "$portoMetroCount metros agora"
                        if (chosen.isEmpty()) "$n · pelo horário"
                        else "${if (chosen.size == 1) "1 linha escolhida" else "${chosen.size} linhas escolhidas"} · pelo horário"
                    }
                    chosen.isNotEmpty() -> {
                        val n = live.count { it.line in chosen }
                        "${if (chosen.size == 1) "1 linha escolhida" else "${chosen.size} linhas escolhidas"} · $n em direto"
                    }
                    fertagus -> "${if (live.size == 1) "1 comboio" else "${live.size} comboios"} · anda com os comboios"
                    live.isEmpty() -> "Sem veículos agora"
                    else -> "${live.size} em direto agora"
                }
                OperatorRow(op.shortName, op.color, op.name, sub, on, onOpen = { onOpen(op.key) }, onToggle = {
                    if (fertagus && !on && !filters.cp) onChange { it.copy(cp = true) }
                    onShown(op.key, !on)
                })
            }
        }
        // As paragens aparecem sempre (não há interruptor); os amigos escolhem-se aqui
        if (matches("Amigos")) {
            item(key = "h-mapa") { SectionTitle("NO MAPA") }
            item(key = "amigos") {
                OperatorRow(
                    "A", "#3B82F6", "Amigos", "Onde estão os teus amigos", filters.friends,
                    onOpen = { onChange { it.copy(friends = !it.friends) } }, onToggle = { onChange { it.copy(friends = !it.friends) } }
                )
            }
        }
    }

    val total = (if (filters.metro) metroCount else 0) + (if (filters.cp) cpCount else 0) +
        (if (portoMetroShown(filters)) portoMetroCount else 0) + FILTER_OPERATORS.sumOf { op ->
        if (op.key == FERTAGUS || !filters.regional || op.key in filters.hiddenOperators) 0
        else {
            val chosen = filters.linesOf(op.key)
            liveByOp[op.key].orEmpty().count { chosen.isEmpty() || it.line in chosen }
        }
    }
    BottomAction(if (total == 1) "Mostrar 1 veículo" else "Mostrar $total veículos", onClose)
}

/** Os tipos de comboio a ver (AP, IC, IR, R, U, FT) e "só atrasados", como em "Tipos" na barra do mapa. */
@Composable
private fun ColumnScope.CpTypesPage(
    filters: Prefs.MapFilters,
    services: List<String>,
    cpCount: Int,
    onBack: () -> Unit,
    onChange: ((Prefs.MapFilters) -> Prefs.MapFilters) -> Unit,
    onClose: () -> Unit
) {
    Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White) }
        OperatorSwatch("CP", "#4ADE80", on = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Comboios CP", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Todo o país · $cpCount em circulação", color = TextSecondaryDark, fontSize = 12.sp)
        }
    }
    SectionTitle("TIPOS")
    val all = (services + filters.shownCpServices).distinct()
        .sortedBy { com.example.metrotime.data.cp.CpService.ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RegionChip("Todos", filters.shownCpServices.isEmpty()) { onChange { it.copy(shownCpServices = emptySet()) } }
        all.forEach { service ->
            val on = service in filters.shownCpServices
            RegionChip(service, on) {
                onChange { f -> f.copy(cp = true, shownCpServices = if (on) f.shownCpServices - service else f.shownCpServices + service) }
            }
        }
    }
    Text(
        "Toca num tipo para ver só esse; noutro para o juntar.",
        color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).fillMaxWidth().heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp)).background(CHIP_OFF).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Só atrasados", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Os comboios com atraso", color = TextSecondaryDark, fontSize = 12.sp)
        }
        Switch(
            checked = filters.cpOnlyDelayed,
            onCheckedChange = { checked -> onChange { it.copy(cp = true, cpOnlyDelayed = checked) } },
            colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue, checkedThumbColor = Color.White)
        )
    }
    Spacer(Modifier.weight(1f))
    BottomAction("Mostrar $cpCount comboios", onClose)
}

@Composable
private fun ColumnScope.LinesPage(
    op: RegionalOperator,
    chosen: Set<String>,
    live: List<RegionalVehicle>,
    onBack: () -> Unit,
    onLines: (Set<String>) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable(op.key) { mutableStateOf("") }
    val all by produceState<List<RegionalRepository.LineChoice>?>(null, op.key) {
        value = runCatching { RegionalRepository.lines(op) }.getOrDefault(emptyList())
    }
    val liveLines = remember(live) { live.mapNotNull { it.line }.toSet() }

    Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar às empresas", tint = Color.White) }
        OperatorSwatch(op.shortName, op.color, on = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(op.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${op.area.label} · ${if (live.size == 1) "1 veículo em direto" else "${live.size} veículos em direto"}", color = TextSecondaryDark, fontSize = 12.sp)
        }
    }

    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).fillMaxWidth().heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp)).background(CHIP_OFF).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Todas as linhas", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Toca numa linha para ver só as que escolheres", color = TextSecondaryDark, fontSize = 12.sp)
        }
        Switch(
            checked = chosen.isEmpty(),
            onCheckedChange = { if (it) onLines(emptySet()) },
            colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue, checkedThumbColor = Color.White)
        )
    }
    SearchField(query, "Número ou destino") { query = it }

    val q = query.trim()
    val lines = all.orEmpty().filter { l -> q.isEmpty() || l.label.startsWith(q, ignoreCase = true) || l.name.contains(q, ignoreCase = true) }
    val withLive = lines.filter { it.label in liveLines }
    val others = lines.filter { it.label !in liveLines }
    val toggle = { label: String -> onLines(if (label in chosen) chosen - label else chosen + label) }

    if (all == null) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MetrotimeBlue, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (chosen.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ESCOLHIDAS", color = SECTION, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onLines(emptySet()) }) { Text("Limpar", color = MetrotimeBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (withLive.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { GridTitle("COM VEÍCULOS AGORA") }
                items(withLive, key = { "l-" + it.label }) { l -> LineButton(l, l.label in chosen, op.color) { toggle(l.label) } }
            }
            if (others.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { GridTitle(if (withLive.isEmpty()) "LINHAS" else "OUTRAS LINHAS") }
                items(others, key = { "o-" + it.label }) { l -> LineButton(l, l.label in chosen, op.color) { toggle(l.label) } }
            }
            if (lines.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (all.orEmpty().isEmpty()) "Não há lista de linhas para esta empresa." else "Nenhuma linha com \"$q\".",
                        color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }

    BottomAction(
        when {
            chosen.isEmpty() -> "Mostrar todas as linhas"
            chosen.size == 1 -> "Mostrar só a linha ${chosen.first()}"
            chosen.size <= 3 -> "Mostrar só as linhas " + chosen.sorted().let { it.dropLast(1).joinToString(", ") + " e " + it.last() }
            else -> "Mostrar ${chosen.size} linhas"
        },
        onClose
    )
}

@Composable
private fun GridTitle(text: String) {
    Text(text, color = SECTION, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, modifier = Modifier.padding(top = 14.dp))
}

/** Uma linha na grelha: o número na cor dela; a escolhida com aro branco e visto. */
@Composable
private fun LineButton(line: RegionalRepository.LineChoice, selected: Boolean, fallback: String, onClick: () -> Unit) {
    val hex = line.color ?: fallback
    Box(
        Modifier
            .height(44.dp)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(22.dp)).padding(3.dp) else Modifier)
            .clip(RoundedCornerShape(22.dp))
            .background(busColor(hex))
            .clickable(onClickLabel = if (selected) "Tirar a linha ${line.label}" else "Escolher a linha ${line.label}", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            line.label, color = busColor(com.example.metrotime.data.bus.BusColors.textOn(com.example.metrotime.data.bus.BusColors.normalize(hex) ?: "#8C8C99")),
            fontSize = if (line.label.length > 4) 11.sp else 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = busColor(hex), modifier = Modifier.size(12.dp)) }
        }
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, color = SECTION) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = SECTION) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MetrotimeBlue, unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = FIELD, unfocusedContainerColor = FIELD,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = SECTION, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun RegionChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) MetrotimeBlue.copy(alpha = 0.2f) else CHIP_OFF)
            .border(1.dp, if (on) MetrotimeBlue else OUTLINE, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) Color.White else TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OperatorSwatch(short: String, colorHex: String, on: Boolean) {
    Box(
        Modifier.defaultMinSize(minWidth = 40.dp).height(28.dp).clip(RoundedCornerShape(14.dp))
            .alpha(if (on) 1f else 0.4f).background(busColor(colorHex)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(short, color = busColor(com.example.metrotime.data.bus.BusColors.textOn(colorHex)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Uma empresa: o nome (abre as linhas) e o interruptor ([on] null = sem interruptor). */
@Composable
private fun OperatorRow(
    short: String, color: String, name: String, sub: String, on: Boolean?,
    onOpen: () -> Unit, onToggle: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clickable(onClickLabel = "Ver as linhas de $name", onClick = onOpen).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OperatorSwatch(short, color, on != false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFF6F747C))
        }
        if (on != null) {
            Switch(
                checked = on,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = MetrotimeBlue, checkedThumbColor = Color.White)
            )
        }
    }
}

@Composable
private fun BottomAction(text: String, onClick: () -> Unit) {
    HorizontalDivider(color = FIELD)
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp).height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MetrotimeBlue)
    ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
