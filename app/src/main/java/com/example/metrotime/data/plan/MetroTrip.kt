package com.example.metrotime.data.plan

import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.MetroNetwork

/**
 * Um percurso de metro dividido em troços (um por linha), com o sentido de cada troço, os metros que o
 * servem e a hora do último metro. Sem dependências Android (testado em app/src/test).
 */
object MetroTrip {

    /** Troço numa só linha: estações por ordem, no sentido de [towards] (terminal, id da API), e os minutos previstos. */
    data class Leg(val line: String, val stations: List<String>, val towards: String, val minutes: Double) {
        val from: String get() = stations.first()
        val to: String get() = stations.last()
        val stops: Int get() = stations.size - 1
    }

    /** Últimas partidas dos terminais à 1:00, em minutos do dia de serviço (25 h = 1:00 do dia seguinte). */
    const val LAST_DEPARTURE_MIN = 25 * 60
    /** Primeiras partidas dos terminais às 6:30. */
    const val FIRST_DEPARTURE_MIN = 6 * 60 + 30
    /** No último metro não há espera: mudar de linha é só andar nos corredores. */
    private const val TRANSFER_WALK_MIN = 3.0

    /** Linha em que [a] e [b] são vizinhas; null se não forem vizinhas em nenhuma. */
    fun lineBetween(a: String, b: String): String? = MetroLines.NAMES.firstOrNull { line ->
        val s = MetroLines.stops(line)
        val i = s.indexOf(a)
        i >= 0 && (s.getOrNull(i + 1) == b || s.getOrNull(i - 1) == b)
    }

    /** Os troços de um percurso (vazio se o percurso não tiver pelo menos duas estações). */
    fun legs(route: MetroNetwork.Route): List<Leg> {
        val st = route.stations
        if (st.size < 2) return emptyList()
        val groups = mutableListOf<Pair<String, MutableList<String>>>()
        for ((a, b) in st.zipWithNext()) {
            val line = lineBetween(a, b) ?: return emptyList()
            val last = groups.lastOrNull()
            if (last != null && last.first == line) last.second.add(b) else groups.add(line to mutableListOf(a, b))
        }
        return groups.map { (line, stations) ->
            val order = MetroLines.stops(line)
            val forward = order.indexOf(stations.last()) > order.indexOf(stations.first())
            Leg(
                line = line,
                stations = stations,
                towards = if (forward) order.last() else order.first(),
                minutes = stations.zipWithNext().sumOf { (a, b) -> MetroNetwork.hopMinutes(a, b) ?: 0.0 }
            )
        }
    }

    /**
     * Um metro com destino [destinationName] (o que a API mostra: o terminal ou o fim de um percurso curto,
     * como "Laranjeiras") serve o troço se vai no mesmo sentido e não acaba antes da estação de saída.
     */
    fun servesLeg(leg: Leg, destinationName: String): Boolean {
        val dest = MetroLines.idByName(destinationName) ?: return false
        val order = MetroLines.stops(leg.line)
        val from = order.indexOf(leg.from)
        val to = order.indexOf(leg.to)
        val d = order.indexOf(dest)
        if (from < 0 || to < 0 || d < 0) return false
        return if (to > from) d >= to else d <= to
    }

    /** Minutos de [from] a [to] na mesma linha, pelo modelo da rede. */
    private fun rideMinutes(line: String, from: String, to: String): Double? {
        val order = MetroLines.stops(line)
        val i = order.indexOf(from)
        val j = order.indexOf(to)
        if (i < 0 || j < 0) return null
        var total = 0.0
        for (k in minOf(i, j) until maxOf(i, j)) total += MetroNetwork.hopMinutes(order[k], order[k + 1]) ?: return null
        return total
    }

    /** Hora (minutos do dia de serviço) a que o último metro no sentido de [towards] passa em [station]. */
    fun lastPass(line: String, station: String, towards: String): Double? {
        val order = MetroLines.stops(line)
        if (order.isEmpty()) return null
        val origin = if (towards == order.last()) order.first() else order.last()
        return rideMinutes(line, origin, station)?.let { LAST_DEPARTURE_MIN + it }
    }

    /**
     * Até que horas se pode apanhar o metro na primeira estação do percurso e ainda chegar ao fim com as mudanças.
     * Vai de trás para a frente: cada troço fica limitado pelo seu último metro e pela hora a que é preciso
     * chegar à mudança seguinte. Minutos do dia de serviço; null sem percurso.
     */
    fun latestDeparture(route: MetroNetwork.Route): Double? {
        val legs = legs(route)
        if (legs.isEmpty()) return null
        var latest = Double.MAX_VALUE
        for (leg in legs.asReversed()) {
            val last = lastPass(leg.line, leg.from, leg.towards) ?: return null
            val byConnection = if (latest == Double.MAX_VALUE) Double.MAX_VALUE else latest - TRANSFER_WALK_MIN - leg.minutes
            latest = minOf(last, byConnection)
        }
        return latest
    }

    /** Minutos do dia de serviço: até às 4h ainda conta como o dia anterior (0:30 → 24:30). */
    fun serviceMinutes(hour: Int, minute: Int): Int = (if (hour < 4) hour + 24 else hour) * 60 + minute

    /** "01:07" a partir dos minutos do dia de serviço. */
    fun clock(serviceMinutes: Double): String {
        val m = Math.floorMod(Math.round(serviceMinutes).toInt(), 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
