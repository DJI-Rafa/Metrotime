package com.example.metrotime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetroLinesTest {

    @Test
    fun terminalsAndStationLines() {
        assertEquals("Reboleira ↔ Santa Apolónia", MetroLines.terminals("Azul"))
        assertEquals("Telheiras ↔ Cais do Sodré", MetroLines.terminals("Verde"))
        assertEquals(listOf("Verde", "Vermelha"), MetroLines.linesOf("AM")) // Alameda
        assertEquals(listOf("Amarela", "Verde"), MetroLines.linesOf("CG")) // Campo Grande
    }

    @Test
    fun namesMatchWithoutAccentsOrHyphens() {
        assertEquals("SS", MetroLines.idByName("Sao Sebastiao"))
        assertEquals("BC", MetroLines.idByName("Baixa Chiado"))
        assertEquals("CM", MetroLines.idByName("Colégio Militar/Luz"))
        assertNull(MetroLines.idByName("Destino 99"))
    }

    @Test
    fun lineOfTravelIsTheLineYouAreOn() {
        // De Alameda para São Sebastião vai-se na Vermelha (antes a cor vinha do destino: azul e vermelha)
        assertEquals("Vermelha", MetroLines.lineOfTravel("AM", "São Sebastião"))
        assertEquals("Verde", MetroLines.lineOfTravel("AM", "Telheiras"))
        // Marquês de Pombal (Azul e Amarela) para o Rato: Amarela
        assertEquals("Amarela", MetroLines.lineOfTravel("MP", "Rato"))
        // Campo Grande é da Amarela e da Verde: de Alvalade só pode ser a Verde
        assertEquals("Verde", MetroLines.lineOfTravel("AL", "Campo Grande"))
    }

    @Test
    fun affectedSectionFromTheMetroMessage() {
        val verde = MetroLines.stops("Verde")
        val range = MetroLines.affectedRange("Verde", "Circulação interrompida entre Cais do Sodré e Baixa-Chiado.")
        assertEquals(verde.indexOf("BC")..verde.indexOf("CS"), range)
        // Uma só estação referida (ou nenhuma): não se sabe o troço
        assertNull(MetroLines.affectedRange("Verde", "Circulação com perturbações."))
        assertNull(MetroLines.affectedRange("Verde", "Estação Roma encerrada."))
        // "Roma" não pode apanhar "Romaria" nem estações de outras linhas
        assertNull(MetroLines.affectedRange("Verde", "Romaria em Odivelas e Rato"))
    }

    @Test
    fun arrivalsGroupedByLineAndDirection() {
        val arrivals = listOf(
            Arrival("São Sebastião", 300, "Azul"),
            Arrival("Telheiras", 120, "Verde"),
            Arrival("Telheiras", 480, "Verde"),
            Arrival("Cais do Sodré", 240, "Verde"),
            Arrival("Aeroporto", 20, "Vermelha"),
            Arrival("São Sebastião", 660, "Azul")
        )
        val groups = MetroLines.groupByLine("AM", arrivals)
        assertEquals(listOf("Verde", "Vermelha"), groups.map { it.first })
        val verde = groups[0].second
        assertEquals(listOf("Telheiras", "Cais do Sodré"), verde.map { it.destination })
        assertEquals(listOf(120, 480), verde[0].seconds)
        val vermelha = groups[1].second
        assertEquals(listOf("Aeroporto", "São Sebastião"), vermelha.map { it.destination })
        assertEquals(listOf(300, 660), vermelha[1].seconds)
    }
}
