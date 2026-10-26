package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainMatcherTest {

    // Linha reta de oeste para este, com 20 km, à latitude de Lisboa
    private val lat = 38.75
    private val metrePerLon = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun east(m: Double) = lat to (-9.2 + m / metrePerLon)
    private val track = CpTrack((0..40).map { east(it * 500.0) })

    private fun train(atM: Double, ageSec: Double = 60.0, bearing: Double? = 90.0) =
        east(atM).let { TrainMatcher.Train("521", it.first, it.second, ageSec, bearing) }

    private fun user(atM: Double, northM: Double = 0.0, speedKmh: Double = 108.0, bearing: Double? = 90.0) =
        east(atM).let { TrainMatcher.User(it.first + northM / 111_320.0, it.second, speedKmh, bearing) }

    @Test
    fun youMustBeWhereTheTrainCanBeNow() {
        // Visto há 60 s aos 5 km, a 108 km/h pode estar até aos 6,8 km
        assertEquals(0.0, TrainMatcher.gap(user(6_000.0), train(5_000.0), track)!!, 1.0)
        assertEquals(1_000.0, TrainMatcher.gap(user(4_000.0), train(5_000.0), track)!!, 5.0)   // atrás do comboio
        assertEquals(2_200.0, TrainMatcher.gap(user(9_000.0), train(5_000.0), track)!!, 5.0)   // muito à frente
    }

    @Test
    fun offTheTrackOrTheOtherWayIsNotYourTrain() {
        // Carro numa estrada a 400 m da linha, à mesma velocidade e no mesmo sentido
        assertNull(TrainMatcher.gap(user(6_000.0, northM = 400.0), train(5_000.0), track))
        // Sentido contrário
        assertNull(TrainMatcher.gap(user(6_000.0, bearing = 270.0), train(5_000.0), track))
    }

    @Test
    fun withoutTrackItUsesTheTrainsHeading() {
        // Sem traçado: o segmento entre onde foi visto e onde estaria agora (1,8 km para este)
        assertTrue(TrainMatcher.gap(user(6_000.0), train(5_000.0), null)!! < 20.0)
        assertTrue(TrainMatcher.gap(user(5_000.0, northM = 3_000.0), train(5_000.0), null)!! > 2_900.0)
        // Sem rumo do comboio, conta o que pode ter andado para qualquer lado
        assertEquals(0.0, TrainMatcher.gap(user(6_500.0, bearing = null), train(5_000.0, bearing = null), null)!!, 1.0)
    }

    @Test
    fun theBestTrainIsTheClosestThatFits() {
        val trains = listOf(
            TrainMatcher.Train("1", east(5_800.0).first, east(5_800.0).second, 30.0, 90.0),
            TrainMatcher.Train("2", east(3_000.0).first, east(3_000.0).second, 30.0, 90.0),   // 3 km atrás: não
            TrainMatcher.Train("3", east(6_100.0).first, east(6_100.0).second, 30.0, 270.0)   // sentido contrário
        )
        val (best, gap) = TrainMatcher.best(user(6_000.0), trains) { track }!!
        assertEquals("1", best.id)
        assertEquals(0.0, gap, 1.0)
        assertNull(TrainMatcher.best(user(6_000.0), listOf(trains[1])) { track })
    }

    @Test
    fun stationsAreTheTrainsStopsAndKmGoAlongTheTrack() {
        val stops = listOf(
            TrainMatcher.Stop("A", "Estação A", east(0.0).first, east(0.0).second),
            TrainMatcher.Stop("B", "Estação B", east(8_000.0).first, east(8_000.0).second),
            TrainMatcher.Stop("C", "Estação C", east(15_000.0).first, east(15_000.0).second)
        )
        assertEquals("B", TrainMatcher.nearestStop(stops, east(8_300.0))!!.code)
        assertNull(TrainMatcher.nearestStop(stops, east(11_500.0)))  // a mais de 1,5 km de qualquer paragem
        assertEquals(15.0, TrainMatcher.trackKm(track, stops[0], stops[2])!!, 0.05)
        assertNull(TrainMatcher.trackKm(null, stops[0], stops[2]))
    }

    @Test
    fun distanceToTheRailway() {
        val onTrack = east(6_000.0)
        assertTrue(TrainMatcher.distanceToRail(listOf(track), onTrack) < 1.0)
        val road = (onTrack.first + 2_000 / 111_320.0) to onTrack.second
        assertTrue(TrainMatcher.distanceToRail(listOf(track), road) > 1_900.0)
        assertNotNull(TrainMatcher.move(onTrack, 90.0, 1_000.0))
    }
}
