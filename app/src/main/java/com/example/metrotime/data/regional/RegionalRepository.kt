package com.example.metrotime.data.regional

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Vai buscar os veículos dos outros operadores (ver [RegionalOperators]). O mapa chama [tml] e [eleven] em ciclo
 * só enquanto está à vista e com a camada ligada.
 *
 * Na Eleven a lista de veículos não diz a linha: cada veículo tem de ser aberto (`/api/locations/{id}`, ~2 KB).
 * Para não carregar os servidores deles, abrem-se no máximo [DETAILS_PER_ROUND] veículos por rede e por volta,
 * e o que se sabe de cada um fica guardado [LINE_TTL_MS]. Entretanto vale a linha que é a única com aquela cor.
 */
object RegionalRepository {

    private const val TML_POSITIONS = "https://go.tmlmobilidade.pt/hub/api/v1/0XJC9/vehicles/positions"
    /** STCP no broker FIWARE da Porto Digital: só os campos precisos (~65 KB em vez de 260 KB). */
    private const val STCP_POSITIONS = "https://broker.fiware.urbanplatform.portodigital.pt/v2/entities" +
        "?q=vehicleType==bus&limit=1000&attrs=location,annotations,observationDateTime,fleetVehicleId&options=keyValues"
    private fun elevenBase(code: String) = "https://$code.elevensystems.pt/api"
    private val LISBON: java.time.ZoneId = java.time.ZoneId.of("Europe/Lisbon")

    @Volatile private var appContext: android.content.Context? = null

    /** Para ler os pacotes de paragens e horários (assets). Chamado pelo mapa ao arrancar. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private val packs = ConcurrentHashMap<String, TransitPack>()
    private val packFailed = ConcurrentHashMap.newKeySet<String>()

    /** O pacote descarregado do servidor (mais recente que o da app), se houver. */
    private fun downloadedPack(context: android.content.Context, code: String) =
        java.io.File(context.filesDir, "transit/${code.lowercase()}.bin")

    /**
     * Pacote de paragens e horários de um operador, lido uma vez: o que o servidor gerou (descarregado por [syncPacks])
     * ou, sem ele, o que vem dentro da app (assets/transit_<código>.bin).
     */
    fun pack(code: String): TransitPack? {
        packs[code]?.let { return it }
        if (code in packFailed) return null
        val context = appContext ?: return null
        synchronized(this) {
            packs[code]?.let { return it }
            val fromServer = downloadedPack(context, code).takeIf { it.isFile }?.let { file ->
                runCatching { file.inputStream().use { TransitPack.parse(it) } }.onFailure { file.delete() }.getOrNull()
            }
            return (fromServer ?: runCatching { context.assets.open("transit_${code.lowercase()}.bin").use { TransitPack.parse(it) } }
                .onFailure { packFailed += code; it.printStackTrace() }
                .getOrNull())
                ?.also { packs[code] = it }
        }
    }

    /**
     * Descarrega do servidor os pacotes de horários que mudaram (o índice tem a impressão digital de cada um; só vem o
     * que é novo, ~5–130 KB cada). Devolve true se algum mudou (as paragens do mapa devem ser lidas de novo).
     */
    suspend fun syncPacks(): Boolean = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext false
        val db = com.google.firebase.database.FirebaseDatabase.getInstance(com.example.metrotime.data.FIREBASE_DB_URL)
        val prefs = context.getSharedPreferences("transit_packs", android.content.Context.MODE_PRIVATE)
        val index = runCatching {
            kotlinx.coroutines.withTimeout(20_000) {
                db.getReference("transit_packs_index").get().await()
            }
        }.getOrNull() ?: return@withContext false
        var changed = false
        for (child in index.children) {
            val code = child.key ?: continue
            if (RegionalOperators.byPack(code) == null) continue
            val hash = child.child("hash").getValue(String::class.java) ?: continue
            if (hash == prefs.getString(code, null) && downloadedPack(context, code).isFile) continue
            runCatching {
                val data = kotlinx.coroutines.withTimeout(30_000) {
                    db.getReference("transit_packs/$code/data").get().await()
                }.getValue(String::class.java) ?: return@runCatching
                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                // Só fica se se ler bem
                TransitPack.parse(bytes.inputStream())
                val file = downloadedPack(context, code)
                file.parentFile?.mkdirs()
                val tmp = java.io.File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                prefs.edit().putString(code, hash).apply()
                packs.remove(code)
                packFailed.remove(code)
                changed = true
            }.onFailure { it.printStackTrace() }
        }
        changed
    }

    // ── Posições do servidor (regional_live/<região>) ─────────────────────────

    /** Uma região como o servidor a gravou: a hora (do servidor) e os veículos, já com o horário. */
    class ServerArea(val updatedAt: Long, val vehicles: List<RegionalVehicle>)

    private fun num(s: com.google.firebase.database.DataSnapshot, key: String): Double? =
        (s.child(key).value as? Number)?.toDouble()

    private fun str(s: com.google.firebase.database.DataSnapshot, key: String): String? =
        s.child(key).value?.toString()?.takeIf { it.isNotBlank() }

    /** Um veículo compacto do servidor (o, id, la, lo, l, c, s, t, st, d, sd, at, ds) → [RegionalVehicle]. */
    private fun fromServer(s: com.google.firebase.database.DataSnapshot): RegionalVehicle? {
        val op = str(s, "o")?.let { RegionalOperators.byKey(it) } ?: return null
        val id = str(s, "id") ?: return null
        val lat = num(s, "la") ?: return null
        val lon = num(s, "lo") ?: return null
        return RegionalVehicle(
            operator = op,
            vehicleId = id,
            lat = lat,
            lon = lon,
            line = str(s, "l"),
            color = RegionalParsers.color(str(s, "c")) ?: op.color,
            status = str(s, "s"),
            observedAt = num(s, "at")?.toLong() ?: 0L,
            tripId = str(s, "t"),
            stopId = str(s, "st"),
            direction = num(s, "d")?.toInt(),
            serviceDate = num(s, "sd")?.toInt(),
            destination = str(s, "ds")?.let(RegionalParsers::prettyName)
        )
    }

    /** Cor e destino de um autocarro da STCP pelo percurso da linha e sentido mais perto. */
    private fun withStcpRoute(v: RegionalVehicle, pack: TransitPack?): RegionalVehicle {
        val line = v.line ?: return v
        val (p, _) = pack?.let { TransitLive.nearestPattern(it, line, v.direction, v.lat, v.lon) } ?: return v
        val pattern = pack.patterns[p]
        return v.copy(
            color = pack.routes[pattern.route].color ?: v.color,
            destination = RegionalParsers.prettyName(pattern.headsign)
        )
    }

    /**
     * As posições de uma região ([area]: LISBOA, PORTO, NORTE, CENTRO) gravadas pelo servidor de 10 em 10 s. Ouve o
     * Realtime Database (só chega o que mudou) e junta a cada veículo o horário (atraso, destino), como na leitura direta.
     */
    fun serverArea(area: String): Flow<ServerArea> = callbackFlow {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance(com.example.metrotime.data.FIREBASE_DB_URL)
            .getReference("regional_live/$area")
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                trySend(snapshot)
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }.map { snapshot ->
        val nowMs = System.currentTimeMillis()
        val now = nowLisbon()
        val today = dateInt(now.toLocalDate())
        val nowSec = now.toLocalTime().toSecondOfDay()
        val stcpPack = if (area == "PORTO") pack("STCP") else null
        val vehicles = snapshot.child("vehicles").children.mapNotNull { child ->
            val v = fromServer(child) ?: return@mapNotNull null
            when (v.operator.source) {
                RegionalSource.TML -> withSchedule(v, today, nowSec)
                RegionalSource.STCP -> withStcpRoute(v, stcpPack)
                else -> v
            }
        }
        // Para as chegadas às paragens (as mesmas listas da leitura direta)
        when (area) {
            "LISBOA" -> lastTml = LastList(vehicles, nowMs)
            "PORTO" -> lastStcp = LastList(vehicles, nowMs)
        }
        ServerArea((snapshot.child("updatedAt").value as? Number)?.toLong() ?: 0L, vehicles)
    }.flowOn(Dispatchers.IO)

    /** As últimas posições do GO Hub e da STCP, para as chegadas às paragens. */
    @Volatile private var lastTml: LastList? = null
    @Volatile private var lastStcp: LastList? = null

    private fun nowLisbon(): java.time.ZonedDateTime = java.time.ZonedDateTime.now(LISBON)
    private fun dateInt(d: java.time.LocalDate) = d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth

    private const val DETAILS_PER_ROUND = 5
    private const val LINE_TTL_MS = 8 * 60_000L
    private const val COLORS_TTL_MS = 6 * 60 * 60_000L
    /** Se uma rede falhar, mostra-se o que veio antes durante este tempo. */
    private const val KEEP_ON_ERROR_MS = 2 * 60_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private data class KnownLine(val line: String?, val color: String, val at: Long)
    private data class ColorLines(val byColor: Map<String, String>, val at: Long)
    private data class LastList(val vehicles: List<RegionalVehicle>, val at: Long)

    /** "cov|11010288" → linha e cor da última vez que o veículo foi aberto. */
    private val knownLines = ConcurrentHashMap<String, KnownLine>()
    private val colorLines = ConcurrentHashMap<String, ColorLines>()
    private val lastLists = ConcurrentHashMap<String, LastList>()
    /** Viagens abertas há pouco ("cov|11010288"), para o painel abrir logo. */
    private val trips = ConcurrentHashMap<String, Pair<RegionalTrip, Long>>()

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** MobiCascais, TCB, MTS, Fertagus e barcos (um só pedido ao GO Hub, ~170 KB comprimido). */
    suspend fun tml(nowMs: Long = System.currentTimeMillis()): List<RegionalVehicle> = withContext(Dispatchers.IO) {
        val json = get(TML_POSITIONS) ?: throw java.io.IOException("GO Hub sem resposta")
        val now = nowLisbon()
        val today = dateInt(now.toLocalDate())
        val nowSec = now.toLocalTime().toSecondOfDay()
        RegionalParsers.tmlPositions(json, RegionalOperators.TML, nowMs)
            .map { v -> withSchedule(v, today, nowSec) }
            .also { lastTml = LastList(it, nowMs) }
    }

    /** Atraso, destino e linha de um veículo do GO Hub pela viagem dele no horário (pacote do operador). */
    private fun withSchedule(v: RegionalVehicle, today: Int, nowSecOfDay: Int): RegionalVehicle {
        val pack = v.operator.pack?.let(::pack) ?: return v
        val (p, t) = v.tripId?.let { pack.findTrip(it) } ?: return v
        val pattern = pack.patterns[p]
        val nowSec = TransitLive.serviceSeconds(nowSecOfDay, today, v.serviceDate)
        // A paragem da posição; sem ela (a Fertagus), a que fica mais perto no percurso
        val byStop = TransitLive.positionOf(pack, pattern, v.stopId)?.let { it to (v.status == "STOPPED_AT") }
        val place = byStop ?: TransitLive.positionByLocation(pack, pattern, v.lat, v.lon)
        val pos = place?.first
        val delay = place?.let { (p, stopped) -> TransitLive.delayOf(pattern, t, p, stopped, nowSec) }
        val stopId = v.stopId ?: pos?.let { pack.stops[pattern.stops[it]].id }
        val route = pack.routes[pattern.route]
        return v.copy(
            delaySeconds = delay,
            stopId = stopId,
            status = v.status ?: if (place?.second == true) "STOPPED_AT" else "IN_TRANSIT_TO",
            destination = RegionalParsers.prettyName(pattern.headsign),
            line = v.line ?: route.label,
            color = route.color ?: v.color
        )
    }

    /** Autocarros da STCP em direto (broker FIWARE da Porto Digital, ~65 KB). */
    suspend fun stcp(nowMs: Long = System.currentTimeMillis()): List<RegionalVehicle> = withContext(Dispatchers.IO) {
        val json = get(STCP_POSITIONS) ?: throw java.io.IOException("STCP sem resposta")
        val pack = pack("STCP")
        RegionalParsers.stcpVehicles(json, RegionalOperators.STCP, nowMs).map { v ->
            // Cor e destino pelo percurso da linha e sentido mais perto
            val line = v.line ?: return@map v
            val (p, _) = pack?.let { TransitLive.nearestPattern(it, line, v.direction, v.lat, v.lon) } ?: return@map v
            val pattern = pack.patterns[p]
            v.copy(
                color = pack.routes[pattern.route].color ?: v.color,
                destination = RegionalParsers.prettyName(pattern.headsign)
            )
        }.also { lastStcp = LastList(it, nowMs) }
    }

    /**
     * Próximas chegadas a uma paragem de um pacote ([code]): horário mais os veículos em direto (os últimos que o mapa
     * leu; se forem velhos, lê-os de novo). No formato das paragens da Carris, para usar o mesmo painel.
     */
    suspend fun stopArrivals(
        code: String,
        stopId: String,
        aheadSecs: Int
    ): List<com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo> = withContext(Dispatchers.IO) {
        val pack = pack(code) ?: return@withContext emptyList()
        val operator = RegionalOperators.byPack(code)
        val nowMs = System.currentTimeMillis()
        val live = when (operator?.source) {
            RegionalSource.TML -> (lastTml?.takeIf { nowMs - it.at < 60_000 }?.vehicles ?: runCatching { tml(nowMs) }.getOrDefault(emptyList()))
            RegionalSource.STCP -> (lastStcp?.takeIf { nowMs - it.at < 60_000 }?.vehicles ?: runCatching { stcp(nowMs) }.getOrDefault(emptyList()))
            else -> emptyList()
        }.filter { it.operator.pack == code }
        val now = nowLisbon()
        val nowSec = now.toLocalTime().toSecondOfDay()
        TransitLive.arrivals(pack, stopId, now.toLocalDate(), nowSec, aheadSecs, live).map { a ->
            com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo(
                routeShortName = a.route.label,
                routeLongName = RegionalParsers.prettyName(a.route.longName),
                destination = RegionalParsers.prettyName(a.headsign),
                etaMinutes = ((a.expectedSec - nowSec + 30) / 60).coerceAtLeast(0),
                vehicleId = a.vehicleKey,
                operator = "pack:$code",
                color = a.route.color ?: operator?.color ?: com.example.metrotime.data.bus.BusColors.FALLBACK,
                realtime = a.realtime,
                delayMinutes = a.delaySeconds?.let { com.example.metrotime.data.bus.BusTiming.roundMinutes(it) },
                scheduledTime = a.scheduledSec?.let { com.example.metrotime.data.bus.BusTiming.clock(it) }
            )
        }
    }

    /** Todas as redes Eleven, em paralelo. Uma rede que falhe não estraga as outras. */
    suspend fun eleven(nowMs: Long = System.currentTimeMillis()): List<RegionalVehicle> = coroutineScope {
        RegionalOperators.ELEVEN.map { op ->
            async(Dispatchers.IO) {
                runCatching { elevenNetwork(op, nowMs) }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastLists[op.code]?.takeIf { nowMs - it.at < KEEP_ON_ERROR_MS }?.vehicles.orEmpty()
                }
            }
        }.awaitAll().flatten()
    }

    private fun elevenNetwork(op: RegionalOperator, nowMs: Long): List<RegionalVehicle> {
        val base = elevenBase(op.code)
        val byColor = colorLines[op.code]?.takeIf { nowMs - it.at < COLORS_TTL_MS }?.byColor
            ?: runCatching { get("$base/routes?passengerInfo=true")?.let(RegionalParsers::elevenLinesByColor) }
                .getOrNull()
                ?.also { colorLines[op.code] = ColorLines(it, nowMs) }
            ?: emptyMap()

        val json = get("$base/locations?passengerInfo=true") ?: throw java.io.IOException("${op.code} sem resposta")
        val raw = RegionalParsers.elevenLocations(json, op, nowMs) { _, _ -> null }

        // Abre os veículos de que não se sabe a linha (ou que mudaram de cor = de linha), poucos de cada vez
        val stale = raw.filter { v ->
            val known = knownLines["${op.code}|${v.vehicleId}"]
            known == null || known.color != v.color || nowMs - known.at > LINE_TTL_MS
        }.sortedBy { knownLines["${op.code}|${it.vehicleId}"]?.at ?: 0L }
        for (v in stale.take(DETAILS_PER_ROUND)) {
            runCatching { fetchElevenTrip(op, v.vehicleId, v.color, nowMs) }
        }

        val vehicles = raw.map { v ->
            val known = knownLines["${op.code}|${v.vehicleId}"]?.takeIf { it.color == v.color }
            v.copy(line = known?.line ?: byColor[v.color])
        }
        lastLists[op.code] = LastList(vehicles, nowMs)
        // O que se sabe de veículos que já não andam não fica para sempre
        val alive = vehicles.mapTo(HashSet()) { "${op.code}|${it.vehicleId}" }
        trips.keys.removeIf { k -> k.startsWith("${op.code}|") && k !in alive }
        knownLines.keys.removeIf { k -> k.startsWith("${op.code}|") && k !in alive }
        return vehicles
    }

    /** [listColor]: a cor que o veículo tem na lista (muda quando muda de linha). */
    private fun fetchElevenTrip(op: RegionalOperator, vehicleId: String, listColor: String, nowMs: Long): RegionalTrip? {
        val json = get("${elevenBase(op.code)}/locations/$vehicleId") ?: return null
        val trip = RegionalParsers.elevenTrip(json) ?: return null
        val key = "${op.code}|$vehicleId"
        knownLines[key] = KnownLine(trip.line, listColor, nowMs)
        trips[key] = trip to nowMs
        return trip
    }

    /**
     * Viagem do veículo aberto no mapa (paragens, minutos e traçado): na Eleven pela API deles; no GO Hub pelo horário
     * do operador com o atraso; na STCP pelo percurso da linha e sentido mais perto e os tempos típicos.
     */
    suspend fun trip(vehicle: RegionalVehicle, maxAgeMs: Long = 20_000L): RegionalTrip? = withContext(Dispatchers.IO) {
        when (vehicle.operator.source) {
            RegionalSource.TML, RegionalSource.SCHEDULE -> return@withContext scheduleTrip(vehicle)
            RegionalSource.STCP -> return@withContext stcpTrip(vehicle)
            RegionalSource.ELEVEN -> Unit
            // (a Carris e a CM abrem no painel delas, com o horário de cada viagem)
            RegionalSource.NONE, RegionalSource.CITY -> return@withContext null
        }
        val now = System.currentTimeMillis()
        trips["${vehicle.operator.code}|${vehicle.vehicleId}"]?.takeIf { now - it.second < maxAgeMs }?.first
            ?: fetchElevenTrip(vehicle.operator, vehicle.vehicleId, vehicle.color, now)
    }

    private fun scheduleTrip(v: RegionalVehicle): RegionalTrip? {
        val pack = v.operator.pack?.let(::pack) ?: return null
        val (p, t) = v.tripId?.let { pack.findTrip(it) } ?: return null
        val now = nowLisbon()
        val nowSec = TransitLive.serviceSeconds(now.toLocalTime().toSecondOfDay(), dateInt(now.toLocalDate()), v.serviceDate)
        val pos = TransitLive.positionOf(pack, pack.patterns[p], v.stopId)
        return TransitLive.scheduledTrip(pack, p, t, pos, v.delaySeconds, nowSec)
    }

    private fun stcpTrip(v: RegionalVehicle): RegionalTrip? {
        val pack = pack("STCP") ?: return null
        val line = v.line ?: return null
        val (p, next) = TransitLive.nearestPattern(pack, line, v.direction, v.lat, v.lon) ?: return null
        return TransitLive.estimatedTrip(pack, p, next)
    }

    /** Um veículo das últimas leituras do GO Hub ou da STCP pela chave, mesmo que os filtros o escondam. */
    fun liveVehicle(key: String): RegionalVehicle? =
        lastTml?.vehicles?.firstOrNull { it.key == key } ?: lastStcp?.vehicles?.firstOrNull { it.key == key }

    /** O que se sabe sem pedir nada (para o painel abrir já com a linha). */
    fun cachedTrip(vehicle: RegionalVehicle): RegionalTrip? = when (vehicle.operator.source) {
        RegionalSource.TML, RegionalSource.SCHEDULE -> runCatching { scheduleTrip(vehicle) }.getOrNull()
        RegionalSource.STCP -> runCatching { stcpTrip(vehicle) }.getOrNull()
        else -> trips["${vehicle.operator.code}|${vehicle.vehicleId}"]?.first
    }

    /** Uma linha para os filtros: o número, o nome e a cor. */
    data class LineChoice(val label: String, val name: String, val color: String?)

    private val elevenLines = ConcurrentHashMap<String, List<LineChoice>>()

    /** "12" antes de "100", "1A" depois de "1", e os nomes sem número no fim. */
    private val lineOrder = compareBy<LineChoice>(
        { it.label.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE },
        { it.label }
    )

    /** As linhas de um operador (pacote ou API da Eleven), por ordem do número. Vazio se não houver. */
    suspend fun lines(op: RegionalOperator): List<LineChoice> = withContext(Dispatchers.IO) {
        op.pack?.let(::pack)?.let { pack ->
            return@withContext pack.routes
                .map { LineChoice(it.label, RegionalParsers.prettyName(it.longName), it.color) }
                .distinctBy { it.label }
                .sortedWith(lineOrder)
        }
        // Carris e CM: as linhas da Carris (dados da app) e as da CM (API, já em cache)
        if (op.source == RegionalSource.CITY) {
            val cm = op.key == RegionalOperators.CM.key
            val list = if (cm) {
                runCatching { com.example.metrotime.data.bus.BusMapRepository.getCmLines() }.getOrDefault(emptyList())
                    .map { LineChoice(it.shortName, it.longName, it.color) }
            } else {
                val context = appContext ?: return@withContext emptyList()
                com.example.metrotime.data.bus.CarrisGtfsData.ensureLoaded(context)
                com.example.metrotime.data.bus.CarrisGtfsData.routes.values.map {
                    LineChoice(it.shortName, it.longName, com.example.metrotime.data.bus.BusColors.forRoute(it.shortName, "carris"))
                }
            }
            return@withContext list.distinctBy { it.label }.sortedWith(lineOrder)
        }
        if (op.source != RegionalSource.ELEVEN) return@withContext emptyList()
        elevenLines[op.code]?.let { return@withContext it }
        val json = runCatching { get("${elevenBase(op.code)}/routes?passengerInfo=true") }.getOrNull() ?: return@withContext emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return@withContext emptyList()
        val list = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = r.optString("nameShort").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LineChoice(label, RegionalParsers.prettyName(r.optString("name")), RegionalParsers.color(r.optString("color")))
        }.distinctBy { it.label }.sortedWith(lineOrder)
        elevenLines[op.code] = list
        list
    }

    // ── Paragens da Eleven (Covilhã, Viseu, Guimarães…) ──────────────────────

    /** As paragens mudam pouco: guardam-se uma semana. */
    private const val ELEVEN_STOPS_TTL_MS = 7L * 24 * 3600 * 1000

    /** As paragens de todas as redes da Eleven (do ficheiro guardado, ou da API se tiver mais de uma semana). */
    suspend fun elevenStops(): List<Pair<RegionalOperator, RegionalParsers.ElevenStop>> = coroutineScope {
        val context = appContext
        RegionalOperators.ELEVEN.map { op ->
            async(Dispatchers.IO) {
                val file = context?.let { java.io.File(it.filesDir, "eleven/${op.code}_stops.json") }
                val fresh = file?.takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < ELEVEN_STOPS_TTL_MS }
                val json = fresh?.readText()
                    ?: runCatching { get("${elevenBase(op.code)}/stops") }.getOrNull()?.also { text ->
                        runCatching {
                            RegionalParsers.elevenStops(text) // só se guarda se se ler bem
                            file?.parentFile?.mkdirs()
                            file?.writeText(text)
                        }
                    }
                    ?: file?.takeIf { it.isFile }?.readText() // sem rede: a lista antiga
                val stops = json?.let { runCatching { RegionalParsers.elevenStops(it) }.getOrNull() }.orEmpty()
                stops.map { op to it }
            }
        }.awaitAll().flatten()
    }

    /** As linhas e as próximas passagens numa paragem da Eleven (para o planeador). */
    suspend fun elevenStopPasses(code: String, stopId: String): List<RegionalParsers.ElevenStopRoute> = withContext(Dispatchers.IO) {
        runCatching { get("${elevenBase(code)}/stops/$stopId/routes")?.let(RegionalParsers::elevenStopRoutes) }.getOrNull().orEmpty()
    }

    /** As viagens já abertas ("cov|1002|3377"): as paragens e as horas não mudam no mesmo dia. */
    private val elevenJourneys = ConcurrentHashMap<String, List<RegionalParsers.ElevenCall>>()

    /** As paragens e horas de uma viagem da Eleven (guardadas enquanto a app estiver aberta). */
    suspend fun elevenJourney(code: String, routeId: String, journeyId: String): List<RegionalParsers.ElevenCall> = withContext(Dispatchers.IO) {
        val key = "$code|$routeId|$journeyId"
        elevenJourneys[key]?.let { return@withContext it }
        val calls = runCatching {
            get("${elevenBase(code)}/routes/$routeId/journeys/$journeyId")?.let(RegionalParsers::elevenCirculations)
        }.getOrNull().orEmpty()
        if (calls.isNotEmpty()) elevenJourneys[key] = calls
        calls
    }

    /** Destino de cada linha e sentido ("cov|1002|1"), da última paragem de uma viagem; "" numa circular. */
    private val elevenDestinations = ConcurrentHashMap<String, String>()

    /**
     * As próximas passagens numa paragem da Eleven, pelo horário (a API não liga as passagens aos autocarros em
     * direto). Cada linha dá as 1–2 seguintes; o destino vem da última paragem da viagem (guardado por linha e sentido).
     */
    suspend fun elevenStopArrivals(
        code: String,
        stopId: String,
        aheadSecs: Int
    ): List<com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo> = coroutineScope {
        val op = RegionalOperators.ELEVEN.firstOrNull { it.code == code } ?: return@coroutineScope emptyList()
        val json = withContext(Dispatchers.IO) { get("${elevenBase(code)}/stops/$stopId/routes") } ?: return@coroutineScope emptyList()
        val routes = RegionalParsers.elevenStopRoutes(json)
        val nowS = nowLisbon().toLocalTime().toSecondOfDay()
        routes.flatMap { r -> r.passes.map { r to it } }
            .mapNotNull { (r, p) ->
                var dt = p.seconds - nowS
                if (dt < -12 * 3600) dt += 24 * 3600
                if (dt < -120 || dt > aheadSecs) null else Triple(r, p, dt)
            }
            .map { (r, p, dt) ->
                async(Dispatchers.IO) {
                    val key = "$code|${r.id}|${p.direction}"
                    // "" = circular (mostra-se o nome da linha); sem resposta não se guarda
                    val dest = elevenDestinations[key] ?: run {
                        val text = runCatching { get("${elevenBase(code)}/routes/${r.id}/journeys/${p.journeyId}") }.getOrNull()
                        if (text == null) ""
                        else (runCatching { RegionalParsers.elevenJourneyDestination(text) }.getOrNull() ?: "")
                            .also { elevenDestinations[key] = it }
                    }
                    com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo(
                        routeShortName = r.label,
                        routeLongName = r.name,
                        destination = dest.ifEmpty { r.name.ifEmpty { r.label } },
                        etaMinutes = (dt / 60).coerceAtLeast(0),
                        vehicleId = null,
                        operator = "eleven:$code",
                        color = r.color ?: op.color,
                        realtime = false,
                        delayMinutes = null,
                        scheduledTime = com.example.metrotime.data.bus.BusTiming.clock(p.seconds % (24 * 3600))
                    )
                }
            }.awaitAll()
            .sortedBy { it.etaMinutes }
    }

    /** Todas as paragens dos pacotes (MobiCascais, TCB, MTS, Fertagus, barcos, STCP, Metro do Porto), para o mapa. */
    fun packStops(): List<Pair<RegionalOperator, TransitPack.Stop>> =
        RegionalOperators.WITH_PACK.filter { it.kind != VehicleKind.SUBWAY }.flatMap { op ->
            // (as estações do Metro do Porto são as bolas do metro, como em Lisboa)
            val pack = op.pack?.let(::pack) ?: return@flatMap emptyList()
            pack.stops.map { op to it }
        }
}
