package com.example.metrotime.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripRulesTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    @Test
    fun metroTripGoesByTheNetwork() {
        val t = TripRules.metroTrip("a", "AM", "BC", t0, t0 + 12 * min, "auto")
        assertEquals("Alameda", t.fromName)
        assertEquals("Baixa-Chiado", t.toName)
        assertEquals(listOf("Verde"), t.lines)
        assertTrue(t.km > 2)
        assertEquals(12, t.minutes)
    }

    @Test
    fun sameOriginWithinHalfAnHourIsADuplicate() {
        val a = TripRules.metroTrip("a", "AM", "BC", t0, t0 + 12 * min, "auto")
        assertTrue(TripRules.isDuplicate(listOf(a), a.copy(id = "b", startMs = t0 + 10 * min)))
        assertFalse(TripRules.isDuplicate(listOf(a), a.copy(id = "b", startMs = t0 + 40 * min)))
        assertFalse(TripRules.isDuplicate(listOf(a), a.copy(id = "b", fromId = "RO")))
    }

    @Test
    fun legsThatFollowOnAreOneTrip() {
        // Odivelas → Campo Grande e logo Campo Grande → Marquês: uma viagem só
        val a = TripRules.metroTrip("a", "OD", "CG", t0, t0 + 14 * min, "auto")
        val b = TripRules.metroTrip("b", "CG", "MP", t0 + 16 * min, t0 + 26 * min, "auto")
        val joined = TripRules.chain(a, b)!!
        assertEquals("OD" to "MP", joined.fromId to joined.toId)
        assertEquals(t0, joined.startMs)
        assertEquals(t0 + 26 * min, joined.endMs)
        // Muito depois, ou a voltar para trás, são viagens diferentes
        assertNull(TripRules.chain(a, b.copy(startMs = t0 + 40 * min)))
        assertNull(TripRules.chain(a, TripRules.metroTrip("c", "CG", "OD", t0 + 16 * min, t0 + 30 * min, "auto")))
    }

    @Test
    fun manualStartIsClosedByTheDetectedEnd() {
        val open = Trip("h", "metro", "AM", "Alameda", lines = listOf("Verde", "Vermelha"), startMs = t0, source = "manual", direction = "Cais do Sodré")
        val detected = TripRules.metroTrip("m", "AM", "BC", t0 + min, t0 + 12 * min, "auto")
        val closed = TripRules.close(open, detected)!!
        assertEquals("BC", closed.toId)
        assertEquals("h", closed.id)
        assertEquals("manual", closed.source)
        assertEquals("Cais do Sodré", closed.direction)
    }

    @Test
    fun oldVisitsBecomeTrips() {
        fun v(name: String, t: Long, type: String = "metro", line: String = "Verde") = StationVisit(name, name, type, line, 0.0, 0.0, t)
        val trips = TripRules.fromVisits(
            listOf(
                v("Alameda", t0), v("Baixa-Chiado", t0 + 15 * min),   // par: A → B
                v("Rossio", t0 + 5 * 60 * min),                        // sozinha: só origem
                v("Lisboa - Oriente", t0 + 24 * 60 * min, "cp", ""), v("Azambuja", t0 + 24 * 60 * min + 40 * min, "cp", "")
            )
        )
        assertEquals(3, trips.size)
        assertEquals("AM" to "BC", trips[0].fromId to trips[0].toId)
        assertEquals("legacy", trips[0].source)
        assertNull(trips[1].toId)
        assertEquals(listOf("Verde"), trips[1].lines)
        assertEquals("cp", trips[2].mode)
        assertEquals("Azambuja", trips[2].toName)
    }

    @Test
    fun friendsNumbersDoNotGetStuck() {
        val stats = TripStats(40, 80.0, 300, 4, 9, emptyList(), emptyList(), 12, "2026-10", "2026-10-24")
        assertEquals(12, stats.monthlyOn(LocalDate.of(2026, 10, 25)))
        assertEquals(0, stats.monthlyOn(LocalDate.of(2026, 11, 2)))   // novembro: ainda não viajou
        assertEquals(4, stats.streakOn(LocalDate.of(2026, 10, 25)))   // última viagem ontem
        assertEquals(0, stats.streakOn(LocalDate.of(2026, 10, 27)))   // dois dias sem viajar
        // Dados antigos (sem mês nem dia): ficam como vieram
        val old = stats.copy(monthKey = null, lastTripDay = null)
        assertEquals(12, old.monthlyOn(LocalDate.of(2026, 11, 2)))
        assertEquals(4, old.streakOn(LocalDate.of(2026, 12, 1)))
        assertEquals("2026-03", TripStats.monthKey(LocalDate.of(2026, 3, 9)))
    }
}
