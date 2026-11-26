package com.example.metrotime.data.cp

import java.time.LocalDate

/**
 * Dia de greve na CP, para o distintivo "Dia de greve". Os suprimidos sozinhos enganavam: há sempre alguns (avarias,
 * obras) e de noite circulam poucos, por isso "mais suprimidos do que a circular" dava-se sem greve nenhuma. Agora
 * são precisas as duas coisas: a CP anunciou uma greve para esse dia ([announced]) e ela nota-se nos comboios ([felt]).
 */
object CpStrike {
    /** Suprimidos a partir dos quais a greve se nota (a 24/11/2026, com serviços mínimos, eram 970 e circulavam 50). */
    const val MIN_SUPPRESSED = 100

    private val STRIKE = Regex("\\bgreves?\\b")

    /**
     * A CP tem um aviso de greve com [day] dentro das datas. Só pelo título ("Greve - serviços mínimos a 24 e 25 de
     * novembro"): no resumo não chega, o aviso do bar dos Alfa Pendular diz "Greve desde dia 7 de fevereiro" há meses.
     * As datas do aviso apanham dias à volta da greve (23 a 27 para uma greve a 24 e 25): esses ficam de fora por [felt].
     */
    fun announced(notices: List<CpNotice>, day: LocalDate): Boolean = notices.any { n ->
        STRIKE.containsMatchIn(CpText.normalize(n.title)) &&
            (n.start == null || !day.isBefore(n.start)) && (n.end == null || !day.isAfter(n.end))
    }

    /** A greve nota-se: muitos comboios suprimidos, e mais do que os que estão a circular. */
    fun felt(suppressed: Int, running: Int): Boolean = suppressed >= MIN_SUPPRESSED && suppressed > running

    fun isStrikeDay(notices: List<CpNotice>, day: LocalDate, suppressed: Int, running: Int): Boolean =
        announced(notices, day) && felt(suppressed, running)
}
