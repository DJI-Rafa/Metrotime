package com.example.metrotime.data.plan

import com.example.metrotime.data.bus.BusScheduleStop
import com.example.metrotime.data.bus.busScheduleDelay
import com.example.metrotime.data.bus.reanchorSchedule
import com.example.metrotime.work.LeaveAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Juntar as viagens (a mais rápida / chegar às…), o texto do aviso para sair e o atraso dos autocarros. */
class JourneyPlannerTest {

    private val day = LocalDateTime.of(2026, 11, 25, 0, 0)
    private fun t(h: Int, m: Int) = day.withHour(h).withMinute(m)

    private fun walk(a: LocalDateTime, b: LocalDateTime, to: String, lat: Double? = null) =
        PlanLeg(LegMode.WALK, a, b, "Aqui", to, "A pé", toLat = lat, toLon = lat?.let { -9.14 }, metres = 400.0)

    private fun ride(mode: LegMode, a: LocalDateTime, b: LocalDateTime, label: String, from: String, to: String, agency: String? = null) =
        PlanLeg(mode, a, b, from, to, label, agency = agency)

    private fun option(vararg legs: PlanLeg) = PlanOption(legs.toList())

    @Test
    fun departingSortsByArrivalAndTheLocalOneWinsWhenTheSame() {
        val metro = PlanAlternative(listOf(option(ride(LegMode.METRO, t(8, 10), t(8, 30), "Linha Verde", "Rossio", "Cais do Sodré"))))
        val boat = PlanAlternative(listOf(option(ride(LegMode.BOAT, t(8, 5), t(8, 20), "Barco", "Cais do Sodré", "Cacilhas", "Transtejo"))))
        val sameAsMetro = PlanAlternative(listOf(option(ride(LegMode.METRO, t(8, 12), t(8, 33), "Linha Verde", "Rossio", "Cais do Sodré", "Metropolitano de Lisboa"))))
        val merged = JourneyPlanner.merge(listOf(metro), listOf(boat, sameAsMetro), arriveBy = false, target = t(8, 0))
        assertEquals(listOf(LegMode.BOAT, LegMode.METRO), merged.map { it.first.transit.first().mode })
        // A do metro é a da app (8:10), não a online (8:12)
        assertEquals(t(8, 10), merged[1].first.depart)
    }

    @Test
    fun arrivingKeepsOnlyOnTimeDeparturesLatestFirst() {
        val bus = PlanAlternative(
            listOf(
                option(ride(LegMode.BUS, t(8, 0), t(8, 40), "728", "A", "B")),
                option(ride(LegMode.BUS, t(8, 15), t(8, 55), "728", "A", "B")),
                option(ride(LegMode.BUS, t(8, 30), t(9, 10), "728", "A", "B"))
            )
        )
        val train = PlanAlternative(listOf(option(ride(LegMode.TRAIN, t(8, 20), t(8, 50), "U", "A", "B"))))
        val late = PlanAlternative(listOf(option(ride(LegMode.BOAT, t(8, 45), t(9, 5), "Barco", "A", "B"))))
        val merged = JourneyPlanner.merge(listOf(bus, train), listOf(late), arriveBy = true, target = t(9, 0))
        // O barco chega tarde demais; o comboio deixa sair mais tarde (8:20) que o autocarro (8:15)
        assertEquals(listOf(LegMode.TRAIN, LegMode.BUS), merged.map { it.first.transit.first().mode })
        assertEquals(listOf(t(8, 15), t(8, 0)), merged[1].options.map { it.depart })
    }

    @Test
    fun theLeaveAlarmSaysWhatToTakeAndWhere() {
        val o = option(
            walk(t(8, 4), t(8, 9), "Rossio", 38.714),
            ride(LegMode.METRO, t(8, 9), t(8, 11), "Linha Verde", "Rossio", "Cais do Sodré"),
            ride(LegMode.TRAIN, t(8, 21), t(8, 51), "U", "Cais do Sodré", "Cascais")
        )
        val s = LeaveAlarm.describe(o, "Cascais")
        assertEquals(
            "Às 08:04 vai a pé até Rossio (5 min) e apanha a Linha Verde das 08:09. Chegas a Cascais às 08:51.",
            s.soonText
        )
        assertEquals("A pé até Rossio (400 m). A Linha Verde parte às 08:09.", s.nowText)
        assertEquals("Rossio", s.walkName)
        assertEquals(38.714, s.walkLat!!, 1e-9)
    }

    private fun stop(id: String, sec: Int?, passed: Boolean) = BusScheduleStop(id, id, "", passed, sec)

    @Test
    fun busDelayAgainstItsTimetable() {
        val schedule = listOf(stop("a", 8 * 3600, true), stop("b", 8 * 3600 + 300, false), stop("c", 8 * 3600 + 600, false))
        // Às 8:09 ainda a caminho de "b" (devia lá estar às 8:05): 4 min atrasado
        assertEquals(240, busScheduleDelay(schedule, stopped = false, nowSec = 8 * 3600 + 540))
        // Às 7:58 já saiu de "a" (devia sair às 8:00): 2 min adiantado
        assertEquals(-120, busScheduleDelay(schedule, stopped = false, nowSec = 7 * 3600 + 58 * 60))
        // Sem o horário desta viagem não se sabe
        assertNull(busScheduleDelay(schedule.map { it.copy(scheduledSec = null) }, false, 8 * 3600))
        // Viagem que passa da meia-noite: 00:10 conta como 24:10
        val night = listOf(stop("x", 23 * 3600 + 3000, true), stop("y", 24 * 3600 + 300, false))
        assertEquals(300, busScheduleDelay(night, stopped = false, nowSec = 600))
    }

    @Test
    fun theOpenBusMovesForwardOnly() {
        val schedule = listOf(stop("a", 0, true), stop("b", 60, false), stop("c", 120, false), stop("b2", 180, false))
        val moved = reanchorSchedule(schedule, "c")
        assertEquals(listOf(true, true, false, false), moved.map { it.isPassed })
        // Uma posição antiga (para trás) não faz recuar
        assertEquals(moved, reanchorSchedule(moved, "a"))
        assertTrue(reanchorSchedule(schedule, "") === schedule)
    }
}
