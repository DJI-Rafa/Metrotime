package com.example.metrotime.data.metro

import com.example.metrotime.data.LineArrival
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.cp.CpTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetroMotionTest {

    // Linha reta de oeste para este com 6 estações a 1 km umas das outras: A … F
    private val lat = 38.75
    private val metrePerLon = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun east(m: Double) = lat to (-9.2 + m / metrePerLon)
    private val ids = listOf("A", "B", "C", "D", "E", "F")
    private val track = MetroTracks.build(
        shapes = mapOf("Teste" to (0..50).map { east(it * 100.0) }),
        order = mapOf("Teste" to ids),
        coords = ids.withIndex().associate { (i, id) -> id to east(i * 1000.0) }
    ).getValue("Teste")
    private val t0 = 1_800_000_000_000L

    private fun arrival(station: String, seconds: Int, train: String = "7", dest: String = "F") =
        LineArrival(station, train, dest, dest, seconds)

    private fun train(vararg a: LineArrival) = MetroMotion.build(mapOf("Teste" to a.toList()), mapOf("Teste" to track), t0).single()

    @Test
    fun theTrackPutsEachStationInOrder() {
        assertEquals(5_000.0, track.length, 1.0)
        assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0), track.stationAt.map { Math.round(it / 10.0) * 10.0 })
    }

    @Test
    fun betweenTwoStationsItMovesAndAtAStationItStops() {
        // A chegar a D daqui a 60 s, a E daqui a 200 s
        val tr = train(arrival("D", 60), arrival("E", 200))
        val now = tr.placeAt(t0, track)!!
        assertNull(now.atStation)
        assertEquals(3, now.nextStation)
        assertTrue("entre C e D: ${now.s}", now.s > 2000 && now.s < 3000)
        // Mais perto de D a cada segundo
        val later = tr.placeAt(t0 + 30_000, track)!!
        assertTrue(later.s > now.s)
        // Em D: parado alguns segundos
        val atD = tr.placeAt(t0 + 65_000, track)!!
        assertEquals(3, atD.atStation)
        assertEquals(3000.0, atD.s, 0.5)
        // A caminho de E e, sem mais horas, até ao fim da linha
        assertTrue(tr.placeAt(t0 + 150_000, track)!!.s in 3000.0..4000.0)
        assertEquals(5, tr.placeAt(t0 + 340_000, track)!!.let { it.atStation ?: it.nextStation })
        // Depois de chegar ao fim e de lá ficar, desaparece
        assertNull(tr.placeAt(t0 + 60 * 60_000, track))
    }

    @Test
    fun theOtherWayRoundGoesBackwards() {
        val tr = train(arrival("C", 50, dest = "A"), arrival("B", 170, dest = "A"))
        val p = tr.placeAt(t0, track)!!
        assertEquals(-1, p.dir)
        assertTrue("entre D e C: ${p.s}", p.s > 2000 && p.s < 3000)
        assertTrue(tr.placeAt(t0 + 120_000, track)!!.s < 2000)
    }

    @Test
    fun theFutureComesFromTheMetrosOwnTimes() {
        // O seletor de tempo: daqui a 5 min, onde as horas da API dizem
        val tr = train(arrival("B", 30), arrival("C", 140), arrival("D", 250), arrival("E", 360))
        val in5 = tr.placeAt(t0 + 5 * 60_000, track)!!
        assertTrue("entre D e E daqui a 5 min: ${in5.s}", in5.s in 3000.0..4000.0)
        val upcoming = tr.upcoming(t0)
        assertEquals(listOf(1, 2, 3, 4, 5), upcoming.map { it.first })
        assertEquals(t0 + 140_000, upcoming[1].second)
    }

    @Test
    fun aTrainThatTurnsAroundIsStillOneTrain() {
        // Chega a F (fim) e volta para A com outro destino: um só metro, sem dois desenhados no terminal
        val trains = MetroMotion.build(
            mapOf("Teste" to listOf(arrival("F", 40), arrival("E", 260, dest = "A"), arrival("D", 380, dest = "A"))),
            mapOf("Teste" to track), t0
        )
        assertEquals(1, trains.size)
        val tr = trains.single()
        assertEquals(2, tr.runs.size)
        assertEquals(1, tr.placeAt(t0, track)!!.dir)
        val back = tr.placeAt(t0 + 330_000, track)!!
        assertEquals(-1, back.dir)
        assertTrue(back.s < 4000.0)
    }

    @Test
    fun theSmootherNeverJumpsBackForALittle() {
        val sm = MetroSmoother()
        assertEquals(1000.0, sm.step("x", 1000.0, 1, 0.033), 0.0)
        // Dados novos põem-no 80 m atrás: fica onde está
        assertEquals(1000.0, sm.step("x", 920.0, 1, 0.033), 0.0)
        // Para a frente: aproxima-se aos poucos
        val next = sm.step("x", 1100.0, 1, 0.1)
        assertTrue(next > 1000.0 && next < 1100.0)
        // Muito longe (outro metro com o mesmo número): salta
        assertEquals(4000.0, sm.step("x", 4000.0, 1, 0.033), 0.0)
        // No seletor de tempo anda depressa, também para trás
        val fast = sm.step("x", 3000.0, 1, 0.1, fast = true)
        assertTrue(fast < 3700.0)
    }

    @Test
    fun theLastMetrosAreTheOnesFurthestBehindAfterOneOClock() {
        fun p(id: String, s: Double, dir: Int) = MetroPlace("Azul", id, s, dir, null, null, "?", 1.0)
        val places = listOf(p("1", 3000.0, 1), p("2", 9000.0, 1), p("3", 5000.0, -1), p("4", 12_000.0, -1))
        assertTrue(MetroNight.lastTrains(places, 24 * 60 + 50).isEmpty())
        assertEquals(setOf("Azul|1", "Azul|4"), MetroNight.lastTrains(places, 25 * 60 + 5))
        // Em São Sebastião: os últimos de cada sentido das duas linhas, os que faltam primeiro
        val passes = MetroNight.lastPasses("SS", 25 * 60)
        assertEquals(3, passes.size)
        assertTrue(passes.zipWithNext().all { (a, b) -> !a.passed || b.passed })
        assertTrue(MetroNight.showCard(24 * 60 + 30))
        assertTrue(!MetroNight.showCard(22 * 60))
    }

    @Test
    fun theRealTracksFitTheStations() {
        val shapes = MetroTracks.parse(File("src/main/assets/metro_tracks.json.bin").inputStream())
        assertEquals(setOf("Azul", "Amarela", "Verde", "Vermelha"), shapes.keys)
        val lines = MetroTracks.build(shapes)
        for ((name, line) in lines) {
            val order = MapConfiguration.LINE_STATION_ORDER.getValue(name)
            assertEquals(order.size, line.stationAt.size)
            assertTrue(name, line.stationAt.toList().zipWithNext().all { (a, b) -> b > a })
            // Cada estação a menos de 200 m do sítio onde cai no traçado
            order.forEachIndexed { i, id ->
                val c = MapConfiguration.STATION_COORDS.getValue(id)
                val d = CpTrack.metres(c.lat to c.lon, line.point(line.stationAt[i]))
                assertTrue("$name/$id a ${d.toInt()} m", d < 200)
            }
            assertNotNull(line.slice(line.stationAt[1], line.stationAt[2]).takeIf { it.size >= 2 })
        }
        assertEquals(13.8, lines.getValue("Azul").length / 1000, 0.5)
    }
}
