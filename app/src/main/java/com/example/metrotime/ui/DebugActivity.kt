package com.example.metrotime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.metrotime.BuildConfig
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.alerts.MetroLineStatus
import com.example.metrotime.data.caderneta.Caderneta
import com.example.metrotime.data.caderneta.CadernetaRepository
import com.example.metrotime.data.caderneta.StampKind
import com.example.metrotime.data.caderneta.Tier
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpStrike
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.TrainMatcher
import com.example.metrotime.data.cp.TrainRide
import com.example.metrotime.data.history.TripDetector
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.data.history.TripRules
import com.example.metrotime.location.LocationResult
import com.example.metrotime.location.Locator
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.DarkBackground
import com.example.metrotime.ui.theme.MetrotimeTheme
import com.example.metrotime.ui.theme.TextSecondaryDark
import com.example.metrotime.work.LiveDebug
import com.example.metrotime.work.TrainTrackingDebugger
import com.example.metrotime.work.WidgetUpdateService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Debug (só admin): o que a app está a ver agora, por partes. Serviço e localização, em que metro estás (o detetor de
 * viagens), em que comboio estás (a procura e o TrainRide), as últimas viagens, os distintivos e a conta.
 * Atualiza-se de 2 em 2 s; o que vem da rede só se lê ao carregar no botão.
 */
class DebugActivity : ComponentActivity() {
    companion object {
        fun newIntent(context: Context) = Intent(context, DebugActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A entrada está na área de administrador; isto só impede de a abrir por outro lado
        if (!Prefs.isAdmin(this)) {
            finish()
            return
        }
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) { DebugScreen(this) }
            }
        }
    }
}

private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")
private val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val CARD = Color(0xFF2A2C31)
private val OK_GREEN = Color(0xFF7BD389)
private val AMBER = Color(0xFFE9C77A)
private val RED = Color(0xFFFF6B6B)
private val GREY = Color(0xFF8A8F98)

private fun hms(ms: Long): String = if (ms <= 0) "—" else Instant.ofEpochMilli(ms).atZone(ZONE).format(HMS)
private fun dayHm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZONE).format(DAY_HM)

private fun ago(ms: Long, now: Long): String {
    if (ms <= 0) return "nunca"
    val s = (now - ms) / 1000
    return when {
        s < 0 -> "agora"
        s < 60 -> "há $s s"
        s < 3600 -> "há ${s / 60} min"
        s < 86_400 -> "há ${s / 3600} h ${(s % 3600) / 60} min"
        else -> "há ${s / 86_400} dias"
    }
}

private fun metres(m: Double?): String = when {
    m == null -> "—"
    m < 1000 -> "${m.toInt()} m"
    else -> "%.1f km".format(m / 1000)
}

private fun yesNo(b: Boolean) = if (b) "sim" else "não"

/** Distância em metros entre dois pontos. */
private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

@Composable
private fun DebugScreen(activity: ComponentActivity) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val now = remember(tick) { System.currentTimeMillis() }
    // A leitura pedida à mão (botão) passa à frente da do serviço para as estações perto
    var manualFix by remember { mutableStateOf<LocationResult?>(null) }
    val serviceFix by LiveDebug.fix.collectAsState()
    val lastKnown = remember(tick / 15) { TripHistoryManager.getLastUserLocation(activity) }
    val here: Pair<Double, Double>? = manualFix?.let { it.latitude to it.longitude }
        ?: serviceFix?.let { it.lat to it.lon }
        ?: lastKnown

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { activity.finish() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Column {
                Text("Debug", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Atualiza de 2 em 2 s · ${hms(now)}", color = TextSecondaryDark, fontSize = 12.sp)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ServiceSection(activity, now, manualFix) { manualFix = it } }
            item { MetroSection(activity, now, tick, here) }
            item { TrainSection(activity, now) }
            item { TripsSection(activity, tick) }
            item { BadgesSection(activity) }
            item { AccountSection(activity) }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

// ── Peças ────────────────────────────────────────────────────────────────────

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = GREY, fontSize = 12.sp, lineHeight = 16.sp) }
        content()
    }
}

/** Uma linha "rótulo: valor". */
@Composable
private fun KV(label: String, value: String, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = TextSecondaryDark, fontSize = 13.sp, modifier = Modifier.weight(0.45f))
        Text(value, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
    }
}

/** O estado de uma parte numa frase, com a cor (verde: a acontecer; âmbar: quase; cinzento: nada). */
@Composable
private fun Status(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun Sub(text: String) {
    Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Note(text: String, color: Color = GREY) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
private fun Action(text: String, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) RED else Color.White),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) { Text(text, fontSize = 13.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) { content() }
}

// ── Serviço e localização ────────────────────────────────────────────────────

@Composable
private fun ServiceSection(context: Context, now: Long, manualFix: LocationResult?, onManualFix: (LocationResult?) -> Unit) {
    val loop by LiveDebug.loop.collectAsState()
    val fix by LiveDebug.fix.collectAsState()
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }

    Section("Serviço e localização", "O serviço em segundo plano dá uma volta a cada 5 s e lê a localização a cada 30 s (com a partilha ou o registo de viagens ligados).") {
        val l = loop
        if (l == null) {
            Status("O serviço ainda não correu desde que a app abriu", AMBER)
        } else {
            val late = now - l.atMs > 30_000
            Status(if (late) "Serviço parado (última volta ${ago(l.atMs, now)})" else "Serviço a correr", if (late) RED else OK_GREEN)
            KV("Última volta", "${hms(l.atMs)} · ${ago(l.atMs, now)}")
            KV("Voltas", "${l.loops}")
            KV("Ecrã", if (l.screenOn) "ligado" else "desligado")
            KV("Partilha com amigos", yesNo(l.sharing), if (l.sharing) OK_GREEN else GREY)
            KV("Registo de viagens", yesNo(l.tracking), if (l.tracking) OK_GREEN else RED)
        }
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val battery = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        Sub("Permissões")
        KV("Localização precisa", yesNo(fine), if (fine) OK_GREEN else RED)
        KV("Localização em segundo plano", yesNo(background), if (background) OK_GREEN else RED)
        KV("Notificações", yesNo(notifications), if (notifications) OK_GREEN else RED)
        KV("Sem otimização de bateria", yesNo(battery), if (battery) OK_GREEN else AMBER)

        Sub("Última leitura do serviço")
        val f = fix
        if (f == null) Note("Nenhuma ainda.")
        else {
            KV("Posição", "%.5f, %.5f".format(f.lat, f.lon))
            KV("Erro", "±${f.accuracyM.toInt()} m", if (f.accuracyM <= TripDetector.GOOD_ACCURACY_M) OK_GREEN else AMBER)
            KV("Velocidade (GPS)", f.speedKmh?.let { "%.0f km/h".format(it) } ?: "—")
            KV("Quando", "${hms(f.atMs)} · ${ago(f.atMs, now)}")
        }
        manualFix?.let { m ->
            Sub("Leitura pedida agora")
            KV("Posição", "%.5f, %.5f".format(m.latitude, m.longitude))
            KV("Erro", "±${m.accuracyM.toInt()} m", if (m.accuracyM <= TripDetector.GOOD_ACCURACY_M) OK_GREEN else AMBER)
            KV("Velocidade (GPS)", if (m.speedMps >= 0f) "%.0f km/h".format(m.speedMps * 3.6) else "—")
            KV("Quando", "${hms(m.timeMs)} · ${ago(m.timeMs, now)}")
        }
        readError?.let { Note(it, RED) }
        Actions {
            Action(if (reading) "A ler…" else "Ler localização agora") {
                if (reading) return@Action
                reading = true
                readError = null
                scope.launch {
                    val r = runCatching { withTimeoutOrNull(20_000) { Locator.getCurrentLocationWithSpeed(context) } }.getOrNull()
                    if (r == null) readError = "Não deu para ler a localização (permissão ou GPS desligado?)."
                    onManualFix(r ?: manualFix)
                    reading = false
                }
            }
        }
    }
}

// ── Metro ────────────────────────────────────────────────────────────────────

@Composable
private fun MetroSection(context: Context, now: Long, tick: Int, here: Pair<Double, Double>?) {
    val scope = rememberCoroutineScope()
    val det = remember(tick) { TripHistoryManager.debugDetectorState(context) }
    val pending = remember(tick) { TripHistoryManager.pendingTrips(context) }
    var lines by remember { mutableStateOf<List<MetroLineStatus>?>(null) }
    var linesNote by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    fun name(id: String?) = id?.let { MapConfiguration.STATION_COORDS[it]?.name ?: it } ?: "—"

    Section("Metro · em que metro estou", "No subterrâneo não há GPS: a viagem conta quando voltas a aparecer noutra estação, num tempo que bate com o do metro.") {
        val anchorAlive = det.anchorId != null && now - det.anchorLastMs <= TripDetector.ANCHOR_TTL_MS
        when {
            det.riding -> {
                Status("Em viagem: de ${name(det.rideFromId)}", OK_GREEN)
                KV("Partida", hms(det.rideStartMs))
                KV("Última estação onde apareceste", name(det.rideLastId))
                KV("Chegaste lá", hms(det.rideLastFirstMs))
                KV("Visto lá", ago(det.rideLastMs, now))
                KV("Leituras cá fora seguidas", "${det.rideOutside} de ${TripDetector.OUTSIDE_FIXES}", if (det.rideOutside > 0) AMBER else Color.White)
                val stay = TripDetector.STATION_TTL_MS - (now - det.rideLastFirstMs)
                KV("Acaba sozinha em", if (stay > 0) "${stay / 60_000} min (30 min na mesma estação)" else "já devia ter acabado")
            }
            anchorAlive -> {
                Status("Origem possível: ${name(det.anchorId)}", AMBER)
                KV("Ao pé dela", ago(det.anchorLastMs, now))
                KV("Caduca em", "${(TripDetector.ANCHOR_TTL_MS - (now - det.anchorLastMs)) / 60_000} min")
                KV("Leituras à superfície desde lá", "${det.surfaceFixes} (3 ou mais: não foi metro)", if (det.surfaceFixes >= 3) RED else Color.White)
                Note("Para contar: desaparece (sem leituras boas 4 min ou mais) e volta a aparecer noutra estação.")
            }
            else -> Status("Fora do metro", GREY)
        }
        val gap = det.lastGoodFixMs > 0 && now - det.lastGoodFixMs >= TripDetector.GAP_MS
        KV("Última leitura boa (≤${TripDetector.GOOD_ACCURACY_M.toInt()} m)", ago(det.lastGoodFixMs, now), if (gap) AMBER else Color.White)
        if (gap) Note("Sem leituras boas há 4 min ou mais: para o detetor, estás sem sinal (no subterrâneo?).", AMBER)
        det.manualTripId?.let { KV("Iniciar viagem (à espera do destino)", it, AMBER) }

        Sub("Estações do metro perto")
        if (here == null) Note("Sem localização.")
        else {
            MapConfiguration.STATION_COORDS.values
                .map { it to distanceM(here.first, here.second, it.lat, it.lon) }
                .sortedBy { it.second }
                .take(3)
                .forEach { (s, d) ->
                    KV(s.name, metres(d) + if (d <= TripDetector.NEAR_M) " · ao pé" else "", if (d <= TripDetector.NEAR_M) OK_GREEN else Color.White)
                }
        }

        Sub("Por confirmar (${pending.size})")
        if (pending.isEmpty()) Note("Nenhuma.")
        pending.forEach { p ->
            val left = (TripRules.PENDING_TTL_MS - (now - p.createdMs)) / 3_600_000
            KV("${p.trip.fromName} → ${p.trip.toName ?: "?"}", "${hms(p.trip.startMs)} · expira em ${left} h")
        }

        Sub("Estado das linhas")
        val l = lines
        if (l == null) Note(linesNote ?: "Carrega em \"Ler estado das linhas\".")
        else {
            linesNote?.let { Note(it) }
            l.forEach { s -> KV("Linha ${s.line}", if (s.ok) "normal" else s.message, if (s.ok) OK_GREEN else RED) }
        }

        Actions {
            Action("Ler estado das linhas") {
                scope.launch {
                    linesNote = "A ler…"
                    val operating = RemoteArrivalsSource.isMetroOperating()
                    val r = runCatching { withTimeoutOrNull(10_000) { RemoteArrivalsSource.fetchLineStatus() } }.getOrNull()
                    lines = r?.takeIf { it.isNotEmpty() }
                    linesNote = when {
                        r.isNullOrEmpty() -> "Não deu para ler o estado das linhas."
                        operating -> "Metro a funcionar · lido às ${hms(System.currentTimeMillis())}"
                        else -> "Metro fechado (o estado pode não interessar) · lido às ${hms(System.currentTimeMillis())}"
                    }
                }
            }
            Action("Limpar o detetor", danger = true) { confirmReset = true }
        }
        Note(
            "Regras: leituras com erro até ${TripDetector.GOOD_ACCURACY_M.toInt()} m · ao pé = ${TripDetector.NEAR_M.toInt()} m · " +
                "sem sinal = ${TripDetector.GAP_MS / 60_000} min · reaparecer até ${TripDetector.REAPPEAR_M.toInt()} m · " +
                "a origem dura ${TripDetector.ANCHOR_TTL_MS / 3_600_000} h · uma viagem sem notícias acaba em ${TripDetector.RIDE_TTL_MS / 60_000} min."
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Limpar o detetor do metro?") },
            text = { Text("Esquece a origem possível e a viagem em curso. As viagens já guardadas ficam.") },
            confirmButton = {
                TextButton(onClick = {
                    TripHistoryManager.debugResetDetector(context)
                    confirmReset = false
                }) { Text("Limpar", color = RED) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } }
        )
    }
}

// ── Comboio ──────────────────────────────────────────────────────────────────

/** O que se leu da CP no Firebase (botão). */
private data class CpSnapshot(
    val running: Int?, val updatedAt: Long?, val source: String?, val suppressed: Int?,
    val strikeNotice: Boolean, val strikeFelt: Boolean, val notices: Int, val readAt: Long
)

@Composable
private fun TrainSection(context: Context, now: Long) {
    val onBoard by LiveDebug.onBoard.collectAsState()
    val search by LiveDebug.search.collectAsState()
    val logs by TrainTrackingDebugger.debugLogs.collectAsState()
    val scope = rememberCoroutineScope()
    var cp by remember { mutableStateOf<CpSnapshot?>(null) }
    var cpNote by remember { mutableStateOf<String?>(null) }

    Section("Comboio · em que comboio estou", "Procura-se comboio a ${TrainMatcher.MIN_SPEED_KMH.toInt()} km/h ou mais e a menos de ${TrainMatcher.MAX_RAIL_DISTANCE_M.toInt()} m da linha; entra-se quando o mesmo comboio bate ${TrainMatcher.LOCK_CHECKS} vezes seguidas.") {
        val b = onBoard
        if (b == null) Status("Em nenhum comboio", GREY)
        else {
            Status("A bordo: ${b.name ?: b.id}", OK_GREEN)
            KV("Destino", b.dest ?: "—")
            KV("Serviço · número", "${b.service ?: "?"} · ${b.id}")
            KV("Embarque", "${b.boarding ?: "?"} · ${hms(b.startMs)}")
            KV("Percorrido", "%.1f km".format(b.pathKm))
            KV("Velocidade máxima (20 s)", b.topKmh?.let { "%.0f km/h".format(it) } ?: "—")
            KV("Paragens · traçado", "${b.stops} · ${if (b.hasTrack) "com traçado" else "sem traçado"}", if (b.hasTrack && b.stops > 0) Color.White else AMBER)
            KV("Distância à linha", metres(b.railM), if ((b.railM ?: 0.0) <= TrainRide.ON_RAIL_M) OK_GREEN else AMBER)
            KV("Distância ao comboio", b.gapM?.let { metres(it) } ?: "sem posição dele")
            KV("Idade da posição do comboio", b.trainAgeSec?.let { "${it.toInt()} s" } ?: "—", if ((b.trainAgeSec ?: 0.0) > TrainRide.FRESH_SEC) AMBER else Color.White)
            KV("Última estação com o comboio", b.lastStation ?: "—")
            Sub("Contadores para sair")
            KV("Longe da linha e da estação", "${b.awayFixes} de ${TrainRide.AWAY_FIXES}", if (b.awayFixes > 0) AMBER else Color.White)
            KV("Na estação, fora da linha", "${b.stationOffRailFixes} de ${TrainRide.STATION_OFF_RAIL_FIXES}", if (b.stationOffRailFixes > 0) AMBER else Color.White)
            KV("Comboio a mais de ${(TrainRide.APART_M / 1000).toInt()} km", "${b.apartChecks} de ${TrainRide.APART_CHECKS}", if (b.apartChecks > 0) AMBER else Color.White)
            KV("Atualizado", ago(b.atMs, now))
        }

        Sub("Última procura")
        val s = search
        if (s == null) Note("Ainda não se procurou (só com a partilha ou o registo de viagens ligados).")
        else {
            Note(s.verdict, if (s.candidateId != null) OK_GREEN else Color.White)
            KV("Quando", "${hms(s.atMs)} · ${ago(s.atMs, now)}")
            KV("Velocidade (média de 3)", "%.0f km/h".format(s.speedKmh), if (s.speedKmh >= TrainMatcher.MIN_SPEED_KMH) OK_GREEN else Color.White)
            s.railM?.let { KV("Distância à linha", metres(it), if (it <= TrainMatcher.MAX_RAIL_DISTANCE_M) OK_GREEN else RED) }
            s.nearbyTrains?.let { KV("Comboios a menos de 15 km", "$it") }
            s.candidateId?.let { KV("Candidato", "$it · ${s.hits} de ${TrainMatcher.LOCK_CHECKS}", AMBER) }
            s.gapM?.let { KV("Distância ao candidato", metres(it)) }
        }

        Sub("Dados da CP")
        val c = cp
        if (c == null) Note(cpNote ?: "Carrega em \"Ler dados da CP\".")
        else {
            KV("Comboios a circular", c.running?.toString() ?: "—")
            KV("Atualizados", c.updatedAt?.let { ago(it, now) } ?: "—", if (c.updatedAt != null && now - c.updatedAt > 3 * 60_000) RED else Color.White)
            KV("Fonte", c.source ?: "—")
            KV("Suprimidos", c.suppressed?.toString() ?: "—")
            KV("Avisos da CP a decorrer", "${c.notices}")
            KV("Aviso de greve hoje", yesNo(c.strikeNotice), if (c.strikeNotice) AMBER else Color.White)
            KV("Greve nota-se nos comboios", yesNo(c.strikeFelt), if (c.strikeFelt) AMBER else Color.White)
            KV("Dia de greve (distintivo)", yesNo(c.strikeNotice && c.strikeFelt), if (c.strikeNotice && c.strikeFelt) OK_GREEN else GREY)
            Note("Lido às ${hms(c.readAt)}. Dia de greve: aviso de greve da CP para hoje, ${CpStrike.MIN_SUPPRESSED} ou mais suprimidos e mais suprimidos do que a circular.")
        }
        Actions {
            Action("Ler dados da CP") {
                scope.launch {
                    cpNote = "A ler…"
                    cp = readCp()
                    cpNote = if (cp == null) "Não deu para ler o Firebase." else null
                }
            }
            if (b != null) {
                Action("Não estou neste comboio", danger = true) {
                    runCatching { context.startService(Intent(context, WidgetUpdateService::class.java).setAction(WidgetUpdateService.ACTION_TRAIN_NOT_ME)) }
                }
            }
        }

        Sub("Registo (${logs.size})")
        if (logs.isEmpty()) Note("Vazio.")
        logs.take(30).forEach { Text(it, color = Color(0xFFC9CDD3), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp) }
        if (logs.isNotEmpty()) Actions { Action("Limpar o registo") { TrainTrackingDebugger.clear() } }
    }
}

private suspend fun readCp(): CpSnapshot? = runCatching {
    withTimeoutOrNull(12_000) {
        val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
        val vehicles = db.getReference("cp_vehicles").child("count").get().await().value as? Number
        val updated = db.getReference("cp_vehicles").child("updatedAt").get().await().value as? Number
        val source = db.getReference("cp_vehicles").child("source").get().await().value as? String
        val suppressed = db.getReference("cp_suppressions").child("count").get().await().value as? Number
        val notices = CpRepository.getNotices()
        val announced = CpStrike.announced(notices, CpTime.today())
        val felt = suppressed != null && vehicles != null && CpStrike.felt(suppressed.toInt(), vehicles.toInt())
        CpSnapshot(vehicles?.toInt(), updated?.toLong(), source, suppressed?.toInt(), announced, felt, notices.size, System.currentTimeMillis())
    }
}.getOrNull()

// ── Viagens ──────────────────────────────────────────────────────────────────

@Composable
private fun TripsSection(context: Context, tick: Int) {
    val all = remember(tick / 5) { TripHistoryManager.trips(context) }
    val recent = remember(all) { all.sortedByDescending { it.startMs }.take(15) }
    Section("Viagens", "As últimas 15, como ficaram guardadas.") {
        KV("Guardadas", "${all.size} (metro ${all.count { it.mode == "metro" }} · comboio ${all.count { it.mode == "cp" }})")
        recent.forEach { t ->
            val train = listOfNotNull(t.service, t.trainNumber).joinToString(" ").takeIf { it.isNotEmpty() }
            val extra = listOfNotNull(
                "%.1f km".format(t.km),
                t.source,
                train,
                t.delayMin?.let { if (it <= 0) "a horas" else "+$it min" },
                t.topKmh?.let { "máx. %.0f km/h".format(it) },
                if (t.strikeDay) "greve" else null
            ).joinToString(" · ")
            Column(Modifier.padding(top = 6.dp)) {
                Text(
                    "${if (t.mode == "metro") "Metro" else "Comboio"} · ${t.fromName} → ${t.toName ?: "?"}",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text("${dayHm(t.startMs)}${t.endMs?.let { "–" + hms(it).take(5) } ?: ""} · $extra", color = GREY, fontSize = 12.sp)
            }
        }
        if (recent.isEmpty()) Note("Nenhuma.")
    }
}

// ── Distintivos ──────────────────────────────────────────────────────────────

@Composable
private fun BadgesSection(context: Context) {
    var reload by remember { mutableIntStateOf(0) }
    var caderneta by remember { mutableStateOf<Caderneta?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadedAt by remember { mutableStateOf(0L) }
    var onlyMissing by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        loading = true
        caderneta = runCatching { CadernetaRepository.load(context) }.getOrNull()
        loadedAt = System.currentTimeMillis()
        loading = false
    }
    Section("Distintivos e caderneta", "Calculados agora a partir das viagens (com os guardados de antes). Em cada um por ganhar, porquê.") {
        val c = caderneta
        when {
            loading && c == null -> Note("A calcular…")
            c == null -> Note("Não deu para calcular a caderneta.", RED)
            else -> {
                val stamps = c.book.stamps.values
                fun count(prefix: String, kind: StampKind) = stamps.count { it.key.startsWith(prefix) && it.kind == kind }
                KV("Ganhos", "${c.badges.count { it.earned }} de ${c.badges.size}", OK_GREEN)
                KV("Metro: cheios · de passagem", "${count("m:", StampKind.FULL)} · ${count("m:", StampKind.PASSED)}")
                KV("CP: cheios · de passagem", "${count("c:", StampKind.FULL)} · ${count("c:", StampKind.PASSED)}")
                KV("Estações da CP conhecidas", "${c.cpStations.size}", if (c.cpStations.isEmpty()) RED else Color.White)
                KV("Linhas da CP conhecidas", "${c.cpLines.size}", if (c.cpLines.isEmpty()) AMBER else Color.White)
                Actions {
                    Action(if (onlyMissing) "Mostrar todos" else "Só os por ganhar") { onlyMissing = !onlyMissing }
                }
                Tier.entries.forEach { tier ->
                    val inTier = c.badges.filter { it.def.tier == tier }
                    Sub("${tier.plural} (${inTier.count { it.earned }} de ${inTier.size})")
                    inTier.filter { !onlyMissing || !it.earned }.forEach { s ->
                        val detail = listOfNotNull(
                            s.label.takeIf { it.isNotEmpty() },
                            s.progressText,
                            s.def.soon.takeIf { it }?.let { "ainda não se ganha" }
                        ).joinToString(" · ")
                        Column(Modifier.padding(top = 2.dp)) {
                            Text(
                                "${if (s.earned) "✓" else "·"} ${s.def.name}",
                                color = if (s.earned) OK_GREEN else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            if (detail.isNotEmpty()) Text(detail, color = GREY, fontSize = 12.sp)
                            s.story?.let { Text(it, color = AMBER, fontSize = 12.sp) }
                            // Porque não está ganho (ou de onde vem um guardado)
                            s.why?.let { Text(if (s.earned) it else "Porquê: $it", color = if (s.earned) GREY else Color(0xFFFFA07A), fontSize = 12.sp, lineHeight = 16.sp) }
                        }
                    }
                }
                Note("Calculado às ${hms(loadedAt)}.")
            }
        }
        Actions { Action(if (loading) "A calcular…" else "Recalcular") { if (!loading) reload++ } }
    }
}

// ── Conta e app ──────────────────────────────────────────────────────────────

@Composable
private fun AccountSection(context: Context) {
    var connected by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(Unit) {
        val ref = FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                connected = snapshot.getValue(Boolean::class.java) == true
            }
            override fun onCancelled(error: DatabaseError) {
                connected = false
            }
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }
    val user = FirebaseAuth.getInstance().currentUser
    Section("Conta e app") {
        KV("Versão", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        KV("Sessão", if (user != null) "com sessão" else "sem sessão", if (user != null) OK_GREEN else AMBER)
        KV("UID", user?.uid ?: "—")
        KV("Admin", yesNo(Prefs.isAdmin(context)))
        KV("Firebase", when (connected) { null -> "a ver…"; true -> "ligado"; false -> "desligado" }, if (connected == true) OK_GREEN else AMBER)
    }
}
