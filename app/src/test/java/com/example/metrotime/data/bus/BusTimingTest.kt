package com.example.metrotime.data.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusTimingTest {

    @Test
    fun statusLikeGoogleMaps() {
        assertEquals(BusTiming.Status("a horas", BusTiming.Kind.ON_TIME), BusTiming.status(true, 1))
        assertEquals(BusTiming.Status("a horas", BusTiming.Kind.ON_TIME), BusTiming.status(true, -1))
        assertEquals(BusTiming.Status("atrasado 3 min", BusTiming.Kind.LATE), BusTiming.status(true, 3))
        assertEquals(BusTiming.Status("atrasado 8 min", BusTiming.Kind.VERY_LATE), BusTiming.status(true, 8))
        assertEquals(BusTiming.Status("adiantado 2 min", BusTiming.Kind.EARLY), BusTiming.status(true, -2))
        assertEquals(BusTiming.Status("tempo real", BusTiming.Kind.LIVE), BusTiming.status(true, null))
        val scheduled = BusTiming.status(false, null)
        assertEquals("previsto", scheduled.text)
        assertFalse(scheduled.realtime)
        assertTrue(BusTiming.status(true, 5).realtime)
    }

    @Test
    fun everythingInMinutesAndTheClockOnlyAnHourAhead() {
        assertEquals("4 min", BusTiming.eta(4, realtime = true, scheduledTime = "19:58"))
        assertEquals("a chegar", BusTiming.eta(0, realtime = true, scheduledTime = null))
        // Previstos em minutos, como no Google ("9 min", agendado), sem misturar com horas
        assertEquals("18 min", BusTiming.eta(18, realtime = false, scheduledTime = "19:58"))
        assertEquals("agora", BusTiming.eta(0, realtime = false, scheduledTime = "19:40"))
        assertEquals("21:05", BusTiming.eta(85, realtime = false, scheduledTime = "21:05"))
        assertEquals("85 min", BusTiming.eta(85, realtime = true, scheduledTime = "21:05"))
    }

    @Test
    fun clockWrapsAfterMidnight() {
        assertEquals("19:58", BusTiming.clock(19 * 3600 + 58 * 60 + 30))
        assertEquals("01:10", BusTiming.clock(25 * 3600 + 10 * 60))
        assertEquals("23:50", BusTiming.clock(-10 * 60))
    }

    @Test
    fun delayFromWhereTheBusIs() {
        val prev = 18 * 3600          // 18:00 na paragem anterior
        val next = prev + 3 * 60      // 18:03 na seguinte
        // A caminho da seguinte às 18:07: 4 min atrasado
        assertEquals(240, BusTiming.delaySeconds(prev, next, next + 240, stoppedAtNext = false))
        // Entre as duas horas: a horas
        assertEquals(0, BusTiming.delaySeconds(prev, next, prev + 60, stoppedAtNext = false))
        // Ainda antes da hora da anterior: adiantado
        assertEquals(-120, BusTiming.delaySeconds(prev, next, prev - 120, stoppedAtNext = false))
        // Parado na paragem: agora menos a hora dela
        assertEquals(-60, BusTiming.delaySeconds(prev, next, next - 60, stoppedAtNext = true))
        // Primeira paragem (sem anterior) e ainda antes da hora: a horas
        assertEquals(0, BusTiming.delaySeconds(null, next, next - 300, stoppedAtNext = false))
    }

    @Test
    fun roundsToTheNearestMinute() {
        assertEquals(2, BusTiming.roundMinutes(90))
        assertEquals(1, BusTiming.roundMinutes(89))
        assertEquals(-2, BusTiming.roundMinutes(-90))
        assertEquals(0, BusTiming.roundMinutes(20))
    }
}
