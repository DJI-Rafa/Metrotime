package com.example.metrotime.data.history

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.MetroNetwork
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Visita a uma estação, do histórico antigo (só para migrar para viagens). */
data class StationVisit(
    val stationId: String,
    val stationName: String,
    val type: String,           // "metro" or "cp"
    val lineName: String,       // e.g. "Azul", "Urbano", etc.
    val lat: Double,
    val lon: Double,
    val timestampMs: Long,
    val confirmed: Boolean = true
)

/** Estação para a deteção dos comboios (metro e CP). */
data class NearbyStation(
    val id: String,
    val name: String,
    val type: String,       // "metro" or "cp"
    val lineName: String,
    val lat: Double,
    val lon: Double
)

/**
 * Histórico de viagens: guarda as viagens num ficheiro (trips.json, escrito de forma atómica e mantido
 * em memória), corre o detetor de viagens de metro com cada localização, junta as viagens dos comboios
 * seguidos e as começadas à mão, e envia os totais para o Firebase (para os amigos).
 */
object TripHistoryManager {

    private const val PREFS_FILE = "trip_history"
    private const val FILE = "trips.json"
    // Histórico antigo (visitas), migrado uma vez
    private const val KEY_VISITS = "confirmed_visits"
    private const val KEY_PENDING_OLD = "pending_detections"
    private const val KEY_STREAK = "streak_data"
    private const val KEY_MIGRATED = "migrated_to_trips"
    private const val KEY_DETECTOR = "detector_state"
    private const val KEY_LAST_USER_LAT = "last_user_lat"
    private const val KEY_LAST_USER_LON = "last_user_lon"
    private const val KEY_LAST_USER_TIME = "last_user_time"

    private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

    private class Data(val trips: MutableList<Trip>, val pending: MutableList<PendingTrip>, var legacyKm: Double)

    @Volatile private var cache: Data? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    @Synchronized
    private fun data(context: Context): Data {
        cache?.let { return it }
        val loaded = runCatching { parse(String(file(context).readFully(), Charsets.UTF_8)) }.getOrNull()
            ?: Data(mutableListOf(), mutableListOf(), 0.0)
        migrate(context, loaded)
        cache = loaded
        return loaded
    }

    /** As visitas antigas passam a viagens (uma vez); os km extra dos comboios ficam à parte, sem data. */
    private fun migrate(context: Context, d: Data) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val visits = runCatching { parseVisits(p.getString(KEY_VISITS, "[]") ?: "[]") }.getOrDefault(emptyList())
        d.trips.addAll(TripRules.fromVisits(visits))
        d.trips.sortBy { it.startMs }
        d.legacyKm += p.getFloat("extra_distance_km", 0f).toDouble()
        save(context, d)
        // As deteções antigas (ficar 3 min ao pé de uma estação) deixam de existir
        p.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_PENDING_OLD).apply()
    }

    // ── Viagens ─────────────────────────────────────────────────────────────

    fun trips(context: Context): List<Trip> = synchronized(this) { data(context).trips.toList() }

    /** Km dos comboios do histórico antigo (sem data; só entram em "sempre"). */
    fun legacyKm(context: Context): Double = synchronized(this) { data(context).legacyKm }

    /** Recorde de dias seguidos do histórico antigo, para não se perder. */
    fun legacyLongestStreak(context: Context): Int =
        runCatching { JSONObject(prefs(context).getString(KEY_STREAK, null) ?: "{}").optInt("longest", 0) }.getOrDefault(0)

    /**
     * Junta uma viagem: ignora duplicados (mesma origem há menos de 30 min) e junta à anterior se for a
     * continuação dela (troço à superfície a meio de uma viagem de metro). false se não entrou.
     */
    @Synchronized
    fun addTrip(context: Context, trip: Trip): Boolean {
        val d = data(context)
        val last = d.trips.lastOrNull { it.mode == trip.mode }
        val chained = last?.let { TripRules.chain(it, trip) }
        if (chained != null) {
            d.trips[d.trips.indexOf(last)] = chained
        } else {
            if (TripRules.isDuplicate(d.trips, trip)) return false
            d.trips.add(trip)
            d.trips.sortBy { it.startMs }
        }
        save(context, d)
        syncStatsToFirebase(context)
        return true
    }

    // ── Viagens por confirmar ───────────────────────────────────────────────

    /** As que esperam confirmação; as com mais de 24 h saem (não contam). */
    @Synchronized
    fun pendingTrips(context: Context): List<PendingTrip> {
        val d = data(context)
        val now = System.currentTimeMillis()
        if (d.pending.removeAll { now - it.createdMs > TripRules.PENDING_TTL_MS }) save(context, d)
        return d.pending.toList()
    }

    @Synchronized
    fun confirmPending(context: Context, tripId: String) {
        val d = data(context)
        val p = d.pending.firstOrNull { it.trip.id == tripId } ?: return
        d.pending.remove(p)
        save(context, d)
        addTrip(context, p.trip.copy(source = "confirmed"))
    }

    @Synchronized
    fun dismissPending(context: Context, tripId: String) {
        val d = data(context)
        if (d.pending.removeAll { it.trip.id == tripId }) save(context, d)
    }

    // ── Deteção ─────────────────────────────────────────────────────────────

    private val metroStops: List<TripDetector.MetroStop> by lazy {
        MapConfiguration.STATION_COORDS.values.map { TripDetector.MetroStop(it.id, it.lat, it.lon) }
    }

    /**
     * Cada localização (ou a falta dela, no subterrâneo) com o registo de viagens ligado. Devolve uma
     * viagem que ficou à espera de confirmação, para avisar.
     */
    @Synchronized
    fun onLocation(context: Context, fix: TripDetector.Fix?): PendingTrip? {
        if (fix != null && fix.accuracyM <= TripDetector.GOOD_ACCURACY_M) {
            if (isTeleport(context, fix)) return null
            prefs(context).edit()
                .putFloat(KEY_LAST_USER_LAT, fix.lat.toFloat())
                .putFloat(KEY_LAST_USER_LON, fix.lon.toFloat())
                .putLong(KEY_LAST_USER_TIME, fix.timeMs)
                .apply()
        }
        val (state, detection) = TripDetector.step(detectorState(context), fix, metroStops)
        saveDetectorState(context, state)
        detection ?: return null

        val trip = TripRules.metroTrip("m_${detection.startMs}", detection.from, detection.to, detection.startMs, detection.endMs, "auto")
        // Começada à mão: esta deteção dá-lhe o destino (mesmo que fraca, a origem já foi confirmada por ti)
        val manual = detection.manualTripId?.let { id -> data(context).trips.firstOrNull { it.id == id } }
        val closed = manual?.let { TripRules.close(it, trip) }
        if (closed != null) {
            val d = data(context)
            d.trips[d.trips.indexOf(manual)] = closed
            save(context, d)
            syncStatsToFirebase(context)
            return null
        }
        if (detection.strong) {
            addTrip(context, trip)
            return null
        }
        val pending = PendingTrip(trip, System.currentTimeMillis())
        val d = data(context)
        if (d.pending.none { it.trip.fromId == trip.fromId && kotlin.math.abs(it.trip.startMs - trip.startMs) < TripRules.DUPLICATE_WINDOW_MS }) {
            d.pending.add(pending)
            save(context, d)
            return pending
        }
        return null
    }

    /** Mais de 300 km/h desde a última leitura em menos de 10 min: localização falsa, ignorada. */
    private fun isTeleport(context: Context, fix: TripDetector.Fix): Boolean {
        val p = prefs(context)
        if (!p.contains(KEY_LAST_USER_LAT)) return false
        val dt = (fix.timeMs - p.getLong(KEY_LAST_USER_TIME, 0L)) / 1000.0
        if (dt <= 0 || dt >= 600) return false
        val metres = MetroNetwork.haversineKm(fix.lat, fix.lon, p.getFloat(KEY_LAST_USER_LAT, 0f).toDouble(), p.getFloat(KEY_LAST_USER_LON, 0f).toDouble()) * 1000
        return metres / dt > 85.0
    }

    /**
     * Iniciar viagem à mão numa estação do metro: conta logo com a origem; a próxima estação detetada
     * dá-lhe o destino. null se já houver uma viagem dali há menos de 30 min.
     */
    @Synchronized
    fun startManualTrip(context: Context, apiId: String, direction: String?): Trip? {
        val now = System.currentTimeMillis()
        val trip = Trip(
            id = "h_$now", mode = "metro",
            fromId = apiId, fromName = com.example.metrotime.data.MetroLines.nameOf(apiId),
            lines = MetroNetwork.linesAt(apiId), startMs = now, source = "manual", direction = direction
        )
        if (!addTrip(context, trip)) return null
        saveDetectorState(context, TripDetector.startManual(detectorState(context), apiId, now, trip.id))
        return trip
    }

    /**
     * Comboio seguido pelo GPS do princípio ao fim: viagem com o percurso medido, o serviço (Alfa, IC,
     * Urbano…) e o número do comboio.
     */
    fun trainTrip(context: Context, from: NearbyStation?, to: NearbyStation?, startMs: Long, endMs: Long, km: Double, service: String?, trainNumber: String?) {
        val origin = from ?: to ?: return
        addTrip(
            context,
            Trip(
                id = "c_$startMs", mode = "cp",
                fromId = origin.id, fromName = origin.name,
                toId = to?.takeIf { from != null && it.id != from.id }?.id, toName = to?.takeIf { from != null && it.id != from.id }?.name,
                lines = listOf(service ?: "CP"), startMs = startMs, endMs = endMs, km = km, source = "train",
                service = service, trainNumber = trainNumber
            )
        )
    }

    private fun detectorState(context: Context): TripDetector.State = runCatching {
        val o = JSONObject(prefs(context).getString(KEY_DETECTOR, null) ?: return@runCatching TripDetector.State())
        TripDetector.State(
            anchorId = o.optString("anchorId").ifEmpty { null },
            anchorLastMs = o.optLong("anchorLastMs"),
            lastGoodFixMs = o.optLong("lastGoodFixMs"),
            surfaceFixes = o.optInt("surfaceFixes"),
            manualTripId = o.optString("manualTripId").ifEmpty { null }
        )
    }.getOrDefault(TripDetector.State())

    private fun saveDetectorState(context: Context, s: TripDetector.State) {
        val o = JSONObject()
            .put("anchorId", s.anchorId ?: "")
            .put("anchorLastMs", s.anchorLastMs)
            .put("lastGoodFixMs", s.lastGoodFixMs)
            .put("surfaceFixes", s.surfaceFixes)
            .put("manualTripId", s.manualTripId ?: "")
        prefs(context).edit().putString(KEY_DETECTOR, o.toString()).apply()
    }

    // ── Localização ─────────────────────────────────────────────────────────

    fun getLastUserLocation(context: Context): Pair<Double, Double>? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_LAST_USER_LAT)) return null
        return prefs.getFloat(KEY_LAST_USER_LAT, 0f).toDouble() to prefs.getFloat(KEY_LAST_USER_LON, 0f).toDouble()
    }

    // ── Firebase (os amigos veem os totais) ─────────────────────────────────

    /**
     * Totais para os amigos. Vai com o mês e o dia da última viagem: quem lê sabe se "este mês" e os dias
     * seguidos ainda são de agora (antes ficavam presos no último valor enviado).
     */
    fun syncStatsToFirebase(context: Context) {
        val user = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }.getOrNull() ?: return
        val stats = computeStats(context)
        val updates = mapOf(
            "totalVisits" to stats.totalVisits,
            "totalDistanceKm" to stats.totalDistanceKm,
            "travelMinutes" to stats.travelMinutes,
            "currentStreak" to stats.currentStreak,
            "longestStreak" to stats.longestStreak,
            "topStations" to stats.topStations.map { mapOf("name" to it.first, "type" to it.second, "count" to it.third) },
            "topLines" to stats.topLines.map { mapOf("name" to it.first, "count" to it.second) },
            "monthlyVisits" to stats.monthlyVisits,
            "monthKey" to stats.monthKey,
            "lastTripDay" to stats.lastTripDay
        )
        com.google.firebase.database.FirebaseDatabase.getInstance(com.example.metrotime.data.FIREBASE_DB_URL)
            .getReference("user_stats").child(user.uid).setValue(updates)
    }

    fun computeStats(context: Context): TripStats {
        val trips = trips(context)
        val today = LocalDate.now(ZONE)
        val all = StatsCalc.summary(trips, StatsCalc.Period.ALL, today, ZONE, legacyKm(context))
        val month = StatsCalc.summary(trips, StatsCalc.Period.MONTH, today, ZONE)
        val streak = StatsCalc.streak(trips, today, ZONE, legacyLongestStreak(context))
        return TripStats(
            totalVisits = trips.size,
            totalDistanceKm = all.km,
            travelMinutes = all.travelMinutes,
            currentStreak = streak.current,
            longestStreak = streak.longest,
            topStations = all.stations.map { Triple(it.name, it.type, it.count) },
            topLines = all.lines.map { it.key to it.count },
            monthlyVisits = month.total,
            monthKey = TripStats.monthKey(today),
            lastTripDay = trips.maxOfOrNull { it.startMs }?.let { java.time.Instant.ofEpochMilli(it).atZone(ZONE).toLocalDate().toString() }
        )
    }

    @Synchronized
    fun clearHistory(context: Context) {
        prefs(context).edit().clear().putBoolean(KEY_MIGRATED, true).apply()
        cache = Data(mutableListOf(), mutableListOf(), 0.0)
        save(context, cache!!)
    }

    // ── Ficheiro ────────────────────────────────────────────────────────────

    private fun save(context: Context, d: Data) {
        val o = JSONObject()
            .put("trips", JSONArray().apply { d.trips.forEach { put(tripJson(it)) } })
            .put("pending", JSONArray().apply { d.pending.forEach { put(tripJson(it.trip).put("createdMs", it.createdMs)) } })
            .put("legacyKm", d.legacyKm)
        val f = file(context)
        val out = f.startWrite()
        try {
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            e.printStackTrace()
        }
    }

    private fun tripJson(t: Trip) = JSONObject()
        .put("id", t.id).put("mode", t.mode)
        .put("fromId", t.fromId).put("fromName", t.fromName)
        .put("toId", t.toId ?: JSONObject.NULL).put("toName", t.toName ?: JSONObject.NULL)
        .put("lines", JSONArray(t.lines))
        .put("startMs", t.startMs).put("endMs", t.endMs ?: JSONObject.NULL)
        .put("km", t.km).put("source", t.source)
        .put("direction", t.direction ?: JSONObject.NULL)
        .put("service", t.service ?: JSONObject.NULL)
        .put("trainNumber", t.trainNumber ?: JSONObject.NULL)

    private fun parseTrip(o: JSONObject) = Trip(
        id = o.getString("id"), mode = o.getString("mode"),
        fromId = o.getString("fromId"), fromName = o.getString("fromName"),
        toId = o.optString("toId").takeIf { !o.isNull("toId") && it.isNotEmpty() },
        toName = o.optString("toName").takeIf { !o.isNull("toName") && it.isNotEmpty() },
        lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        startMs = o.getLong("startMs"),
        endMs = if (o.isNull("endMs")) null else o.optLong("endMs"),
        km = o.optDouble("km", 0.0), source = o.optString("source", "auto"),
        direction = o.optString("direction").takeIf { !o.isNull("direction") && it.isNotEmpty() },
        service = o.optString("service").takeIf { !o.isNull("service") && it.isNotEmpty() },
        trainNumber = o.optString("trainNumber").takeIf { !o.isNull("trainNumber") && it.isNotEmpty() }
    )

    private fun parse(json: String): Data {
        val o = JSONObject(json)
        val trips = o.optJSONArray("trips")?.let { a -> (0 until a.length()).map { parseTrip(a.getJSONObject(it)) } }.orEmpty()
        val pending = o.optJSONArray("pending")?.let { a ->
            (0 until a.length()).map { val p = a.getJSONObject(it); PendingTrip(parseTrip(p), p.optLong("createdMs")) }
        }.orEmpty()
        return Data(trips.toMutableList(), pending.toMutableList(), o.optDouble("legacyKm", 0.0))
    }

    private fun parseVisits(json: String): List<StationVisit> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            StationVisit(
                stationId = obj.getString("stationId"),
                stationName = obj.getString("stationName"),
                type = obj.optString("type", "metro"),
                lineName = obj.optString("lineName", ""),
                lat = obj.optDouble("lat", 0.0),
                lon = obj.optDouble("lon", 0.0),
                timestampMs = obj.getLong("timestampMs"),
                confirmed = obj.optBoolean("confirmed", true)
            )
        }
    }
}
