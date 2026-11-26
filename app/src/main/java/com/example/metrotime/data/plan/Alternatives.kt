package com.example.metrotime.data.plan

/**
 * Maneiras diferentes de fazer uma viagem e as partidas de cada uma. Sem dependências Android (testado em app/src/test).
 *
 * Descobrir: o [Raptor] com todas as linhas, depois sem cada uma das linhas que apareceram (e sem duas), e também
 * só de metro e só de autocarro. Partidas: para cada maneira, o RAPTOR só com as linhas dela, a partir de 1 minuto
 * depois da partida anterior, até ao fim da janela.
 */
object Alternatives {

    class Found(val signature: String, val journeys: List<Journey>, val frequent: Boolean)

    /** As linhas da viagem por ordem ("BUS:728>METRO:Linha Azul"). */
    fun signatureOf(net: TransitNetwork, j: Journey): String =
        j.rides.joinToString(">") { "${net.routes[it.route].mode}:${net.routes[it.route].label}" }

    fun find(
        net: TransitNetwork,
        access: List<Access>,
        egress: List<Access>,
        t0: Int,
        windowSecs: Int,
        maxAlternatives: Int = 8,
        maxDepartures: Int = 8,
        maxRuns: Int = 14
    ): List<Found> {
        val labelOf = net.routes.map { "${it.mode}:${it.label}" }
        val found = LinkedHashMap<String, Journey>()
        val tried = HashSet<Set<String>>()
        val queue = ArrayDeque<Set<String>>()
        queue += emptySet<String>()
        val hasMetro = net.routes.any { it.mode == LegMode.METRO }
        val hasBus = net.routes.any { it.mode == LegMode.BUS }
        if (hasMetro && hasBus) {
            queue += net.routes.filter { it.mode == LegMode.BUS }.map { "BUS:${it.label}" }.toSet()      // só metro
            queue += net.routes.filter { it.mode == LegMode.METRO }.map { "METRO:${it.label}" }.toSet()  // só autocarros
        }
        var runs = 0
        while (queue.isNotEmpty() && runs < maxRuns && found.size < maxAlternatives + 4) {
            val banned = queue.removeFirst()
            if (!tried.add(banned)) continue
            runs++
            for (j in Raptor.run(net, access, egress, t0, allowRoute = { labelOf[it] !in banned })) {
                val sig = signatureOf(net, j)
                if (sig.isEmpty()) continue
                val old = found[sig]
                if (old == null || j.arr < old.arr) found[sig] = j
                // Tirar uma linha de cada vez (só a partir de pesquisas com no máximo uma linha tirada)
                if (banned.size <= 1) j.rides.forEach { r -> queue += banned + labelOf[r.route] }
            }
        }
        if (found.isEmpty()) return emptyList()

        // Só as que chegam até meia hora (ou metade da viagem) depois da melhor
        val bestArr = found.values.minOf { it.arr }
        val bestDur = found.values.minOf { it.arr - t0 }
        val kept = found.entries
            .filter { (_, j) -> j.arr <= bestArr + maxOf(30 * 60, bestDur / 2) }
            .sortedWith(compareBy({ it.value.arr }, { it.value.rides.size }))
            .take(maxAlternatives)

        return kept.map { (sig, first) ->
            val lines = sig.split(">").toSet()
            val metroOnly = lines.all { it.startsWith("METRO:") }
            val limit = if (metroOnly) 3 else maxDepartures
            val list = mutableListOf(first)
            var t = nextSearch(first)
            var misses = 0
            while (list.size < limit && t < t0 + windowSecs && misses < 6) {
                val next = Raptor.run(net, access, egress, t, allowRoute = { labelOf[it] in lines })
                    .filter { signatureOf(net, it) == sig }
                    .minByOrNull { it.arr }
                val firstRide = next?.rides?.firstOrNull()
                if (next == null || firstRide == null || firstRide.dep <= list.last().rides.first().dep || next.dep >= t0 + windowSecs) {
                    t += 15 * 60
                    misses++
                    continue
                }
                // Sai mais tarde e chega à mesma hora (o metro seguinte apanha o mesmo autocarro): fica só esta
                if (next.arr == list.last().arr) list[list.lastIndex] = next else list += next
                t = nextSearch(next)
            }
            Found(sig, list, metroOnly)
        }
    }

    /** Hora a partir da qual já não se apanha o primeiro veículo de [j] (a partir daí, a partida seguinte). */
    private fun nextSearch(j: Journey): Int {
        val ride = j.rides.first()
        val walk = (j.legs.first() as? JLeg.Walk)?.takeIf { it.from == -1 }?.let { it.arr - it.dep } ?: 0
        return ride.dep - walk + 1
    }
}
