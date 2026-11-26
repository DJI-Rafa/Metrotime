package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Os casos verdadeiros vistos na API da CP a 28/10/2026 (comboios 5684 e 16031). */
class CpTrainNowTest {

    private val day = LocalDate.of(2026, 10, 28)
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(day, LocalTime.of(h, m, s))

    private fun stop(code: String, arr: String?, dep: String?, eta: String? = null, etd: String? = null, delay: Int? = null) =
        CpTrainStop(station = CpGenericEntity(code, code), arrival = arr, departure = dep, eta = eta, etd = etd, delay = delay)

    /** O regional 5684 (Guarda → Castelo Branco) a chegar à Covilhã: a CP já a dá como última estação. */
    private val arrivingCovilha = listOf(
        stop("BELMONTE", "18:10", "18:11", "18:20", "18:21", 10),
        stop("CARIA", "18:15", "18:15", "18:24", "18:25", 10),
        stop("COVILHA", "18:26", "18:27", "18:34", null, 8),
        stop("TORTOSENDO", "18:32", "18:32", null, null, null),
        stop("ALCARIA", "18:37", "18:37", null, null, null)
    )

    private fun now(stops: List<CpTrainStop>, last: String?, status: String?, gps: Float?, t: LocalDateTime) =
        CpTrainNow.of(stops, CpTripClock.moments(day, stops), last, status, gps, t)

    @Test
    fun gpsLaggingBehindDoesNotPullTheTrainBack() {
        // O GPS ainda o mostra entre Caria e Covilhã (1.7), mas a CP já o dá na Covilhã: está a chegar à Covilhã
        val n = now(arrivingCovilha, "COVILHA", VehicleStatus.IN_TRANSIT, 1.7f, at(18, 33))
        assertEquals(2f, n.position)
        assertTrue(n.atStation)
        assertTrue(n.arriving)
        // O atraso é o da partida da Covilhã: 18:27 → 18:35 (+8), o mesmo que a lista mostra
        assertEquals(8, n.delayMinutes)
        assertEquals(2, n.nextIndex)
    }

    @Test
    fun aSuppressedTrainDoesNotMove() {
        // Dia de greve: o AP 123 vinha como CANCELLED e andava pelo relógio, "Na hora"
        val plan = listOf(
            stop("LISBOA", null, "09:00"), stop("ORIENTE", "09:08", "09:09"),
            stop("COIMBRA", "10:45", "10:47"), stop("AVEIRO", "11:13", "11:15")
        )
        val n = now(plan, null, VehicleStatus.CANCELLED, null, at(11, 0))
        assertTrue(n.cancelled)
        assertFalse(n.started)
        assertEquals(-1f, n.position)
        assertEquals(null, n.delayMinutes)
        // Sem o estado, mas com todas as paragens suprimidas: o mesmo
        val all = plan.map { it.copy(supression = CpGenericEntity("S", "Greve")) }
        assertTrue(now(all, null, VehicleStatus.NOT_STARTED, null, at(11, 0)).cancelled)
    }

    @Test
    fun suppressedPartOfTheRouteIsNotDriven() {
        // Só se faz até Coimbra: às 11:00 já chegou lá e acabou (antes ia "a caminho de Aveiro")
        val plan = listOf(
            stop("LISBOA", null, "09:00"), stop("ORIENTE", "09:08", "09:09"),
            stop("COIMBRA", "10:45", "10:47"), stop("AVEIRO", "11:13", "11:15").copy(supression = CpGenericEntity("S", "Greve"))
        )
        val n = now(plan, null, VehicleStatus.IN_TRANSIT, null, at(11, 0))
        assertFalse(n.cancelled)
        assertTrue(n.finished)
        assertEquals(2f, n.position)
        // E se só se faz a partir de Coimbra, antes disso ainda não começou
        val late = listOf(
            stop("LISBOA", null, "09:00").copy(supression = CpGenericEntity("S", "Greve")),
            stop("ORIENTE", "09:08", "09:09").copy(supression = CpGenericEntity("S", "Greve")),
            stop("COIMBRA", "10:45", "10:47"), stop("AVEIRO", "11:13", "11:15")
        )
        assertFalse(now(late, null, VehicleStatus.NOT_STARTED, null, at(10, 0)).started)
    }

    @Test
    fun stillAtTheStationUntilTheDepartureTime() {
        val n = now(arrivingCovilha, "COVILHA", VehicleStatus.IN_TRANSIT, null, at(18, 34, 30))
        assertTrue(n.atStation)
        assertFalse(n.arriving)
        assertEquals(2f, n.position)
    }

    @Test
    fun afterLeavingItGoesTowardsTheNextOneWithTheGps() {
        // 18:38: partiu da Covilhã às 18:37 e o GPS vai a 28 % do caminho para o Tortosendo
        val left = listOf(
            stop("BELMONTE", "18:10", "18:11", "18:20", "18:21", 10),
            stop("CARIA", "18:15", "18:15", "18:24", "18:25", 10),
            stop("COVILHA", "18:26", "18:27", "18:34", "18:37", 10),
            stop("TORTOSENDO", "18:32", "18:32", "18:42", "18:42", 10),
            stop("ALCARIA", "18:37", "18:37", "18:47", "18:47", 10)
        )
        val n = now(left, "COVILHA", VehicleStatus.IN_TRANSIT, 2.28f, at(18, 38, 30))
        assertFalse(n.atStation)
        assertEquals(2.28f, n.position, 0.001f)
        assertEquals(3, n.nextIndex)
        // A caminho: o atraso da chegada ao Tortosendo (18:32 → 18:42)
        assertEquals(10, n.delayMinutes)
        // O GPS nunca o põe para lá da estação seguinte
        assertEquals(2.95f, now(left, "COVILHA", VehicleStatus.IN_TRANSIT, 3.4f, at(18, 41)).position, 0.001f)
    }

    @Test
    fun heldAtAStationWhileTheDepartureIsAhead() {
        // O urbano 16031 parado em Alverca: chegou às 18:17 e só parte às 18:35
        val stops = listOf(
            stop("POVOA", "17:46", "17:47", "17:45", null, 0),
            stop("ALVERCA", "17:50", "17:51", "18:17", "18:35", 44),
            stop("ALHANDRA", "17:54", "17:54", "18:38", "18:38", 44)
        )
        val n = now(stops, "ALVERCA", VehicleStatus.IN_TRANSIT, 1.2f, at(18, 30))
        assertTrue(n.atStation)
        assertEquals(1f, n.position)
        assertEquals(44, n.delayMinutes)
    }

    @Test
    fun notStartedAndFinished() {
        val early = now(arrivingCovilha, null, VehicleStatus.NOT_STARTED, null, at(17, 0))
        assertEquals(-1f, early.position)
        assertFalse(early.started)
        val done = now(arrivingCovilha, "ALCARIA", VehicleStatus.IN_TRANSIT, null, at(19, 0))
        assertTrue(done.finished)
        assertEquals(4f, done.position)
    }

    @Test
    fun withoutTheLastStationItGoesByTheClock() {
        val n = now(arrivingCovilha, null, VehicleStatus.IN_TRANSIT, null, at(18, 22))
        // 18:22: saiu de Belmonte às 18:21 e chega a Caria às 18:24
        assertEquals(0, n.stopIndex)
        assertFalse(n.atStation)
    }
}
