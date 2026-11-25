package com.example.metrotime.data.cp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CpStrikeTest {

    // Avisos reais da CP a 24/11/2026
    private val strike = CpNotice(
        "Greve - serviços mínimos a 24 e 25 de novembro", "Perturbações na circulação de 23 a 27 de novembro 2026",
        start = LocalDate.of(2026, 11, 23), end = LocalDate.of(2026, 11, 27)
    )
    private val bar = CpNotice(
        "Serviço de Cafetaria e Bar - Alfa Pendular e Intercidades", "Greve desde dia 7 de fevereiro",
        start = LocalDate.of(2026, 2, 7)
    )
    private val works = CpNotice(
        "Linha do Douro - Circulação de comboios suspensa", "Entre Marco de Canaveses e Régua - a partir de 3 de novembro",
        start = LocalDate.of(2026, 11, 3), end = LocalDate.of(2027, 3, 24)
    )

    private fun day(d: Int) = LocalDate.of(2026, 11, d)

    @Test
    fun aStrikeNoticeCoversItsDates() {
        assertTrue(CpStrike.announced(listOf(strike, works), day(24)))
        assertTrue(CpStrike.announced(listOf(strike), day(27)))
        assertFalse(CpStrike.announced(listOf(strike), day(22)))
        assertFalse(CpStrike.announced(listOf(strike), day(28)))
    }

    @Test
    fun aStrikeOnlyInTheSummaryIsNotACpStrike() {
        // O bar dos Alfa está em greve há meses: não é greve dos comboios
        assertFalse(CpStrike.announced(listOf(bar, works), day(24)))
        assertFalse(CpStrike.announced(emptyList(), day(24)))
    }

    @Test
    fun theStrikeHasToBeFeltInTheTrains() {
        assertTrue(CpStrike.felt(suppressed = 970, running = 50))
        // De noite circulam poucos: uns suprimidos por obras não fazem uma greve
        assertFalse(CpStrike.felt(suppressed = 30, running = 5))
        // Muitos suprimidos mas a maior parte a circular (greve parcial ou dia à volta da greve)
        assertFalse(CpStrike.felt(suppressed = 150, running = 200))
    }

    @Test
    fun aStrikeDayNeedsTheNoticeAndTheTrains() {
        assertTrue(CpStrike.isStrikeDay(listOf(strike), day(24), 970, 50))
        // Sem aviso de greve, nem com muitos suprimidos
        assertFalse(CpStrike.isStrikeDay(listOf(bar, works), day(24), 970, 50))
        // Com aviso, mas num dia normal à volta da greve
        assertFalse(CpStrike.isStrikeDay(listOf(strike), day(27), 12, 180))
    }
}
