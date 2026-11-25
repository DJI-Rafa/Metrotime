package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpMarkerTest {

    // Linha reta para norte: Santa Apolónia (sul) ← Braço de Prata ← Oriente (norte)
    private val oriente = 38.7680 to -9.0990
    private val braco = 38.7480 to -9.1050
    private val apolonia = 38.7158 to -9.1207
    private val track = CpTrack(listOf(oriente, braco, apolonia))

    @Test
    fun arrivedAtTheEndStaysAtTheStationEvenWithAnOldGps() {
        // O caso do IC 540: GPS ainda 1 km antes de Santa Apolónia, mas a CP já o dá lá
        val gps = 38.7250 to -9.1170
        val p = CpMarker.place(gps, VehicleStatus.IN_TRANSIT, apolonia, lastIsDestination = true, destination = apolonia, track = track)
        assertEquals(apolonia, p.point)
        assertTrue(p.atStation)
    }

    @Test
    fun atStationUsesTheStation() {
        val p = CpMarker.place(38.7500 to -9.1040, VehicleStatus.AT_STATION, braco, false, apolonia, track)
        assertEquals(braco, p.point)
        assertTrue(p.atStation)
    }

    @Test
    fun gpsBehindTheLastStationIsPulledToIt() {
        // Já passou Braço de Prata, mas o GPS ainda está perto do Oriente
        val gps = 38.7650 to -9.1000
        val p = CpMarker.place(gps, VehicleStatus.IN_TRANSIT, braco, false, apolonia, track)
        assertEquals(braco, p.point)
        assertFalse(p.atStation)
    }

    @Test
    fun gpsAheadOfTheLastStationIsKept() {
        val gps = 38.7300 to -9.1140
        val p = CpMarker.place(gps, VehicleStatus.IN_TRANSIT, braco, false, apolonia, track)
        assertEquals(gps, p.point)
    }

    @Test
    fun aFewMetresBehindIsNoise() {
        val gps = 38.7490 to -9.1046 // ~120 m antes
        assertEquals(gps, CpMarker.place(gps, VehicleStatus.IN_TRANSIT, braco, false, apolonia, track).point)
    }

    @Test
    fun withoutTrackComparesByTheDestination() {
        val gps = 38.7650 to -9.1000
        assertTrue(CpMarker.isBehind(gps, braco, apolonia, null))
        assertFalse(CpMarker.isBehind(38.7300 to -9.1140, braco, apolonia, null))
    }

    @Test
    fun trackDrawnTheOtherWayStillWorks() {
        val reversed = CpTrack(listOf(apolonia, braco, oriente))
        assertTrue(CpMarker.isBehind(38.7650 to -9.1000, braco, apolonia, reversed))
    }

    @Test
    fun withoutLastStationKeepsTheGps() {
        val gps = 38.7650 to -9.1000
        assertEquals(gps, CpMarker.place(gps, VehicleStatus.IN_TRANSIT, null, false, apolonia, track).point)
    }
}
