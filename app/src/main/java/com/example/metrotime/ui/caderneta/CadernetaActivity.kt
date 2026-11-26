package com.example.metrotime.ui.caderneta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.caderneta.BadgeCatalog
import com.example.metrotime.data.caderneta.BadgeCloud
import com.example.metrotime.data.caderneta.BadgeState
import com.example.metrotime.data.caderneta.Caderneta
import com.example.metrotime.data.caderneta.CadernetaRepository
import com.example.metrotime.data.caderneta.Stamp
import com.example.metrotime.data.caderneta.StampKind
import com.example.metrotime.data.caderneta.Stamps
import com.example.metrotime.data.caderneta.Tier
import com.example.metrotime.ui.theme.LineAmarela
import com.example.metrotime.ui.theme.LineAzul
import com.example.metrotime.ui.theme.LineVerde
import com.example.metrotime.ui.theme.LineVermelha
import com.example.metrotime.ui.theme.MetrotimeTheme
import com.example.metrotime.ui.theme.TextOnYellow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos

private val BG = Color(0xFF101113)
private val SHEET = Color(0xFF1C1C20)
private val CARD = Color(0xFF2B2D30)
private val MUTED = Color(0xFFB7BBC3)
private val FAINT = Color(0xFF8A8F98)
private val CREAM = Color(0xFFF3EFE6)
private val GREEN = Color(0xFF4ADE80)
private val CP_ON_CREAM = Color(0xFF1C7444)

/** As cores de cada linha do metro: a da linha, a do desenho no azulejo creme e a do contorno no escuro. */
private data class LineColors(val color: Color, val onCream: Color, val onDark: Color, val text: Color)

private val METRO_COLORS = mapOf(
    "Azul" to LineColors(LineAzul, Color(0xFF0069B4), Color(0xFF4DA3F0), Color.White),
    "Amarela" to LineColors(LineAmarela, Color(0xFFB38A00), LineAmarela, TextOnYellow),
    "Verde" to LineColors(LineVerde, Color(0xFF008752), Color(0xFF2BC47F), Color.White),
    "Vermelha" to LineColors(LineVermelha, Color(0xFFCC141C), Color(0xFFFF5A5F), Color.White)
)

/** A caderneta de estações (metro e comboio) e os distintivos. */
class CadernetaActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TAB = "tab"
        fun newIntent(context: Context, tab: Int = 0): Intent = Intent(context, CadernetaActivity::class.java).putExtra(EXTRA_TAB, tab)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tab = intent.getIntExtra(EXTRA_TAB, 0)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = BG) { CadernetaScreen(tab) { finish() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CadernetaScreen(initialTab: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<Caderneta?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var station by remember { mutableStateOf<String?>(null) }
    var badge by remember { mutableStateOf<BadgeState?>(null) }
    var sharing by remember { mutableStateOf<BadgeState?>(null) }
    var unlocked by remember { mutableStateOf<List<BadgeState>>(emptyList()) }
    var counts by remember { mutableStateOf<BadgeCloud.Counts?>(null) }
    LaunchedEffect(Unit) {
        val c = CadernetaRepository.load(context)
        data = c
        unlocked = CadernetaRepository.unseen(context, c)
        counts = BadgeCloud.counts()
        // Os distintivos sobem para o Firebase; os que lá estão e aqui não (outro telemóvel, app apagada) voltam
        if (BadgeCloud.sync(context, c)) data = CadernetaRepository.load(context)
    }

    Column(Modifier.fillMaxSize().background(BG).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("Caderneta", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Os teus carimbos e distintivos", color = MUTED, fontSize = 12.sp)
            }
        }
        Tabs(tab) { tab = it }
        val c = data
        if (c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (tab) {
                    0 -> metroTab(c) { station = it }
                    1 -> trainTab(c) { station = it }
                    else -> badgesTab(c) { badge = it }
                }
            }
        }
    }

    val c = data
    val key = station
    if (c != null && key != null) {
        ModalBottomSheet(onDismissRequest = { station = null }, containerColor = SHEET) { StationSheet(c, key) }
    }
    val b = badge
    if (b != null) {
        ModalBottomSheet(onDismissRequest = { badge = null }, containerColor = SHEET) {
            BadgeSheet(b, counts?.text(b.def.id, mine = b.earned)) { sharing = b }
        }
    }
    sharing?.let { BadgeShareDialog(it) { sharing = null } }
    if (unlocked.isNotEmpty()) {
        BadgeUnlockDialog(unlocked, onShowAll = { tab = 2 }) {
            CadernetaRepository.markSeen(context, unlocked.map { it.def.id })
            unlocked = emptyList()
        }
    }
}

@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(CARD).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("Metro", "Comboio", "Distintivos").forEachIndexed { i, name ->
            val on = i == selected
            val bg by animateColorAsState(if (on) CREAM else Color.Transparent, label = "separador")
            val fg by animateColorAsState(if (on) BG else MUTED, label = "texto")
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) { Text(name, color = fg, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold) }
        }
    }
}

// ── Progresso ────────────────────────────────────────────────────────────────

@Composable
private fun ProgressCard(stamped: Int, full: Int, passed: Int, total: Int, fullColor: Color, passedColor: Color, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SHEET).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$stamped", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp)
            Text("de $total estações", color = MUTED, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(CARD)) {
            if (full > 0) Box(Modifier.weight(full.toFloat()).fillMaxHeight().background(fullColor))
            if (passed > 0) Box(Modifier.weight(passed.toFloat()).fillMaxHeight().background(passedColor))
            val rest = total - full - passed
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(fullColor, "$full com carimbo")
            Legend(passedColor, "$passed de passagem")
            Legend(CARD, "${total - full - passed} por descobrir")
        }
        extra()
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, color = MUTED, fontSize = 12.sp)
    }
}

// ── Metro ────────────────────────────────────────────────────────────────────

private fun LazyListScope.metroTab(c: Caderneta, onStation: (String) -> Unit) {
    val keys = c.metroIds.map(Stamps::metro)
    val full = keys.count { c.book.isFull(it) }
    val passed = keys.count { c.book.has(it) && !c.book.isFull(it) }
    item("progresso") {
        ProgressCard(full + passed, full, passed, keys.size, CREAM, Color(0xFF6F7C93)) {
            val complete = c.metroLines.filter { (_, ids) -> ids.all { c.book.has(Stamps.metro(it)) } }.keys
            if (complete.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    complete.forEach { line ->
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF22262B)).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(METRO_COLORS[line]?.color ?: Color.Gray))
                            Text("Linha $line completa", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    c.metroLines.forEach { (line, ids) ->
        val colors = METRO_COLORS[line] ?: return@forEach
        val got = ids.count { c.book.has(Stamps.metro(it)) }
        item("linha-$line") {
            SectionHeader(
                pill = "Linha $line", pillColor = colors.color, pillText = colors.text,
                count = "$got de ${ids.size} estações", complete = got == ids.size
            )
        }
        ids.chunked(5).forEachIndexed { row, chunk ->
            item("linha-$line-$row") {
                TileRow(chunk.map { id ->
                    val key = Stamps.metro(id)
                    val others = c.metroLines.filter { (l, list) -> l != line && id in list }.keys.mapNotNull { METRO_COLORS[it]?.color }
                    TileItem(key, c.metroNames[id] ?: id, c.book.stamps[key], c.patterns[key], colors.onCream, colors.onDark, others)
                }, onStation)
            }
        }
    }
}

private data class TileItem(
    val key: String, val name: String, val stamp: Stamp?, val pattern: com.example.metrotime.data.caderneta.Azulejos.Pattern?,
    val onCream: Color, val onDark: Color, val others: List<Color> = emptyList()
)

@Composable
private fun SectionHeader(pill: String, pillColor: Color, pillText: Color, count: String, complete: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier).padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            pill, color = pillText, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(pillColor).padding(horizontal = 10.dp, vertical = 4.dp).weight(1f, fill = false)
        )
        Text(count, color = MUTED, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (complete) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
                Text("Completa", color = GREEN, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun TileRow(items: List<TileItem>, onStation: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onStation(item.key) }.padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val kind = item.stamp?.kind
                val pattern = item.pattern ?: com.example.metrotime.data.caderneta.Azulejos.of(0)
                AzulejoTile(
                    pattern, kind, item.onCream, item.onDark, Modifier.size(62.dp), item.others,
                    description = when (kind) {
                        StampKind.FULL -> "Carimbo de ${item.name}"
                        StampKind.PASSED -> "De passagem por ${item.name}"
                        null -> "${item.name}, por descobrir"
                    }
                )
                Text(
                    item.name, color = when (kind) { StampKind.FULL -> Color.White; StampKind.PASSED -> MUTED; null -> Color(0xFF7A7F88) },
                    fontSize = 10.5.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(27.dp)
                )
            }
        }
        repeat(5 - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

// ── Comboio ──────────────────────────────────────────────────────────────────

private fun LazyListScope.trainTab(c: Caderneta, onStation: (String) -> Unit) {
    val keys = c.cpStations.map { Stamps.cp(it.code) }
    val full = keys.count { c.book.isFull(it) }
    val passed = keys.count { c.book.has(it) && !c.book.isFull(it) }
    item("progresso") {
        ProgressCard(full + passed, full, passed, keys.size, GREEN, Color(0xFF2E6B47)) {
            val pct = if (keys.isEmpty()) 0 else Math.round(100.0 * (full + passed) / keys.size).toInt()
            Text("$pct % de Portugal", color = Color(0xFF7FE3A8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
    item("mapa") { TrainMap(c) }
    item("por-linha") { Text("Por linha", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    if (c.cpLines.isEmpty()) {
        // Sem a lista da CP: as estações por onde já passaste
        val mine = c.cpStations.filter { c.book.has(Stamps.cp(it.code)) }.sortedBy { it.name }
        item("sem-linhas") {
            Text(
                "A lista das linhas da CP ainda não chegou (precisa de internet uma vez). Para já, as estações onde já estiveste:",
                color = MUTED, fontSize = 13.sp, lineHeight = 18.sp
            )
        }
        mine.chunked(5).forEachIndexed { row, chunk ->
            item("minhas-$row") { TileRow(chunk.map { tileOf(c, it.code, it.name) }, onStation) }
        }
    } else {
        val lines = c.cpLines.entries.sortedWith(compareByDescending<Map.Entry<String, List<String>>> { e -> e.value.count { c.book.has(Stamps.cp(it)) }.toFloat() / e.value.size }.thenBy { it.key })
        lines.forEach { (line, codes) ->
            item("cp-$line") { CpLineSection(c, line, codes, onStation) }
        }
    }
}

private fun tileOf(c: Caderneta, code: String, name: String): TileItem {
    val key = Stamps.cp(code)
    return TileItem(key, name, c.book.stamps[key], c.patterns[key], CP_ON_CREAM, GREEN)
}

@Composable
private fun CpLineSection(c: Caderneta, line: String, codes: List<String>, onStation: (String) -> Unit) {
    var open by rememberSaveable(line) { mutableStateOf(false) }
    val got = codes.count { c.book.has(Stamps.cp(it)) }
    val byCode = remember(c) { c.cpStations.associateBy { it.code } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            pill = line, pillColor = GREEN, pillText = Color(0xFF0B2015), count = "$got de ${codes.size}",
            complete = got == codes.size,
            trailing = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Fechar" else "Abrir", tint = MUTED) },
            onClick = { open = !open }
        )
        if (open) {
            codes.chunked(5).forEach { chunk ->
                TileRow(chunk.map { code -> tileOf(c, code, byCode[code]?.name ?: code) }, onStation)
            }
        }
    }
}

/** As estações da CP nos sítios reais, com a rede por baixo: cheias a verde, de passagem em contorno. */
@Composable
private fun TrainMap(c: Caderneta) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SHEET).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("O teu Portugal ferroviário", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Todas as estações da CP, no sítio onde estão", color = FAINT, fontSize = 12.sp)
        }
        val stations = c.cpStations
        if (stations.isEmpty()) return@Column
        val minLat = stations.minOf { it.lat }
        val maxLat = stations.maxOf { it.lat }
        val minLon = stations.minOf { it.lon }
        val maxLon = stations.maxOf { it.lon }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val aspect = ((maxLon - minLon) * kx / (maxLat - minLat)).toFloat()
        Canvas(Modifier.fillMaxWidth(0.72f).aspectRatio(aspect)) {
            val pad = 6f
            val sx = (size.width - 2 * pad) / ((maxLon - minLon) * kx).toFloat()
            val sy = (size.height - 2 * pad) / (maxLat - minLat).toFloat()
            fun p(lat: Double, lon: Double) = Offset(pad + ((lon - minLon) * kx).toFloat() * sx, pad + (maxLat - lat).toFloat() * sy)
            val net = Path()
            c.networkLines.forEach { line ->
                line.forEachIndexed { i, (lat, lon) -> val o = p(lat, lon); if (i == 0) net.moveTo(o.x, o.y) else net.lineTo(o.x, o.y) }
            }
            drawPath(net, Color(0xFF34383E), style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val r = 1.6.dp.toPx()
            stations.forEach { s ->
                if (!c.book.has(Stamps.cp(s.code))) drawCircle(Color(0xFF4A4E55), r * 0.9f, p(s.lat, s.lon))
            }
            stations.forEach { s ->
                val key = Stamps.cp(s.code)
                if (c.book.has(key) && !c.book.isFull(key)) {
                    drawCircle(Color(0xFF101113), r * 1.2f, p(s.lat, s.lon))
                    drawCircle(GREEN, r * 1.2f, p(s.lat, s.lon), style = Stroke(1.dp.toPx()))
                }
            }
            stations.forEach { s -> if (c.book.isFull(Stamps.cp(s.code))) drawCircle(GREEN, r * 1.6f, p(s.lat, s.lon)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(GREEN, "Com carimbo")
            Legend(Color(0xFF2E6B47), "De passagem")
            Legend(Color(0xFF4A4E55), "Por descobrir")
        }
    }
}

// ── Distintivos ──────────────────────────────────────────────────────────────

private fun LazyListScope.badgesTab(c: Caderneta, onBadge: (BadgeState) -> Unit) {
    item("resumo") {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SHEET).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${c.earnedCount}", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp)
                Text("de ${c.badges.size} distintivos", color = MUTED, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tier.entries.forEach { tier ->
                    val all = c.badges.filter { it.def.tier == tier }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF24262A)).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("${all.count { it.earned }}/${all.size}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        Text(tier.plural, color = MUTED, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    Tier.entries.forEach { tier ->
        val list = c.badges.filter { it.def.tier == tier }
        item("tier-$tier") {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tier.plural, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("${list.count { it.earned }} de ${list.size}", color = MUTED, fontSize = 13.sp)
            }
        }
        list.chunked(4).forEachIndexed { row, chunk ->
            item("tier-$tier-$row") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    chunk.forEach { state ->
                        val secret = state.def.tier == Tier.SECRETO && !state.earned
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onBadge(state) }.padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BadgeArt(
                                state.def.id, state.def.tier, state.label, locked = !state.earned, modifier = Modifier.size(76.dp),
                                description = if (secret) "Distintivo secreto por descobrir" else "${state.def.name}, ${state.def.tier.title}${if (state.earned) "" else ", por ganhar"}"
                            )
                            Text(
                                if (secret) "???" else state.def.name, color = if (state.earned) Color.White else Color(0xFF7A7F88),
                                fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.height(29.dp)
                            )
                        }
                    }
                    repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ── Folhas ───────────────────────────────────────────────────────────────────

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("pt-PT"))
private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

@Composable
private fun StationSheet(c: Caderneta, key: String) {
    val stamp = c.book.stamps[key]
    val name = CadernetaRepository.stationName(c, key)
    val metroId = key.removePrefix("m:").takeIf { key.startsWith("m:") }
    val lines = metroId?.let { id -> c.metroLines.filter { id in it.value }.keys.toList() }
        ?: c.cpStations.firstOrNull { it.code == key.removePrefix("c:") }?.lines.orEmpty()
    val main = metroId?.let { METRO_COLORS[lines.firstOrNull()] }
    val others = metroId?.let { lines.drop(1).mapNotNull { METRO_COLORS[it]?.color } }.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AzulejoTile(
            c.patterns[key] ?: com.example.metrotime.data.caderneta.Azulejos.of(0), stamp?.kind,
            main?.onCream ?: CP_ON_CREAM, main?.onDark ?: GREEN, Modifier.size(180.dp), others,
            description = "Azulejo de $name"
        )
        Text(name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        if (lines.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.take(4).forEach { l ->
                    val lc = METRO_COLORS[l]
                    Text(
                        l, color = lc?.text ?: Color(0xFF0B2015), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(lc?.color ?: GREEN).padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        val status = when (stamp?.kind) {
            StampKind.FULL -> "Carimbo cheio"
            StampKind.PASSED -> "De passagem: sai aqui para o carimbo cheio"
            null -> "Por descobrir"
        }
        Text(status, color = if (stamp?.kind == StampKind.FULL) GREEN else MUTED, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (stamp != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("${stamp.full}", "vezes que entraste ou saíste", Modifier.weight(1f))
                StatBox("${stamp.passed}", "vezes de passagem", Modifier.weight(1f))
                StatBox(Instant.ofEpochMilli(stamp.firstMs).atZone(ZONE).format(DAY), "a primeira vez", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(CARD).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = MUTED, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun BadgeSheet(state: BadgeState, holders: String?, onShare: () -> Unit) {
    val secret = state.def.tier == Tier.SECRETO && !state.earned
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BadgeArt(state.def.id, state.def.tier, state.label, locked = !state.earned, modifier = Modifier.size(200.dp))
        TierPill(state.def.tier)
        Text(if (secret) "???" else state.def.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(
            if (secret) "Pista: ${BadgeCatalog.CLUES[state.def.id] ?: "descobre-o a viajar."}" else state.def.how,
            color = Color(0xFFC9CDD3), fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center
        )
        when {
            state.earned -> {
                // O que aconteceu (no "Dia de greve": o comboio em que ias)
                state.story?.let { Text(it, color = Color(0xFFE9C77A), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center) }
                Text("Ganho", color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Button(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CREAM, contentColor = BG)
                ) { Text("Partilhar", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
            }
            state.progress != null && !secret -> {
                LinearProgressIndicator(
                    progress = { state.progress }, color = CREAM, trackColor = CARD,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )
                state.progressText?.let { Text(it, color = MUTED, fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
            state.progressText != null && !secret -> Text(state.progressText, color = MUTED, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        // Quantas pessoas da app o têm (contado no Firebase)
        holders?.let { Text(it, color = Color(0xFFE9C77A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
    }
}

@Composable
internal fun TierPill(tier: Tier) {
    val (bg, fg) = when (tier) {
        Tier.COMUM -> CREAM to Color(0xFF1E4FAE)
        Tier.RARO -> Color(0xFF1E4AA8) to Color.White
        Tier.LENDARIO -> Color(0xFFF4D27A) to Color(0xFF2A1E05)
        Tier.IMPOSSIVEL -> Color(0xFF9FE7FF) to Color(0xFF0D0D16)
        Tier.SECRETO -> Color(0xFFB9A3F0) to Color(0xFF22163A)
    }
    Text(
        tier.title.uppercase(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 12.dp, vertical = 4.dp)
    )
}
