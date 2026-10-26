package com.example.metrotime.data.history

import com.example.metrotime.data.MapConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {

    private val stops = MapConfiguration.STATION_COORDS.values.map { TripDetector.MetroStop(it.id, it.lat, it.lon) }
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    /** Leitura boa (10 m de erro) ao pé de uma estação, deslocada [eastM] metros para nascente. */
    private fun at(id: String, t: Long, eastM: Double = 0.0, accuracy: Float = 10f): TripDetector.Fix {
        val s = MapConfiguration.STATION_COORDS.getValue(id)
        return TripDetector.Fix(t, s.lat, s.lon + eastM / (111_000.0 * Math.cos(Math.toRadians(s.lat))), accuracy)
    }

    /** Corre as leituras pela ordem e devolve as viagens detetadas e o estado final. */
    private fun run(vararg fixes: TripDetector.Fix?): Pair<List<TripDetector.Detection>, TripDetector.State> {
        var state = TripDetector.State()
        val out = mutableListOf<TripDetector.Detection>()
        for (f in fixes) {
            val (s, d) = TripDetector.step(state, f, stops)
            state = s
            d?.let { out += it }
        }
        return out to state
    }

    @Test
    fun undergroundTripIsCountedRightAway() {
        // Alameda, depois sem sinal (leituras da rede com 800 m de erro), aparece na Baixa-Chiado 12 min depois
        val (trips, state) = run(
            at("AM", t0), at("AM", t0 + min / 2),
            at("AN", t0 + 4 * min, accuracy = 800f), null,
            at("BC", t0 + 12 * min)
        )
        assertEquals(1, trips.size)
        val t = trips.single()
        assertEquals("AM" to "BC", t.from to t.to)
        assertTrue(t.strong)
        assertEquals(t0 + min / 2, t.startMs) // partida: a última vez que estivemos na Alameda
        assertEquals("BC", state.anchorId)    // a próxima viagem parte daqui
    }

    @Test
    fun reappearingFurtherFromTheExitStillCounts() {
        // Ao sair do metro o primeiro sinal pode vir a 450 m da estação (aqui, do Oriente para o rio)
        val (trips, _) = run(at("AM", t0), null, null, at("OR", t0 + 16 * min, eastM = 450.0))
        assertEquals("OR", trips.single().to)
    }

    @Test
    fun travellingOnTheSurfaceIsNotAMetroTrip() {
        // Saldanha → Campo Pequeno de autocarro: leituras boas pelo caminho, longe das estações
        val s = MapConfiguration.STATION_COORDS.getValue("SA")
        val midway = (1..4).map { TripDetector.Fix(t0 + it * 40_000L, s.lat + 0.004 * it, s.lon + 0.003, 10f) }
        val (trips, _) = run(at("SA", t0), *midway.toTypedArray(), at("CP", t0 + 4 * min))
        assertTrue(trips.isEmpty())
    }

    @Test
    fun nearbyStationsAskForConfirmation() {
        // Baixa-Chiado → Rossio: dá para ir a pé; sem leituras pelo caminho, pergunta
        val (trips, _) = run(at("BC", t0), null, at("RO", t0 + 5 * min))
        assertEquals(1, trips.size)
        assertFalse(trips.single().strong)
    }

    @Test
    fun tooSlowOrTooFastIsNotAMetroTrip() {
        // Uma hora para uma viagem de ~12 min: ficou-se por lá
        assertTrue(run(at("AM", t0), at("BC", t0 + 60 * min)).first.isEmpty())
        // 1 min para atravessar a cidade: salto do GPS
        assertTrue(run(at("RB", t0), at("OR", t0 + min)).first.isEmpty())
        // Mais de 3 h: a origem já não conta
        val (trips, state) = run(at("AM", t0), at("BC", t0 + 4 * 60 * min))
        assertTrue(trips.isEmpty())
        assertEquals("BC", state.anchorId)
    }

    @Test
    fun manualStartGetsItsDestination() {
        var state = TripDetector.startManual(TripDetector.State(), "AM", t0, "h_1")
        state = TripDetector.step(state, null, stops).first
        val (s, d) = TripDetector.step(state, at("BC", t0 + 11 * min), stops)
        assertNotNull(d)
        assertEquals("h_1", d!!.manualTripId)
        assertNull(s.manualTripId)
    }

    @Test
    fun badFixesChangeNothing() {
        val start = TripDetector.State("AM", t0, t0, 0, null)
        assertEquals(start, TripDetector.step(start, at("BC", t0 + 10 * min, accuracy = 500f), stops).first)
        assertEquals(start, TripDetector.step(start, null, stops).first)
    }
}
