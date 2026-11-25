package com.example.metrotime.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.metrotime.R
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.plan.LastTrip
import com.example.metrotime.data.plan.MetroTrip
import com.example.metrotime.data.plan.Place
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.location.LocationResult
import com.example.metrotime.location.Locator
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.LiveMapActivity
import com.example.metrotime.ui.TripPlannerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Canais das notificações de viagem (autocarro seguido, aviso antes de sair, último metro para casa). */
internal object TravelNotify {
    private const val CHANNEL_STATUS = "travel_status"
    private const val CHANNEL_ALERTS = "travel_alerts"

    fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels(context: Context) {
        val m = manager(context)
        m.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Viagem em curso", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Autocarro que estás a seguir e aviso antes de sair"
            setShowBadge(false)
        })
        m.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Avisos de viagem", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Autocarro a chegar, prepara-te para sair e último metro para casa"
            enableVibration(true)
        })
    }

    fun open(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun stopAction(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, TravelAlertReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Notificação permanente com o estado, com um botão para cancelar. */
    fun status(context: Context, id: Int, title: String, text: String, open: PendingIntent, stopLabel: String, stop: PendingIntent) {
        ensureChannels(context)
        manager(context).notify(id, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, stopLabel, stop)
            .build())
    }

    /** Aviso que toca e vibra. */
    fun alert(context: Context, id: Int, title: String, text: String, open: PendingIntent) {
        ensureChannels(context)
        manager(context).notify(id, NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * "Avisar quando o autocarro estiver a chegar", para vários autocarros ao mesmo tempo (o sino de cada um na
 * paragem do mapa, até 5 h à frente). Cada aviso é para um autocarro concreto: o da hora
 * [Followed.scheduledTime] dessa linha e desse destino (ou, sem hora, o que chega perto de [Followed.dueAt]);
 * agarra-se ao GPS dele assim que aparece. O WidgetUpdateService chama [check] a cada 30 s; os que ainda faltam
 * muito só são procurados ao aproximar-se a hora.
 */
object BusFollow {

    data class Followed(
        val operator: String,
        val stopId: String,
        val stopName: String,
        val stopLat: Double,
        val stopLon: Double,
        val line: String,
        val destination: String,
        val vehicleId: String? = null,
        /** Hora do horário do autocarro escolhido nesta paragem ("20:07"); null = o que chegar perto de [dueAt]. */
        val scheduledTime: String? = null,
        /** Quando se espera que chegue à paragem (ms); atualizado a cada verificação. */
        val dueAt: Long = System.currentTimeMillis(),
        val alertMinutes: Int = 3,
        val lastEta: Int? = null,
        val alerted: Boolean = false,
        val id: String = "$operator|$stopId|$line|$destination|${scheduledTime ?: (dueAt / 60_000)}"
    )

    private const val PREFS = "bus_follow"
    private const val KEY_LIST = "follows"
    private const val NOTIF_STATUS = 4301
    private const val NOTIF_ALERT = 4302
    /** Até quantos autocarros ao mesmo tempo. */
    const val MAX_FOLLOWS = 8
    /** A menos disto da hora, o autocarro é procurado a cada 30 s (e o telemóvel fica acordado para avisar). */
    private const val SOON_MS = 30 * 60_000L
    /** Desiste deste tempo depois da hora (o autocarro não veio ou não aparece nas chegadas). */
    private const val GIVE_UP_MS = 45 * 60_000L
    const val ACTION_STOP = "com.example.metrotime.BUS_FOLLOW_STOP"
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val LISBON = ZoneId.of("Europe/Lisbon")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Followed> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> runCatching { fromJson(a.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun fromJson(o: JSONObject) = Followed(
        operator = o.getString("operator"),
        stopId = o.getString("stopId"),
        stopName = o.getString("stopName"),
        stopLat = o.getDouble("stopLat"),
        stopLon = o.getDouble("stopLon"),
        line = o.getString("line"),
        destination = o.optString("destination"),
        vehicleId = o.optString("vehicleId").ifBlank { null },
        scheduledTime = o.optString("scheduledTime").ifBlank { null },
        dueAt = o.optLong("dueAt", System.currentTimeMillis()),
        alertMinutes = o.optInt("alertMinutes", 3),
        lastEta = if (o.has("lastEta")) o.getInt("lastEta") else null,
        alerted = o.optBoolean("alerted"),
        id = o.getString("id")
    )

    private fun toJson(f: Followed): JSONObject = JSONObject()
        .put("operator", f.operator).put("stopId", f.stopId).put("stopName", f.stopName)
        .put("stopLat", f.stopLat).put("stopLon", f.stopLon)
        .put("line", f.line).put("destination", f.destination).put("vehicleId", f.vehicleId ?: "")
        .put("scheduledTime", f.scheduledTime ?: "").put("dueAt", f.dueAt)
        .put("alertMinutes", f.alertMinutes).put("alerted", f.alerted).put("id", f.id)
        .apply { f.lastEta?.let { put("lastEta", it) } }

    private fun saveAll(context: Context, list: List<Followed>) {
        prefs(context).edit {
            clear()
            if (list.isNotEmpty()) putString(KEY_LIST, JSONArray(list.map(::toJson)).toString())
        }
    }

    /** Há algum aviso ligado. */
    fun isActive(context: Context): Boolean = all(context).isNotEmpty()

    /** Há algum autocarro perto da hora: é preciso ver a cada 30 s, mesmo com o ecrã desligado. */
    fun hasSoon(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return all(context).any { it.dueAt - now <= SOON_MS }
    }

    /** Se a chegada [a] é o autocarro de [f]: o mesmo GPS, a mesma hora do horário, ou desta linha perto da hora. */
    fun matches(f: Followed, a: BusMapRepository.StopArrivalInfo, now: Long = System.currentTimeMillis()): Boolean {
        if (a.routeShortName != f.line) return false
        if (f.vehicleId != null && a.realtime && a.vehicleId == f.vehicleId) return true
        if (f.destination.isNotBlank() && a.destination != f.destination) return false
        if (f.scheduledTime != null) return a.scheduledTime == f.scheduledTime
        return kotlin.math.abs(now + a.etaMinutes * 60_000L - f.dueAt) <= 4 * 60_000L
    }

    /** Liga o aviso de [f] (ou atualiza-o, se já estava). false se já houver [MAX_FOLLOWS]. */
    fun start(context: Context, f: Followed): Boolean {
        val list = all(context).filterNot { it.id == f.id }
        if (list.size >= MAX_FOLLOWS) return false
        saveAll(context, (list + f).sortedBy { it.dueAt })
        WidgetUpdateService.requestFetch(context, fromUi = true)
        return true
    }

    /** Desliga o aviso [id]. */
    fun stop(context: Context, id: String) {
        val list = all(context).filterNot { it.id == id }
        saveAll(context, list)
        if (list.isEmpty()) TravelNotify.manager(context).cancel(NOTIF_STATUS) else showStatus(context, list, emptyMap())
    }

    /** Desliga todos (botão da notificação). */
    fun stopAll(context: Context) {
        saveAll(context, emptyList())
        TravelNotify.manager(context).cancel(NOTIF_STATUS)
    }

    private fun openStop(context: Context, f: Followed) = TravelNotify.open(
        context, NOTIF_STATUS,
        Intent(context, LiveMapActivity::class.java).putExtra(
            LiveMapActivity.EXTRA_BUS_STOP,
            listOf(f.operator, f.stopId, f.stopName.replace("|", "/"), f.stopLat, f.stopLon).joinToString("|")
        )
    )

    private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(LISBON).format(HM)

    /** "o 728 das 20:07" (a hora do horário, ou a prevista). */
    fun label(f: Followed): String = "o ${f.line} das ${f.scheduledTime ?: clock(f.dueAt)}"

    suspend fun check(context: Context) {
        val now = System.currentTimeMillis()
        val list = all(context).filter { now - it.dueAt < GIVE_UP_MS }
        if (list.isEmpty()) { stopAll(context); return }

        // Só se perguntam as chegadas das paragens com autocarros perto da hora
        val arrivals = mutableMapOf<Pair<String, String>, List<BusMapRepository.StopArrivalInfo>>()
        for ((operator, stopId) in list.filter { it.dueAt - now <= SOON_MS }.map { it.operator to it.stopId }.distinct()) {
            try {
                arrivals[operator to stopId] = BusMapRepository.getStopArrivals(context, stopId, operator, perLine = 6)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // sem rede: tenta na próxima volta
            }
        }

        val kept = mutableListOf<Followed>()
        val found = mutableMapOf<String, BusMapRepository.StopArrivalInfo>()
        for (f in list) {
            val arr = arrivals[f.operator to f.stopId]
            if (arr == null) { kept += f; continue }
            val match = arr.firstOrNull { f.vehicleId != null && it.realtime && it.vehicleId == f.vehicleId }
                ?: arr.filter { matches(f, it, now) }.minByOrNull { kotlin.math.abs(now + it.etaMinutes * 60_000L - f.dueAt) }
            if (match == null) {
                // Estava quase a chegar (ou já tinha avisado) e saiu das chegadas: já passou na paragem
                if (f.alerted || (f.lastEta != null && f.lastEta <= 2)) continue
                kept += f
                continue
            }
            var g = f.copy(lastEta = match.etaMinutes, dueAt = now + match.etaMinutes * 60_000L)
            // Agarra-se ao autocarro com GPS assim que aparece
            if (g.vehicleId == null && match.realtime && match.vehicleId != null) g = g.copy(vehicleId = match.vehicleId)
            if (!g.alerted && match.etaMinutes <= g.alertMinutes) {
                val eta = match.etaMinutes
                TravelNotify.alert(
                    context, NOTIF_ALERT + Math.floorMod(g.id.hashCode(), 1000), "O ${match.routeShortName} está a chegar",
                    "Chega a ${g.stopName} ${if (eta <= 0) "agora" else "dentro de $eta min"} (${match.status.text}).",
                    openStop(context, g)
                )
                g = g.copy(alerted = true)
            }
            kept += g
            found[g.id] = match
        }
        saveAll(context, kept.sortedBy { it.dueAt })
        if (kept.isEmpty()) TravelNotify.manager(context).cancel(NOTIF_STATUS) else showStatus(context, kept, found)
    }

    /** Uma notificação só: o próximo autocarro (quanto falta) e, por baixo, os outros avisos ligados. */
    private fun showStatus(context: Context, list: List<Followed>, found: Map<String, BusMapRepository.StopArrivalInfo>) {
        val now = System.currentTimeMillis()
        val first = list.minBy { it.dueAt }
        val m = found[first.id]
        val text = buildString {
            append(
                when {
                    m != null && m.etaMinutes <= 0 -> "A chegar a ${first.stopName} · ${m.status.text}"
                    m != null -> "Chega a ${first.stopName} em ${m.etaMinutes} min · ${m.status.text}"
                    first.dueAt - now > SOON_MS -> "Aviso ${first.alertMinutes} min antes de chegar a ${first.stopName} (${clock(first.dueAt)})."
                    else -> "Ainda sem o ${first.line} a caminho de ${first.stopName}. Continuo a ver."
                }
            )
            val others = list.filter { it.id != first.id }
            if (others.isNotEmpty()) append("\nTambém: " + others.joinToString(", ") { label(it).removePrefix("o ") })
        }
        TravelNotify.status(
            context, NOTIF_STATUS, "${first.line} → ${first.destination}".trimEnd(' ', '→'), text, openStop(context, first),
            if (list.size > 1) "Cancelar avisos" else "Cancelar aviso", TravelNotify.stopAction(context, NOTIF_STATUS, ACTION_STOP)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * "Avisa-me antes de sair". Dois modos:
 * - Pelo GPS (autocarro, à superfície): avisa quando a paragem de saída está a menos de [ALERT_DISTANCE_M].
 * - Pelo relógio (metro, sem GPS no subterrâneo): avisos a horas marcadas, antes de cada mudança e da chegada.
 * O WidgetUpdateService chama [check] a cada volta (5 s); a localização chega de 30 em 30 s.
 */
object ExitAlarm {

    data class Step(val atMs: Long, val title: String, val text: String)

    data class Alarm(
        val label: String,
        val targetName: String,
        val lat: Double? = null,
        val lon: Double? = null,
        val steps: List<Step> = emptyList(),
        val arriveAtMs: Long? = null,
        val startedAt: Long = System.currentTimeMillis()
    ) {
        val isGps: Boolean get() = lat != null && lon != null
    }

    private const val PREFS = "exit_alarm"
    private const val NOTIF_STATUS = 4401
    private const val NOTIF_ALERT = 4402
    private const val MAX_MS = 3 * 3600_000L
    const val ALERT_DISTANCE_M = 500.0
    const val ACTION_STOP = "com.example.metrotime.EXIT_ALARM_STOP"
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val LISBON = ZoneId.of("Europe/Lisbon")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): Alarm? {
        val raw = prefs(context).getString("alarm", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val steps = o.optJSONArray("steps") ?: JSONArray()
            Alarm(
                label = o.getString("label"),
                targetName = o.getString("targetName"),
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lon = if (o.has("lon")) o.getDouble("lon") else null,
                steps = (0 until steps.length()).map { i ->
                    val s = steps.getJSONObject(i)
                    Step(s.getLong("at"), s.getString("title"), s.getString("text"))
                },
                arriveAtMs = if (o.has("arriveAt")) o.getLong("arriveAt") else null,
                startedAt = o.optLong("startedAt", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun save(context: Context, a: Alarm) {
        val o = JSONObject().put("label", a.label).put("targetName", a.targetName).put("startedAt", a.startedAt)
        a.lat?.let { o.put("lat", it) }
        a.lon?.let { o.put("lon", it) }
        a.arriveAtMs?.let { o.put("arriveAt", it) }
        o.put("steps", JSONArray(a.steps.map { JSONObject().put("at", it.atMs).put("title", it.title).put("text", it.text) }))
        prefs(context).edit { clear(); putString("alarm", o.toString()) }
    }

    /** Pelo GPS; false sem permissão de localização. */
    fun startGps(context: Context, label: String, targetName: String, lat: Double, lon: Double): Boolean {
        if (!hasLocationPermission(context)) return false
        save(context, Alarm(label, targetName, lat = lat, lon = lon))
        WidgetUpdateService.requestFetch(context, fromUi = true)
        return true
    }

    /** Pelo relógio (metro). */
    fun startTimed(context: Context, label: String, targetName: String, steps: List<Step>, arriveAtMs: Long) {
        save(context, Alarm(label, targetName, steps = steps.sortedBy { it.atMs }, arriveAtMs = arriveAtMs))
        WidgetUpdateService.requestFetch(context, fromUi = true)
    }

    fun stop(context: Context) {
        prefs(context).edit { clear() }
        TravelNotify.manager(context).cancel(NOTIF_STATUS)
    }

    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(LISBON).format(HM)

    /** [location] só nas voltas em que o serviço a pediu (no modo GPS); no modo relógio não é usada. */
    fun check(context: Context, location: LocationResult?) {
        val a = current(context) ?: return
        val now = System.currentTimeMillis()
        if (now - a.startedAt > MAX_MS) { stop(context); return }
        val open = TravelNotify.open(context, NOTIF_STATUS, Intent(context, TripPlannerActivity::class.java))
        val stopAction = TravelNotify.stopAction(context, NOTIF_STATUS, ACTION_STOP)
        val p = prefs(context)

        if (a.isGps) {
            if (location == null) {
                if (!p.contains("lastDistance")) {
                    TravelNotify.status(context, NOTIF_STATUS, "Aviso antes de ${a.targetName}", "À espera da localização…", open, "Cancelar", stopAction)
                }
                return
            }
            val d = TripPlanner.metres(location.latitude, location.longitude, a.lat!!, a.lon!!)
            p.edit { putFloat("lastDistance", d.toFloat()) }
            if (d <= ALERT_DISTANCE_M) {
                TravelNotify.alert(
                    context, NOTIF_ALERT, "Prepara-te para sair",
                    "${a.targetName} está a ${TripPlanner.distanceText(d)}. Pede paragem no ${a.label}.", open
                )
                stop(context)
                return
            }
            TravelNotify.status(
                context, NOTIF_STATUS, "${a.label}: sair em ${a.targetName}",
                "Faltam ${TripPlanner.distanceText(d)}. Aviso quando estiveres a ${ALERT_DISTANCE_M.toInt()} m.",
                open, "Cancelar", stopAction
            )
            return
        }

        // Pelo relógio
        var next = p.getInt("nextStep", 0)
        while (next < a.steps.size && a.steps[next].atMs <= now) {
            val s = a.steps[next]
            TravelNotify.alert(context, NOTIF_ALERT + next, s.title, s.text, open)
            next++
        }
        p.edit { putInt("nextStep", next) }
        val arrive = a.arriveAtMs
        if (next >= a.steps.size && (arrive == null || now > arrive + 5 * 60_000L)) { stop(context); return }
        val left = arrive?.let { ((it - now) / 60_000L).coerceAtLeast(0) }
        TravelNotify.status(
            context, NOTIF_STATUS, "${a.label} até ${a.targetName}",
            if (arrive == null) "A contar o tempo da viagem."
            else "Chegada prevista às ${clock(arrive)}${if (left != null && left > 0) " (em $left min)" else ""}. Hora estimada: no subterrâneo não há GPS.",
            open, "Cancelar", stopAction
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * À noite (21h–2h30), longe de casa: avisa uma vez por noite quando faltam 30 min para a última forma de
 * voltar (o último metro ou o último comboio direto, o que for mais tarde). Chamado a cada minuto.
 */
object LastTripAlert {

    private const val PREFS = "last_trip_alert"
    private const val NOTIF = 4501
    private const val NEAR_HOME_M = 1_500.0
    private const val WARN_MIN = 30L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Onde estás: a última leitura com menos de 15 min, senão uma nova (no máximo a cada 10 min). */
    private suspend fun here(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val at = p.getLong("locAt", 0L)
        if (now - at < 10 * 60_000L) {
            return if (p.contains("lat")) p.getFloat("lat", 0f).toDouble() to p.getFloat("lon", 0f).toDouble() else null
        }
        if (!ExitAlarm.hasLocationPermission(context)) return null
        val loc = runCatching { Locator.getCurrentLocation(context) }.getOrNull()
        p.edit {
            putLong("locAt", now)
            if (loc != null) { putFloat("lat", loc.first.toFloat()); putFloat("lon", loc.second.toFloat()) } else { remove("lat"); remove("lon") }
        }
        return loc
    }

    suspend fun check(context: Context) {
        if (!Prefs.isLastTripAlerts(context)) return
        val home = LastTrip.home(context) ?: return
        val now = CpTime.now()
        val nowMin = MetroTrip.serviceMinutes(now.hour, now.minute)
        if (nowMin < 21 * 60 || nowMin > 26 * 60 + 30) return
        val serviceDay = (if (now.hour < 4) now.toLocalDate().minusDays(1) else now.toLocalDate()).toString()
        val p = prefs(context)
        if (p.getString("alertedDay", null) == serviceDay) return

        val (lat, lon) = here(context) ?: return
        val here = Place(Place.Kind.HERE, "", "Aqui", lat, lon)
        if (TripPlanner.metres(lat, lon, home.lat, home.lon) < NEAR_HOME_M) return

        // O comboio pede-se à CP no máximo a cada 15 min; fica guardado entre voltas
        if (System.currentTimeMillis() - p.getLong("trainCheckedAt", 0L) > 15 * 60_000L) {
            val train = runCatching { LastTrip.lastTrain(context, here, home, now) }.getOrNull()
            p.edit {
                putLong("trainCheckedAt", System.currentTimeMillis())
                if (train != null) {
                    putString("trainAt", train.departure.toString())
                    putString("trainText", "O ${train.label} sai de ${train.fromName} às ${train.departure.toLocalTime().toString().take(5)} para ${train.toName}.")
                } else { remove("trainAt"); remove("trainText") }
            }
        }
        val trainAt = p.getString("trainAt", null)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
        val trainLeft = trainAt?.let { Duration.between(now, it).toMinutes() }
        val metroOption = LastTrip.lastMetro(here, home)?.let { m ->
            val left = (m.serviceMinutes - nowMin).toLong()
            if (left < 0) null
            else Triple(
                left, "Último metro para casa às ${m.clock}",
                "Sai de ${m.fromName} às ${m.clock} para ${m.toName} (daqui a $left min). Hora estimada: os últimos metros saem dos terminais à 1:00."
            )
        }

        // A última forma de voltar: a que sai mais tarde e ainda não passou
        val options = listOfNotNull(
            metroOption,
            trainLeft?.takeIf { it >= 0 }?.let { left ->
                Triple(left, "Último comboio para casa daqui a $left min", p.getString("trainText", "") ?: "")
            }
        )
        val last = options.maxByOrNull { it.first } ?: return
        if (last.first > WARN_MIN) return
        val open = TravelNotify.open(
            context, NOTIF,
            TripPlannerActivity.newIntent(context, to = home.encode())
        )
        TravelNotify.alert(context, NOTIF, last.second, last.third, open)
        p.edit { putString("alertedDay", serviceDay) }
    }
}

/** Botões "Deixar de seguir" e "Cancelar" das notificações de viagem. */
class TravelAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BusFollow.ACTION_STOP -> BusFollow.stopAll(context)
            ExitAlarm.ACTION_STOP -> ExitAlarm.stop(context)
            LeaveAlarm.ACTION -> LeaveAlarm.fire(context, intent)
            LeaveAlarm.ACTION_CANCEL -> LeaveAlarm.cancel(context)
        }
    }
}
