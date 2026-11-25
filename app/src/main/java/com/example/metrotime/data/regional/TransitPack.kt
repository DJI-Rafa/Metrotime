package com.example.metrotime.data.regional

import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/**
 * Paragens, linhas, traçados e horários de um operador (gerados do GTFS por functions/transit.js: todos os dias pela
 * Cloud Function, e os assets/transit_<código>.bin por `node build_packs.js`): MobiCascais, TCB, MTS, Fertagus,
 * Transtejo/Soflusa, STCP e Metro do Porto (horários oficiais com o traçado do OpenStreetMap).
 * Serve para pôr as paragens no mapa, as próximas partidas de cada uma, e as paragens e o traçado do veículo aberto.
 * Sem dependências Android (testado em app/src/test).
 */
class TransitPack(
    val code: String,
    val version: String,
    val services: List<Service>,
    val routes: List<Route>,
    val stops: List<Stop>,
    private val encodedShapes: List<String>,
    val patterns: List<Pattern>
) {
    class Service(val name: String, private val days: Int, private val start: Int, private val end: Int,
                  private val adds: IntArray, private val removes: IntArray) {
        fun runsOn(date: LocalDate): Boolean {
            val d = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
            if (d in adds) return true
            if (d in removes) return false
            return d in start..end && days and (1 shl (date.dayOfWeek.value - 1)) != 0
        }
    }

    class Route(val id: String, val shortName: String, val longName: String, val color: String?, val type: Int) {
        /** O número da linha, ou o nome quando não há número. */
        val label: String get() = shortName.ifBlank { longName }
    }

    class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

    /**
     * Um percurso: linha, sentido, destino, as paragens (índices em [stops]) e os perfis de tempos (segundos desde a
     * primeira paragem), com as viagens (id, partida, perfil e serviço).
     */
    class Pattern(
        val route: Int,
        val direction: Int,
        val headsign: String,
        /** Índice em [shapes]; -1 sem traçado. */
        val shape: Int,
        val stops: IntArray,
        val profiles: Array<IntArray>,
        val tripIds: Array<String>,
        val starts: IntArray,
        val profile: IntArray,
        val service: IntArray
    ) {
        /** Segundos desde a meia-noite (do dia de serviço) em que a viagem [trip] passa na paragem [pos]. */
        fun timeAt(trip: Int, pos: Int): Int = starts[trip] + profiles[profile[trip]][pos]

        /** Tempo típico entre duas paragens do percurso (o primeiro perfil), para quando não há viagens. */
        fun typicalSeconds(from: Int, to: Int): Int = profiles.firstOrNull()?.let { it[to] - it[from] } ?: 0
    }

    /** Uma viagem que passa numa paragem: hora (segundos desde a meia-noite de hoje; pode ser negativa ou > 24 h). */
    class Departure(val pattern: Int, val trip: Int, val position: Int, val seconds: Int)

    private val stopIndex: Map<String, Int> = HashMap<String, Int>(stops.size * 2).apply {
        stops.forEachIndexed { i, s -> put(s.id, i) }
    }

    /** Paragem → percursos que lá passam e a posição dela em cada um (os circulares podem passar duas vezes). */
    private val byStop: Map<Int, List<Pair<Int, Int>>> by lazy {
        HashMap<Int, MutableList<Pair<Int, Int>>>().apply {
            patterns.forEachIndexed { p, pattern ->
                pattern.stops.forEachIndexed { pos, s -> getOrPut(s) { mutableListOf() }.add(p to pos) }
            }
        }
    }

    /** trip_id → (percurso shl 32) or viagem. Construído na primeira procura. */
    private val tripIndex: Map<String, Long> by lazy {
        HashMap<String, Long>().apply {
            patterns.forEachIndexed { p, pattern ->
                pattern.tripIds.forEachIndexed { t, id -> put(id, (p.toLong() shl 32) or t.toLong()) }
            }
        }
    }

    private val decodedShapes = arrayOfNulls<List<Pair<Double, Double>>>(encodedShapes.size)

    fun stopIndexOf(id: String): Int? = stopIndex[id]

    fun shape(index: Int): List<Pair<Double, Double>> {
        if (index !in encodedShapes.indices) return emptyList()
        decodedShapes[index]?.let { return it }
        return RegionalParsers.decodePolyline(encodedShapes[index]).also { decodedShapes[index] = it }
    }

    /** As paragens de um percurso por onde passa o traçado; sem traçado, linhas retas entre as paragens. */
    fun geometry(pattern: Pattern): List<Pair<Double, Double>> =
        shape(pattern.shape).takeIf { it.size >= 2 } ?: pattern.stops.map { stops[it].lat to stops[it].lon }

    /**
     * Viagem pelo trip_id. Os do GO Hub vêm com o plano e o operador à frente ("[1NN3V][HF16N]M45-1-004-E…"): tiram-se.
     */
    fun findTrip(tripId: String): Pair<Int, Int>? {
        val clean = tripId.replace(Regex("^(\\[[^]]*])+"), "")
        val ref = tripIndex[clean] ?: tripIndex[tripId] ?: return null
        return (ref shr 32).toInt() to (ref and 0xFFFFFFFFL).toInt()
    }

    /**
     * Viagens que passam na paragem [stopId] entre [fromSec] e [toSec] (segundos desde a meia-noite de [today]),
     * dos serviços de hoje e das viagens de ontem que passam da meia-noite. Ordenadas pela hora.
     */
    fun departures(stopId: String, today: LocalDate, fromSec: Int, toSec: Int): List<Departure> {
        val s = stopIndex[stopId] ?: return emptyList()
        val runsToday = BooleanArray(services.size) { services[it].runsOn(today) }
        val runsYesterday = BooleanArray(services.size) { services[it].runsOn(today.minusDays(1)) }
        val out = ArrayList<Departure>()
        for ((p, pos) in byStop[s].orEmpty()) {
            val pattern = patterns[p]
            // Na última paragem já não se parte para lado nenhum
            if (pos == pattern.stops.lastIndex) continue
            for (t in pattern.starts.indices) {
                val at = pattern.timeAt(t, pos)
                val svc = pattern.service[t]
                if (runsToday[svc] && at in fromSec..toSec) out += Departure(p, t, pos, at)
                if (runsYesterday[svc] && at - 86_400 in fromSec..toSec) out += Departure(p, t, pos, at - 86_400)
            }
        }
        out.sortBy { it.seconds }
        return out
    }

    /** Percursos de uma linha (pelo número) num sentido; o mais comprido primeiro. */
    fun patternsOf(routeShort: String, direction: Int?): List<Int> =
        patterns.indices.filter { p ->
            val pattern = patterns[p]
            routes[pattern.route].shortName.equals(routeShort, ignoreCase = true) &&
                (direction == null || pattern.direction == direction)
        }.sortedByDescending { patterns[it].stops.size }

    companion object {
        fun parse(input: InputStream): TransitPack {
            val bytes = GZIPInputStream(input.buffered()).use { it.readBytes() }
            var pos = 0
            fun varint(): Int {
                var result = 0
                var shift = 0
                while (true) {
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            fun text(): String {
                val n = varint()
                return String(bytes, pos, n, Charsets.UTF_8).also { pos += n }
            }
            fun ints(): IntArray = IntArray(varint()) { varint() }

            val magic = String(bytes, 0, 4, Charsets.US_ASCII).also { pos = 4 }
            require(magic == "TPK1") { "pacote de transportes: formato desconhecido ($magic)" }
            val code = text()
            val version = text()
            val services = List(varint()) { Service(text(), varint(), varint(), varint(), ints(), ints()) }
            val routes = List(varint()) {
                Route(text(), text(), text(), text().takeIf { it.length == 6 }?.let { "#$it" }, varint())
            }
            val stops = List(varint()) { Stop(text(), text(), varint() / 1e6, varint() / 1e6 - 180) }
            val shapes = List(varint()) { text() }
            val patterns = List(varint()) {
                val route = varint()
                val direction = varint()
                val headsign = text()
                val shape = varint() - 1
                val stopIdx = ints()
                val profiles = Array(varint()) {
                    IntArray(stopIdx.size).also { offs -> for (s in 1 until stopIdx.size) offs[s] = offs[s - 1] + varint() }
                }
                val n = varint()
                val ids = arrayOfNulls<String>(n)
                val st = IntArray(n); val pr = IntArray(n); val sv = IntArray(n)
                for (i in 0 until n) { ids[i] = text(); st[i] = varint(); pr[i] = varint(); sv[i] = varint() }
                @Suppress("UNCHECKED_CAST")
                Pattern(route, direction, headsign, shape, stopIdx, profiles, ids as Array<String>, st, pr, sv)
            }
            return TransitPack(code, version, services, routes, stops, shapes, patterns)
        }
    }
}

/**
 * Junta o horário ([TransitPack]) com as posições em direto: onde vai cada veículo, o atraso, as paragens que lhe
 * faltam e as próximas chegadas a uma paragem. Sem dependências Android (testado em app/src/test).
 */
object TransitLive {

    /** Chegada a uma paragem: com veículo em direto ([vehicleKey]) ou só pelo horário. Horas em segundos de hoje. */
    data class Arrival(
        val route: TransitPack.Route,
        val headsign: String,
        val scheduledSec: Int?,
        val expectedSec: Int,
        val vehicleKey: String?,
        val delaySeconds: Int?
    ) {
        val realtime: Boolean get() = vehicleKey != null
    }

    /** Segundos do dia de serviço [serviceDate] (AAAAMMDD) para [nowSecOfDay] de [today]: a viagem de ontem passa das 24 h. */
    fun serviceSeconds(nowSecOfDay: Int, today: Int, serviceDate: Int?): Int =
        if (serviceDate != null && serviceDate < today) nowSecOfDay + 86_400 else nowSecOfDay

    /** Posição da paragem [stopId] no percurso (a primeira; nos circulares, a mais perto de [near]). */
    fun positionOf(pack: TransitPack, pattern: TransitPack.Pattern, stopId: String?, near: Int? = null): Int? {
        val idx = stopId?.let { pack.stopIndexOf(it) } ?: return null
        val all = pattern.stops.indices.filter { pattern.stops[it] == idx }
        if (all.isEmpty()) return null
        return if (near == null) all.first() else all.minBy { kotlin.math.abs(it - near) }
    }

    /**
     * Atraso de uma viagem com GPS pela paragem [pos] para onde vai (ou onde está, se [stopped]): o mesmo cálculo dos
     * autocarros da Carris (agora menos a hora prevista).
     */
    fun delayOf(pattern: TransitPack.Pattern, trip: Int, pos: Int, stopped: Boolean, nowSec: Int): Int =
        com.example.metrotime.data.bus.BusTiming.delaySeconds(
            if (pos > 0) pattern.timeAt(trip, pos - 1) else null, pattern.timeAt(trip, pos), nowSec, stopped
        )

    /** A viagem [trip] do percurso [p] com as horas do horário mais o atraso; [pos]: paragem atual ou seguinte. */
    fun scheduledTrip(pack: TransitPack, p: Int, trip: Int, pos: Int?, delay: Int?, nowSec: Int): RegionalTrip {
        val pattern = pack.patterns[p]
        val route = pack.routes[pattern.route]
        val stops = pattern.stops.indices.map { i ->
            val stop = pack.stops[pattern.stops[i]]
            val sched = pattern.timeAt(trip, i)
            val expected = sched + (delay ?: 0)
            val passed = if (pos != null) i < pos else expected < nowSec - 30
            RegionalStop(
                name = RegionalParsers.prettyName(stop.name),
                lat = stop.lat,
                lon = stop.lon,
                clock = RegionalParsers.clock(sched),
                dueMinutes = if (passed) null else ((expected - nowSec + 30) / 60).coerceAtLeast(0),
                passed = passed
            )
        }
        return RegionalTrip(
            line = route.label,
            lineName = route.longName.takeIf { it.isNotBlank() }?.let(RegionalParsers::prettyName),
            color = route.color,
            destination = RegionalParsers.prettyName(pattern.headsign),
            stops = stops,
            shape = pack.geometry(pattern)
        )
    }

    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(aLat))
        val dx = (bLon - aLon) * kx
        val dy = (bLat - aLat) * 110_540.0
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Sem paragem na posição (a Fertagus no GO Hub): onde vai o veículo no percurso pela posição. A paragem mais perto
     * se está nela (até [atStopMetres]); senão, a seguinte. (posição, parado nela); null se estiver a mais de 10 km
     * (a viagem já é conhecida: o limite só tira posições impossíveis; na ponte há 5 km entre estações).
     */
    fun positionByLocation(
        pack: TransitPack, pattern: TransitPack.Pattern, lat: Double, lon: Double, atStopMetres: Double = 150.0
    ): Pair<Int, Boolean>? {
        var k = -1
        var dk = Double.MAX_VALUE
        for (i in pattern.stops.indices) {
            val s = pack.stops[pattern.stops[i]]
            val d = metres(lat, lon, s.lat, s.lon)
            if (d < dk) { dk = d; k = i }
        }
        if (k < 0 || dk > 10_000) return null
        if (dk <= atStopMetres) return k to true
        if (k == pattern.stops.lastIndex) return k to false
        val a = pack.stops[pattern.stops[k]]
        val b = pack.stops[pattern.stops[k + 1]]
        return (if (metres(lat, lon, b.lat, b.lon) < metres(a.lat, a.lon, b.lat, b.lon)) k + 1 else k) to false
    }

    /**
     * Sem viagem no horário (STCP): o percurso da linha e sentido que passa mais perto do veículo e a paragem seguinte
     * nele (a mais perto, ou a depois dela se o veículo já vai a caminho dessa). null se nenhum passar a 400 m.
     */
    fun nearestPattern(pack: TransitPack, routeShort: String, direction: Int?, lat: Double, lon: Double): Pair<Int, Int>? {
        var best: Triple<Int, Int, Double>? = null
        val candidates = pack.patternsOf(routeShort, direction).ifEmpty { pack.patternsOf(routeShort, null) }
        for (p in candidates) {
            val pattern = pack.patterns[p]
            var k = -1
            var dk = Double.MAX_VALUE
            for (i in pattern.stops.indices) {
                val s = pack.stops[pattern.stops[i]]
                val d = metres(lat, lon, s.lat, s.lon)
                if (d < dk) { dk = d; k = i }
            }
            if (k < 0) continue
            if (best == null || dk < best.third) best = Triple(p, k, dk)
        }
        val (p, k, d) = best ?: return null
        if (d > 400) return null
        val pattern = pack.patterns[p]
        // Entre a paragem mais perto e a seguinte: vai para a seguinte
        val next = if (k < pattern.stops.lastIndex && d > 25) {
            val a = pack.stops[pattern.stops[k]]
            val b = pack.stops[pattern.stops[k + 1]]
            if (metres(lat, lon, b.lat, b.lon) < metres(a.lat, a.lon, b.lat, b.lon)) k + 1 else k
        } else k
        return p to next
    }

    /** Viagem estimada (sem horário): os minutos até cada paragem pelos tempos típicos do percurso. */
    fun estimatedTrip(pack: TransitPack, p: Int, nextPos: Int): RegionalTrip {
        val pattern = pack.patterns[p]
        val route = pack.routes[pattern.route]
        val stops = pattern.stops.indices.map { i ->
            val stop = pack.stops[pattern.stops[i]]
            RegionalStop(
                name = RegionalParsers.prettyName(stop.name),
                lat = stop.lat,
                lon = stop.lon,
                clock = null,
                dueMinutes = if (i < nextPos) null else ((pattern.typicalSeconds(nextPos, i) + 30) / 60).coerceAtLeast(0),
                passed = i < nextPos
            )
        }
        return RegionalTrip(
            line = route.label,
            lineName = route.longName.takeIf { it.isNotBlank() }?.let(RegionalParsers::prettyName),
            color = route.color,
            destination = RegionalParsers.prettyName(pattern.headsign),
            stops = stops,
            shape = pack.geometry(pattern)
        )
    }

    /**
     * Próximas chegadas à paragem [stopId]: as do horário (com o veículo em direto quando ele vai nessa viagem) e, nas
     * redes sem horário (STCP), os veículos em direto que vêm a caminho. [nowSec] em segundos de [today].
     */
    fun arrivals(
        pack: TransitPack,
        stopId: String,
        today: java.time.LocalDate,
        nowSec: Int,
        aheadSec: Int,
        live: List<RegionalVehicle>
    ): List<Arrival> {
        val out = ArrayList<Arrival>()
        // Viagens em direto deste pacote: (percurso, viagem) → veículo
        val liveTrips = HashMap<Long, RegionalVehicle>()
        for (v in live) {
            val (p, t) = v.tripId?.let { pack.findTrip(it) } ?: continue
            liveTrips[(p.toLong() shl 32) or t.toLong()] = v
        }
        for (d in pack.departures(stopId, today, nowSec - 45 * 60, nowSec + aheadSec)) {
            val pattern = pack.patterns[d.pattern]
            val route = pack.routes[pattern.route]
            val v = liveTrips[(d.pattern.toLong() shl 32) or d.trip.toLong()]
            if (v != null) {
                // Já passou nesta paragem?
                val vPos = positionOf(pack, pattern, v.stopId, d.position)
                if (vPos != null && vPos > d.position) continue
                val expected = d.seconds + (v.delaySeconds ?: 0)
                if (expected < nowSec - 60) continue
                out += Arrival(route, pattern.headsign, d.seconds, expected.coerceAtLeast(nowSec), v.key, v.delaySeconds)
            } else if (d.seconds >= nowSec) {
                out += Arrival(route, pattern.headsign, d.seconds, d.seconds, null, null)
            }
        }
        // Redes sem horário (ou veículos fora dele): os que vêm a caminho pela linha e sentido
        val stopIdx = pack.stopIndexOf(stopId)
        if (stopIdx != null) {
            for (v in live) {
                if (v.tripId != null && pack.findTrip(v.tripId) != null) continue
                val line = v.line ?: continue
                val (p, next) = nearestPattern(pack, line, v.direction, v.lat, v.lon) ?: continue
                val pattern = pack.patterns[p]
                val pos = (next until pattern.stops.size).firstOrNull { pattern.stops[it] == stopIdx } ?: continue
                val eta = pattern.typicalSeconds(next, pos)
                if (eta > aheadSec) continue
                out += Arrival(pack.routes[pattern.route], pattern.headsign, null, nowSec + eta, v.key, null)
            }
        }
        out.sortBy { it.expectedSec }
        return out
    }
}
