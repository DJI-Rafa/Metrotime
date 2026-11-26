package com.example.metrotime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroNetworkTest {

    @Test
    fun sameLineGoesStraightAlongIt() {
        // Alameda → Baixa-Chiado pela Verde: AM, AR, AN, IN, MM, RO, BC
        val r = MetroNetwork.route("AM", "BC")!!
        assertEquals(listOf("AM", "AR", "AN", "IN", "MM", "RO", "BC"), r.stations)
        assertEquals(listOf("Verde"), r.lines)
        // Pela linha é mais do que em linha reta
        val p = MapConfiguration.STATION_COORDS.getValue("AM")
        val q = MapConfiguration.STATION_COORDS.getValue("BC")
        assertTrue(r.km > MetroNetwork.haversineKm(p.lat, p.lon, q.lat, q.lon))
        assertTrue(r.minutes in 8.0..20.0)
    }

    @Test
    fun changingLinesCostsTime() {
        // Reboleira (Azul) → Oriente (Vermelha): muda em São Sebastião
        val r = MetroNetwork.route("RB", "OR")!!
        assertEquals(listOf("Azul", "Vermelha"), r.lines)
        assertTrue("SS" in r.stations)
        assertTrue(r.minutes > 30)
    }

    @Test
    fun unknownStationsHaveNoRoute() {
        assertNull(MetroNetwork.route("AM", "XX"))
        assertEquals(0.0, MetroNetwork.route("AM", "AM")!!.km, 0.0)
    }
}
