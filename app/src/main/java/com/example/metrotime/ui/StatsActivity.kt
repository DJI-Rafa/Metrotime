package com.example.metrotime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.UserDirectory
import com.example.metrotime.data.doubleOrNull
import com.example.metrotime.data.history.StatsCalc
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.data.history.TripStats
import com.example.metrotime.data.intOrNull
import com.example.metrotime.data.stringOrNull
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId

// ──────────────────────────────────────────────────────────────────────────────
// Estatísticas: o período (7 dias, este mês, sempre) muda o resumo com o gráfico, os três números, as
// linhas, as estações e as horas. As contas estão em StatsCalc. Mesmo estilo do resto da app.
// ──────────────────────────────────────────────────────────────────────────────

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

private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")
private val MEDALS = listOf(Color(0xFFE6B422), Color(0xFFB8BCC4), Color(0xFFC98A4B))
private val FRIEND_COLORS = listOf(Color(0xFF7C3AED), Color(0xFF0D9488), Color(0xFFDB2777), Color(0xFFEA580C), Color(0xFF0891B2))
private val GREEN_TEXT = Color(0xFF7BD389)

/** Cor de um amigo pelo nome (sempre a mesma). */
private fun friendColor(name: String): Color = FRIEND_COLORS[Math.floorMod(name.hashCode(), FRIEND_COLORS.size)]

/** Cor de uma linha do metro ou de um serviço da CP (Alfa a vermelho, IC a azul…); o verde da CP nos antigos. */
private fun lineColor(key: String): Color = when (key) {
    "CP" -> CpGreen
    in StatsCalc.TRAIN_SERVICES -> getServiceColor(key)
    else -> lineColorOf(key)
}

/** Um amigo no "Entre amigos": total de sempre e deste mês (os dois vêm do Firebase). */
data class FriendRank(val name: String, val visits: Int, val isMe: Boolean = false, val monthly: Int = 0)

@Composable
fun StatsScreen(activity: ComponentActivity, friendUid: String?, friendName: String?) {
    if (friendUid != null) FriendStatsScreen(activity, friendUid, friendName ?: "Amigo") else MyStatsScreen(activity)
}

@Composable
private fun StatsHeader(title: String, subtitle: String, onBack: () -> Unit, avatar: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
        }
        avatar?.let { it(); Spacer(Modifier.width(10.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextSecondaryDark, fontSize = 12.sp)
        }
    }
}

// ── As minhas estatísticas ───────────────────────────────────────────────────

@Composable
private fun MyStatsScreen(activity: ComponentActivity) {
    val trips = remember { TripHistoryManager.trips(activity) }
    val today = remember { LocalDate.now(ZONE) }
    val legacyKm = remember { TripHistoryManager.legacyKm(activity) }
    val streak = remember { StatsCalc.streak(trips, today, ZONE, TripHistoryManager.legacyLongestStreak(activity)) }
    var period by rememberSaveable { mutableStateOf(StatsCalc.Period.MONTH) }
    val summary = remember(period) { StatsCalc.summary(trips, period, today, ZONE, legacyKm) }
    var friends by remember { mutableStateOf<List<FriendRank>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Entre amigos: eu e os amigos aceites, com o total e as viagens deste mês
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@LaunchedEffect
        try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance(FIREBASE_DB_URL)
            val myName = db.getReference("users").child(uid).child("name").get().await().stringOrNull() ?: "Eu"
            val thisMonth = StatsCalc.summary(trips, StatsCalc.Period.MONTH, today, ZONE).total
            val ranking = mutableListOf(FriendRank(myName.split(" ")[0], trips.size, true, thisMonth))
            for (f in db.getReference("friends").child(uid).get().await().children) {
                val fUid = f.key ?: continue
                if (f.stringOrNull() != "accepted") continue
                // Um amigo que falhe não impede os outros de aparecer
                val stats = runCatching { friendStats(db.getReference("user_stats").child(fUid).get().await()) }.getOrNull()
                val name = runCatching { UserDirectory.getProfile(fUid)?.name }.getOrNull() ?: "Amigo"
                // "Este mês" de um amigo só conta se for deste mês (antes ficava preso no último valor enviado)
                ranking.add(FriendRank(name.split(" ")[0], stats?.totalVisits ?: 0, false, stats?.monthlyOn(today) ?: 0))
            }
            friends = ranking
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StatsHeader("Estatísticas", "As tuas viagens de metro e comboio", onBack = { activity.finish() })
        if (trips.isEmpty()) {
            EmptyStats(activity)
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key = "period") { PeriodTabs(period) { period = it } }
            item(key = "summary") { SummaryCard(summary, period) }
            item(key = "metrics") {
                MetricsRow(
                    listOf(
                        Metric(RouteIcon, MetrotimeBlue, StatsCalc.distance(summary.km), "percorridos"),
                        Metric(ClockIcon, GREEN, StatsCalc.duration(summary.travelMinutes), "em viagem"),
                        Metric(PinIcon, ORANGE, "${summary.distinctStations}", if (summary.distinctStations == 1) "estação" else "estações diferentes")
                    )
                )
            }
            item(key = "streak") { HomeSection("Sequência") { StreakCard(streak) } }
            if (summary.total == 0) {
                item(key = "none") {
                    Text(
                        if (period == StatsCalc.Period.WEEK) "Sem viagens nos últimos 7 dias." else "Sem viagens este mês.",
                        color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp)
                    )
                }
            } else {
                item(key = "lines") { HomeSection("Linhas que mais usas") { LinesCard(summary.lines) } }
                item(key = "stations") { HomeSection("Estações mais visitadas") { StationsCard(summary.stations) } }
                item(key = "recent") { HomeSection("Últimas viagens") { RecentTripsCard(summary.recent, today) } }
                item(key = "hours") { HomeSection("Quando viajas") { HoursCard(summary.hours) } }
            }
            if (friends.size > 1) {
                item(key = "friends") {
                    val monthly = period == StatsCalc.Period.MONTH
                    HomeSection(if (monthly) "Entre amigos · este mês" else "Entre amigos · desde sempre") { FriendsCard(friends, monthly) }
                }
            }
        }
    }
}

/** 7 dias, este mês ou sempre, no estilo dos separadores da app. */
@Composable
private fun PeriodTabs(period: StatsCalc.Period, onChange: (StatsCalc.Period) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBackground).padding(3.dp)
    ) {
        listOf(StatsCalc.Period.WEEK to "7 dias", StatsCalc.Period.MONTH to "Este mês", StatsCalc.Period.ALL to "Sempre").forEach { (p, label) ->
            val on = p == period
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) MetrotimeBlue else Color.Transparent)
                    .clickable { onChange(p) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) Color.White else TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Número de viagens, comparação com o período anterior e o gráfico por dia (ou por mês). */
@Composable
private fun SummaryCard(summary: StatsCalc.Summary, period: StatsCalc.Period) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${summary.total}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)
                Spacer(Modifier.width(6.dp))
                Text(if (summary.total == 1) "viagem" else "viagens", color = TextSecondaryDark, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                if (summary.change.text.isNotEmpty()) {
                    val (bg, fg) = when (summary.change.trend) {
                        1 -> GREEN.copy(alpha = 0.16f) to GREEN_TEXT
                        -1 -> Color(0xFFFF5252).copy(alpha = 0.16f) to Color(0xFFFF8A80)
                        else -> Color(0xFF34363A) to TextSecondaryDark
                    }
                    Text(
                        summary.change.text,
                        color = fg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            val gap = when (period) {
                StatsCalc.Period.WEEK -> 8.dp
                StatsCalc.Period.MONTH -> 3.dp
                StatsCalc.Period.ALL -> 10.dp
            }
            BarChart(summary.bars, gap)
        }
    }
}

@Composable
private fun BarChart(bars: List<StatsCalc.Bar>, gap: Dp) {
    val max = (bars.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            bars.forEach { b ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight((b.value.toFloat() / max).coerceAtLeast(0.03f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (b.current) MetrotimeBlue else MetrotimeBlue.copy(alpha = 0.45f))
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            bars.forEach { b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // Rótulos que não cabem na barra (dias do mês) passam por cima das vizinhas
                    Text(b.label, color = Color(0xFF8A8D93), fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true))
                }
            }
        }
    }
}

private data class Metric(val icon: ImageVector, val color: Color, val value: String, val label: String)

@Composable
private fun MetricsRow(metrics: List<Metric>) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { m ->
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(m.color.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(m.icon, contentDescription = null, tint = m.color, modifier = Modifier.size(18.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(m.value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(m.label, color = TextSecondaryDark, fontSize = 12.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

/** Dias seguidos, o recorde e os dias desta semana (de segunda a domingo) em que viajaste. */
@Composable
private fun StreakCard(streak: StatsCalc.Streak) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    when (streak.current) {
                        0 -> "Sem viagens hoje nem ontem"
                        1 -> "1 dia a viajar"
                        else -> "${streak.current} dias seguidos a viajar"
                    },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (streak.longest > 0) Text("recorde: ${streak.longest}", color = TextSecondaryDark, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("S", "T", "Q", "Q", "S", "S", "D").forEachIndexed { i, day ->
                    val travelled = streak.week[i]
                    val isToday = i == streak.todayIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (travelled == true) MetrotimeBlue else Color.Transparent)
                                .border(
                                    2.dp,
                                    when {
                                        travelled == true -> MetrotimeBlue
                                        isToday -> TextSecondaryDark
                                        else -> DIVIDER
                                    },
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (travelled == true) Icon(Icons.Filled.Check, contentDescription = "Viajaste", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            day,
                            color = if (isToday) Color.White else Color(0xFF8A8D93),
                            fontSize = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/** Linhas: quadrado na cor da linha (CP a verde), nome, viagens e uma barra em relação à mais usada. */
/** Os distintivos de um amigo, dos mais raros para os mais comuns, numa fila que se arrasta para o lado. */
@Composable
private fun FriendBadges(badges: List<Pair<com.example.metrotime.data.caderneta.BadgeDef, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(badges.size) { i ->
                val (def, label) = badges[i]
                Column(Modifier.width(74.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.example.metrotime.ui.caderneta.BadgeArt(
                        def.id, def.tier, label, locked = false, modifier = Modifier.size(60.dp), animate = false,
                        description = "${def.name}, ${def.tier.title}"
                    )
                    Text(
                        def.name, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LinesCard(lines: List<StatsCalc.LineCount>) {
    val max = (lines.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            lines.forEach { l ->
                val color = lineColor(l.key)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        if (l.isTrain) Text(l.key, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(l.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // Comboios: quantos km e quanto tempo a bordo nesse serviço
                                if (l.isTrain && (l.km >= 0.05 || l.minutes > 0)) {
                                    Text(
                                        listOfNotNull(
                                            l.km.takeIf { it >= 0.05 }?.let { StatsCalc.distance(it) },
                                            l.minutes.takeIf { it > 0 }?.let { StatsCalc.duration(it) }
                                        ).joinToString(" · "),
                                        color = TextSecondaryDark, fontSize = 12.sp
                                    )
                                }
                            }
                            Text(if (l.count == 1) "1 viagem" else "${l.count} viagens", color = TextSecondaryDark, fontSize = 14.sp)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(DIVIDER)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(l.count.toFloat() / max).clip(RoundedCornerShape(3.dp)).background(color))
                        }
                    }
                }
            }
        }
    }
}

/** Estações num só cartão: posição (as três primeiras a ouro, prata e bronze), linhas, nome e visitas. */
@Composable
private fun StationsCard(stations: List<StatsCalc.StationCount>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        stations.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val medal = MEDALS.getOrNull(i)
                Box(
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(medal?.copy(alpha = 0.2f) ?: Color(0xFF34363A)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${i + 1}", color = medal ?: TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(modifier = Modifier.width(26.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    s.lines.take(2).forEach { Box(Modifier.size(9.dp).clip(CircleShape).background(lineColor(it))) }
                }
                Text(s.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.count}×", color = Color(0xFFDADDE2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * As últimas viagens: de onde para onde, linhas (metro) ou serviço e número (comboio, "IC 521"), tempo
 * a bordo, km e quando.
 */
@Composable
private fun RecentTripsCard(trips: List<com.example.metrotime.data.history.Trip>, today: LocalDate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        trips.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(1.dp).background(DIVIDER))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val key = if (t.mode == "cp") StatsCalc.trainKey(t) else t.lines.firstOrNull { it in StatsCalc.METRO_LINES } ?: "Azul"
                Box(
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(lineColor(key)),
                    contentAlignment = Alignment.Center
                ) {
                    if (t.mode == "cp") Text(key, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(StatsCalc.tripTitle(t), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(StatsCalc.tripDetail(t), color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(StatsCalc.whenText(t, today, ZONE), color = Color(0xFF8A8D93), fontSize = 12.sp)
            }
        }
    }
}

/** Viagens por hora, das 6h à meia-noite, com as horas a que mais viajas destacadas. */
@Composable
private fun HoursCard(hours: List<Int>) {
    val peaks = StatsCalc.peakHours(hours)
    val shown = (6..23).map { hours[it] }
    val max = (shown.maxOrNull() ?: 0).coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (peaks.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        append("Mais vezes por volta das ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${peaks[0]}h") }
                        if (peaks.size > 1) {
                            append(" e das ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${peaks[1]}h") }
                        }
                    },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                shown.forEachIndexed { i, v ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.04f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if ((i + 6) in peaks) MetrotimeBlue else MetrotimeBlue.copy(alpha = 0.45f))
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("6h", "12h", "18h", "0h").forEach { Text(it, color = Color(0xFF8A8D93), fontSize = 11.sp) }
            }
        }
    }
}

/** Classificação entre amigos (este mês ou desde sempre), contigo em destaque. */
@Composable
private fun FriendsCard(friends: List<FriendRank>, monthly: Boolean) {
    val ranked = friends.sortedByDescending { if (monthly) it.monthly else it.visits }.take(5)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        ranked.forEachIndexed { i, f ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(DIVIDER))
            val count = if (monthly) f.monthly else f.visits
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (f.isMe) MetrotimeBlue.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("${i + 1}", color = MEDALS.getOrNull(i) ?: TextSecondaryDark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
                Avatar(f.name, if (f.isMe) Color(0xFF2563EB) else friendColor(f.name), 34.dp)
                Text(
                    if (f.isMe) "Tu" else f.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (f.isMe) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(if (count == 1) "1 viagem" else "$count viagens", color = Color(0xFFDADDE2), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun Avatar(name: String, color: Color, size: Dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Sem viagens: como a app as reconhece. Com o registo desligado, um botão para o ligar (antes o texto
 * dizia "com a localização ligada", mas era preciso partilhar a localização com os amigos).
 */
@Composable
private fun EmptyStats(activity: ComponentActivity) {
    var tracking by remember { mutableStateOf(com.example.metrotime.prefs.Prefs.isTripTracking(activity)) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            com.example.metrotime.prefs.Prefs.setTripTracking(activity, true)
            tracking = true
            com.example.metrotime.work.WidgetUpdateService.requestFetch(activity, fromUi = true)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MetrotimeBlue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(ChartIcon, contentDescription = null, tint = MetrotimeBlue, modifier = Modifier.size(28.dp))
                }
                Text("Ainda sem viagens", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (tracking) "Quando andares de metro ou de comboio, a viagem aparece aqui."
                    else "Liga o registo de viagens para a app reconhecer as tuas viagens de metro e comboio. Fica só no telemóvel.",
                    color = TextSecondaryDark,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                if (!tracking) {
                    Button(
                        onClick = {
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                com.example.metrotime.prefs.Prefs.setTripTracking(activity, true)
                                tracking = true
                                com.example.metrotime.work.WidgetUpdateService.requestFetch(activity, fromUi = true)
                            } else {
                                permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MetrotimeBlue),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Registar as minhas viagens", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        HomeSection("Como funciona") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                listOf(
                    "No metro, a app vê em que estação perdes o sinal e em que estação o voltas a ter.",
                    "No comboio, segue o comboio em que vais pelo GPS, do embarque à saída.",
                    "Se a viagem podia ter sido a pé, pergunta-te. Se não confirmares, não conta.",
                    "Na página de uma estação, \"Iniciar viagem\" regista a partida à mão."
                ).forEachIndexed { i, text ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(DIVIDER))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF34363A)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Color(0xFFDADDE2), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(text, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

/** Os totais de um amigo no Firebase (com o mês e o dia da última viagem, se a app dele já os envia). */
private fun friendStats(snap: com.google.firebase.database.DataSnapshot): TripStats = TripStats(
    totalVisits = snap.child("totalVisits").intOrNull() ?: 0,
    totalDistanceKm = snap.child("totalDistanceKm").doubleOrNull() ?: 0.0,
    travelMinutes = snap.child("travelMinutes").intOrNull() ?: -1,
    currentStreak = snap.child("currentStreak").intOrNull() ?: 0,
    longestStreak = snap.child("longestStreak").intOrNull() ?: 0,
    topStations = snap.child("topStations").children.map { s ->
        Triple(s.child("name").stringOrNull() ?: "", s.child("type").stringOrNull() ?: "metro", s.child("count").intOrNull() ?: 0)
    },
    topLines = snap.child("topLines").children.map { s -> Pair(s.child("name").stringOrNull() ?: "", s.child("count").intOrNull() ?: 0) },
    monthlyVisits = snap.child("monthlyVisits").intOrNull() ?: 0,
    monthKey = snap.child("monthKey").stringOrNull(),
    lastTripDay = snap.child("lastTripDay").stringOrNull()
)

// ── Estatísticas de um amigo ─────────────────────────────────────────────────

/**
 * Um amigo: tu contra ele e os números dele. Só os totais (a app não guarda as viagens dos amigos,
 * por isso não há período nem gráficos).
 */
@Composable
private fun FriendStatsScreen(activity: ComponentActivity, friendUid: String, friendName: String) {
    var stats by remember { mutableStateOf<TripStats?>(null) }
    var failed by remember { mutableStateOf(false) }
    var badges by remember { mutableStateOf<List<Pair<com.example.metrotime.data.caderneta.BadgeDef, String>>>(emptyList()) }
    val myTotal = remember { TripHistoryManager.trips(activity).size }
    val today = remember { LocalDate.now(ZONE) }
    val first = friendName.split(" ").first()

    LaunchedEffect(friendUid) {
        // Os distintivos do amigo (do Firebase), dos mais raros para os mais comuns
        launch {
            val earned = com.example.metrotime.data.caderneta.BadgeCloud.of(friendUid) ?: return@launch
            badges = earned.days.keys
                .mapNotNull { id -> com.example.metrotime.data.caderneta.BadgeCatalog.byId[id]?.let { it to (earned.labels[id] ?: it.label) } }
                .sortedWith(compareByDescending<Pair<com.example.metrotime.data.caderneta.BadgeDef, String>> { it.first.tier.ordinal }.thenBy { it.first.name })
        }
        // Leitura tolerante; numa falha mostra o aviso (antes mostrava as MINHAS estatísticas como se fossem do amigo)
        try {
            stats = friendStats(
                com.google.firebase.database.FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                    .getReference("user_stats").child(friendUid).get().await()
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            failed = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StatsHeader(first, "Estatísticas desde que usa a app", onBack = { activity.finish() }) {
            Avatar(first, friendColor(first), 34.dp)
        }
        val s = stats
        when {
            failed -> Text(
                "Não foi possível carregar as estatísticas de $first.",
                color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(20.dp)
            )
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetrotimeBlue)
            }
            s.totalVisits == 0 -> Text(
                "$first ainda não tem viagens registadas.",
                color = TextSecondaryDark, fontSize = 14.sp, modifier = Modifier.padding(20.dp)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "versus") { VersusCard(first, s.totalVisits, myTotal) }
                item(key = "metrics") {
                    MetricsRow(
                        listOf(
                            Metric(RouteIcon, MetrotimeBlue, StatsCalc.distance(s.totalDistanceKm), "percorridos"),
                            // Versões antigas da app não enviam o tempo em viagem
                            Metric(ClockIcon, GREEN, if (s.travelMinutes >= 0) StatsCalc.duration(s.travelMinutes) else "—", "em viagem"),
                            s.streakOn(today).let { streak ->
                                Metric(
                                    PinIcon, ORANGE, if (streak == 1) "1 dia" else "$streak dias",
                                    if (s.longestStreak > 0) "seguidos (recorde ${s.longestStreak})" else "seguidos"
                                )
                            }
                        )
                    )
                }
                val lines = friendLines(s.topLines)
                if (badges.isNotEmpty()) {
                    item(key = "badges") {
                        HomeSection("Distintivos de $first · ${badges.size} de ${com.example.metrotime.data.caderneta.BadgeCatalog.ALL.size}") {
                            FriendBadges(badges)
                        }
                    }
                }
                if (lines.isNotEmpty()) item(key = "lines") { HomeSection("Linhas que $first mais usa") { LinesCard(lines) } }
                if (s.topStations.isNotEmpty()) {
                    item(key = "stations") {
                        val metro = remember { StationsRepo.loadStations(activity) }
                        HomeSection("Estações mais visitadas") {
                            StationsCard(s.topStations.map { (name, type, count) ->
                                val lines = if (type == "cp") listOf("CP")
                                else StatsCalc.metroLines(metro.firstOrNull { it.name == name }?.line.orEmpty())
                                StatsCalc.StationCount(name, type, lines, count)
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * As linhas guardadas no Firebase: as novas vêm por linha ("Azul", "CP"); as antigas com as linhas da
 * estação juntas ("Verde Vermelha") ou com a linha da CP. Junta-as por linha.
 */
private fun friendLines(raw: List<Pair<String, Int>>): List<StatsCalc.LineCount> {
    val counts = LinkedHashMap<String, Int>()
    for ((name, count) in raw) {
        val keys = if (StatsCalc.isTrainKey(name)) listOf(name) else StatsCalc.metroLines(name).ifEmpty { listOf("CP") }
        keys.forEach { counts[it] = (counts[it] ?: 0) + count }
    }
    return counts.entries.sortedByDescending { it.value }.take(5).map { (k, n) -> StatsCalc.LineCount(k, StatsCalc.lineName(k), n) }
}

/** Viagens: o amigo e tu, com barras em relação ao maior. */
@Composable
private fun VersusCard(friend: String, friendTotal: Int, myTotal: Int) {
    val max = maxOf(friendTotal, myTotal, 1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Viagens", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            listOf(Triple(friend, friendTotal, friendColor(friend)), Triple("Tu", myTotal, MetrotimeBlue)).forEach { (name, total, color) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(name, color = Color.White, fontSize = 14.sp, fontWeight = if (name == "Tu") FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("$total", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DIVIDER)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(total.toFloat() / max).clip(RoundedCornerShape(4.dp)).background(color))
                    }
                }
            }
        }
    }
}
