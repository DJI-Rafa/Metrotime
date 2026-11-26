package com.example.metrotime.data.metro

import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.plan.MetroTrip

/**
 * O último metro à noite. Sem dependências Android (testado em app/src/test).
 *
 * As últimas partidas dos terminais são à 1:00: a partir daí, os metros que ainda circulam são os últimos, e
 * o último de cada linha e sentido é o que vai mais atrás. Antes disso, o cartão diz a que horas passa o último
 * na estação mais perto (pelo modelo da rede, como o aviso do último metro para casa).
 */
object MetroNight {

    /** Minutos do dia de serviço (25 h = 1:00 do dia seguinte). */
    const val LAST_DEPARTURES_MIN = MetroTrip.LAST_DEPARTURE_MIN
    /** O cartão dos últimos metros: da meia-noite até um pouco depois das últimas partidas. */
    const val CARD_FROM_MIN = 24 * 60
    const val CARD_UNTIL_MIN = 25 * 60 + 45

    fun showCard(serviceMinute: Int): Boolean = serviceMinute in CARD_FROM_MIN until CARD_UNTIL_MIN

    /** Os últimos metros (chave "linha|número"), um por linha e sentido, depois das últimas partidas. */
    fun lastTrains(places: List<MetroPlace>, serviceMinute: Int): Set<String> {
        if (serviceMinute < LAST_DEPARTURES_MIN || serviceMinute >= 27 * 60) return emptySet()
        return places.groupBy { it.line to it.dir }.values
            .mapNotNull { group -> group.minByOrNull { it.s * it.dir }?.let { "${it.line}|${it.trainId}" } }
            .toSet()
    }

    /** Quando passa o último metro de um sentido numa estação (minutos do dia de serviço) e se já passou. */
    data class LastPass(val line: String, val towardsName: String, val minute: Double, val passed: Boolean)

    /** Os últimos metros de cada sentido em [stationId], os que ainda faltam primeiro. */
    fun lastPasses(stationId: String, serviceMinute: Int): List<LastPass> =
        MetroLines.linesOf(stationId).flatMap { line ->
            val stops = MetroLines.stops(line)
            listOf(stops.first(), stops.last()).filter { it != stationId }.mapNotNull { towards ->
                val at = MetroTrip.lastPass(line, stationId, towards) ?: return@mapNotNull null
                LastPass(line, MetroLines.nameOf(towards), at, serviceMinute > at)
            }
        }.sortedWith(compareBy({ it.passed }, { it.minute }))
}
