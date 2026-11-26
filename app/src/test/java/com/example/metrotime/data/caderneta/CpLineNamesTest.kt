package com.example.metrotime.data.caderneta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpLineNamesTest {

    private fun st(code: String, name: String, lat: Double, lon: Double) = CpStationInfo(code, name, lat, lon, emptyList(), null)

    private val stations = listOf(
        st("94-61101", "Sintra", 38.80, -9.38), st("94-61002", "Agualva-Cacém", 38.77, -9.30), st("94-60004", "Campolide", 38.73, -9.17),
        st("94-12005", "Pocinho", 41.13, -7.11), st("94-10009", "Régua", 41.16, -7.79), st("94-1008", "Porto São Bento", 41.14, -8.61),
        st("94-3004", "Contumil", 41.17, -8.58), st("94-8383", "Caíde", 41.20, -8.24), st("94-4002", "Ermesinde", 41.21, -8.55),
        st("94-90001", "Ligação A", 39.0, -8.0), st("94-90002", "Ligação B", 39.01, -8.01)
    ).associateBy { it.code }

    @Test
    fun aLineIsRecognisedByAStationOnlyItHas() {
        val named = CpLineNames.name(
            mapOf("61" to listOf("94-60004", "94-61002", "94-61101"), "12" to listOf("94-1008", "94-10009", "94-12005")),
            stations
        )
        assertEquals(listOf("94-60004", "94-61002", "94-61101"), named["Linha de Sintra"])
        assertEquals(listOf("94-1008", "94-10009", "94-12005"), named["Linha do Douro"])
    }

    @Test
    fun anUnknownLineIsNamedByItsEnds() {
        val named = CpLineNames.name(mapOf("33" to listOf("94-1008", "94-3004", "94-4002", "94-8383")), stations)
        // As duas mais afastadas, de norte para sul
        assertEquals(setOf("Caíde – Porto São Bento"), named.keys)
    }

    @Test
    fun shortConnectionsDoNotCount() {
        val named = CpLineNames.name(mapOf("21" to listOf("94-90001"), "63" to listOf("94-90001", "94-90002")), stations)
        assertTrue(named.isEmpty())
    }

    @Test
    fun twoNumbersOfTheSameLineBecomeOne() {
        val named = CpLineNames.name(
            mapOf("61" to listOf("94-60004", "94-61002", "94-61101"), "62" to listOf("94-61101", "94-61002", "94-60004")),
            stations
        )
        assertEquals(1, named.size)
        assertEquals(3, named["Linha de Sintra"]?.size)
    }

    @Test
    fun ribbonLabels() {
        assertEquals("SINTRA", Badges.shortLabel("Linha de Sintra"))
        assertEquals("BRAGA", Badges.shortLabel("Ramal de Braga"))
        assertEquals("PORTO SÃO BENTO", Badges.shortLabel("Caíde – Porto São Bento"))
        assertFalse(Badges.shortLabel("Linha da Beira Baixa").startsWith("DA "))
    }
}
