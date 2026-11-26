package com.example.metrotime.data.regional

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** O Metro do Porto pelo horário, com o pacote real (GTFS oficial + traçado do OpenStreetMap). */
class TransitScheduleTest {

    private val pack = File("src/main/assets/transit_mdp.bin").inputStream().use { TransitPack.parse(it) }
    private val mdp = RegionalOperators.MDP
    /** Uma quarta-feira normal com o horário de setembro em vigor. */
    private val day = LocalDate.of(2026, 11, 25)

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(a.first))
        return kotlin.math.hypot((b.second - a.second) * kx, (b.first - a.first) * 110_540.0)
    }

    @Test
    fun atRushHourThereAreTrainsOnEveryLine() {
        val vehicles = TransitSchedule.vehicles(pack, mdp, day, 8 * 3600 + 15 * 60, 0L, HashMap())
        assertTrue("só ${vehicles.size} metros", vehicles.size > 30)
        val lines = vehicles.mapNotNull { it.line }.toSet()
        assertTrue(lines.containsAll(listOf("A", "B", "C", "D", "E", "F")))
        assertTrue(vehicles.all { it.key.startsWith("pack:MDP|") && it.serviceDate == 20261125 })
    }

    @Test
    fun trainsAreOnTheTrackAndAtTheStationAtItsTime() {
        val geometries = HashMap<Int, TransitSchedule.Geometry>()
        val p = pack.patterns.indexOfFirst { pack.routes[it.route].shortName == "D" && it.starts.isNotEmpty() }
        val pattern = pack.patterns[p]
        val t = pattern.starts.indices.first { pack.services[pattern.service[it]].runsOn(day) && pattern.starts[it] > 8 * 3600 }
        val g = TransitSchedule.geometry(pack, pattern)
        // As estações caem em cima do traçado, por ordem
        for (i in 1 until g.stopAt.size) assertTrue(g.stopAt[i] >= g.stopAt[i - 1])
        pattern.stops.forEachIndexed { k, idx ->
            val s = pack.stops[idx]
            assertTrue("estação ${s.name} fora do traçado", metres(g.pointAt(g.stopAt[k]), s.lat to s.lon) < 150)
        }
        // À hora da 3.ª estação, o metro está nela, parado
        val station = pack.stops[pattern.stops[2]]
        val at = TransitSchedule.vehicles(pack, mdp, day, pattern.timeAt(t, 2) + 5, 0L, geometries)
            .first { it.tripId == pattern.tripIds[t] }
        assertEquals("STOPPED_AT", at.status)
        assertEquals(station.id, at.stopId)
        assertTrue(metres(at.lat to at.lon, station.lat to station.lon) < 150)
        // A meio do troço seguinte já saiu e vai para a 4.ª
        val mid = (pattern.timeAt(t, 2) + pattern.timeAt(t, 3)) / 2 + 10
        val moving = TransitSchedule.vehicles(pack, mdp, day, mid, 0L, geometries).first { it.tripId == pattern.tripIds[t] }
        assertEquals("IN_TRANSIT_TO", moving.status)
        assertEquals(pack.stops[pattern.stops[3]].id, moving.stopId)
    }

    @Test
    fun noTrainsAtFourInTheMorning() {
        assertTrue(TransitSchedule.vehicles(pack, mdp, day, 4 * 3600, 0L, HashMap()).isEmpty())
    }
}
