package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CpRouteTest {

    // O AP 125 de hoje, como a Cloud Function o guarda em cp_routes
    private val ap125 = "2026-11-23|94-30007,,12:00,0;94-31039,12:08,12:09,0;94-36004,13:45,13:47,0;" +
        "94-38000,14:13,14:15,0;94-39164,14:42,14:43,1;94-2006,14:48,,0"

    @Test
    fun readsTheStopsAndTimes() {
        val r = CpRoute.parse(ap125)!!
        assertEquals(LocalDate.of(2026, 11, 23), r.runDate)
        assertEquals(6, r.stops.size)
        assertEquals("94-30007", r.stops[0].station?.code)
        assertNull(r.stops[0].arrival)
        assertEquals("12:00", r.stops[0].departure)
        assertEquals("14:48", r.stops[5].arrival)
        assertNull(r.stops[5].departure)
        assertNotNull(r.stops[4].supression)
        assertNull(r.stops[3].supression)
    }

    @Test
    fun theDelayOfNowGoesToTheStopsThatRun() {
        val stops = CpRoute.parse(ap125)!!.withDelay(855)
        assertEquals(14, stops[2].delay)
        // A suprimida fica sem atraso
        assertNull(stops[4].delay)
        // E as horas previstas saem com ele: Coimbra-B 13:45 → 13:59
        val m = CpTripClock.moments(LocalDate.of(2026, 11, 23), stops)
        assertEquals("13:59", m[2].arrival!!.toLocalTime().toString())
    }

    @Test
    fun brokenTextGivesNothing() {
        assertNull(CpRoute.parse(null))
        assertNull(CpRoute.parse(""))
        assertNull(CpRoute.parse("sem barra"))
        assertNull(CpRoute.parse("2026-13-40|A,,10:00,0;B,10:10,,0"))
        assertNull(CpRoute.parse("2026-11-23|A,,10:00,0"))
        assertNull(CpRoute.parse("2026-11-23|A,,10:00;B,10:10,,0"))
    }
}
