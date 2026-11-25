package com.example.metrotime.data.history

import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.MetroNetwork

/**
 * Uma viagem: de onde para onde, quando, por que linhas e quantos km. Substitui as "visitas" soltas a
 * estações (que contavam estar ao pé de uma estação como uma viagem, e A→B como duas).
 */
data class Trip(
    val id: String,
    /** "metro" ou "cp". */
    val mode: String,
    /** Metro: id da API ("AM"); CP: código da estação. */
    val fromId: String,
    val fromName: String,
    val toId: String? = null,
    val toName: String? = null,
    /** Metro: linhas do percurso (ou as da estação, se só se sabe a origem); CP: ["CP"]. */
    val lines: List<String>,
    val startMs: Long,
    val endMs: Long? = null,
    /** Pela linha do metro ou pelo percurso do GPS no comboio; 0 se não se sabe. */
    val km: Double = 0.0,
    /** auto (metro detetado), confirmed (confirmada por ti), manual (Iniciar viagem), train (comboio seguido), legacy. */
    val source: String,
    /** No início manual: o sentido escolhido ("Reboleira"). */
    val direction: String? = null,
    /** Comboio: o serviço (AP, IC, IR, R, U, IN, FT); null nos antigos. */
    val service: String? = null,
    /** Comboio: o número ("521"). */
    val trainNumber: String? = null,
    /** Comboio: a maior velocidade mantida 20 s seguidos (km/h, pelo GPS do telemóvel); null nos antigos. */
    val topKmh: Double? = null,
    /** Comboio: minutos de atraso à chegada à estação onde saíste (negativo = adiantado); null sem dados. */
    val delayMin: Int? = null,
    /** Comboio: dia de greve na CP (aviso de greve e mais suprimidos do que a circular, [com.example.metrotime.data.cp.CpStrike]). */
    val strikeDay: Boolean = false
) {
    /** Minutos a bordo, se se sabe o fim (no máximo 3 h, para um fim errado não estragar as contas). */
    val minutes: Int? get() = endMs?.let { ((it - startMs) / 60_000).toInt().coerceIn(0, 180) }
}

/** Viagem detetada com dúvidas (podia ter sido a pé): só conta se a confirmares; expira em 24 h. */
data class PendingTrip(val trip: Trip, val createdMs: Long)

/** Regras das viagens, sem Android (testadas em app/src/test). */
object TripRules {

    const val PENDING_TTL_MS = 24 * 60 * 60 * 1000L
    /** A mesma origem dentro deste tempo é a mesma viagem (evita duplicados de toques ou deteções). */
    const val DUPLICATE_WINDOW_MS = 30 * 60 * 1000L
    /** Um troço que começa onde o anterior acabou, até este tempo depois, é a continuação da viagem. */
    const val CHAIN_WINDOW_MS = 6 * 60 * 1000L
    /**
     * Comboio: o mesmo comboio apanhado outra vez até este tempo depois é a mesma viagem (a app deixou de o
     * ver numa paragem ou enquanto a posição dele chegava atrasada, e voltou a dar por ele).
     */
    const val TRAIN_CHAIN_WINDOW_MS = 45 * 60 * 1000L

    /** Viagem de metro de A para B pela rede (linhas e km pela linha). */
    fun metroTrip(id: String, from: String, to: String, startMs: Long, endMs: Long, source: String): Trip {
        val route = MetroNetwork.route(from, to)
        return Trip(
            id = id, mode = "metro",
            fromId = from, fromName = MetroLines.nameOf(from),
            toId = to, toName = MetroLines.nameOf(to),
            lines = route?.lines ?: (MetroNetwork.linesAt(from) + MetroNetwork.linesAt(to)).distinct(),
            startMs = startMs, endMs = endMs,
            km = route?.km ?: 0.0,
            source = source
        )
    }

    /** Já há uma viagem com a mesma origem e o mesmo meio há pouco tempo? */
    fun isDuplicate(existing: List<Trip>, trip: Trip): Boolean = existing.any {
        it.mode == trip.mode && it.fromId == trip.fromId && kotlin.math.abs(it.startMs - trip.startMs) < DUPLICATE_WINDOW_MS
    }

    /**
     * Se [next] começa na estação onde [last] acabou, logo a seguir (troços à superfície em que o GPS
     * volta a apanhar uma estação a meio), junta os dois numa só viagem de metro. Nos comboios, junta os
     * troços do mesmo comboio ([chainTrain]); [trainKm] dá os km pelo traçado da viagem já junta.
     */
    fun chain(last: Trip, next: Trip, trainKm: (Trip) -> Double? = { null }): Trip? {
        if (last.mode == "cp" && next.mode == "cp") return chainTrain(last, next, trainKm)
        if (last.mode != "metro" || next.mode != "metro") return null
        val end = last.endMs ?: return null
        if (last.toId == null || last.toId != next.fromId) return null
        if (next.startMs - end > CHAIN_WINDOW_MS || next.startMs < end - 60_000) return null
        val to = next.toId ?: return null
        if (to == last.fromId) return null // ida e volta não é uma viagem só
        return metroTrip(last.id, last.fromId, to, last.startMs, next.endMs ?: end, last.source)
    }

    /**
     * O mesmo comboio (número) outra vez, pouco depois de a app o ter perdido: uma viagem só, da primeira
     * origem ao último destino. Os km são os do traçado entre as duas ([trainKm]) ou, sem ele, a soma dos troços.
     */
    fun chainTrain(last: Trip, next: Trip, trainKm: (Trip) -> Double? = { null }): Trip? {
        val number = trainKey(last.trainNumber) ?: return null
        if (trainKey(next.trainNumber) != number) return null
        val end = last.endMs ?: return null
        if (next.startMs < last.startMs || next.startMs - end > TRAIN_CHAIN_WINDOW_MS) return null
        val toId = next.toId ?: next.fromId
        val toName = next.toName ?: next.fromName
        val back = toId == last.fromId
        val joined = last.copy(
            toId = if (back) null else toId,
            toName = if (back) null else toName,
            endMs = maxOf(end, next.endMs ?: end),
            km = last.km + next.km,
            service = last.service ?: next.service,
            topKmh = listOfNotNull(last.topKmh, next.topKmh).maxOrNull(),
            delayMin = next.delayMin ?: last.delayMin,
            strikeDay = last.strikeDay || next.strikeDay
        )
        return trainKm(joined)?.takeIf { it > joined.km }?.let { joined.copy(km = it) } ?: joined
    }

    /** O número do comboio para comparar: sem espaços nem zeros à esquerda ("0621 " e "621" são o mesmo comboio). */
    fun trainKey(number: String?): String? = number?.trim()?.trimStart('0')?.takeIf { it.isNotEmpty() }

    /**
     * Junta os troços do mesmo comboio que já estão guardados (de antes de se juntarem ao gravar): só quando
     * não há outra viagem entre eles. Devolve a mesma lista se não houver nada a juntar.
     */
    fun mergeTrainLegs(trips: List<Trip>, trainKm: (Trip) -> Double? = { null }): List<Trip> {
        val out = ArrayList<Trip>(trips.size)
        var changed = false
        for (t in trips.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            val joined = if (last != null && last.mode == "cp" && t.mode == "cp") chainTrain(last, t, trainKm) else null
            if (joined != null) {
                out[out.lastIndex] = joined
                changed = true
            } else {
                out += t
            }
        }
        return if (changed) out else trips
    }

    /** Fecha uma viagem só com origem (início manual) com o destino detetado depois. */
    fun close(open: Trip, detected: Trip): Trip? {
        if (open.mode != "metro" || open.endMs != null || detected.fromId != open.fromId) return null
        val to = detected.toId ?: return null
        val closed = metroTrip(open.id, open.fromId, to, open.startMs, detected.endMs ?: return null, open.source)
        return closed.copy(direction = open.direction)
    }

    /**
     * Converte as visitas antigas: duas estações do metro seguidas (menos de 90 min) são uma viagem A→B;
     * duas da CP (menos de 6 h) também; o resto fica como viagem só com origem.
     */
    fun fromVisits(visits: List<StationVisit>): List<Trip> {
        val sorted = visits.sortedBy { it.timestampMs }
        val out = mutableListOf<Trip>()
        var i = 0
        while (i < sorted.size) {
            val v = sorted[i]
            val next = sorted.getOrNull(i + 1)
            val dt = next?.let { it.timestampMs - v.timestampMs } ?: -1
            val id = "legacy_${v.timestampMs}_$i"
            if (next != null && v.type == "metro" && next.type == "metro" && next.stationName != v.stationName && dt in 60_000L..90 * 60_000L) {
                val a = metroId(v)
                val b = metroId(next)
                out += metroTrip(id, a, b, v.timestampMs, next.timestampMs, "legacy").let {
                    // Estações que a rede não conhece: ficam com os nomes guardados
                    if (MetroNetwork.route(a, b) == null) it.copy(fromName = v.stationName, toName = next.stationName) else it
                }
                i += 2
            } else if (next != null && v.type == "cp" && next.type == "cp" && next.stationId != v.stationId && dt in 5 * 60_000L..6 * 60 * 60_000L) {
                // Os km dos comboios antigos estão nos "km extra" (sem data), contados à parte em "sempre"
                out += Trip(id, "cp", v.stationId, v.stationName, next.stationId, next.stationName, listOf("CP"), v.timestampMs, next.timestampMs, 0.0, "legacy")
                i += 2
            } else {
                out += if (v.type == "cp") {
                    Trip(id, "cp", v.stationId, v.stationName, lines = listOf("CP"), startMs = v.timestampMs, source = "legacy")
                } else {
                    Trip(id, "metro", metroId(v), v.stationName, lines = StatsCalc.metroLines(v.lineName), startMs = v.timestampMs, source = "legacy")
                }
                i += 1
            }
        }
        return out
    }

    private fun metroId(v: StationVisit): String = MetroLines.idByName(v.stationName) ?: v.stationId
}

/**
 * Os totais que vão para o Firebase (user_stats) e o que se lê dos amigos. "totalVisits" e
 * "monthlyVisits" contam viagens (os nomes ficaram do tempo das visitas, por compatibilidade).
 */
data class TripStats(
    val totalVisits: Int,
    val totalDistanceKm: Double,
    val travelMinutes: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val topStations: List<Triple<String, String, Int>>,
    val topLines: List<Pair<String, Int>>,
    val monthlyVisits: Int,
    /** Mês a que [monthlyVisits] se refere ("2026-10"); null nos dados antigos. */
    val monthKey: String?,
    /** Dia da última viagem ("2026-10-24"); null nos dados antigos. */
    val lastTripDay: String?
) {
    /** Viagens deste mês: as enviadas só valem se forem deste mês (senão o amigo não viajou desde então). */
    fun monthlyOn(today: java.time.LocalDate): Int = if (monthKey == null || monthKey == monthKey(today)) monthlyVisits else 0

    /** Dias seguidos: só valem se a última viagem foi hoje ou ontem. */
    fun streakOn(today: java.time.LocalDate): Int {
        val last = lastTripDay?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return currentStreak
        return if (!last.isBefore(today.minusDays(1))) currentStreak else 0
    }

    companion object {
        fun monthKey(day: java.time.LocalDate): String = "%d-%02d".format(day.year, day.monthValue)
    }
}
