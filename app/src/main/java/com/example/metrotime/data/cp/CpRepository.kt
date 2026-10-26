package com.example.metrotime.data.cp

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/** Resultado da janela de horários de uma estação. */
data class CpTimetableResult(val stops: List<CpTimedStop>, val messages: List<String>)

/**
 * Ponto único de acesso aos dados da CP:
 *  • estações (com cache em disco — mudam muito raramente),
 *  • informação da estação,
 *  • horários numa janela que atravessa a meia-noite,
 *  • comboios em circulação, lidos do Firebase (escritos pela Cloud Function) com
 *    fallback direto ao comboios.live quando esses dados estão em falta ou desatualizados.
 */
object CpRepository {

    const val FIREBASE_URL = "https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app"

    private const val STATIONS_FILE = "cp_stations_cache.json"
    private const val STATIONS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val VEHICLES_STALE_MS = 5L * 60 * 1000

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val stationsAdapter: JsonAdapter<List<CpStation>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, CpStation::class.java))

    private val stationsMutex = Mutex()
    @Volatile private var memStations: List<CpStation>? = null
    private val stationInfoCache = ConcurrentHashMap<String, CpStationInfo>()

    // ── Estações ────────────────────────────────────────────────────────────────

    suspend fun getStations(context: Context, forceRefresh: Boolean = false): List<CpStation> =
        withContext(Dispatchers.IO) {
            stationsMutex.withLock {
                if (!forceRefresh) memStations?.let { return@withLock it }

                val file = File(context.applicationContext.filesDir, STATIONS_FILE)
                val cached = readStationsCache(file)
                val fresh = cached != null && System.currentTimeMillis() - file.lastModified() < STATIONS_TTL_MS
                if (!forceRefresh && fresh) {
                    memStations = cached
                    return@withLock cached!!
                }

                try {
                    val remote = CpApi.service.getStations()
                    if (remote.isNotEmpty()) {
                        runCatching { file.writeText(stationsAdapter.toJson(remote)) }
                        memStations = remote
                    }
                    remote
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Sem rede: usar a cópia antiga em disco, mesmo expirada
                    cached?.also { memStations = it } ?: throw e
                }
            }
        }

    private fun readStationsCache(file: File): List<CpStation>? =
        if (!file.exists()) null
        else runCatching { stationsAdapter.fromJson(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }

    suspend fun getStationInfo(stationCode: String): CpStationInfo? = withContext(Dispatchers.IO) {
        stationInfoCache[stationCode]?.let { return@withContext it }
        try {
            CpApi.service.getStationInfo(stationCode).also { stationInfoCache[stationCode] = it }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // ── Horários ────────────────────────────────────────────────────────────────

    /**
     * Horários de [stationCode] para a janela [now − 5 min, now + 5 h], na hora de Lisboa.
     * Pede o dia seguinte quando a janela passa a meia-noite. O primeiro pedido é obrigatório;
     * o do dia seguinte é "best effort".
     */
    suspend fun getTimetableWindow(
        stationCode: String,
        now: LocalDateTime,
        aheadHours: Long = CpTimetableWindow.AHEAD_HOURS
    ): CpTimetableResult =
        withContext(Dispatchers.IO) {
            val queries = CpTimetableWindow.queriesFor(now, aheadHours)
            val responses = supervisorScope {
                val deferred = queries.map { q ->
                    async { CpApi.service.getStationTimetable(stationCode, q.date.toString(), q.start) }
                }
                deferred.mapIndexed { i, d ->
                    if (i == 0) d.await()
                    else try {
                        d.await()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                }
            }

            val seen = HashSet<String>()
            val stops = mutableListOf<CpTimedStop>()
            val messages = LinkedHashSet<String>()
            queries.zip(responses).forEach { (q, resp) ->
                if (resp == null) return@forEach
                val qStart = CpTime.parseHm(q.start) ?: LocalTime.MIDNIGHT
                resp.messages.orEmpty().forEach { raw ->
                    cpMessageText(raw)?.let { CpText.stripHtml(it) }?.takeIf { it.isNotBlank() }?.let { messages.add(it) }
                }
                for (stop in resp.stationStops.orEmpty()) {
                    val timed = CpTimedStop(stop, q.date, qStart)
                    val at = timed.dateTimeOf(stop.departureTime ?: stop.arrivalTime) ?: continue
                    if (seen.add("${at}_${stop.trainNumber}")) stops += timed
                }
            }
            CpTimetableResult(stops, messages.toList())
        }

    // ── Viagem A → B ────────────────────────────────────────────────────────────

    /** Comboios diretos de [originCode] para [destinationCode] a partir de [from] (hora de Lisboa). */
    suspend fun findJourneys(originCode: String, destinationCode: String, from: LocalDateTime): List<CpJourneyOption> =
        withContext(Dispatchers.IO) {
            supervisorScope {
                val origin = async { getTimetableWindow(originCode, from, aheadHours = 6) }
                // O destino precisa de mais horas: a chegada pode ser bem depois da última partida
                val destination = async { getTimetableWindow(destinationCode, from, aheadHours = 18) }
                CpJourney.match(origin.await().stops, destination.await().stops, from)
            }
        }

    // ── Detalhe de um comboio ───────────────────────────────────────────────────

    /**
     * Detalhe de um comboio e a data a que diz respeito. Usa a data indicada (ou hoje, em Lisboa); se a CP
     * não tiver paragens para essa data, tenta o dia anterior (comboios que partiram antes da meia-noite).
     * Só recorre à API alternativa (ruicosta) se a da CP falhar — e nunca quando o pedido foi cancelado.
     */
    suspend fun getTripDetail(trainNumber: Int, runDate: String?): Pair<CpTripDetail, java.time.LocalDate> =
        withContext(Dispatchers.IO) {
            val date = CpTime.parseDate(runDate) ?: CpTime.today()
            try {
                val primary = CpApi.service.getTripDetail(trainNumber, date.toString())
                if (primary.trainStops.isNullOrEmpty()) {
                    val previousDate = date.minusDays(1)
                    val previous = CpApi.service.getTripDetail(trainNumber, previousDate.toString())
                    if (!previous.trainStops.isNullOrEmpty()) previous to previousDate else primary to date
                } else primary to date
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                (RuicostaApi.service.getTrain(trainNumber).fixed ?: throw e) to date
            }
        }

    // ── Avisos ─────────────────────────────────────────────────────────────────

    /** Avisos da CP (nó cp_avisos, escrito pelo scripts/cp_scraper.py). Só os que ainda estão a decorrer. */
    suspend fun getNotices(): List<CpNotice> = withContext(Dispatchers.IO) {
        val snap = withTimeoutOrNull(10_000) {
            FirebaseDatabase.getInstance(FIREBASE_URL).getReference("cp_avisos/lista").get().await()
        } ?: return@withContext emptyList()
        val today = CpTime.today()
        snap.children.mapNotNull(::parseNotice).filter { it.isCurrent(today) }
    }

    fun parseNotice(s: DataSnapshot): CpNotice? {
        val title = s.str("titulo")?.takeIf { it.isNotBlank() } ?: return null
        return CpNotice(
            title = title,
            summary = s.str("resumo").orEmpty(),
            link = s.str("link").orEmpty(),
            start = CpTime.parseDate(s.str("inicio")),
            end = CpTime.parseDate(s.str("fim")),
            categories = s.child("categorias").children.mapNotNull { it.value as? String }
        )
    }

    // ── Comboios em circulação ─────────────────────────────────────────────────

    /**
     * Lê `cp_vehicles` (Cloud Function, atualizado a cada minuto). Se não existir ou tiver mais
     * de 5 min, vai diretamente ao comboios.live. Aplica sempre o mesmo filtro de "fantasmas".
     */
    suspend fun getActiveVehicles(): List<CpVehicle> = withContext(Dispatchers.IO) {
        val today = CpTime.today()

        val fromFirebase: List<CpVehicle>? = try {
            val snap = withTimeoutOrNull(10_000) {
                FirebaseDatabase.getInstance(FIREBASE_URL).getReference("cp_vehicles").get().await()
            }
            val updatedAt = (snap?.child("updatedAt")?.value as? Number)?.toLong() ?: 0L
            if (snap != null && System.currentTimeMillis() - updatedAt < VEHICLES_STALE_MS) {
                snap.child("vehicles").children.mapNotNull { parseVehicle(it) }
            } else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            null
        }

        val vehicles = fromFirebase ?: ComboiosLiveApi.service.getVehicles().vehicles.orEmpty()

        vehicles
            .filter {
                CpVehicleFilter.isActive(
                    status = it.status,
                    runDate = it.runDate,
                    delaySeconds = it.delay,
                    hasGps = !it.latitude.isNullOrBlank() && !it.longitude.isNullOrBlank(),
                    today = today
                )
            }
            .distinctBy { it.uniqueKey }
            .sortedBy { it.trainNumber }
    }

    private fun DataSnapshot.str(key: String): String? = child(key).value?.toString()

    private fun DataSnapshot.int(key: String): Int? = when (val v = child(key).value) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun DataSnapshot.entity(key: String): CpGenericEntity? {
        val c = child(key)
        if (!c.exists()) return null
        return CpGenericEntity(code = c.str("code"), designation = c.str("designation"))
    }

    fun parseVehicle(s: DataSnapshot): CpVehicle? {
        val number = s.int("trainNumber") ?: s.key?.toIntOrNull() ?: return null
        return CpVehicle(
            trainNumber = number,
            runDate = s.str("runDate"),
            delay = s.int("delay"),
            lastStation = s.str("lastStation"),
            latitude = s.str("latitude"),
            longitude = s.str("longitude"),
            status = s.str("status"),
            hasDisruptions = s.child("hasDisruptions").value as? Boolean,
            timestamp = (s.child("timestamp").value as? Number)?.toLong(),
            service = s.entity("service"),
            origin = s.entity("origin"),
            destination = s.entity("destination"),
            originTime = s.str("originTime"),
            originETA = s.str("originETA"),
            destinationTime = s.str("destinationTime"),
            destinationETA = s.str("destinationETA"),
            bearing = (s.child("bearing").value as? Number)?.toDouble()
        )
    }
}
