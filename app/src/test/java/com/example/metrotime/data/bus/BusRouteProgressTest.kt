package com.example.metrotime.data.bus

import org.junit.Assert.assertEquals
import org.junit.Test

class BusRouteProgressTest {

    @Test
    fun busIsPlacedAtTheClosestStop() {
        val stops = listOf(38.700 to -9.140, 38.705 to -9.140, 38.710 to -9.140)
        assertEquals(1, BusRouteProgress.nearestStop(stops, 38.7051 to -9.1401))
        assertEquals(-1, BusRouteProgress.nearestStop(emptyList(), 38.7 to -9.1))
    }

    @Test
    fun stopsAwayCountsFromTheLastBusBefore() {
        // Autocarros nas paragens 1 e 4 de um percurso com 7
        assertEquals(listOf(null, 0, 1, 2, 0, 1, 2), BusRouteProgress.stopsAway(7, listOf(4, 1)))
        assertEquals(listOf(null, null, null), BusRouteProgress.stopsAway(3, emptyList()))
        assertEquals(listOf(0, 1), BusRouteProgress.stopsAway(2, listOf(0, 9)))
    }

    @Test
    fun labels() {
        assertEquals("", BusRouteProgress.label(null))
        assertEquals("a chegar", BusRouteProgress.label(0))
        assertEquals("a 1 paragem", BusRouteProgress.label(1))
        assertEquals("a 4 paragens", BusRouteProgress.label(4))
    }
}
