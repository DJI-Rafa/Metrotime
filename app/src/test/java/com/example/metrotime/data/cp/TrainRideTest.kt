package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrainRideTest {

    // Linha reta de oeste para este à latitude de Lisboa: A aos 0 km, B aos 8 km, C (fim do percurso) aos 15 km
    private val lat = 38.75
    private val metrePerLon = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun east(m: Double) = lat to (-9.2 + m / metrePerLon)
    private fun north(p: Pair<Double, Double>, m: Double) = (p.first + m / 111_320.0) to p.second
    private fun stop(code: String, m: Double) = east(m).let { TrainMatcher.Stop(code, "Estação $code", it.first, it.second) }
    private val stops = listOf(stop("A", 0.0), stop("B", 8_000.0), stop("C", 15_000.0))
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    /** Uma verificação: [railM] = distância à linha; o comboio a [gap] m de ti com uma posição de [age] s. */
    private fun step(
        s: TrainRide.State, at: Pair<Double, Double>, t: Long, railM: Double,
        gap: Double? = 0.0, age: Double? = 30.0, accuracy: Float = 10f
    ) = TrainRide.step(s, TrainRide.Fix(at.first, at.second, accuracy, t), stops, stops.last(), railM, gap, age)

    @Test
    fun onTheRailsOrInTheStationYouAreStillOnTheTrain() {
        var s = TrainRide.State()
        // A andar entre A e B, mesmo com o comboio "longe" numa posição velha (chega atrasada)
        for (i in 1..6) {
            val (next, exit) = step(s, east(1_000.0 * i), t0 + i * min, railM = 15.0, gap = 3_000.0, age = 300.0)
            assertNull(exit)
            s = next
        }
        // Parado em B, no cais
        val (atB, e1) = step(s, north(east(8_100.0), 20.0), t0 + 8 * min, railM = 20.0)
        assertNull(e1)
        assertEquals("B", atB.lastStation?.stop?.code)
        assertEquals(t0 + 8 * min, atB.lastStation?.arrivedMs)
        // Sai da zona da estação mas em cima dos carris: continua no comboio, e B continua a ser a última estação
        val (afterB, e2) = step(atB, east(9_500.0), t0 + 10 * min, railM = 25.0)
        assertNull(e2)
        assertEquals("B", afterB.lastStation?.stop?.code)
        assertEquals(t0 + 8 * min, afterB.lastStation?.arrivedMs)
    }

    @Test
    fun leavingTheStationAwayFromTheRailsEndsTheTripThereWhenYouArrived() {
        var s = step(TrainRide.State(), east(5_000.0), t0, railM = 10.0).first
        s = step(s, north(east(8_000.0), 30.0), t0 + 4 * min, railM = 30.0).first          // chega a B
        s = step(s, north(east(8_000.0), 250.0), t0 + 6 * min, railM = 250.0).first        // no átrio, ainda na estação
        val (out1, e1) = step(s, north(east(8_000.0), 600.0), t0 + 9 * min, railM = 600.0)  // cá fora, longe da linha
        assertNull(e1)                                                                      // uma leitura não chega
        val (_, exit) = step(out1, north(east(8_000.0), 700.0), t0 + 10 * min, railM = 700.0)
        assertNotNull(exit)
        assertEquals("B", exit!!.stop?.code)
        assertEquals(t0 + 4 * min, exit.endMs)                                              // a hora a que chegou a B
    }

    @Test
    fun badReadingsDoNotCount() {
        val s = step(TrainRide.State(), east(5_000.0), t0, railM = 10.0).first
        // Duas leituras com 300 m de erro (estação coberta, túnel): longe da linha, mas não contam
        val (s1, e1) = step(s, north(east(5_500.0), 500.0), t0 + min, railM = 500.0, accuracy = 300f)
        val (s2, e2) = step(s1, north(east(6_000.0), 500.0), t0 + 2 * min, railM = 500.0, accuracy = 300f)
        assertNull(e1)
        assertNull(e2)
        assertEquals(0, s2.awayFixes)
    }

    @Test
    fun theTrainLeavesWithoutYouWhileYouWaitInTheStation() {
        var s = step(TrainRide.State(), north(east(8_000.0), 20.0), t0, railM = 20.0).first   // em B, no cais
        // O comboio parte e afasta-se (posição fresca): ao fim de 4 verificações a mais de 8 km, ficaste em B
        val gaps = listOf(2_000.0, 9_000.0, 11_000.0, 13_000.0)
        var exit: TrainRide.Exit? = null
        for ((i, g) in gaps.withIndex()) {
            val r = step(s, north(east(8_000.0), 20.0), t0 + (i + 1) * min, railM = 20.0, gap = g)
            s = r.first
            exit = r.second
        }
        assertNull(exit)                                                                        // só 3 longe
        exit = step(s, north(east(8_000.0), 20.0), t0 + 5 * min, railM = 20.0, gap = 15_000.0).second
        assertEquals("B", exit?.stop?.code)
        assertEquals(t0, exit?.endMs)
        // Com uma posição velha (o comboio pode só parecer longe) não conta
        var stale = step(TrainRide.State(), north(east(8_000.0), 20.0), t0, railM = 20.0).first
        repeat(6) { stale = step(stale, north(east(8_000.0), 20.0), t0 + (it + 1) * min, railM = 20.0, gap = 20_000.0, age = 400.0).also { r -> assertNull(r.second) }.first }
    }

    @Test
    fun threeMinutesInTheStationAwayFromTheRailsIsLeavingTheTrain() {
        var s = step(TrainRide.State(), north(east(8_000.0), 20.0), t0, railM = 20.0).first
        var exit: TrainRide.Exit? = null
        for (i in 1..6) {
            val r = step(s, north(east(8_000.0), 150.0), t0 + i * 30_000L, railM = 150.0, gap = null, age = null)
            s = r.first
            exit = r.second
            if (i < 6) assertNull(r.second)
        }
        assertEquals("B", exit?.stop?.code)
        assertEquals(t0, exit?.endMs)
    }

    @Test
    fun theEndOfTheLineIsWhereYouGotOff() {
        var s = step(TrainRide.State(), east(12_000.0), t0, railM = 10.0).first
        s = step(s, east(15_050.0), t0 + 3 * min, railM = 10.0).first                         // chega a C, o fim
        // Deixa a estação, mesmo em cima dos carris (já é outro comboio): saiu em C
        val exit = step(s, east(16_000.0), t0 + 20 * min, railM = 10.0).second
        assertEquals("C", exit?.stop?.code)
        assertEquals(t0 + 3 * min, exit?.endMs)
    }

    @Test
    fun withoutSeeingAStationTheExitIsTheStopNearestTheLastPointOnTheRails() {
        var s = step(TrainRide.State(), east(7_000.0), t0, railM = 10.0).first               // a 1 km de B
        s = step(s, north(east(7_000.0), 600.0), t0 + min, railM = 600.0).first
        val exit = step(s, north(east(7_000.0), 800.0), t0 + 2 * min, railM = 800.0).second
        assertEquals("B", exit?.stop?.code)
        assertEquals(t0, exit?.endMs)
    }
}
