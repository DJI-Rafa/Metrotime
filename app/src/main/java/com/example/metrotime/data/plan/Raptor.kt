package com.example.metrotime.data.plan

/**
 * Viagens com mudanças pelo algoritmo RAPTOR (Delling, Pajor e Werneck, 2012), sobre o metro e a Carris.
 * Em cada volta faz-se mais uma viagem num veículo: a volta k dá a chegada mais cedo com até k veículos.
 * No fim fica o conjunto de viagens em que cada uma tem menos veículos ou chega mais cedo do que as outras.
 * Sem dependências Android (testado em app/src/test).
 */

/** Uma paragem ou estação de metro. lat/lon NaN se não se souber onde fica. */
class TransitStop(val id: String, val name: String, val lat: Double, val lon: Double, val isMetro: Boolean) {
    val hasPosition: Boolean get() = !lat.isNaN() && !lon.isNaN()
}

/**
 * Um percurso: sempre as mesmas paragens pela mesma ordem, com as viagens do dia. Horas em segundos desde a
 * meia-noite do dia da pesquisa (podem passar das 24 h ou ser negativas: viagens de ontem e de amanhã).
 */
class TransitRoute(
    val mode: LegMode,
    /** "Linha Azul", "728". */
    val label: String,
    /** Nome da linha do metro ("Azul") ou cor do autocarro ("#8C8C99"). */
    val color: String,
    /** Para onde vai (última paragem / terminal). */
    val headsign: String,
    val stops: IntArray,
    /** Segundos desde a partida até cada paragem, por perfil de tempos (os autocarros demoram mais à hora de ponta). */
    val profiles: Array<IntArray>,
    /** Hora de partida de cada viagem, por ordem crescente. */
    val starts: IntArray,
    /** Perfil de tempos de cada viagem. */
    val profileOf: IntArray
) {
    val size: Int get() = stops.size
    private val minOffset = IntArray(stops.size) { pos -> profiles.minOfOrNull { it[pos] } ?: 0 }
    private val maxOffset = IntArray(stops.size) { pos -> profiles.maxOfOrNull { it[pos] } ?: 0 }

    fun time(trip: Int, pos: Int): Int = starts[trip] + profiles[profileOf[trip]][pos]

    /**
     * A viagem que passa mais cedo em [pos] a partir de [t]; -1 se não houver. Começa pela primeira que pode lá
     * chegar a tempo (pela partida e pelo perfil mais lento) e pára quando já nenhuma pode passar mais cedo.
     */
    fun earliestTrip(pos: Int, t: Int): Int {
        var i = lowerBound(starts, t - maxOffset[pos])
        var best = -1
        var bestTime = Int.MAX_VALUE
        while (i < starts.size && starts[i] + minOffset[pos] < bestTime) {
            val at = time(i, pos)
            if (at >= t && at < bestTime) { best = i; bestTime = at }
            i++
        }
        return best
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** A rede de uma pesquisa: paragens, percursos e os caminhos a pé entre paragens perto umas das outras. */
class TransitNetwork(
    val stops: List<TransitStop>,
    val routes: List<TransitRoute>,
    /** Para cada paragem, as paragens a que se vai a pé e quantos segundos se leva. */
    val walkTo: Array<IntArray>,
    val walkSecs: Array<IntArray>
) {
    /** Percursos que passam em cada paragem e a posição dela (um circular pode passar duas vezes). */
    val routesAtRoute: Array<IntArray>
    val routesAtPos: Array<IntArray>

    init {
        val r = Array(stops.size) { ArrayList<Int>() }
        val p = Array(stops.size) { ArrayList<Int>() }
        routes.forEachIndexed { ri, route ->
            route.stops.forEachIndexed { pos, s -> r[s].add(ri); p[s].add(pos) }
        }
        routesAtRoute = Array(stops.size) { r[it].toIntArray() }
        routesAtPos = Array(stops.size) { p[it].toIntArray() }
    }

    /** Segundos a pé de [from] para [to] (0 se for a mesma); null se não houver caminho na rede. */
    fun walk(from: Int, to: Int): Int? {
        if (from == to) return 0
        val i = walkTo[from].indexOf(to)
        return if (i < 0) null else walkSecs[from][i]
    }
}

/** Uma paragem a que se chega a pé desde a partida (ou de onde se vai a pé até à chegada). */
data class Access(val stop: Int, val secs: Int)

sealed interface JLeg {
    val dep: Int
    val arr: Int

    /** Num veículo: percurso, viagem e posições de entrada e de saída. */
    data class Ride(val route: Int, val trip: Int, val fromPos: Int, val toPos: Int, override val dep: Int, override val arr: Int) : JLeg

    /** A pé entre paragens; -1 = o sítio de partida (em [from]) ou de chegada (em [to]). */
    data class Walk(val from: Int, val to: Int, override val dep: Int, override val arr: Int) : JLeg
}

data class Journey(val legs: List<JLeg>) {
    val dep: Int get() = legs.first().dep
    val arr: Int get() = legs.last().arr
    val rides: List<JLeg.Ride> get() = legs.filterIsInstance<JLeg.Ride>()
    val walkSecs: Int get() = legs.filterIsInstance<JLeg.Walk>().sumOf { it.arr - it.dep }
}

object Raptor {

    private const val INF = Int.MAX_VALUE / 2
    private const val NONE = 0
    private const val ACCESS = 1
    private const val RIDE = 2
    private const val WALK = 3

    /** Mudar de veículo na mesma paragem: 3 min no metro (corredores), 1 min nos autocarros. */
    fun changeSecs(stop: TransitStop): Int = if (stop.isMetro) 180 else 60

    /**
     * Viagens de [access] para [egress] a partir de [departure]: para cada número de veículos (1 até [maxRides]),
     * a que chega mais cedo, se chegar mais cedo do que todas as que usam menos veículos.
     * [allowRoute]: percursos que se podem usar (filtros por transporte, alternativas).
     */
    fun run(
        net: TransitNetwork,
        access: List<Access>,
        egress: List<Access>,
        departure: Int,
        maxRides: Int = 4,
        allowRoute: (Int) -> Boolean = { true }
    ): List<Journey> {
        val n = net.stops.size
        if (access.isEmpty() || egress.isEmpty() || n == 0) return emptyList()
        val rounds = maxRides + 1
        val arr = Array(rounds) { IntArray(n) { INF } }
        // Pronto para apanhar outro veículo (chegada de veículo + tempo de mudança)
        val ready = Array(rounds) { IntArray(n) { INF } }
        val kind = Array(rounds) { IntArray(n) }
        val pa = Array(rounds) { IntArray(n) }
        val pb = Array(rounds) { IntArray(n) }
        val pc = Array(rounds) { IntArray(n) }
        val best = IntArray(n) { INF }
        val egressSecs = IntArray(n) { -1 }
        egress.forEach { e -> if (egressSecs[e.stop] < 0 || e.secs < egressSecs[e.stop]) egressSecs[e.stop] = e.secs }
        val accessSecs = IntArray(n) { -1 }
        access.forEach { a -> if (accessSecs[a.stop] < 0 || a.secs < accessSecs[a.stop]) accessSecs[a.stop] = a.secs }
        val accessOf = { s: Int -> accessSecs[s].takeIf { it >= 0 } }

        var marked = BooleanArray(n)
        for (a in access) {
            val t = departure + a.secs
            if (t < arr[0][a.stop]) {
                arr[0][a.stop] = t
                ready[0][a.stop] = t
                best[a.stop] = t
                kind[0][a.stop] = ACCESS
                pa[0][a.stop] = a.secs
                marked[a.stop] = true
            }
        }

        val journeys = mutableListOf<Journey>()
        var bestTarget = INF
        val queuePos = IntArray(net.routes.size) { -1 }

        for (k in 1 until rounds) {
            System.arraycopy(arr[k - 1], 0, arr[k], 0, n)
            System.arraycopy(ready[k - 1], 0, ready[k], 0, n)

            // Percursos a explorar, cada um a partir da primeira paragem marcada
            java.util.Arrays.fill(queuePos, -1)
            var any = false
            for (s in 0 until n) {
                if (!marked[s]) continue
                val rs = net.routesAtRoute[s]
                val ps = net.routesAtPos[s]
                for (i in rs.indices) {
                    val r = rs[i]
                    if (!allowRoute(r)) continue
                    if (queuePos[r] < 0 || ps[i] < queuePos[r]) queuePos[r] = ps[i]
                    any = true
                }
            }
            if (!any) break
            val newMarked = BooleanArray(n)

            for (r in net.routes.indices) {
                val startPos = queuePos[r]
                if (startPos < 0) continue
                val route = net.routes[r]
                var trip = -1
                var boardPos = -1
                for (pos in startPos until route.size) {
                    val s = route.stops[pos]
                    if (trip >= 0) {
                        val t = route.time(trip, pos)
                        if (t < best[s] && t < bestTarget) {
                            arr[k][s] = t
                            ready[k][s] = t + changeSecs(net.stops[s])
                            best[s] = t
                            kind[k][s] = RIDE
                            pa[k][s] = r
                            pb[k][s] = trip
                            pc[k][s] = boardPos
                            newMarked[s] = true
                        }
                    }
                    val rdy = ready[k - 1][s]
                    if (rdy < INF && (trip < 0 || rdy <= route.time(trip, pos))) {
                        val t2 = route.earliestTrip(pos, rdy)
                        if (t2 >= 0 && (trip < 0 || route.time(t2, pos) < route.time(trip, pos))) {
                            trip = t2
                            boardPos = pos
                        }
                    }
                }
            }

            // A pé entre paragens, só a partir das que se alcançaram de veículo nesta volta
            val walked = BooleanArray(n)
            for (s in 0 until n) {
                if (!newMarked[s]) continue
                val to = net.walkTo[s]
                val secs = net.walkSecs[s]
                for (i in to.indices) {
                    val q = to[i]
                    val t = arr[k][s] + secs[i]
                    if (t < best[q] && t < arr[k][q] && t < bestTarget) {
                        arr[k][q] = t
                        ready[k][q] = t
                        best[q] = t
                        kind[k][q] = WALK
                        pa[k][q] = s
                        pb[k][q] = secs[i]
                        walked[q] = true
                    }
                }
            }
            for (s in 0 until n) if (walked[s]) newMarked[s] = true
            marked = newMarked

            // Chegada ao destino com até k veículos
            var bestStop = -1
            var bestArr = bestTarget
            for (s in 0 until n) {
                val e = egressSecs[s]
                if (e < 0 || arr[k][s] >= INF) continue
                val t = arr[k][s] + e
                if (t < bestArr) { bestArr = t; bestStop = s }
            }
            if (bestStop >= 0) {
                bestTarget = bestArr
                journeys += rebuild(net, arr, kind, pa, pb, pc, k, bestStop, egressSecs[bestStop], departure, accessOf)
            }
        }
        return journeys
    }

    private fun rebuild(
        net: TransitNetwork,
        arr: Array<IntArray>, kind: Array<IntArray>,
        pa: Array<IntArray>, pb: Array<IntArray>, pc: Array<IntArray>,
        k0: Int, target: Int, egressSecs: Int, departure: Int, accessOf: (Int) -> Int?
    ): Journey {
        val legs = ArrayList<JLeg>()
        legs += JLeg.Walk(target, -1, arr[k0][target], arr[k0][target] + egressSecs)
        var k = k0
        var s = target
        while (true) {
            when (kind[k][s]) {
                NONE -> { if (k == 0) break; k-- }
                ACCESS -> { legs += JLeg.Walk(-1, s, arr[k][s] - pa[k][s], arr[k][s]); break }
                RIDE -> {
                    val route = net.routes[pa[k][s]]
                    val trip = pb[k][s]
                    val from = pc[k][s]
                    val to = route.stops.indices.firstOrNull { p -> p > from && route.stops[p] == s && route.time(trip, p) == arr[k][s] } ?: route.size - 1
                    legs += JLeg.Ride(pa[k][s], trip, from, to, route.time(trip, from), route.time(trip, to))
                    s = route.stops[from]
                    k--
                }
                WALK -> {
                    val from = pa[k][s]
                    legs += JLeg.Walk(from, s, arr[k][s] - pb[k][s], arr[k][s])
                    s = from
                }
            }
        }
        legs.reverse()
        // A pé no fim com 0 s (a paragem é o destino) não interessa
        return tighten(net, Journey(legs.filter { it !is JLeg.Walk || it.arr > it.dep || it.from == -1 }), departure, accessOf)
    }

    /**
     * Arruma uma viagem sem mudar a hora de chegada: em cada veículo entra-se na paragem do mesmo percurso a que se
     * chega com menos caminho a pé (e, em empate, na mais à frente: menos tempo no veículo) e sai-se de casa o mais
     * tarde possível, com 1 min de folga na paragem.
     */
    fun tighten(net: TransitNetwork, j: Journey, departure: Int, accessSecs: ((Int) -> Int?)? = null): Journey {
        val legs = j.legs.toMutableList()
        for (i in legs.indices) {
            val ride = legs[i] as? JLeg.Ride ?: continue
            val route = net.routes[ride.route]
            // De onde se vem: a saída do veículo anterior, ou a partida (com o andar a pé até cada paragem)
            val prevRide = legs.subList(0, i).lastOrNull { it is JLeg.Ride } as? JLeg.Ride
            val fromStop = prevRide?.let { net.routes[it.route].stops[it.toPos] }
            val readyAt = prevRide?.arr ?: departure
            val walkLeg = legs.getOrNull(i - 1) as? JLeg.Walk
            var bestPos = ride.fromPos
            var bestWalk = Int.MAX_VALUE
            for (pos in ride.fromPos until ride.toPos) {
                val s = route.stops[pos]
                val w = when {
                    fromStop == null -> if (pos == ride.fromPos && walkLeg != null && walkLeg.from == -1) walkLeg.arr - walkLeg.dep else accessSecs?.invoke(s)
                    fromStop == s -> changeSecs(net.stops[s])
                    else -> net.walk(fromStop, s)
                } ?: continue
                if (readyAt + w > route.time(ride.trip, pos)) continue
                val pure = if (fromStop == s) 0 else w
                if (pure < bestWalk || pure == bestWalk) { bestWalk = pure; bestPos = pos }
            }
            if (bestPos == ride.fromPos) continue
            val newStop = route.stops[bestPos]
            val dep = route.time(ride.trip, bestPos)
            legs[i] = ride.copy(fromPos = bestPos, dep = dep)
            // A pé até à nova paragem
            if (walkLeg != null && (walkLeg.to == route.stops[ride.fromPos])) {
                val w = if (fromStop == null) (accessSecs?.invoke(newStop) ?: (walkLeg.arr - walkLeg.dep)) else if (fromStop == newStop) 0 else net.walk(fromStop, newStop) ?: 0
                legs[i - 1] = walkLeg.copy(to = newStop, arr = walkLeg.dep + w)
            } else if (fromStop != null && fromStop != newStop) {
                val w = net.walk(fromStop, newStop) ?: 0
                legs.add(i, JLeg.Walk(fromStop, newStop, readyAt, readyAt + w))
            }
        }
        // Sair de casa o mais tarde possível: o primeiro andar a pé acaba 1 min antes do primeiro veículo
        val firstRide = legs.indexOfFirst { it is JLeg.Ride }
        if (firstRide > 0) {
            val w = legs[firstRide - 1] as? JLeg.Walk
            if (w != null && w.from == -1) {
                val secs = w.arr - w.dep
                val end = (legs[firstRide].dep - 60).coerceAtLeast(w.arr)
                legs[firstRide - 1] = w.copy(dep = end - secs, arr = end)
            }
        }
        return Journey(legs)
    }
}
