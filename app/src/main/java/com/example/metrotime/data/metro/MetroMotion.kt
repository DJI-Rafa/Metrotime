package com.example.metrotime.data.metro

import com.example.metrotime.data.LineArrival
import com.example.metrotime.data.MetroLines
import kotlin.math.abs
import kotlin.math.exp

/**
 * Onde está cada metro em cada instante: agora, ou daqui a uns minutos (o seletor de tempo do mapa).
 * Sem dependências Android (testado em app/src/test).
 *
 * A API do Metro não dá posições: para cada estação e sentido, os próximos 3 metros (número e segundos até
 * chegarem). Juntando as estações pelo número do metro, cada um fica com as horas a que chega às estações
 * seguintes. Entre duas estações anda pelo traçado real a acelerar e a travar; em cada uma pára uns segundos.
 */
object MetroMotion {

    /** Paragem normal numa estação. */
    const val DWELL_MS = 25_000L
    /** A mais do que isto entre duas estações já não é paragem: é andar mais devagar. */
    const val MAX_DWELL_MS = 45_000L
    /** Depois de chegar ao fim da linha ainda se vê lá este tempo (a seguir volta como outro sentido, ou recolhe). */
    const val TERMINAL_MS = 90_000L
    /** Antes da primeira hora que se conhece, aparece no máximo isto antes (a sair do terminal). */
    const val LEAD_MS = 4 * 60_000L
    /** O seletor de tempo vai até este número de minutos à frente. */
    const val MAX_OFFSET_MIN = 15
    /** Velocidade média a andar entre estações (com arranque e travagem) e a máxima possível. */
    private const val RUN_MPS = 12.5
    private const val MAX_MPS = 22.0

    /** Tempo a andar entre duas estações a [metres] uma da outra. */
    fun runMs(metres: Double): Long = maxOf(35_000L, (metres / RUN_MPS * 1000).toLong())

    private fun minRunMs(metres: Double): Long = (metres / MAX_MPS * 1000).toLong()

    /** Arranca devagar, anda, trava: 0 → 1 com velocidade nula nas pontas. */
    fun ease(u: Double): Double {
        val x = u.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    /**
     * Os metros a partir das chegadas da API de cada linha, lidas às [fetchedAtMs]. Um metro pode ter duas
     * viagens seguidas (chega ao fim da linha e volta com outro destino): ficam encadeadas, não são dois metros.
     */
    fun build(
        arrivals: Map<String, List<LineArrival>>,
        tracks: Map<String, MetroLineTrack>,
        fetchedAtMs: Long
    ): List<MetroTrain> = arrivals.flatMap { (line, list) ->
        val track = tracks[line] ?: return@flatMap emptyList()
        list.filter { it.secondsToArrive >= 0 && track.indexOf(it.stationId) >= 0 }
            .groupBy { it.trainId }
            .mapNotNull { (trainId, predictions) ->
                val runs = predictions.groupBy { it.destinationId }
                    .mapNotNull { (_, group) -> runOf(line, trainId, group, track, fetchedAtMs) }
                    .sortedBy { it.arrive.first() }
                if (runs.isEmpty()) null else MetroTrain(line, trainId, runs)
            }
    }

    /** Uma viagem num sentido a partir das previsões de um metro para um destino. */
    internal fun runOf(line: String, trainId: String, group: List<LineArrival>, track: MetroLineTrack, fetchedAtMs: Long): MetroRun? {
        val n = track.stations.size
        val sorted = group.map { track.indexOf(it.stationId) to it.secondsToArrive }
            .distinctBy { it.first }
            .sortedBy { it.second }
        if (sorted.isEmpty()) return null
        val first = sorted.first().first
        val destinationName = group.first().destinationName
        val destinationId = MetroLines.idByName(destinationName)?.let { id -> track.indexOf(id).takeIf { it >= 0 } }
        // Sentido: pela ordem das horas; com uma só estação, pelo destino
        val dir = when {
            sorted.size >= 2 && sorted.last().first != first -> if (sorted.last().first > first) 1 else -1
            destinationId != null && destinationId != first -> if (destinationId > first) 1 else -1
            destinationId == 0 -> -1
            else -> 1
        }
        // Só as que seguem no sentido, cada uma depois da anterior
        val known = ArrayList<Pair<Int, Long>>()
        for ((idx, sec) in sorted) {
            val last = known.lastOrNull()
            if (last != null && (idx - last.first) * dir <= 0) continue
            var at = fetchedAtMs + sec * 1000L
            if (last != null) at = maxOf(at, last.second + minRunMs(track.between(last.first, idx)))
            known += idx to at
        }
        // Fim desta viagem: o destino, se estiver à frente; senão o fim da linha
        val end = destinationId?.takeIf { (it - known.last().first) * dir >= 0 } ?: if (dir > 0) n - 1 else 0
        // Todas as estações, da anterior à primeira com hora até ao fim, com as horas que faltam
        val stops = ArrayList<Int>()
        val arrive = ArrayList<Long>()
        val previous = known.first().first - dir
        val hasPrevious = previous in 0 until n
        if (hasPrevious) {
            stops += previous
            arrive += 0L // preenchido abaixo, a partir da partida
        }
        var k = 0
        var idx = known.first().first
        while (true) {
            val exact = known.getOrNull(k)?.takeIf { it.first == idx }
            val at = when {
                exact != null -> { k++; exact.second }
                k < known.size -> {
                    // Entre duas horas conhecidas: pela distância
                    val a = known[k - 1]
                    val b = known[k]
                    val total = track.between(a.first, b.first).coerceAtLeast(1.0)
                    a.second + ((b.second - a.second) * (track.between(a.first, idx) / total)).toLong()
                }
                else -> {
                    // Depois da última conhecida: paragem e o tempo a andar
                    arrive.last() + DWELL_MS + runMs(track.between(stops.last(), idx))
                }
            }
            stops += idx
            arrive += at
            if (idx == end) break
            idx += dir
            if (idx !in 0 until n) break
        }
        // Partidas: pára até ter de sair para chegar à seguinte (no máximo MAX_DWELL), e no fim fica TERMINAL_MS
        val depart = LongArray(stops.size)
        for (i in stops.indices) {
            depart[i] = if (i == stops.lastIndex) arrive[i] + TERMINAL_MS
            else {
                val gap = arrive[i + 1] - arrive[i]
                val run = runMs(track.between(stops[i], stops[i + 1]))
                arrive[i] + (gap - run).coerceIn(0L, MAX_DWELL_MS)
            }
        }
        if (hasPrevious) {
            // A estação de onde vem: saiu a tempo de chegar à seguinte
            depart[0] = arrive[1] - runMs(track.between(stops[0], stops[1]))
            arrive[0] = depart[0] - DWELL_MS
        }
        val destination = track.stations.getOrNull(end)
        return MetroRun(line, trainId, dir, destination, destinationName, stops.toIntArray(), arrive.toLongArray(), depart)
    }
}

/** Uma viagem de um metro num sentido: estações por ordem de viagem, com a chegada e a partida de cada uma (ms). */
class MetroRun(
    val line: String,
    val trainId: String,
    val dir: Int,
    val destinationId: String?,
    val destinationName: String,
    val stops: IntArray,
    val arrive: LongArray,
    val depart: LongArray
) {
    val lastArrival: Long get() = arrive.last()
    val end: Long get() = depart.last()
}

/** Onde está um metro: metros pela linha (o centro do metro), sentido, e a estação onde está parado ou a seguinte. */
data class MetroPlace(
    val line: String,
    val trainId: String,
    val s: Double,
    val dir: Int,
    val atStation: Int?,
    val nextStation: Int?,
    val destinationName: String,
    /** 0..1: aparece e desaparece devagar nas pontas. */
    val alpha: Double
) {
    val key: String get() = "$line|$trainId"
}

/** Um metro: as viagens seguidas que a API dá para ele (normalmente uma; duas quando volta no fim da linha). */
class MetroTrain(val line: String, val trainId: String, val runs: List<MetroRun>) {

    val key: String get() = "$line|$trainId"

    private fun runAt(t: Long): Pair<MetroRun, Long>? {
        for ((i, run) in runs.withIndex()) {
            val from = if (i == 0) run.arrive.first() - MetroMotion.LEAD_MS else runs[i - 1].lastArrival
            val until = if (i < runs.lastIndex) run.end.coerceAtLeast(runs[i + 1].arrive.first()) else run.end
            if (t in from until until) return run to from
        }
        return null
    }

    /** Onde está às [t] (null antes de aparecer e depois de acabar). */
    fun placeAt(t: Long, track: MetroLineTrack): MetroPlace? {
        val (run, from) = runAt(t) ?: return null
        val at = { i: Int -> track.stationAt[run.stops[i]] }
        val fadeIn = if (run === runs.first()) ((t - from) / 8_000.0).coerceIn(0.0, 1.0) else 1.0
        val fadeOut = if (run === runs.last()) ((run.end - t) / 8_000.0).coerceIn(0.0, 1.0) else 1.0
        val alpha = minOf(fadeIn, fadeOut)
        for (i in run.stops.indices) {
            if (t <= run.depart[i]) {
                if (i == 0 || t >= run.arrive[i]) {
                    return MetroPlace(line, trainId, at(i), run.dir, run.stops[i], run.stops.getOrNull(i + 1), run.destinationName, alpha)
                }
                val span = (run.arrive[i] - run.depart[i - 1]).coerceAtLeast(1L)
                val u = (t - run.depart[i - 1]).toDouble() / span
                val s = at(i - 1) + (at(i) - at(i - 1)) * MetroMotion.ease(u)
                return MetroPlace(line, trainId, s, run.dir, null, run.stops[i], run.destinationName, alpha)
            }
        }
        val last = run.stops.lastIndex
        return MetroPlace(line, trainId, at(last), run.dir, run.stops[last], null, run.destinationName, alpha)
    }

    /** As estações que faltam a partir de [t] e a hora de chegada a cada uma (a viagem em curso). */
    fun upcoming(t: Long): List<Pair<Int, Long>> {
        val (run, _) = runAt(t) ?: return emptyList()
        return run.stops.indices.filter { run.arrive[it] > t || (run.depart[it] > t && it > 0) }
            .map { run.stops[it] to run.arrive[it] }
    }

    /** O destino da viagem em curso. */
    fun destinationAt(t: Long): String? = runAt(t)?.first?.destinationName
}

/**
 * Tira os saltos: o metro desenhado aproxima-se do sítio onde o modelo diz que está, sem nunca andar para trás
 * por pouco (se os dados novos o põem uns metros atrás, fica parado até o modelo o apanhar).
 */
class MetroSmoother {
    private val shown = HashMap<String, Double>()

    /** Metros pela linha a desenhar para [key], a caminho de [target]; [fast] para as mudanças do seletor de tempo. */
    fun step(key: String, target: Double, dir: Int, dtSec: Double, fast: Boolean = false): Double {
        val current = shown[key]
        if (current == null) {
            shown[key] = target
            return target
        }
        val ahead = (target - current) * dir
        val next = when {
            abs(target - current) > SNAP_M -> target
            ahead < 0 && -ahead < HOLD_BACK_M && !fast -> current
            else -> current + (target - current) * (1 - exp(-dtSec / (if (fast) FAST_TAU_S else TAU_S)))
        }
        shown[key] = next
        return next
    }

    /** Esquece os metros que já não existem. */
    fun retain(keys: Set<String>) {
        shown.keys.retainAll(keys)
    }

    companion object {
        const val TAU_S = 1.2
        const val FAST_TAU_S = 0.12
        const val SNAP_M = 2_500.0
        const val HOLD_BACK_M = 400.0
    }
}
