package com.example.metrotime.data.regional

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Os pacotes reais em app/src/main/assets (gerados por tools/build_transit_packs.py). */
class TransitPackTest {

    private fun load(code: String): TransitPack =
        File("src/main/assets/transit_${code.lowercase()}.bin").inputStream().use { TransitPack.parse(it) }

    @Test
    fun fertagusHasItsStationsLinesAndTrips() {
        val ft = load("7NTB1")
        assertEquals("7NTB1", ft.code)
        assertTrue(ft.stops.any { it.name == "Roma-Areeiro" })
        assertTrue(ft.patterns.isNotEmpty())
        val first = ft.patterns.first { it.tripIds.isNotEmpty() }
        // Os comboios andam para a frente: os tempos entre estações nunca descem
        first.profiles.forEach { offs -> for (i in 1 until offs.size) assertTrue(offs[i] >= offs[i - 1]) }
        assertTrue(ft.shape(first.shape).size > 10)
    }

    @Test
    fun aTrainWithoutAStationIsPlacedByItsPosition() {
        val ft = load("7NTB1")
        val pattern = ft.patterns.first { it.stops.size > 5 }
        val station = ft.stops[pattern.stops[3]]
        // Em cima da estação: parado nela
        assertEquals(3 to true, TransitLive.positionByLocation(ft, pattern, station.lat, station.lon))
        // A meio caminho entre a 3 e a 4: vai para a 4
        val next = ft.stops[pattern.stops[4]]
        val mid = TransitLive.positionByLocation(ft, pattern, (station.lat + next.lat) / 2, (station.lon + next.lon) / 2)
        assertEquals(4 to false, mid)
    }

    @Test
    fun goHubTripIdsAreFoundWithoutThePlanPrefix() {
        val boats = load("LTP61")
        val p = boats.patterns.first { it.tripIds.isNotEmpty() }
        val id = p.tripIds.first()
        assertNotNull(boats.findTrip(id))
        assertEquals(boats.findTrip(id), boats.findTrip("[YYS70][LTP61]$id"))
    }

    @Test
    fun departuresAreSortedAndInsideTheWindow() {
        val boats = load("LTP61")
        val stop = boats.stops.first { s -> boats.patterns.any { p -> p.tripIds.isNotEmpty() && boats.stops[p.stops.first()].id == s.id } }
        // Uma quarta-feira normal com serviço
        val day = LocalDate.of(2026, 11, 25)
        val list = boats.departures(stop.id, day, 6 * 3600, 22 * 3600)
        assertTrue(list.isNotEmpty())
        assertTrue(list.zipWithNext().all { (a, b) -> a.seconds <= b.seconds })
        assertTrue(list.all { it.seconds in 6 * 3600..22 * 3600 })
    }

    @Test
    fun stcpBusesFindTheirRouteByPosition() {
        val stcp = load("STCP")
        val pattern = stcp.patterns.first { stcp.routes[it.route].shortName == "200" }
        val stop = stcp.stops[pattern.stops[3]]
        val found = TransitLive.nearestPattern(stcp, "200", pattern.direction, stop.lat, stop.lon)
        assertNotNull(found)
        val (p, next) = found!!
        assertEquals("200", stcp.routes[stcp.patterns[p].route].shortName)
        assertTrue(next in 3..4)
        val trip = TransitLive.estimatedTrip(stcp, p, next)
        assertTrue(trip.stops.take(next).all { it.passed })
        assertEquals(0, trip.stops[next].dueMinutes)
    }

    @Test
    fun liveVehicleOnAScheduledTripGivesARealtimeArrival() {
        val boats = load("LTP61")
        val op = RegionalOperators.TML.getValue("LTP61")
        val day = LocalDate.of(2026, 11, 25)
        val p = boats.patterns.indexOfFirst { it.tripIds.isNotEmpty() && it.stops.size >= 2 }
        val pattern = boats.patterns[p]
        val t = pattern.starts.indices.first { boats.services[pattern.service[it]].runsOn(day) }
        val first = boats.stops[pattern.stops.first()]
        // O barco ainda está no cais de partida, 3 min atrasado: a partida dele aparece em direto
        val v = RegionalVehicle(
            operator = op, vehicleId = "77", lat = 0.0, lon = 0.0, line = "SX", color = op.color,
            status = "STOPPED_AT", tripId = "[YYS70][LTP61]${pattern.tripIds[t]}",
            stopId = first.id, delaySeconds = 180
        )
        val now = pattern.timeAt(t, 0) + 120
        val arrivals = TransitLive.arrivals(boats, first.id, day, now, 3 * 3600, listOf(v))
        val live = arrivals.firstOrNull { it.realtime }
        assertNotNull(live)
        assertEquals(v.key, live!!.vehicleKey)
        assertEquals(pattern.timeAt(t, 0) + 180, live.expectedSec)
    }
}
