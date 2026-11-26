package com.example.metrotime.data.plan

import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.MetroNetwork
import com.example.metrotime.data.bus.BusColors
import com.example.metrotime.data.bus.CarrisTimetable
import com.example.metrotime.data.bus.CmTimetable
import java.time.LocalDate

/**
 * Constrói a rede do [Raptor]: o metro (horas estimadas pelo intervalo entre metros: a API do Metro não dá
 * horários), a Carris e a Carris Metropolitana (horários do GTFS), com os caminhos a pé entre paragens perto
 * umas das outras. Sem dependências Android (testado em app/src/test).
 */
object TransitBuilder {

    class StopInfo(val id: String, val name: String, val lat: Double, val lon: Double)

    /** Caminhos a pé entre paragens: até 350 m em linha reta. */
    const val WALK_MAX_M = 350.0
    /** Descer ou subir as escadas do metro. */
    const val METRO_ACCESS_SECS = 60

    /** Segundos a pé (cerca de 80 m por minuto em linha reta, como no resto da app). */
    fun walkSecs(metres: Double): Int = (metres * 60 / 80).toInt()

    fun metroStopId(apiId: String) = "M:$apiId"

    /** As paragens da Carris Metropolitana têm o prefixo "CM:" na rede (os números repetem-se com os da Carris). */
    const val CM_PREFIX = "CM:"

    fun cmStopId(id: String) = CM_PREFIX + id

    /** O operador de uma paragem da rede ("carris" ou "carris_metropolitana") e o número dela nesse operador. */
    fun operatorOf(stopId: String): Pair<String, String> =
        if (stopId.startsWith(CM_PREFIX)) "carris_metropolitana" to stopId.removePrefix(CM_PREFIX) else "carris" to stopId

    /** Um horário de autocarros na rede: os percursos dele são os de [first] até [first] + n - 1. */
    internal class BusSource(val tt: CarrisTimetable.Data, val first: Int, val cm: Boolean)

    class MetroLine(val line: String, val stops: IntArray, val offsets: IntArray, val terminal: String)

    /** A parte que não muda de pesquisa para pesquisa: paragens, caminhos a pé e percursos. */
    class Static internal constructor(
        val stops: List<TransitStop>,
        val index: Map<String, Int>,
        val walkTo: Array<IntArray>,
        val walkSecs: Array<IntArray>,
        internal val sources: List<BusSource>,
        internal val patternStops: List<IntArray>,
        internal val patternProfiles: List<Array<IntArray>>,
        internal val patternLabel: List<String>,
        internal val patternHeadsign: List<String>,
        internal val patternColor: List<String>,
        internal val metro: List<MetroLine>
    )

    /** [tt]: horário da Carris (paragens em [carrisStops]); [cm]: horário da Carris Metropolitana (traz as paragens). */
    fun buildStatic(tt: CarrisTimetable.Data?, carrisStops: List<StopInfo>, cm: CarrisTimetable.Data? = null): Static {
        val stops = ArrayList<TransitStop>()
        MapConfiguration.STATION_COORDS.values.forEach { stops += TransitStop(metroStopId(it.id), it.name, it.lat, it.lon, true) }
        val known = carrisStops.associateBy { it.id }
        val carrisIds = LinkedHashSet<String>()
        carrisStops.forEach { carrisIds += it.id }
        tt?.patterns?.forEach { p -> p.stops.forEach { carrisIds += it } }
        carrisIds.forEach { id ->
            val s = known[id]
            stops += TransitStop(id, s?.name ?: id, s?.lat ?: Double.NaN, s?.lon ?: Double.NaN, false)
        }
        cm?.let { c ->
            val ids = LinkedHashSet<String>()
            c.patterns.forEach { p -> p.stops.forEach { ids += it } }
            ids.forEach { id ->
                val m = c.stopMeta[id]
                stops += TransitStop(cmStopId(id), m?.name ?: id, m?.lat ?: Double.NaN, m?.lon ?: Double.NaN, false)
            }
        }
        val index = HashMap<String, Int>(stops.size * 2)
        stops.forEachIndexed { i, s -> index[s.id] = i }

        val (walkTo, walkSecs) = walkTransfers(stops)

        val sources = ArrayList<BusSource>()
        val patternStops = ArrayList<IntArray>()
        val patternProfiles = ArrayList<Array<IntArray>>()
        val patternLabel = ArrayList<String>()
        val patternHeadsign = ArrayList<String>()
        val patternColor = ArrayList<String>()
        for ((source, isCm) in listOf(tt to false, cm to true)) {
            if (source == null) continue
            sources += BusSource(source, patternStops.size, isCm)
            source.patterns.forEach { p ->
                val ids = if (isCm) p.stops.map(::cmStopId) else p.stops.toList()
                val label = source.routes[p.routeId]?.first ?: p.routeId
                patternStops += IntArray(ids.size) { index.getValue(ids[it]) }
                patternProfiles += Array(p.profiles.size) { k -> IntArray(p.stops.size) { p.profiles[k][it] * 60 } }
                patternLabel += label
                patternHeadsign += stops[index.getValue(ids.last())].name
                patternColor += if (isCm) source.colors[p.routeId]?.takeIf { it.length == 6 }?.let { "#$it" } ?: BusColors.FALLBACK
                else BusColors.carris(label)
            }
        }

        val metro = MetroLines.NAMES.flatMap { line ->
            val order = MetroLines.stops(line)
            listOf(order, order.reversed()).map { seq ->
                val offsets = IntArray(seq.size)
                for (i in 1 until seq.size) {
                    offsets[i] = offsets[i - 1] + Math.round((MetroNetwork.hopMinutes(seq[i - 1], seq[i]) ?: 1.5) * 60).toInt()
                }
                MetroLine(line, IntArray(seq.size) { index.getValue(metroStopId(seq[it])) }, offsets, MetroLines.nameOf(seq.last()))
            }
        }
        return Static(stops, index, walkTo, walkSecs, sources, patternStops, patternProfiles, patternLabel, patternHeadsign, patternColor, metro)
    }

    /** Caminhos a pé até [WALK_MAX_M] entre todas as paragens com posição, por uma grelha de ~440 m. */
    private fun walkTransfers(stops: List<TransitStop>): Pair<Array<IntArray>, Array<IntArray>> {
        val cellLat = 0.004
        val cellLon = 0.005
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        stops.forEachIndexed { idx, s ->
            if (s.hasPosition) grid.getOrPut(key(Math.floor(s.lat / cellLat).toInt(), Math.floor(s.lon / cellLon).toInt())) { mutableListOf() } += idx
        }
        val to = Array(stops.size) { IntArray(0) }
        val secs = Array(stops.size) { IntArray(0) }
        stops.forEachIndexed { a, s ->
            if (!s.hasPosition) return@forEachIndexed
            val ci = Math.floor(s.lat / cellLat).toInt()
            val cj = Math.floor(s.lon / cellLon).toInt()
            val ts = ArrayList<Int>()
            val ss = ArrayList<Int>()
            for (di in -1..1) for (dj in -1..1) {
                for (b in grid[key(ci + di, cj + dj)].orEmpty()) {
                    if (b == a) continue
                    val q = stops[b]
                    val m = MetroNetwork.haversineKm(s.lat, s.lon, q.lat, q.lon) * 1000
                    if (m > WALK_MAX_M) continue
                    ts += b
                    ss += walkSecs(m) + if (s.isMetro || q.isMetro) METRO_ACCESS_SECS else 0
                }
            }
            to[a] = ts.toIntArray()
            secs[a] = ss.toIntArray()
        }
        return to to secs
    }

    /** Paragens a que se chega a pé de um sítio: autocarros até [busM] e metro até [metroM], as mais perto primeiro. */
    fun accessFrom(st: Static, lat: Double, lon: Double, busM: Double = 700.0, metroM: Double = 1_000.0, max: Int = 40): List<Access> =
        st.stops.indices.mapNotNull { i ->
            val s = st.stops[i]
            if (!s.hasPosition) return@mapNotNull null
            val m = MetroNetwork.haversineKm(lat, lon, s.lat, s.lon) * 1000
            if (m > (if (s.isMetro) metroM else busM)) null
            else Access(i, walkSecs(m) + if (s.isMetro) METRO_ACCESS_SECS else 0)
        }.sortedBy { it.secs }.take(max)

    // ── Horários de uma pesquisa ────────────────────────────────────────────────

    /**
     * A rede para uma pesquisa no dia [date], com as viagens que andam entre [fromSec] e [toSec] (segundos desde a
     * meia-noite de [date]): as de hoje, as de ontem que passam da meia-noite e as de amanhã de madrugada.
     */
    fun network(st: Static, date: LocalDate, fromSec: Int, toSec: Int, modes: Set<LegMode>): TransitNetwork {
        val routes = ArrayList<TransitRoute>()
        if (LegMode.BUS in modes) for (source in st.sources) {
            val tt = source.tt
            val days = (-1..1).map { off ->
                val day = date.plusDays(off.toLong())
                off to if (source.cm) cmServicesOn(tt, day) else BooleanArray(tt.services.size) { tt.services[it].runsOn(day) }
            }
            tt.patterns.forEachIndexed { k, pattern ->
                val p = source.first + k
                val profiles = st.patternProfiles[p]
                val longest = profiles.maxOfOrNull { it.last() } ?: 0
                val trips = ArrayList<Long>()
                for ((off, active) in days) {
                    for (i in pattern.starts.indices) {
                        if (!active[pattern.service[i]]) continue
                        val start = pattern.starts[i] + off * 86_400
                        if (start > toSec || start + longest < fromSec) continue
                        // Hora e perfil num só número, para ordenar pela hora
                        trips += (start.toLong() shl 20) or pattern.profile[i].toLong()
                    }
                }
                if (trips.isEmpty()) return@forEachIndexed
                trips.sort()
                routes += TransitRoute(
                    mode = LegMode.BUS,
                    label = st.patternLabel[p],
                    color = st.patternColor[p],
                    headsign = st.patternHeadsign[p],
                    stops = st.patternStops[p],
                    profiles = profiles,
                    starts = IntArray(trips.size) { (trips[it] shr 20).toInt() },
                    profileOf = IntArray(trips.size) { (trips[it] and 0xFFFFF).toInt() }
                )
            }
        }
        if (LegMode.METRO in modes) {
            val departures = MetroSchedule.departures()
            st.metro.forEach { line ->
                val longest = line.offsets.last()
                val starts = (-1..1).flatMap { off -> departures.map { it + off * 86_400 } }
                    .filter { it <= toSec && it + longest >= fromSec }
                    .sorted()
                if (starts.isEmpty()) return@forEach
                routes += TransitRoute(
                    mode = LegMode.METRO,
                    label = "Linha ${line.line}",
                    color = line.line,
                    headsign = line.terminal,
                    stops = line.stops,
                    profiles = arrayOf(line.offsets),
                    starts = starts.toIntArray(),
                    profileOf = IntArray(starts.size)
                )
            }
        }
        return TransitNetwork(st.stops, routes, st.walkTo, st.walkSecs)
    }

    /**
     * Serviços da Carris Metropolitana em [day]. Cada operador publica o horário por uns meses (os "planos"): se
     * um deles ainda não tem horário para esse dia, usa-se o do mesmo dia da semana mais recente que tenha (até
     * 10 semanas antes), para o planeador não ficar sem autocarros nessa zona enquanto o horário não é atualizado.
     */
    internal fun cmServicesOn(tt: CarrisTimetable.Data, day: LocalDate): BooleanArray {
        val agency = tt.services.map { CmTimetable.agencyOf(it.name) }
        val out = BooleanArray(tt.services.size) { tt.services[it].runsOn(day) }
        for (a in agency.toSet()) {
            val mine = tt.services.indices.filter { agency[it] == a }
            if (mine.any { out[it] }) continue
            val fallback = (1..10).map { day.minusWeeks(it.toLong()) }.firstOrNull { d -> mine.any { tt.services[it].runsOn(d) } } ?: continue
            mine.forEach { out[it] = tt.services[it].runsOn(fallback) }
        }
        return out
    }
}

/** Partidas estimadas dos terminais do metro: das 6:30 à 1:00, com o intervalo de cada altura do dia. */
object MetroSchedule {

    /** Intervalo entre metros (segundos) a [serviceMinute] minutos do início do dia de serviço (25 h = 1:00). */
    fun headwaySecs(serviceMinute: Int): Int = when {
        serviceMinute < 7 * 60 + 30 -> 450
        serviceMinute < 10 * 60 -> 300
        serviceMinute < 16 * 60 + 30 -> 390
        serviceMinute < 20 * 60 -> 300
        serviceMinute < 22 * 60 -> 480
        else -> 600
    }

    /** Partidas de cada terminal num dia de serviço, em segundos desde a meia-noite desse dia. */
    fun departures(): List<Int> {
        val out = ArrayList<Int>()
        var t = MetroTrip.FIRST_DEPARTURE_MIN * 60
        while (t <= MetroTrip.LAST_DEPARTURE_MIN * 60) {
            out += t
            t += headwaySecs(t / 60)
        }
        return out
    }
}
