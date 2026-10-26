package com.example.metrotime.work

import com.example.metrotime.data.Arrival
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRowsTest {

    // Alameda: Verde (Telheiras / Cais do Sodré) e Vermelha (Aeroporto / São Sebastião), tempos em segundos
    private val alameda = listOf(
        Arrival("Telheiras", 150), Arrival("Telheiras", 500),
        Arrival("Cais do Sodré", 40), Arrival("Cais do Sodré", 320),
        Arrival("Aeroporto", 260),
        Arrival("São Sebastião", 700)
    )

    @Test
    fun metroRowsGoByLineAndDropTrainsThatLeft() {
        // 60 s depois de os tempos chegarem: o de Cais do Sodré (40 s) já passou
        val rows = WidgetRows.metro("AM", alameda, elapsedSec = 60)
        assertEquals(listOf("Verde", "Verde", "Vermelha", "Vermelha"), rows.map { it.line })
        assertEquals(listOf("Telheiras", "Cais do Sodré", "Aeroporto", "São Sebastião"), rows.map { it.destination })
        assertEquals(listOf(90, 440), rows[0].seconds)
        assertEquals(listOf(260), rows[1].seconds)
    }

    @Test
    fun smallShowsTheNextOfEachLineAtTwoLineStations() {
        val rows = WidgetRows.metro("AM", alameda, elapsedSec = 0)
        val small = WidgetRows.metroSmall(rows)
        assertEquals(listOf("Cais do Sodré", "Aeroporto"), small.map { it.destination })
    }

    @Test
    fun lockShowsTheThreeClosest() {
        val rows = WidgetRows.metro("AM", alameda, elapsedSec = 0)
        assertEquals(listOf("Cais do Sodré", "Telheiras", "Aeroporto"), WidgetRows.lock(rows).map { it.destination })
    }

    @Test
    fun cpStatusUsesTheAppThresholds() {
        assertEquals("a horas" to WidgetRows.CpState.ON_TIME, WidgetRows.cpStatus(0, false))
        assertEquals("+4 min" to WidgetRows.CpState.SMALL_DELAY, WidgetRows.cpStatus(4, false))
        assertEquals("+12 min" to WidgetRows.CpState.BIG_DELAY, WidgetRows.cpStatus(12, false))
        assertEquals("Previsto" to WidgetRows.CpState.SCHEDULED, WidgetRows.cpStatus(null, false))
        assertEquals("Suprimido" to WidgetRows.CpState.SUPPRESSED, WidgetRows.cpStatus(-1, false))
        assertEquals("Suprimido" to WidgetRows.CpState.SUPPRESSED, WidgetRows.cpStatus(3, true))
    }

    @Test
    fun busKeepsTheNextOfEachLineAndDestination() {
        data class A(val line: String, val dest: String, val eta: Int)
        val list = listOf(A("735", "Cais do Sodré", 9), A("708", "Martim Moniz", 0), A("735", "Cais do Sodré", 4), A("735", "Hosp. Sta. Maria", 6))
        val next = WidgetRows.nextPerLine(list, { it.line }, { it.dest }, { it.eta })
        assertEquals(listOf(A("708", "Martim Moniz", 0), A("735", "Cais do Sodré", 4), A("735", "Hosp. Sta. Maria", 6)), next)
    }

    @Test
    fun agoLabels() {
        assertEquals("há 12 s", WidgetRows.ago(12))
        assertEquals("há 2 min", WidgetRows.ago(150))
    }
}
