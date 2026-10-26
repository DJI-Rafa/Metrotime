package com.example.metrotime.data.cp

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ──────────────────────────────────────────────────────────────────────────────
// Lógica dos comboios CP sem dependências Android (testada em app/src/test).
// ──────────────────────────────────────────────────────────────────────────────

/** Tipos de serviço. Um só sítio para os códigos que as várias APIs usam. */
object CpService {
    const val AP = "AP"
    const val IC = "IC"
    const val IR = "IR"
    const val R = "R"
    const val U = "U"
    const val IN = "IN"
    const val FT = "FT"

    /** Ordem em que os filtros aparecem. */
    val ORDER = listOf(AP, IC, IR, R, U, IN, FT)

    /** Códigos vistos na CP e no comboios.live: 1 AP, 2 IC, 3 IR, 4/40 Regional, 5/45/55 Urbanos, 12 Internacional. */
    fun shortName(code: String?): String = when (code?.trim()) {
        "1" -> AP
        "2" -> IC
        "3" -> IR
        "4", "40" -> R
        "5", "45", "55" -> U
        "12" -> IN
        "UF" -> FT
        null, "" -> "?"
        else -> code
    }

    fun longName(short: String): String = when (short) {
        AP -> "Alfa Pendular"
        IC -> "Intercidades"
        IR -> "InterRegional"
        R -> "Regional"
        U -> "Urbano"
        IN -> "Internacional"
        FT -> "Fertagus"
        else -> short
    }
}

/** Cor do atraso, como no CP Bot: verde até 1:29, laranja até 5 min, vermelho acima, cinzento sem dados. */
enum class CpTone { IDLE, ON_TIME, LATE, VERY_LATE;
    companion object {
        fun ofSeconds(delaySeconds: Int?, hasRealtime: Boolean = true): CpTone = when {
            delaySeconds == null || !hasRealtime -> IDLE
            delaySeconds < 90 -> ON_TIME
            delaySeconds <= 300 -> LATE
            else -> VERY_LATE
        }
    }
}

/**
 * Horas de um comboio como instantes reais. Os horários vêm em "HH:mm" sem data: a partir da data de
 * partida, cada vez que a hora volta mais de 6 h atrás passou-se a meia-noite (antes usava-se
 * "antes das 4h é +24h", que falhava em comboios que partem de madrugada ou passam das 4h).
 */
object CpTripClock {
    fun datesFor(runDate: LocalDate, times: List<String?>): List<LocalDateTime?> {
        var day = runDate
        var last: LocalDateTime? = null
        return times.map { s ->
            val t = CpTime.parseHm(s) ?: return@map null
            var at = LocalDateTime.of(day, t)
            val previous = last
            if (previous != null && at.isBefore(previous.minusHours(6))) {
                day = day.plusDays(1)
                at = LocalDateTime.of(day, t)
            }
            last = at
            at
        }
    }

    /**
     * Chegada e partida de cada paragem (real quando existe, senão prevista). [estimated] = a CP não
     * deu hora real nem atraso para esta paragem e o atraso veio das paragens vizinhas.
     */
    data class StopMoment(
        val scheduledArrival: LocalDateTime?,
        val scheduledDeparture: LocalDateTime?,
        val arrival: LocalDateTime?,
        val departure: LocalDateTime?,
        val estimated: Boolean = false
    ) {
        /** Hora mais útil para mostrar e comparar: partida, ou chegada no fim do percurso. */
        val departureOrArrival: LocalDateTime? get() = departure ?: arrival
        val arrivalOrDeparture: LocalDateTime? get() = arrival ?: departure
    }

    /**
     * Horas de cada paragem: as reais (ETA/ETD) quando a CP as dá. Às que não têm hora real nem atraso
     * soma-se o atraso das vizinhas — antes ficavam com a hora prevista e, num comboio com 20 min de
     * atraso, o relógio dava-o como já tendo passado uma estação a que ainda não tinha chegado.
     */
    fun moments(runDate: LocalDate, stops: List<CpTrainStop>): List<StopMoment> {
        val scheduled = datesFor(runDate, stops.flatMap { listOf(it.arrival, it.departure) })
        val reported = datesFor(runDate, stops.flatMap { listOf(it.eta ?: it.arrival, it.etd ?: it.departure) })
        // Atraso conhecido em cada paragem (minutos): pelas horas reais ou, sem elas, pelo campo delay
        val known: List<Long?> = stops.mapIndexed { i, stop ->
            listOfNotNull(
                if (stop.eta != null) minutesBetween(scheduled[2 * i], reported[2 * i]) else null,
                if (stop.etd != null) minutesBetween(scheduled[2 * i + 1], reported[2 * i + 1]) else null
            ).maxOrNull() ?: stop.delay?.takeIf { it >= 0 }?.toLong()
        }
        return stops.indices.map { i ->
            val stop = stops[i]
            val late = known[i] ?: neighbourDelay(known, i)
            val arrival = if (stop.eta != null) reported[2 * i] else scheduled[2 * i]?.plusMinutes(late)
            var departure = if (stop.etd != null) reported[2 * i + 1] else scheduled[2 * i + 1]?.plusMinutes(late)
            if (arrival != null && departure != null && departure.isBefore(arrival)) departure = arrival
            StopMoment(scheduled[2 * i], scheduled[2 * i + 1], arrival, departure, estimated = known[i] == null && late != 0L)
        }
    }

    private fun minutesBetween(a: LocalDateTime?, b: LocalDateTime?): Long? =
        if (a != null && b != null) Duration.between(a, b).toMinutes() else null

    /** Atraso de uma paragem sem dados: entre o das vizinhas que os têm (0 se nenhuma tiver). */
    private fun neighbourDelay(known: List<Long?>, i: Int): Long {
        val prev = (i - 1 downTo 0).firstOrNull { known[it] != null }
        val next = (i + 1 until known.size).firstOrNull { known[it] != null }
        return when {
            prev != null && next != null -> known[prev]!! + (known[next]!! - known[prev]!!) * (i - prev) / (next - prev)
            next != null -> known[next]!!
            prev != null -> known[prev]!!
            else -> 0L
        }
    }

    /**
     * Onde vai o comboio, em paragens: 3.0 = na 4.ª paragem, 3.4 = 40 % do caminho entre a 4.ª e a 5.ª,
     * -1 = ainda não partiu. Só pelo relógio (horas reais quando existem).
     */
    fun progress(moments: List<StopMoment>, now: LocalDateTime): Float {
        if (moments.isEmpty()) return -1f
        for (i in moments.indices.reversed()) {
            val dep = moments[i].departure
            val arr = moments[i].arrival
            if (dep != null && !now.isBefore(dep)) {
                if (i == moments.lastIndex) return i.toFloat()
                val nextArr = moments[i + 1].arrivalOrDeparture ?: return i + 0.5f
                val span = Duration.between(dep, nextArr).seconds
                if (span <= 0) return i + 0.5f
                val share = Duration.between(dep, now).seconds.toFloat() / span
                if (share >= 1f) return (i + 1).toFloat()
                return i + share.coerceIn(0.05f, 0.95f)
            }
            if (arr != null && !now.isBefore(arr)) return i.toFloat()
        }
        return -1f
    }
}

/** Uma ligação direta entre duas estações. */
data class CpJourneyOption(
    val trainNumber: Int,
    val serviceCode: String?,
    val serviceName: String?,
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val departureStop: CpStationStop,
    val arrivalStop: CpStationStop,
    val runDate: LocalDate
) {
    val duration: Duration get() = Duration.between(departure, arrival)
    /** Atraso em minutos na estação de partida (null = sem dados em tempo real). */
    val delayMinutes: Int? get() = departureStop.delay?.takeIf { it >= 0 }
    val suppressed: Boolean get() = departureStop.supression != null || arrivalStop.supression != null || departureStop.delay == -1
}

/**
 * Pesquisa A → B sem endpoint próprio: cruza os horários das duas estações pelo número do comboio.
 * Um comboio serve se parte da origem e chega ao destino depois (e em menos de 12 h).
 */
object CpJourney {
    fun match(origin: List<CpTimedStop>, destination: List<CpTimedStop>, from: LocalDateTime): List<CpJourneyOption> {
        val arrivals = destination.mapNotNull { t ->
            val at = t.dateTimeOf(t.stop.arrivalTime) ?: return@mapNotNull null
            Triple(t.stop.trainNumber, at, t)
        }.groupBy { it.first }

        return origin.mapNotNull { o ->
            val dep = o.dateTimeOf(o.stop.departureTime) ?: return@mapNotNull null
            val effectiveDep = dep.plusMinutes((o.stop.delay ?: 0).coerceAtLeast(0).toLong())
            if (effectiveDep.isBefore(from.minusMinutes(1))) return@mapNotNull null
            val arr = arrivals[o.stop.trainNumber].orEmpty()
                .filter { it.second.isAfter(dep) && Duration.between(dep, it.second).toHours() < 12 }
                .minByOrNull { it.second } ?: return@mapNotNull null
            CpJourneyOption(
                trainNumber = o.stop.trainNumber,
                serviceCode = o.stop.trainService?.code,
                serviceName = o.stop.trainService?.designation,
                departure = dep,
                arrival = arr.second,
                departureStop = o.stop,
                arrivalStop = arr.third.stop,
                runDate = o.queryDate
            )
        }.distinctBy { it.trainNumber to it.departure }.sortedBy { it.departure }
    }
}

/** Traçado de um percurso, com a distância acumulada de cada ponto. Porta da lógica do CP Bot (map.js). */
class CpTrack(val line: List<Pair<Double, Double>>) {
    val along: DoubleArray = DoubleArray(line.size).also { a ->
        for (i in 1 until line.size) a[i] = a[i - 1] + metres(line[i - 1], line[i])
    }

    data class Hit(val s: Double, val distance: Double, val segment: Int)

    /** Ponto do traçado mais perto de [at] (lat, lon), procurando nos segmentos [from]..[to]. */
    fun locate(at: Pair<Double, Double>, from: Int = 0, to: Int = line.size - 2): Hit {
        var best = Hit(along.getOrElse(max(0, from)) { 0.0 }, Double.MAX_VALUE, max(0, from))
        val k0 = max(0, from)
        val k1 = min(line.size - 2, to)
        val c = cos(Math.toRadians(at.first))
        for (k in k0..k1) {
            val a = line[k]
            val b = line[k + 1]
            val ax = a.second * c; val ay = a.first
            val bx = b.second * c; val by = b.first
            val px = at.second * c; val py = at.first
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0.0, 1.0)
            val qx = ax + vx * t - px; val qy = ay + vy * t - py
            val d = qx * qx + qy * qy
            if (d < best.distance) best = Hit(along[k] + (along[k + 1] - along[k]) * t, d, k)
        }
        return best
    }

    /** Ponto (lat, lon) a [s] metros do início do traçado. */
    fun pointAt(s: Double): Pair<Double, Double> {
        var k = 0
        while (k < along.size - 2 && along[k + 1] < s) k++
        val span = along[k + 1] - along[k]
        val t = if (span == 0.0) 0.0 else ((s - along[k]) / span).coerceIn(0.0, 1.0)
        return Pair(line[k].first + (line[k + 1].first - line[k].first) * t, line[k].second + (line[k + 1].second - line[k].second) * t)
    }

    /** Pedaço do traçado entre duas distâncias, como (lat, lon). */
    fun slice(s0: Double, s1: Double): List<Pair<Double, Double>> {
        if (line.size < 2 || s1 <= s0) return emptyList()
        val out = mutableListOf(pointAt(s0))
        for (k in line.indices) if (along[k] > s0 && along[k] < s1) out.add(line[k])
        out.add(pointAt(s1))
        return out
    }

    /** Onde cada paragem cai no traçado (metros desde o início), sempre para a frente; null sem coordenadas. */
    fun stopPositions(points: List<Pair<Double, Double>?>): List<Double?> {
        var k = 0
        return points.map { p -> if (p == null) null else locate(p, k).also { k = it.segment }.s }
    }

    /**
     * Posição em paragens (como CpTripClock.progress: 2.5 = a meio entre a 3.ª e a 4.ª) do ponto [at],
     * o GPS do comboio, projetado no traçado. null se estiver a mais de [maxOffTrack] metros do traçado
     * (GPS errado ou traçado de outro percurso) ou se não houver duas paragens com posição.
     */
    fun progressOf(stopAt: List<Double?>, at: Pair<Double, Double>, maxOffTrack: Double = 1_000.0): Float? {
        val known = stopAt.withIndex().mapNotNull { (i, s) -> s?.let { i to it } }
        if (known.size < 2 || line.size < 2) return null
        val s = locate(at).s
        if (metres(at, pointAt(s)) > maxOffTrack) return null
        if (s <= known.first().second) return known.first().first.toFloat()
        if (s >= known.last().second) return known.last().first.toFloat()
        val b = known.indexOfFirst { it.second > s }
        val (ia, sa) = known[b - 1]
        val (ib, sb) = known[b]
        return ia + (ib - ia) * ((s - sa) / (sb - sa)).toFloat()
    }

    companion object {
        fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val k = cos(Math.toRadians((a.first + b.first) / 2))
            val dy = (b.first - a.first) * 111320
            val dx = (b.second - a.second) * 111320 * k
            return sqrt(dx * dx + dy * dy)
        }

        /** Rumo de [a] para [b], em graus (0 = norte, 90 = este). */
        fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dLon = Math.toRadians(b.second - a.second)
            val l1 = Math.toRadians(a.first)
            val l2 = Math.toRadians(b.first)
            val y = kotlin.math.sin(dLon) * cos(l2)
            val x = cos(l1) * kotlin.math.sin(l2) - kotlin.math.sin(l1) * cos(l2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** Diferença entre dois rumos, 0..180. */
        fun angleBetween(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180) 360 - d else d
        }

        /** Google "encoded polyline" (precisão 5) para (lat, lon). */
        fun decode(text: String): List<Pair<Double, Double>> {
            val points = ArrayList<Pair<Double, Double>>(text.length / 4)
            var i = 0
            var lat = 0
            var lon = 0
            fun next(): Int {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    b = text[i++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                return if (result and 1 != 0) (result shr 1).inv() else result shr 1
            }
            while (i < text.length) {
                lat += next()
                lon += next()
                points.add(Pair(lat / 1e5, lon / 1e5))
            }
            return points
        }
    }
}

/** Aviso da CP (lido do Firebase, escrito pelo scripts/cp_scraper.py). */
data class CpNotice(
    val title: String,
    val summary: String = "",
    val link: String = "",
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val categories: List<String> = emptyList()
) {
    /** Ainda não terminou (os que não têm data de fim contam sempre como ativos). */
    fun isCurrent(today: LocalDate): Boolean = end == null || !end.isBefore(today)

    /** Diz respeito a esta estação/linha: categoria igual à linha, ou nome da estação/linha no título. */
    fun concerns(stationName: String?, lineName: String?): Boolean {
        val line = lineName?.let(CpText::normalize)?.takeIf { it.isNotBlank() }
        val station = stationName?.let(CpText::normalize)?.takeIf { it.length >= 4 }
        val title = CpText.normalize(this.title + " " + summary)
        if (line != null && (categories.any { CpText.normalize(it) == line } || title.contains(line))) return true
        return station != null && title.contains(station)
    }
}
