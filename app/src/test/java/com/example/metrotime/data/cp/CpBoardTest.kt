package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CpBoardTest {

    private val day = LocalDate.of(2026, 10, 26)
    private val start = LocalTime.of(17, 30)

    private fun train(number: Int, departure: String, delay: Int? = null, etd: String? = null) = CpTimedStop(
        CpStationStop(trainNumber = number, departureTime = departure, arrivalTime = departure, delay = delay, etd = etd, eta = etd),
        day, start
    )

    @Test
    fun boardIsOrderedByRealDeparture() {
        // O Alfa das 17:31 vai com +30 (parte às 18:01): tem de aparecer depois do Urbano das 17:42
        val alfa = train(131, "17:31", delay = 30, etd = "18:01")
        val urbano = train(18_000, "17:42", delay = 4, etd = "17:46")
        val regional = train(5_000, "17:50")
        val board = CpBoard.board(listOf(alfa, urbano, regional), departures = true, now = LocalDateTime.of(day, LocalTime.of(17, 40)))
        assertEquals(listOf(18_000, 5_000, 131), board.map { it.stop.trainNumber })
    }

    @Test
    fun lateTrainShowsEstimateAndStrikesScheduled() {
        val e = CpBoard.entry(train(131, "17:31", delay = 30, etd = "18:01"), true, LocalDateTime.of(day, LocalTime.of(17, 40)))!!
        assertEquals("18:01", e.shown)
        assertEquals("17:31", e.struck)
        assertEquals("21 min", e.countdown)
        assertEquals("+30 min", e.status)
    }

    @Test
    fun onTimeTrainShowsScheduled() {
        val e = CpBoard.entry(train(5_000, "17:50", delay = 0), true, LocalDateTime.of(day, LocalTime.of(17, 50)))!!
        assertEquals("17:50", e.shown)
        assertNull(e.struck)
        assertEquals("agora", e.countdown)
        assertEquals("Na hora", e.status)
    }

    @Test
    fun withoutEstimateUsesScheduledPlusDelay() {
        val e = CpBoard.entry(train(1, "17:40", delay = 6), true, LocalDateTime.of(day, LocalTime.of(17, 40)))!!
        assertEquals(LocalDateTime.of(day, LocalTime.of(17, 46)), e.effective)
        // Como no ecrã do comboio: a hora esperada em grande e a prevista riscada
        assertEquals("17:46", e.shown)
        assertEquals("17:40", e.struck)
        assertEquals("+6 min", e.status)
    }

    @Test
    fun suppressedHasNoCountdown() {
        val e = CpBoard.entry(train(1, "17:40", delay = -1), true, LocalDateTime.of(day, LocalTime.of(17, 35)))!!
        assertNull(e.countdown)
        assertEquals("Suprimido", e.status)
    }

    @Test
    fun estimateAfterMidnightIsNextDay() {
        val at = CpBoard.effectiveOf(LocalDateTime.of(day, LocalTime.of(23, 50)), "00:10", 20)
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(0, 10)), at)
    }
}
