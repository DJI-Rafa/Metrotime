package com.example.metrotime.data

import java.util.PriorityQueue

/**
 * A rede do metro como um grafo (estações seguidas de cada linha), para saber o percurso entre duas
 * estações: as linhas usadas, os quilómetros pela linha (não em linha reta) e quanto tempo deve demorar.
 * Sem dependências Android (testado em app/src/test).
 */
object MetroNetwork {

    /** Percurso entre duas estações: estações por ordem, linhas usadas (pela ordem), km e minutos previstos. */
    data class Route(val stations: List<String>, val lines: List<String>, val km: Double, val minutes: Double)

    // Tempo entre estações: a 30 km/h de média com um mínimo de 1,2 min, mais 30 s parado em cada uma;
    // mudar de linha custa 4 min (andar nos corredores e esperar)
    private const val SPEED_KMH = 30.0
    private const val MIN_HOP_MIN = 1.2
    private const val DWELL_MIN = 0.5
    const val TRANSFER_MIN = 4.0

    private data class Edge(val to: String, val line: String, val km: Double)

    private val edges: Map<String, List<Edge>> by lazy {
        val map = HashMap<String, MutableList<Edge>>()
        for ((line, stops) in MapConfiguration.LINE_STATION_ORDER) {
            for ((a, b) in stops.zipWithNext()) {
                val km = km(a, b) ?: continue
                map.getOrPut(a) { mutableListOf() }.add(Edge(b, line, km))
                map.getOrPut(b) { mutableListOf() }.add(Edge(a, line, km))
            }
        }
        map
    }

    private fun km(a: String, b: String): Double? {
        val p = MapConfiguration.STATION_COORDS[a] ?: return null
        val q = MapConfiguration.STATION_COORDS[b] ?: return null
        return haversineKm(p.lat, p.lon, q.lat, q.lon)
    }

    /** Linhas que passam numa estação (pelo id da API: "AM" → Verde, Vermelha). */
    fun linesAt(apiId: String): List<String> = MetroLines.linesOf(apiId)

    /**
     * Percurso mais rápido entre duas estações (Dijkstra com o tempo como custo; o estado inclui a linha,
     * para contar as mudanças). null se alguma das estações não existir.
     */
    fun route(from: String, to: String): Route? {
        if (from !in edges || to !in edges) return null
        if (from == to) return Route(listOf(from), linesAt(from).take(1), 0.0, 0.0)

        data class State(val station: String, val line: String?)
        data class Node(val state: State, val minutes: Double)

        val best = HashMap<State, Double>()
        val prev = HashMap<State, State?>()
        val queue = PriorityQueue<Node>(compareBy { it.minutes })
        val start = State(from, null)
        best[start] = 0.0
        prev[start] = null
        queue.add(Node(start, 0.0))
        var end: State? = null
        while (queue.isNotEmpty()) {
            val (state, minutes) = queue.poll()
            if (minutes > (best[state] ?: Double.MAX_VALUE)) continue
            if (state.station == to) { end = state; break }
            for (e in edges[state.station].orEmpty()) {
                val hop = hopMinutes(e.km)
                val transfer = if (state.line != null && state.line != e.line) TRANSFER_MIN else 0.0
                val next = State(e.to, e.line)
                val cost = minutes + hop + transfer
                if (cost < (best[next] ?: Double.MAX_VALUE)) {
                    best[next] = cost
                    prev[next] = state
                    queue.add(Node(next, cost))
                }
            }
        }
        val last = end ?: return null
        // Refaz o caminho para trás
        val path = generateSequence(last) { prev[it] }.toList().reversed()
        val stations = path.map { it.station }
        val lines = path.mapNotNull { it.line }.fold(mutableListOf<String>()) { acc, l -> if (acc.lastOrNull() != l) acc.add(l); acc }
        val km = stations.zipWithNext().sumOf { (a, b) -> km(a, b) ?: 0.0 }
        return Route(stations, lines, km, best[last] ?: 0.0)
    }

    private fun hopMinutes(km: Double): Double = maxOf(MIN_HOP_MIN, km / SPEED_KMH * 60) + DWELL_MIN

    /** Minutos de uma estação à seguinte (com a paragem), pelo mesmo modelo do [route]; null se não forem vizinhas. */
    fun hopMinutes(a: String, b: String): Double? =
        edges[a]?.firstOrNull { it.to == b }?.let { hopMinutes(it.km) }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
