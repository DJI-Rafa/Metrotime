package com.example.metrotime.data.cp

import java.time.Duration
import java.time.LocalDateTime

/**
 * Quando a API da CP não responde, todos os ecrãs vão à MESMA alternativa e ficam iguais entre si: as horas da
 * última resposta boa (ou do ruicosta, no percurso do comboio) e, para os comboios que já circulam, o atraso
 * pelo GPS (Cloud Function cp_vehicles / comboios.live) — o mesmo que o mapa mostra. As horas reais antigas
 * (ETA/ETD) saem, para não ficar uma hora de há meia hora.
 */
object CpFallback {

    /** Só se mexe nos comboios que passam até 6 h antes ou depois de agora (o mesmo número noutro dia não conta). */
    private const val NEAR_HOURS = 6L

    fun withVehicleDelays(stops: List<CpTimedStop>, vehicles: List<CpVehicle>, now: LocalDateTime): List<CpTimedStop> {
        val byNumber = vehicles.associateBy { it.trainNumber }
        return stops.map { t ->
            val stop = t.stop
            if (stop.supression != null || stop.delay == -1) return@map t
            val v = byNumber[stop.trainNumber] ?: return@map t
            val minutes = CpDelay.secondsToMinutes(v.delay)?.coerceAtLeast(0) ?: return@map t
            val at = t.dateTimeOf(stop.departureTime ?: stop.arrivalTime) ?: return@map t
            if (Duration.between(now, at).abs().toHours() >= NEAR_HOURS) return@map t
            t.copy(stop = stop.copy(eta = null, etd = null, delay = minutes))
        }
    }

    /** O percurso de um comboio com o atraso do GPS nas paragens que ainda faltam (depois da última estação). */
    fun withVehicleDelay(trip: CpTripDetail, vehicle: CpVehicle?): CpTripDetail {
        val minutes = CpDelay.secondsToMinutes(vehicle?.delay)?.coerceAtLeast(0) ?: return trip
        val stops = trip.trainStops ?: return trip
        val last = (vehicle?.lastStation ?: trip.lastStationCode)?.let { code -> stops.indexOfLast { it.station?.code == code } } ?: -1
        return trip.copy(
            lastStationCode = vehicle?.lastStation ?: trip.lastStationCode,
            status = vehicle?.status ?: trip.status,
            delay = minutes,
            trainStops = stops.mapIndexed { i, s ->
                if (i <= last || s.supression != null) s else s.copy(eta = null, etd = null, delay = minutes)
            }
        )
    }
}
