package com.example.metrotime.data.cp

import java.time.LocalDate

/**
 * As paragens comerciais de um comboio, como a Cloud Function as guarda em cp_routes: o dia do comboio e cada paragem
 * com as horas do horário (as previstas fazem-se com o atraso de agora, que vem com o comboio em cp_vehicles).
 */
data class CpRoute(val runDate: LocalDate, val stops: List<CpTrainStop>) {

    /** As paragens com o atraso de agora ([delaySeconds], do comboio) em todas as que se fazem. */
    fun withDelay(delaySeconds: Int?): List<CpTrainStop> {
        val minutes = CpDelay.secondsToMinutes(delaySeconds)?.coerceAtLeast(0) ?: return stops
        return stops.map { if (it.supression != null) it else it.copy(delay = minutes) }
    }

    companion object {
        /** "AAAA-MM-DD|código,chegada,partida,suprimida;…" (functions/cp.js, routeOf). null se vier estragado. */
        fun parse(text: String?): CpRoute? {
            if (text.isNullOrBlank()) return null
            val bar = text.indexOf('|')
            if (bar <= 0) return null
            val day = runCatching { LocalDate.parse(text.substring(0, bar)) }.getOrNull() ?: return null
            val stops = text.substring(bar + 1).split(';').map { part ->
                val f = part.split(',')
                if (f.size < 4 || f[0].isBlank()) return null
                CpTrainStop(
                    station = CpGenericEntity(code = f[0], designation = null),
                    arrival = f[1].ifBlank { null },
                    departure = f[2].ifBlank { null },
                    supression = if (f[3] == "1") CpGenericEntity(code = "S", designation = "Suprimido") else null
                )
            }
            return if (stops.size >= 2) CpRoute(day, stops) else null
        }
    }
}
