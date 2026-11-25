package com.example.metrotime.data.caderneta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionsTest {

    private fun check(region: String, vararg stations: Triple<String, Double, Double>) {
        stations.forEach { (name, lat, lon) -> assertEquals(name, region, Regions.of(lat, lon)) }
    }

    @Test
    fun stationsAtTheBorders() {
        // Coordenadas da lista de estações da app (cp_network)
        check(
            Regions.NORTE,
            Triple("Paramos", 40.97551, -8.63685), Triple("Oliveira de Azeméis", 40.84034, -8.48181),
            Triple("Pocinho", 41.13028, -7.12348)
        )
        check(
            Regions.CENTRO,
            Triple("Esmoriz", 40.96341, -8.63553), Triple("Avanca", 40.8075, -8.5848), Triple("Coimbra-B", 40.22472, -8.4405),
            Triple("Carregado", 39.00543, -8.95367), Triple("Sapataria", 38.9688, -9.1994), Triple("Pero Negro", 38.98884, -9.19739),
            Triple("Riachos", 39.43219, -8.50457), Triple("Bemposta", 39.35245, -8.13566), Triple("Vilar Formoso", 40.60611, -6.82897),
            // A Lezíria do Tejo fica no Centro (não no Alentejo)
            Triple("Vila Nova da Rainha", 39.0362, -8.9284), Triple("Azambuja", 39.06816, -8.86693),
            Triple("Santarém", 39.2421, -8.67524), Triple("Mato de Miranda", 39.36848, -8.55435)
        )
        check(
            Regions.LISBOA,
            Triple("Castanheira do Ribatejo", 38.98791, -8.96606), Triple("Jerumelo", 38.94964, -9.23426),
            Triple("Pegões", 38.6623, -8.6191), Triple("São João das Craveiras", 38.668, -8.5857), Triple("Praias do Sado-A", 38.51796, -8.83812)
        )
        check(
            Regions.ALENTEJO,
            Triple("Belver", 39.49198, -7.95871), Triple("Ponte de Sor", 39.2655, -8.013), Triple("Vendas Novas", 38.67942, -8.4544),
            Triple("Santa Clara-Sabóia", 37.49583, -8.48141)
        )
        check(Regions.ALGARVE, Triple("Messines-Alte", 37.2554, -8.2905), Triple("Tunes", 37.16497, -8.25641))
    }

    @Test
    fun stationsInSpainAreNotInPortugal() {
        // A lista da CP traz duas estações de Espanha (código 71), onde chegam comboios da CP
        assertTrue(Regions.inPortugal("94-7005"))   // Valença
        assertFalse(Regions.inPortugal("71-22308")) // Vigo-Guixar
        assertFalse(Regions.inPortugal("71-37606")) // Badajoz
    }

    @Test
    fun onlyTheFiveRegionsAreRegionLabels() {
        assertTrue(Regions.isRegionLabel("CENTRO"))
        assertTrue(Regions.isRegionLabel("Alentejo"))
        assertFalse(Regions.isRegionLabel("COIMBRA"))
        assertFalse(Regions.isRegionLabel(null))
    }
}
