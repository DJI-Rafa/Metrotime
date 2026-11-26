package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * O quadro da estação e o ecrã do comboio dão a mesma hora e o mesmo atraso — com a CP a responder e sem ela
 * (alternativa: atrasos pelo GPS em todo o lado). Casos do comboio 5684 a 28/10/2026.
 */
class CpSyncTest {

    private val day = LocalDate.of(2026, 10, 28)
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, LocalTime.of(h, m))
    private val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    private fun stationStop(number: Int, arr: String, dep: String, eta: String?, etd: String?, delay: Int?) = CpTimedStop(
        CpStationStop(trainNumber = number, arrivalTime = arr, departureTime = dep, eta = eta, etd = etd, delay = delay),
        day, LocalTime.of(18, 0)
    )

    private fun trainStop(code: String, arr: String?, dep: String?, eta: String?, etd: String?, delay: Int?) =
        CpTrainStop(station = CpGenericEntity(code, code), arrival = arr, departure = dep, eta = eta, etd = etd, delay = delay)

    @Test
    fun stationAndTrainShowTheSameDeparture() {
        // A Covilhã com a chegada real (18:34) e ainda sem a partida: as duas contas dão 18:35 (+8)
        val board = CpBoard.entry(stationStop(5684, "18:26", "18:27", "18:34", null, 10), true, at(18, 30))!!
        val trip = CpTripClock.moments(day, listOf(
            trainStop("CARIA", "18:15", "18:15", "18:24", "18:25", 10),
            trainStop("COVILHA", "18:26", "18:27", "18:34", null, 10)
        ))
        assertEquals(trip[1].departure, board.effective)
        assertEquals("18:35", board.shown)
        assertEquals("+8 min", board.status)
    }

    @Test
    fun withoutTheCpBothUseTheGpsDelay() {
        val vehicle = CpVehicle(trainNumber = 5684, delay = 616, lastStation = "COVILHA", status = VehicleStatus.IN_TRANSIT)
        // Quadro do Tortosendo guardado de há uns minutos (com uma estimada antiga de 18:40)
        val saved = listOf(stationStop(5684, "18:32", "18:32", "18:40", "18:40", 8))
        val board = CpBoard.board(CpFallback.withVehicleDelays(saved, listOf(vehicle), at(18, 38)), true, at(18, 38)).single()
        // Percurso do comboio pela alternativa, com o mesmo GPS
        val trip = CpFallback.withVehicleDelay(
            CpTripDetail(lastStationCode = "COVILHA", trainStops = listOf(
                trainStop("COVILHA", "18:26", "18:27", "18:34", "18:37", 10),
                trainStop("TORTOSENDO", "18:32", "18:32", "18:40", "18:40", 8)
            )),
            vehicle
        )
        val moments = CpTripClock.moments(day, trip.trainStops!!)
        // 616 s = 10 min: o Tortosendo às 18:42 nos dois, e a Covilhã (já passou) fica com as horas reais
        assertEquals("18:42", board.shown)
        assertEquals("18:42", moments[1].departure!!.format(hm))
        assertEquals("18:37", moments[0].departure!!.format(hm))
        assertNull(trip.trainStops!![1].etd)
    }

    @Test
    fun earlyTrainIsOnTimeNotSuppressed() {
        val e = CpBoard.entry(stationStop(1, "18:40", "18:40", "18:39", "18:39", 0), true, at(18, 30))!!
        assertEquals(0, e.delayMinutes)
        assertEquals("Na hora", e.status)
    }
}
