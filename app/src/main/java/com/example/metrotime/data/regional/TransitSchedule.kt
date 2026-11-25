package com.example.metrotime.data.regional

import java.time.LocalDate

/**
 * Onde está cada viagem agora, só pelo horário: para o Metro do Porto, que não publica posições em direto. Cada metro
 * anda pelo traçado real entre as estações às horas do horário, a acelerar e a travar, e fica uns segundos parado em
 * cada estação. Sem dependências Android (testado em app/src/test).
 */
object TransitSchedule {

    /** Tempo parado em cada estação (no início de cada troço), se o troço tiver tempo para isso. */
    private const val DWELL_S = 20

    /** O traçado de um percurso com as distâncias acumuladas e onde cai cada paragem nele (metros). */
    class Geometry(val points: List<Pair<Double, Double>>, val cumulative: DoubleArray, val stopAt: DoubleArray) {
        val length: Double get() = cumulative.lastOrNull() ?: 0.0

        /** O ponto a [s] metros do início. */
        fun pointAt(s: Double): Pair<Double, Double> {
            if (points.size == 1 || s <= 0) return points.first()
            if (s >= length) return points.last()
            var lo = 0
            var hi = cumulative.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (cumulative[mid] <= s) lo = mid else hi = mid
            }
            val seg = cumulative[hi] - cumulative[lo]
            val u = if (seg <= 0) 0.0 else (s - cumulative[lo]) / seg
            val a = points[lo]
            val b = points[hi]
            return (a.first + (b.first - a.first) * u) to (a.second + (b.second - a.second) * u)
        }
    }

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians((a.first + b.first) / 2))
        return kotlin.math.hypot((b.second - a.second) * kx, (b.first - a.first) * 110_540.0)
    }

    /**
     * O traçado do percurso e onde cai cada paragem: a projeção dela no traçado, sempre para a frente da anterior
     * (nos circulares a mesma estação aparece duas vezes, em sítios diferentes do traçado).
     */
    fun geometry(pack: TransitPack, pattern: TransitPack.Pattern): Geometry {
        val pts = pack.geometry(pattern)
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + metres(pts[i - 1], pts[i])
        val stopAt = DoubleArray(pattern.stops.size)
        var fromSeg = 0
        for ((k, idx) in pattern.stops.withIndex()) {
            val stop = pack.stops[idx]
            val p = stop.lat to stop.lon
            var best = Double.MAX_VALUE
            var bestS = if (k == 0) 0.0 else stopAt[k - 1]
            var bestSeg = fromSeg
            // Só um pouco à frente da paragem anterior (uma linha que dá a volta não salta para o outro lado)
            val prevS = if (k == 0) 0.0 else stopAt[k - 1]
            val prevStop = if (k == 0) p else pack.stops[pattern.stops[k - 1]].let { it.lat to it.lon }
            val window = 3 * metres(prevStop, p) + 1_000
            for (seg in fromSeg until maxOf(fromSeg + 1, pts.size - 1)) {
                if (seg + 1 >= pts.size) break
                if (k > 0 && cum[seg] - prevS > window) break
                val a = pts[seg]
                val b = pts[seg + 1]
                // Projeção no troço, num plano local em metros
                val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(a.first))
                val ax = 0.0
                val ay = 0.0
                val bx = (b.second - a.second) * kx
                val by = (b.first - a.first) * 110_540.0
                val px = (p.second - a.second) * kx
                val py = (p.first - a.first) * 110_540.0
                val l2 = bx * bx + by * by
                val u = if (l2 <= 0) 0.0 else ((px - ax) * bx + (py - ay) * by) / l2
                val t = u.coerceIn(0.0, 1.0)
                val d = kotlin.math.hypot(px - bx * t, py - by * t)
                if (d < best) {
                    best = d
                    bestS = cum[seg] + kotlin.math.sqrt(l2) * t
                    bestSeg = seg
                }
            }
            stopAt[k] = if (k > 0) maxOf(bestS, stopAt[k - 1]) else bestS
            fromSeg = bestSeg
        }
        return Geometry(pts, cum, stopAt)
    }

    /** Arranca devagar, anda, trava: 0 → 1 com velocidade nula nas pontas. */
    private fun ease(u: Double): Double {
        val x = u.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    /**
     * Os veículos em circulação agora ([nowSec] segundos de [today]) pelo horário do pacote: as viagens de hoje e as de
     * ontem que passam da meia-noite. [geometries]: o traçado de cada percurso já calculado (é guardado aqui).
     */
    fun vehicles(
        pack: TransitPack,
        operator: RegionalOperator,
        today: LocalDate,
        nowSec: Int,
        nowMs: Long,
        geometries: MutableMap<Int, Geometry>
    ): List<RegionalVehicle> {
        val out = ArrayList<RegionalVehicle>()
        val days = listOf(today to 0, today.minusDays(1) to 86_400)
        for ((date, offset) in days) {
            val runs = BooleanArray(pack.services.size) { pack.services[it].runsOn(date) }
            val dateInt = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
            val secs = nowSec + offset
            for ((p, pattern) in pack.patterns.withIndex()) {
                val last = pattern.stops.lastIndex
                if (last < 1) continue
                for (t in pattern.starts.indices) {
                    if (!runs[pattern.service[t]]) continue
                    val start = pattern.timeAt(t, 0)
                    val end = pattern.timeAt(t, last)
                    if (secs < start || secs > end) continue
                    val g = geometries.getOrPut(p) { geometry(pack, pattern) }
                    // O troço onde está: entre a paragem i (já passou a hora dela) e a i + 1
                    var i = 0
                    while (i < last - 1 && pattern.timeAt(t, i + 1) <= secs) i++
                    val t0 = pattern.timeAt(t, i)
                    val t1 = pattern.timeAt(t, i + 1)
                    val dwell = minOf(DWELL_S, (t1 - t0) / 4)
                    val stopped = secs - t0 < dwell
                    val u = if (stopped || t1 - t0 - dwell <= 0) 0.0 else (secs - t0 - dwell).toDouble() / (t1 - t0 - dwell)
                    val s = g.stopAt[i] + (g.stopAt[i + 1] - g.stopAt[i]) * ease(u)
                    val (lat, lon) = g.pointAt(s)
                    val route = pack.routes[pattern.route]
                    out += RegionalVehicle(
                        operator = operator,
                        vehicleId = pattern.tripIds[t],
                        lat = lat,
                        lon = lon,
                        line = route.label,
                        color = route.color ?: operator.color,
                        status = if (stopped) "STOPPED_AT" else "IN_TRANSIT_TO",
                        observedAt = nowMs,
                        tripId = pattern.tripIds[t],
                        stopId = pack.stops[pattern.stops[if (stopped) i else i + 1]].id,
                        serviceDate = dateInt,
                        destination = RegionalParsers.prettyName(pattern.headsign)
                    )
                }
            }
        }
        return out
    }
}
