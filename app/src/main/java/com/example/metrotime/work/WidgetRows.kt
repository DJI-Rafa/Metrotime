package com.example.metrotime.work

import com.example.metrotime.data.Arrival
import com.example.metrotime.data.MetroLines

/**
 * O que os widgets mostram, sem dependências Android (testado em app/src/test): os sentidos do metro,
 * o estado das partidas CP e a hora da última atualização. O desenho está em WidgetViews.
 */
object WidgetRows {

    /** Um sentido do metro: linha, destino e segundos até aos próximos metros (do mais perto). */
    data class MetroRow(val line: String, val destination: String, val seconds: List<Int>)

    /**
     * Sentidos de uma estação pela ordem das linhas, descontando os segundos desde que os tempos
     * chegaram; os metros que já passaram saem.
     */
    fun metro(stationApiId: String, arrivals: List<Arrival>, elapsedSec: Int): List<MetroRow> {
        val upcoming = arrivals.map { it.copy(minutes = it.minutes - elapsedSec) }.filter { it.minutes > 0 }
        return MetroLines.groupByLine(stationApiId, upcoming).flatMap { (line, directions) ->
            directions.filter { it.seconds.isNotEmpty() }.map { MetroRow(line, it.destination, it.seconds) }
        }
    }

    /** Tamanho pequeno: o próximo metro de cada linha numa estação com duas; senão, os dois sentidos. */
    fun metroSmall(rows: List<MetroRow>): List<MetroRow> {
        val byLine = rows.groupBy { it.line }
        val picked = if (byLine.size >= 2) byLine.values.map { list -> list.minBy { it.seconds.first() } } else rows
        return picked.take(2)
    }

    /** Ecrã de bloqueio: os três metros mais próximos, um por sentido. */
    fun lock(rows: List<MetroRow>): List<MetroRow> = rows.sortedBy { it.seconds.first() }.take(3)

    /** "há 12 s", "há 3 min". */
    fun ago(seconds: Int): String = if (seconds < 60) "há ${seconds.coerceAtLeast(0)} s" else "há ${seconds / 60} min"

    enum class CpState { ON_TIME, SMALL_DELAY, BIG_DELAY, SUPPRESSED, SCHEDULED }

    /**
     * Estado de uma partida CP, com os mesmos limites da app: até 5 min de atraso a amarelo, mais a
     * vermelho. [delay] em minutos; null = sem dados em tempo real; -1 = suprimido.
     */
    fun cpStatus(delay: Int?, suppressed: Boolean): Pair<String, CpState> = when {
        suppressed || delay == -1 -> "Suprimido" to CpState.SUPPRESSED
        delay == null -> "Previsto" to CpState.SCHEDULED
        delay <= 0 -> "a horas" to CpState.ON_TIME
        delay <= 5 -> "+$delay min" to CpState.SMALL_DELAY
        else -> "+$delay min" to CpState.BIG_DELAY
    }

    /** Autocarros de uma paragem: o próximo de cada linha e destino, do mais perto para o mais longe. */
    fun <T> nextPerLine(arrivals: List<T>, line: (T) -> String, destination: (T) -> String, eta: (T) -> Int): List<T> =
        arrivals.groupBy { line(it) to destination(it) }.values.map { group -> group.minBy(eta) }.sortedBy(eta)
}
