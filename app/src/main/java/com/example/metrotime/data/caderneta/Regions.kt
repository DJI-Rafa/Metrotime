package com.example.metrotime.data.caderneta

import java.util.Locale

/**
 * As 5 regiões do continente (Norte, Centro, Lisboa, Alentejo, Algarve) de uma estação da CP, pelas coordenadas. A
 * lista de estações da CP traz uma "região" que é uma zona pequena (ex.: "Coimbra"), que não serve para o distintivo
 * "Região". São as regiões de 2013 (NUTS II), com uma diferença: a Lezíria do Tejo (Azambuja, Santarém…) fica no
 * Centro, com o Oeste e o Médio Tejo, e não no Alentejo, onde ninguém a põe. As fronteiras foram acertadas estação a
 * estação onde a linha as atravessa: Espinho é Norte e Esmoriz Centro; a Linha do Vouga é Norte até Oliveira de
 * Azeméis; Castanheira do Ribatejo e Jerumelo são Lisboa, Carregado e Sapataria Centro; Belver e Ponte de Sor são
 * Alentejo e Bemposta Centro; Pegões e São João das Craveiras são Lisboa e Vendas Novas Alentejo.
 */
object Regions {

    const val NORTE = "Norte"
    const val CENTRO = "Centro"
    const val LISBOA = "Lisboa"
    const val ALENTEJO = "Alentejo"
    const val ALGARVE = "Algarve"
    val ALL = listOf(NORTE, CENTRO, LISBOA, ALENTEJO, ALGARVE)

    private val PT = Locale.forLanguageTag("pt-PT")
    private val LABELS = ALL.map { it.uppercase(PT) }.toSet()

    /**
     * A estação é em Portugal? O código começa pelo país (94 Portugal, 71 Espanha): a lista da CP traz Vigo-Guixar e
     * Badajoz, onde chegam comboios da CP, e essas não são de nenhuma região nem contam para as pontas do país.
     */
    fun inPortugal(code: String): Boolean = code.startsWith("94-")

    fun of(lat: Double, lon: Double): String {
        if (lat < 37.42) return ALGARVE
        if (lat > 40.97 || (lat > 40.80 && lon > -8.58)) return NORTE
        // Lisboa (com a Península de Setúbal): na Linha do Oeste acaba antes, na do Norte um pouco depois
        val northLimit = if (lon < -9.1) 38.96 else 38.995
        if (lon < -8.56 && lat >= 38.45 && lat < northLimit) return LISBOA
        if (lon < -8.93) return CENTRO                              // Linha do Oeste e Carregado
        if (lon < -8.3) return if (lat >= 39.03) CENTRO else ALENTEJO // Lezíria e Médio Tejo / Vendas Novas e Alentejo Litoral
        if (lat < 39.30 || (lat < 39.50 && lon > -8.03)) return ALENTEJO
        return CENTRO
    }

    /** O texto da fita do distintivo "Região" é uma das 5 regiões? (antes podia ser uma zona da CP, ex.: COIMBRA) */
    fun isRegionLabel(label: String?): Boolean = label != null && label.uppercase(PT) in LABELS
}
