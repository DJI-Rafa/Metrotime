package com.example.metrotime.data.bus

/**
 * Tempo real, atrasado ou previsto, como no Google Maps. Sem dependências Android (testado em app/src/test).
 *
 * - Tempo real: autocarro com GPS; minutos até chegar e o estado em relação ao horário.
 * - Previsto: sem GPS; os minutos pelo horário.
 */
object BusTiming {

    enum class Kind { ON_TIME, EARLY, LATE, VERY_LATE, LIVE, SCHEDULED }

    data class Status(val text: String, val kind: Kind) {
        val realtime: Boolean get() = kind != Kind.SCHEDULED
    }

    /** Estado: "a horas" até 1 min de diferença; atraso de 2 a 5 min a laranja e de 6 ou mais a vermelho. */
    fun status(realtime: Boolean, delayMinutes: Int?): Status = when {
        !realtime -> Status("previsto", Kind.SCHEDULED)
        delayMinutes == null -> Status("tempo real", Kind.LIVE)
        delayMinutes >= 6 -> Status("atrasado $delayMinutes min", Kind.VERY_LATE)
        delayMinutes >= 2 -> Status("atrasado $delayMinutes min", Kind.LATE)
        delayMinutes <= -2 -> Status("adiantado ${-delayMinutes} min", Kind.EARLY)
        else -> Status("a horas", Kind.ON_TIME)
    }

    /**
     * O tempo grande, em minutos como no Google Maps ("a chegar", "4 min"); os previstos também, e só a
     * partir de uma hora a hora do horário ("19:58"). Antes os previstos davam sempre a hora e ficavam
     * misturados com os minutos.
     */
    fun eta(etaMinutes: Int, realtime: Boolean, scheduledTime: String?): String = when {
        !realtime && scheduledTime != null && etaMinutes >= 60 -> scheduledTime
        etaMinutes <= 0 -> if (realtime) "a chegar" else "agora"
        else -> "$etaMinutes min"
    }

    /** Hora de relógio a partir dos segundos desde a meia-noite do dia de serviço (25:10 → 01:10). */
    fun clock(secondsOfDay: Int): String {
        val m = Math.floorMod(secondsOfDay / 60, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * Atraso (segundos; negativo = adiantado) de um autocarro com GPS, pela hora prevista nas paragens à volta:
     * - parado numa paragem: agora menos a hora prevista nela;
     * - a caminho da paragem seguinte: atrasado se já passou a hora dela, adiantado se ainda não chegou a
     *   hora da anterior; entre as duas, a horas.
     */
    fun delaySeconds(scheduledPrev: Int?, scheduledNext: Int, now: Int, stoppedAtNext: Boolean): Int = when {
        stoppedAtNext -> now - scheduledNext
        now > scheduledNext -> now - scheduledNext
        scheduledPrev != null && now < scheduledPrev -> now - scheduledPrev
        else -> 0
    }

    /** Minutos inteiros mais próximos, igual nos dois sentidos (90 s → 2 min; -90 s → -2 min). */
    fun roundMinutes(seconds: Int): Int {
        val m = Math.round(kotlin.math.abs(seconds) / 60.0).toInt()
        return if (seconds < 0) -m else m
    }
}
