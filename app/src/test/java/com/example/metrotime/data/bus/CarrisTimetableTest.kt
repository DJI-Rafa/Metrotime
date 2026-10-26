package com.example.metrotime.data.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Lê o horário verdadeiro (assets/carris_timetable.bin) e confirma o que a app usa dele. */
class CarrisTimetableTest {

    private val data = File("src/main/assets/carris_timetable.bin").inputStream().use { CarrisTimetable.parse(it) }

    @Test
    fun readsTheWholeTimetable() {
        assertEquals("20260606", data.feedDate)
        assertTrue(data.patterns.size > 250)
        assertTrue(data.tripCount > 50_000)
        assertTrue(data.routes.isNotEmpty())
        // Os tempos de cada percurso começam em 0 e nunca andam para trás
        data.patterns.forEach { p ->
            p.profiles.forEach { prof ->
                assertEquals(0, prof[0])
                assertTrue(prof.toList().zipWithNext().all { (a, b) -> b >= a })
            }
        }
    }

    @Test
    fun tripIdsMatchTheLiveFeed() {
        // Formato dos trip_id do GPS da Carris: "3876_20260606_170_0_12"
        val p = data.patterns.lastIndex
        val pattern = data.patterns[p]
        val i = pattern.starts.lastIndex
        val id = data.tripId(pattern, i)
        assertTrue(id.matches(Regex("\\d+_20260606_${Regex.escape(pattern.routeId)}_\\d+")))
        assertEquals((p shl 16) or i, data.ref(id))
        // De outro GTFS ou mal formado: não está neste horário
        assertEquals(null, data.ref("1_20260101_${pattern.routeId}_1"))
        assertEquals(null, data.ref("lixo"))
    }

    @Test
    fun servicesFollowTheCalendar() {
        fun running(date: LocalDate) = data.services.filter { it.runsOn(date) }.map { it.name }
        // Segunda-feira de outubro: dias úteis de inverno
        assertTrue(running(LocalDate.of(2026, 10, 26)).any { it.startsWith("Inverno_Util") })
        // Domingo: domingos e feriados
        assertTrue(running(LocalDate.of(2026, 10, 25)).any { it.startsWith("Inverno_DomingoFeriado") })
        // Dia de Natal: entra o horário próprio (acrescentado no calendar_dates)
        assertTrue(running(LocalDate.of(2026, 12, 25)).any { it.startsWith("Natal") })
        // Férias escolares: o horário de inverno de dia útil sai nesses dias
        val holidays = running(LocalDate.of(2026, 12, 15))
        assertTrue(holidays.any { it.startsWith("Ferias Escolares") })
        assertFalse(holidays.any { it.startsWith("Inverno_Util") })
    }

    @Test
    fun everyStopHasTripsThroughTheDay() {
        // Corpo Santo (3824) tem muitas linhas
        val here = data.byStop["3824"]
        assertNotNull(here)
        assertTrue(here!!.map { data.patterns[it.first].routeId }.toSet().size > 5)
        val (p, s) = here.first()
        val pattern = data.patterns[p]
        val times = pattern.starts.indices.map { pattern.timeAt(it, s) }
        assertTrue(times.all { it in 0..(30 * 3600) })
    }

    @Test
    fun unlistedBusesArePlacedOnTheirRoute() {
        // 728 para a Portela (204_0, sentido 0): Restelo (Torres) - R. Antão Gonçalves (12405) logo no início
        val (p, s) = data.byStop["12405"]!!.first { (p, _) -> data.patterns[p].routeId == "204_0" && data.patterns[p].direction == 0 }
        val pattern = data.patterns[p]
        val now = 10 * 3600
        fun place(stop: String?, seq: Int?, stopped: Boolean, direction: Int? = 0) =
            data.placeUnlisted("204_0", direction, stop, seq, stopped, "12405", now)
        // Três paragens à frente já passou: não aparece (era o "728 · 5 min · tempo real" a mais)
        assertNull(place(pattern.stops[s + 3], s + 4, true))
        // Parado na paragem antes: chega no tempo do troço; a caminho dela, um pouco mais
        val before = place(pattern.stops[s - 1], s, true)!!
        assertEquals(p, before.pattern)
        assertTrue(before.etaSeconds in 0..300)
        assertTrue(place(pattern.stops[s - 1], s, false)!!.etaSeconds >= before.etaSeconds)
        // Parado na própria paragem
        assertEquals(0, place("12405", s + 1, true)!!.etaSeconds)
        // Sem o sentido do GPS também serve
        assertEquals(before, place(pattern.stops[s - 1], s, true, direction = null))
        // No outro sentido, sem paragem ou numa que não é do percurso: não se sabe onde vai, fica de fora
        assertNull(place(pattern.stops[s - 1], s, true, direction = 1))
        assertNull(place(null, null, false))
        assertNull(place("nenhuma", 2, false))
    }
}
