package com.example.metrotime.data.caderneta

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ShareTextTest {

    @Test
    fun datesInPortuguese() {
        assertEquals("23 NOV 2026", ShareText.date(LocalDate.of(2026, 11, 23)))
        assertEquals("2 AGO 2026", ShareText.date(LocalDate.of(2026, 8, 2)))
        assertEquals("14 SET 2026", ShareText.date(LocalDate.of(2026, 9, 14)))
        assertEquals("1 FEV 2027", ShareText.date(LocalDate.of(2027, 2, 1)))
    }

    @Test
    fun howManyPeopleHaveIt() {
        assertEquals(null, ShareText.holders(0, 0, mine = false))
        assertEquals("Ainda ninguém da app tem este distintivo.", ShareText.holders(0, 40, mine = false))
        // Acabaste de o ganhar e o Firebase ainda não te contou
        assertEquals("És a única pessoa da app com este distintivo.", ShareText.holders(0, 40, mine = true))
        assertEquals("Menos de 1 % das pessoas da app têm este distintivo.", ShareText.holders(1, 400, mine = false))
        assertEquals("Só 4 % das pessoas da app têm este distintivo.", ShareText.holders(2, 50, mine = true))
        assertEquals("25 % das pessoas da app têm este distintivo.", ShareText.holders(10, 40, mine = false))
    }

    @Test
    fun tierTitlesKeepTheirAccents() {
        assertEquals("DISTINTIVO LENDÁRIO", ShareText.tierTitle(Tier.LENDARIO))
        assertEquals("DISTINTIVO IMPOSSÍVEL", ShareText.tierTitle(Tier.IMPOSSIVEL))
        assertEquals("DISTINTIVO COMUM", ShareText.tierTitle(Tier.COMUM))
    }
}
