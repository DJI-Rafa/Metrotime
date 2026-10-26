package com.example.metrotime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTextTest {

    @Test
    fun greetingFollowsTheTimeOfDay() {
        assertEquals("Bom dia, João", HomeText.greeting(8, "João"))
        assertEquals("Boa tarde, João", HomeText.greeting(12, "João"))
        assertEquals("Boa tarde, João", HomeText.greeting(19, "João"))
        assertEquals("Boa noite, João", HomeText.greeting(20, "João"))
        assertEquals("Boa noite, João", HomeText.greeting(2, "João"))
        // Sem nome: só a saudação
        assertEquals("Bom dia", HomeText.greeting(9, null))
        assertEquals("Bom dia", HomeText.greeting(9, " "))
    }

    @Test
    fun firstNameFromFullName() {
        assertEquals("João", HomeText.firstName("João Rafael Dória"))
        assertEquals("João", HomeText.firstName("  joão   rafael "))
        // O nome por omissão dos perfis não é um nome
        assertNull(HomeText.firstName("Utilizador"))
        assertNull(HomeText.firstName(""))
        assertNull(HomeText.firstName(null))
    }

    @Test
    fun laterTrainsAndAgo() {
        assertEquals("depois 6 min", HomeText.laterWaits(listOf(360)))
        assertEquals("depois 6 e 10 min", HomeText.laterWaits(listOf(360, 600)))
        assertNull(HomeText.laterWaits(emptyList()))
        assertEquals("há 5 min", HomeText.ago(5 * 60_000L))
        assertEquals("há 1 min", HomeText.ago(10_000L))
        assertEquals("há 2 h", HomeText.ago(2 * 3_600_000L + 600_000L))
        assertEquals("há 1 dia", HomeText.ago(26 * 3_600_000L))
        assertEquals("há 3 dias", HomeText.ago(3 * 24 * 3_600_000L))
    }

    @Test
    fun metroWaitInMinutes() {
        assertEquals("a chegar", HomeText.metroWait(20))
        assertEquals("1 min", HomeText.metroWait(60))
        assertEquals("2 min", HomeText.metroWait(100))
        assertEquals("4 min", HomeText.metroWait(240))
    }
}
