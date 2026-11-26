package com.example.metrotime.data.bus

import android.content.Context
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/**
 * Horários da Carris (assets/carris_timetable.bin, gerado do GTFS por tools/build_carris_timetable.py):
 * cada percurso com as paragens, os tempos entre elas e as viagens do dia. Serve para saber a hora
 * prevista de um autocarro numa paragem, o atraso dos que vão com GPS e os que ainda só estão previstos.
 */
object CarrisTimetable {

    class Service(val name: String, private val days: Int, private val start: Int, private val end: Int,
                  private val adds: IntArray, private val removes: IntArray) {
        fun runsOn(date: LocalDate): Boolean {
            val d = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
            if (d in adds) return true
            if (d in removes) return false
            return d in start..end && days and (1 shl (date.dayOfWeek.value - 1)) != 0
        }
    }

    /** Um percurso (linha, sentido e paragens) com os perfis de tempos (minutos desde o início) e as viagens. */
    class Pattern(
        val routeId: String,
        val direction: Int,
        val stops: Array<String>,
        val profiles: Array<IntArray>,
        val tripA: IntArray,
        val tripB: IntArray,
        val starts: IntArray,
        val profile: IntArray,
        val service: IntArray
    ) {
        /** Segundos desde a meia-noite (do dia de serviço) em que a viagem [trip] passa na paragem [stopIdx]. */
        fun timeAt(trip: Int, stopIdx: Int): Int = starts[trip] + profiles[profile[trip]][stopIdx] * 60
    }

    /** Paragem com nome, posição e concelho (só no horário da Carris Metropolitana). */
    class StopMeta(val name: String, val lat: Double, val lon: Double, val area: String)

    class Data(
        val feedDate: String,
        val services: List<Service>,
        val routes: Map<String, Pair<String, String>>,
        val patterns: List<Pattern>,
        /** Cor de cada linha ("C61D23"), quando o ficheiro a traz. */
        val colors: Map<String, String> = emptyMap(),
        /** Nome e posição das paragens, quando o ficheiro os traz. */
        val stopMeta: Map<String, StopMeta> = emptyMap()
    ) {
        private val byRoute: Map<String, List<Int>> = patterns.indices.groupBy { patterns[it].routeId }

        /** Paragem → percursos que lá passam e a posição dela em cada um (os circulares podem passar duas vezes). */
        val byStop: Map<String, List<Pair<Int, Int>>> = HashMap<String, MutableList<Pair<Int, Int>>>().apply {
            patterns.forEachIndexed { p, pattern ->
                pattern.stops.forEachIndexed { s, stop -> getOrPut(stop) { mutableListOf() }.add(p to s) }
            }
        }

        val tripCount: Int get() = patterns.sumOf { it.starts.size }

        fun tripId(pattern: Pattern, i: Int) = "${pattern.tripA[i]}_${feedDate}_${pattern.routeId}_${pattern.tripB[i]}"

        /**
         * Viagem do GPS ("3876_20260606_170_0_12") → (índice do percurso shl 16) or índice da viagem; null se
         * não estiver neste horário. Procura só nos percursos da linha (sem um mapa de 58 mil textos).
         */
        fun ref(tripId: String): Int? {
            val parts = tripId.split("_")
            if (parts.size < 4 || parts[1] != feedDate) return null
            val a = parts.first().toIntOrNull() ?: return null
            val b = parts.last().toIntOrNull() ?: return null
            val route = parts.subList(2, parts.size - 1).joinToString("_")
            for (p in byRoute[route].orEmpty()) {
                val pattern = patterns[p]
                for (i in pattern.starts.indices) if (pattern.tripA[i] == a && pattern.tripB[i] == b) return (p shl 16) or i
            }
            return null
        }

        /**
         * Um autocarro com GPS que não está no horário (reforço, viagem nova): num percurso da mesma linha e
         * sentido que passa em [stopId], onde vai (pela paragem e sequência do GPS) e quantos segundos faltam
         * para lá chegar pelos tempos do percurso. null se já passou na paragem, se vai no outro sentido ou se
         * não se sabe onde vai (antes punha-se pela distância em linha reta e aparecia um que já tinha passado).
         */
        fun placeUnlisted(
            routeId: String, direction: Int?, vehicleStop: String?, vehicleSeq: Int?, stopped: Boolean,
            stopId: String, nowSec: Int
        ): Placement? {
            if (vehicleStop == null) return null
            val seqIdx = vehicleSeq?.minus(1)
            var best: Placement? = null
            var bestExact = false
            for ((p, stopIdx) in byStop[stopId].orEmpty()) {
                val pattern = patterns[p]
                if (pattern.routeId != routeId || (direction != null && pattern.direction != direction)) continue
                if (pattern.starts.isEmpty()) continue
                // A sequência do GPS confirmada pelo id da paragem; senão a ocorrência da paragem mais perto dela
                val exact = seqIdx != null && pattern.stops.getOrNull(seqIdx) == vehicleStop
                val cur = if (exact) seqIdx!! else pattern.stops.indices.filter { pattern.stops[it] == vehicleStop }
                    .minByOrNull { if (seqIdx == null) it else kotlin.math.abs(it - seqIdx) } ?: continue
                if (cur > stopIdx) continue
                // Tempos da viagem que devia estar ali agora (os perfis mudam com a hora do dia)
                val i = pattern.starts.indices.minBy { kotlin.math.abs(pattern.timeAt(it, cur) - nowSec) }
                val toStop = pattern.timeAt(i, stopIdx) - pattern.timeAt(i, cur)
                // A caminho da paragem do GPS: conta metade do troço até lá
                val eta = if (stopped || cur == 0) toStop
                else toStop + (pattern.timeAt(i, cur) - pattern.timeAt(i, cur - 1)) / 2
                if (best == null || (exact && !bestExact) || (exact == bestExact && eta < best.etaSeconds)) {
                    best = Placement(p, eta)
                    bestExact = exact
                }
            }
            return best
        }
    }

    /** Onde ficou um autocarro fora do horário: o percurso e os segundos até à paragem. */
    data class Placement(val pattern: Int, val etaSeconds: Int)

    @Volatile private var data: Data? = null
    @Volatile private var failed = false

    /** Carrega uma vez (cerca de 300 KB); null se o ficheiro faltar ou estiver estragado. */
    @Synchronized
    fun load(context: Context): Data? {
        data?.let { return it }
        if (failed) return null
        val t0 = android.os.SystemClock.elapsedRealtime()
        return runCatching { context.assets.open("carris_timetable.bin").use { parse(it) } }
            .onFailure { failed = true; it.printStackTrace() }
            .getOrNull()
            ?.also {
                data = it
                android.util.Log.d("CarrisTimetable", "carregado em ${android.os.SystemClock.elapsedRealtime() - t0} ms: ${it.tripCount} viagens")
            }
    }

    fun parse(input: InputStream): Data {
        // Tudo descomprimido para memória (cerca de 750 KB) e lido daí: byte a byte sobre o gzip era lento
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
        require(magic == "CTT1" || magic == "CTT2") { "horário: formato desconhecido ($magic)" }
        // CTT2 (Carris Metropolitana): cada linha com a cor e cada paragem com nome, posição e concelho
        val v2 = magic == "CTT2"
        val feedDate = text()
        val services = List(varint()) { Service(text(), varint(), varint(), varint(), ints(), ints()) }
        val routes = HashMap<String, Pair<String, String>>()
        val colors = HashMap<String, String>()
        repeat(varint()) {
            val id = text()
            routes[id] = text() to text()
            if (v2) colors[id] = text()
        }
        val stopMeta = HashMap<String, StopMeta>()
        val stopIds = Array(varint()) {
            val id = text()
            if (v2) stopMeta[id] = StopMeta(text(), varint() / 1e6, varint() / 1e6 - 180, text())
            id
        }
        val patterns = List(varint()) {
            val routeId = text()
            val direction = varint()
            val stops = Array(varint()) { stopIds[varint()] }
            val profiles = Array(varint()) {
                // Diferenças em minutos desde a paragem anterior; a primeira é 0
                IntArray(stops.size).also { offs -> for (s in 1 until stops.size) offs[s] = offs[s - 1] + varint() }
            }
            val n = varint()
            val a = IntArray(n); val b = IntArray(n); val st = IntArray(n); val pr = IntArray(n); val sv = IntArray(n)
            for (i in 0 until n) { a[i] = varint(); b[i] = varint(); st[i] = varint(); pr[i] = varint(); sv[i] = varint() }
            Pattern(routeId, direction, stops, profiles, a, b, st, pr, sv)
        }
        return Data(feedDate, services, routes, patterns, colors, stopMeta)
    }
}

/**
 * Horário da Carris Metropolitana (assets/cm_timetable.bin, gerado do GTFS por tools/build_cm_timetable.py): as
 * linhas, as viagens e as paragens (nome, posição e concelho) de toda a área metropolitana, para o planeador e
 * para a pesquisa. Cerca de 460 KB.
 */
object CmTimetable {
    @Volatile private var data: CarrisTimetable.Data? = null
    @Volatile private var failed = false

    @Synchronized
    fun load(context: Context): CarrisTimetable.Data? {
        data?.let { return it }
        if (failed) return null
        return runCatching { context.assets.open("cm_timetable.bin").use { CarrisTimetable.parse(it) } }
            .onFailure { failed = true; it.printStackTrace() }
            .getOrNull()
            ?.also { data = it }
    }

    /** O operador ("[A2L1N]") de uma linha ("[A2L1N]4001_0") ou de um serviço ("[ZLLEW][A2L1N]1000"). */
    fun agencyOf(id: String): String = Regex("\\[([A-Z0-9]+)]").findAll(id).lastOrNull()?.groupValues?.get(1) ?: ""
}
