package com.example.metrotime.data.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusColorsTest {

    @Test
    fun carrisLinesUseTheOfficialColours() {
        assertEquals("#2FB61E", BusColors.carris("735"))  // verde
        assertEquals("#F7941E", BusColors.carris("28E"))  // elétricos
        assertEquals("#F7941E", BusColors.carris("28e"))
        assertEquals("#8C8C99", BusColors.carris("728"))  // circulares
        assertEquals("#091B7D", BusColors.carris("208"))  // madrugada
    }

    @Test
    fun linesMissingFromTheSiteFallBackByPattern() {
        assertEquals("#FFDC00", BusColors.carris("99B"))  // bairro que não está na tabela
        assertEquals("#091B7D", BusColors.carris("299"))
        assertEquals(BusColors.FALLBACK, BusColors.carris("770"))
    }

    @Test
    fun metropolitanaUsesTheColourFromTheApi() {
        assertEquals("#C61D23", BusColors.forRoute("1604", "carris_metropolitana", "c61d23"))
        assertEquals(BusColors.FALLBACK, BusColors.forRoute("1604", "carris_metropolitana", null))
        assertEquals("#2FB61E", BusColors.forRoute("735", "carris", "#123456"))
    }

    @Test
    fun numberIsWhiteExceptOnTheYellowLines() {
        assertEquals("#171717", BusColors.textOn("#FFDC00"))
        assertEquals("#FFFFFF", BusColors.textOn("#F7941E"))
        assertEquals("#FFFFFF", BusColors.textOn("#00AEEF"))
        assertEquals("#FFFFFF", BusColors.textOn("#2FB61E"))
        assertEquals("#FFFFFF", BusColors.textOn("#8C8C99"))
    }

    @Test
    fun normalizeRejectsInvalidColours() {
        assertEquals("#2FB61E", BusColors.normalize(" 2fb61e "))
        assertNull(BusColors.normalize("#FFF"))
        assertNull(BusColors.normalize("verde"))
        assertNull(BusColors.normalize(null))
    }
}
