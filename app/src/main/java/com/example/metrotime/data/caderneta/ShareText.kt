package com.example.metrotime.data.caderneta

import java.time.LocalDate
import java.util.Locale

/** Os textos da imagem para partilhar um distintivo, sem Android (testados em app/src/test). */
object ShareText {

    private val MONTHS = listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")
    private val PT = Locale.forLanguageTag("pt-PT")

    /** "23 NOV 2026". */
    fun date(d: LocalDate): String = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"

    /** "DISTINTIVO LENDÁRIO". */
    fun tierTitle(tier: Tier): String = "DISTINTIVO " + tier.title.uppercase(PT)

    /**
     * Quantas pessoas da app têm um distintivo: [count] das [users] com distintivos guardados. [mine]: tu tens (contas,
     * mesmo que o Firebase ainda não te tenha contado). null se ainda não há números.
     */
    fun holders(count: Int, users: Int, mine: Boolean): String? {
        val c = if (mine) maxOf(count, 1) else count
        val u = maxOf(users, c)
        if (u <= 0) return null
        if (c <= 0) return "Ainda ninguém da app tem este distintivo."
        if (mine && c == 1) return "És a única pessoa da app com este distintivo."
        val pct = c * 100.0 / u
        val rounded = Math.round(pct)
        return when {
            pct < 1 -> "Menos de 1 % das pessoas da app têm este distintivo."
            pct < 10 -> "Só $rounded % das pessoas da app têm este distintivo."
            else -> "$rounded % das pessoas da app têm este distintivo."
        }
    }
}
