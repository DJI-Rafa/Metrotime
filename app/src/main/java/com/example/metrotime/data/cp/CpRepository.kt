package com.example.metrotime.data.cp

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
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

/**
 * Resultado da janela de horários de uma estação. [offline]: a CP não respondeu e são as horas da última resposta
 * boa ([savedAt], ms), com o atraso de cada comboio pelo GPS (o mesmo do mapa e do ecrã do comboio).
 */
data class CpTimetableResult(
    val stops: List<CpTimedStop>,
    val messages: List<String>,
    val offline: Boolean = false,
    val savedAt: Long? = null
)

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

    /** A lista de estações que já se tem (em memória ou em disco, mesmo antiga), sem ir à rede; null se nunca chegou. */
    fun cachedStations(context: Context): List<CpStation>? =
        memStations ?: readStationsCache(File(context.applicationContext.filesDir, STATIONS_FILE))

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
    ): CpTimetableResult = withContext(Dispatchers.IO) {
        val key = "$stationCode|$aheadHours"
        try {
            fetchTimetableWindow(stationCode, now, aheadHours).also { boards[key] = System.currentTimeMillis() to it.stops }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A CP falhou: as horas da última resposta boa desta estação (até 6 h), com o atraso pelo GPS
            val (savedAt, stops) = boards[key]?.takeIf { System.currentTimeMillis() - it.first < BOARD_KEEP_MS } ?: throw e
            val vehicles = runCatching { getActiveVehicles() }.getOrDefault(emptyList())
            CpTimetableResult(CpFallback.withVehicleDelays(stops, vehicles, now), emptyList(), offline = true, savedAt = savedAt)
        }
    }

    /** Última resposta boa de cada estação (e janela): o que se mostra quando a CP não responde. */
    private val boards = ConcurrentHashMap<String, Pair<Long, List<CpTimedStop>>>()
    private const val BOARD_KEEP_MS = 6L * 60 * 60 * 1000

    private suspend fun fetchTimetableWindow(stationCode: String, now: LocalDateTime, aheadHours: Long): CpTimetableResult =
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
                val result = if (primary.trainStops.isNullOrEmpty()) {
                    val previousDate = date.minusDays(1)
                    val previous = CpApi.service.getTripDetail(trainNumber, previousDate.toString())
                    if (!previous.trainStops.isNullOrEmpty()) previous to previousDate else primary to date
                } else primary to date
                if (!result.first.trainStops.isNullOrEmpty()) trips[trainNumber] = result
                result
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // A CP falhou: a alternativa (ruicosta) ou a última resposta boa, e nas paragens que ainda faltam o
                // atraso pelo GPS do comboio — o mesmo que os quadros das estações usam sem a CP
                val (trip, tripDate) = runCatching { RuicostaApi.service.getTrain(trainNumber).fixed }.getOrNull()
                    ?.takeIf { !it.trainStops.isNullOrEmpty() }?.let { it to date }
                    ?: trips[trainNumber]
                    ?: throw e
                val vehicle = runCatching { getActiveVehicles() }.getOrDefault(emptyList()).firstOrNull { it.trainNumber == trainNumber }
                CpFallback.withVehicleDelay(trip, vehicle).copy(offline = true) to tripDate
            }
        }

    /** Última resposta boa de cada comboio. */
    private val trips = ConcurrentHashMap<Int, Pair<CpTripDetail, java.time.LocalDate>>()

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

        clean(fromFirebase ?: ComboiosLiveApi.service.getVehicles().vehicles.orEmpty())
    }

    /** Os comboios de uma leitura e a hora a que os dados são (ms): a da Cloud Function ou a da leitura direta. */
    data class VehiclesUpdate(val vehicles: List<CpVehicle>, val dataAt: Long)

    /**
     * Os comboios em circulação, em tempo real: escuta `cp_vehicles` e emite logo que a Cloud Function escreve (a cada
     * minuto). Antes a app perguntava de 30 em 30 s, o que juntava até mais 30 s de atraso ao minuto da função.
     * Sem dados frescos do Firebase (mais de 5 min: função parada, Firebase inacessível) vai diretamente ao
     * comboios.live, logo e depois de 30 em 30 s, até o Firebase voltar. Larga a ligação quando deixa de ser recolhido.
     */
    fun liveVehicles(): Flow<VehiclesUpdate> = channelFlow {
        val snapshots = Channel<DataSnapshot?>(Channel.CONFLATED)
        val ref = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("cp_vehicles")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { snapshots.trySend(snapshot) }
            override fun onCancelled(error: DatabaseError) { snapshots.trySend(null) }
        }
        ref.addValueEventListener(listener)
        try {
            var freshAt = 0L
            var first = true
            while (isActive) {
                // A primeira resposta do Firebase: até 10 s; depois, a função escreve a cada minuto
                val snap = withTimeoutOrNull(if (first) 10_000L else 30_000L) { snapshots.receive() }
                first = false
                val now = System.currentTimeMillis()
                val updatedAt = (snap?.child("updatedAt")?.value as? Number)?.toLong() ?: 0L
                if (snap != null && now - updatedAt < VEHICLES_STALE_MS) {
                    freshAt = now
                    send(VehiclesUpdate(clean(snap.child("vehicles").children.mapNotNull { parseVehicle(it) }), updatedAt))
                } else if (now - freshAt >= VEHICLES_STALE_MS) {
                    val direct = try {
                        ComboiosLiveApi.service.getVehicles().vehicles.orEmpty()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    if (direct != null) send(VehiclesUpdate(clean(direct), now))
                }
            }
        } finally {
            ref.removeEventListener(listener)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * As paragens de cada comboio (cp_routes, escrito pela Cloud Function), em tempo real, por número. Sem o nó (função
     * antiga) ou sem permissão, dá um mapa vazio: os comboios ficam no GPS, como antes.
     */
    fun liveRoutes(): Flow<Map<Int, CpRoute>> = channelFlow {
        val snapshots = Channel<DataSnapshot?>(Channel.CONFLATED)
        val ref = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("cp_routes").child("trains")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { snapshots.trySend(snapshot) }
            override fun onCancelled(error: DatabaseError) { snapshots.trySend(null) }
        }
        ref.addValueEventListener(listener)
        try {
            for (snap in snapshots) {
                val routes = snap?.children?.mapNotNull { c ->
                    val number = c.key?.toIntOrNull() ?: return@mapNotNull null
                    CpRoute.parse(c.value as? String)?.let { number to it }
                }?.toMap().orEmpty()
                send(routes)
            }
        } finally {
            ref.removeEventListener(listener)
        }
    }.flowOn(Dispatchers.IO)

    /** O mesmo filtro de "fantasmas" para todas as leituras, sem repetidos, por número. */
    private fun clean(vehicles: List<CpVehicle>): List<CpVehicle> {
        val today = CpTime.today()
        return vehicles
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
            bearing = (s.child("bearing").value as? Number)?.toDouble(),
            passCode = s.str("passCode"),
            passTime = s.str("passTime"),
            passLeft = s.child("passLeft").value as? Boolean
        )
    }
}
