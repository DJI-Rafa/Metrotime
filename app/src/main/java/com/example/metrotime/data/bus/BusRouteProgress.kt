package com.example.metrotime.data.bus

import com.example.metrotime.data.cp.CpTrack

/**
 * Onde vão os autocarros de uma linha, em paragens do percurso. Sem dependências Android.
 * Os tempos da Carris são estimativas pela distância, por isso o painel da linha fala em paragens.
 */
object BusRouteProgress {

    /** Paragem do percurso mais perto de um ponto (o GPS de um autocarro). -1 sem paragens. */
    fun nearestStop(stops: List<Pair<Double, Double>>, at: Pair<Double, Double>): Int =
        stops.indices.minByOrNull { CpTrack.metres(stops[it], at) } ?: -1

    /**
     * Para cada paragem, quantas paragens faltam ao autocarro mais próximo que ainda lá vai passar
     * (o que está nessa paragem ou antes dela). null = nenhum autocarro antes dessa paragem.
     */
    fun stopsAway(stopCount: Int, busAt: Collection<Int>): List<Int?> {
        val sorted = busAt.filter { it in 0 until stopCount }.sorted()
        return (0 until stopCount).map { i -> sorted.lastOrNull { it <= i }?.let { i - it } }
    }

    /** "a chegar", "a 1 paragem", "a 3 paragens"; vazio sem autocarro. */
    fun label(away: Int?): String = when (away) {
        null -> ""
        0 -> "a chegar"
        1 -> "a 1 paragem"
        else -> "a $away paragens"
    }
}
