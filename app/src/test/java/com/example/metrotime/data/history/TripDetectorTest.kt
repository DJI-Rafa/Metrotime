package com.example.metrotime.data.history

import com.example.metrotime.data.MapConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private fun run(vararg fixes: TripDetector.Fix?, from: TripDetector.State = TripDetector.State()): Pair<List<TripDetector.Detection>, TripDetector.State> {
        var state = from
        val out = mutableListOf<TripDetector.Detection>()
        for (f in fixes) {
            val (s, d) = TripDetector.step(state, f, stops)
            state = s
            out += d
        }
        return out to state
    }

    @Test
    fun undergroundTripIsCountedWhenYouLeave() {
        // Alameda, depois sem sinal (leituras da rede com 800 m de erro), aparece na Baixa-Chiado 12 min depois
        val (riding, ridingState) = run(
            at("AM", t0), at("AM", t0 + min / 2),
            at("AN", t0 + 4 * min, accuracy = 800f), null,
            at("BC", t0 + 12 * min)
        )
        // Ainda pode ir mais longe: só conta quando saíres
        assertTrue(riding.isEmpty())
        assertTrue(ridingState.riding)

        // Cá fora, longe da estação (duas leituras seguidas)
        val (trips, state) = run(
            at("BC", t0 + 13 * min, eastM = 350.0), at("BC", t0 + 13 * min + min / 2, eastM = 450.0),
            from = ridingState
        )
        val t = trips.single()
        assertEquals("AM" to "BC", t.from to t.to)
        assertTrue(t.strong)
        assertEquals(t0 + min / 2, t.startMs)  // partida: a última vez que estivemos na Alameda
        assertEquals(t0 + 12 * min, t.endMs)   // chegada: quando se apareceu na Baixa-Chiado
        assertEquals("BC", state.anchorId)     // a próxima viagem parte daqui
        assertFalse(state.riding)
    }

    @Test
    fun passingStationsDoesNotEndTheTrip() {
        // Parque → Santa Apolónia: o telemóvel apanha localização no Marquês, nos Restauradores e no Terreiro do
        // Paço sem sair do comboio. Antes ficava "Marquês de Pombal → Restauradores" e o resto perdia-se.
        val (trips, state) = run(
            at("PA", t0),
            null,
            at("MP", t0 + 2 * min),
            at("RE", t0 + 5 * min),
            null,
            at("TP", t0 + 9 * min),
            at("SP", t0 + 11 * min),
            at("SP", t0 + 12 * min, eastM = 300.0), at("SP", t0 + 12 * min + min / 2, eastM = 400.0)
        )
        val t = trips.single()
        assertEquals("PA" to "SP", t.from to t.to)
        assertTrue(t.strong)
        assertEquals(t0, t.startMs)
        assertEquals(t0 + 11 * min, t.endMs)
        assertEquals("SP", state.anchorId)
    }

    @Test
    fun aSingleJumpOutsideDoesNotEndTheTrip() {
        // Entre o Marquês e os Restauradores uma leitura sai do sítio: a viagem continua
        val (trips, _) = run(
            at("PA", t0),
            at("MP", t0 + 2 * min),
            at("MP", t0 + 3 * min, eastM = 400.0),
            at("RE", t0 + 5 * min),
            at("RE", t0 + 6 * min, eastM = -350.0), at("RE", t0 + 6 * min + min / 2, eastM = -450.0)
        )
        assertEquals("PA" to "RE", trips.single().let { it.from to it.to })
    }

    @Test
    fun aTrainHeldAtAStationIsNotAnExit() {
        // O metro fica 5 min parado na Avenida e segue para os Restauradores: uma viagem só, até onde se sai
        val held = arrayOf(
            at("PA", t0), at("MP", t0 + 2 * min),
            at("AV", t0 + 4 * min), at("AV", t0 + 5 * min), at("AV", t0 + 6 * min), at("AV", t0 + 7 * min), at("AV", t0 + 9 * min)
        )
        val (waiting, waitingState) = run(*held)
        assertTrue(waiting.isEmpty())
        assertTrue(waitingState.riding)
        val (trips, _) = run(
            *held,
            at("RE", t0 + 11 * min),
            at("RE", t0 + 12 * min, eastM = -350.0), at("RE", t0 + 12 * min + min / 2, eastM = -450.0)
        )
        assertEquals("PA" to "RE", trips.single().let { it.from to it.to })
    }

    @Test
    fun halfAnHourAtTheSameStationEndsTheTrip() {
        // Saiu na Avenida mas ficou ali ao pé (a localização nunca saiu da estação): ao fim de meia hora, acabou lá
        val fixes = arrayOf(at("PA", t0), at("MP", t0 + 2 * min), at("AV", t0 + 4 * min), at("AV", t0 + 20 * min))
        assertTrue(run(*fixes).first.isEmpty())
        val (trips, state) = run(*fixes, at("AV", t0 + 35 * min))
        val t = trips.single()
        assertEquals("PA" to "AV", t.from to t.to)
        assertEquals(t0 + 4 * min, t.endMs) // a hora a que lá chegou
        assertEquals("AV", state.anchorId)
        assertFalse(state.riding)
    }

    @Test
    fun aRideEndsAfterAnHourWithoutLocation() {
        // Viu-se nos Restauradores e depois nenhuma localização boa (dentro de um prédio): ao fim de uma hora, acabou lá
        val (_, riding) = run(at("PA", t0), at("MP", t0 + 2 * min), at("RE", t0 + 5 * min))
        assertEquals(riding to emptyList<TripDetector.Detection>(), TripDetector.tick(riding, t0 + 50 * min, stops))
        val (state, trips) = TripDetector.tick(riding, t0 + 70 * min, stops)
        assertEquals("PA" to "RE", trips.single().let { it.from to it.to })
        assertEquals(t0 + 5 * min, trips.single().endMs)
        assertFalse(state.riding)
    }

    @Test
    fun changingLinesAtAnInterchangeIsOneTrip() {
        // Restauradores → Marquês (azul), 7 min no Marquês a mudar para a amarela, Picoas → Saldanha
        val (trips, _) = run(
            at("RE", t0),
            at("AV", t0 + 2 * min),
            at("MP", t0 + 4 * min), at("MP", t0 + 6 * min), at("MP", t0 + 8 * min), at("MP", t0 + 11 * min),
            at("PI", t0 + 13 * min),
            at("SA", t0 + 15 * min),
            at("SA", t0 + 16 * min, eastM = -300.0), at("SA", t0 + 16 * min + min / 2, eastM = -350.0)
        )
        val t = trips.single()
        assertEquals("RE" to "SA", t.from to t.to)
        assertEquals(t0 + 15 * min, t.endMs)
    }

    @Test
    fun aRideWithNoNewsEndsAtTheLastStation() {
        // Viu-se nos Restauradores e depois mais nada durante horas: a viagem acabou lá
        val (trips, state) = run(at("PA", t0), at("MP", t0 + 2 * min), at("RE", t0 + 5 * min), at("CG", t0 + 120 * min))
        val t = trips.single()
        assertEquals("PA" to "RE", t.from to t.to)
        assertEquals(t0 + 5 * min, t.endMs)
        assertEquals("CG", state.anchorId)
        assertFalse(state.riding)
    }

    @Test
    fun stationsCloseTogetherDoNotFlicker() {
        // Entre a Baixa-Chiado e o Rossio (345 m, seguidas na verde): uma leitura mais perto do Rossio não muda de
        // estação (antes dava uma viagem Baixa-Chiado → Rossio, com o tempo a bater com o metro)
        val bc = MapConfiguration.STATION_COORDS.getValue("BC")
        val ro = MapConfiguration.STATION_COORDS.getValue("RO")
        val f = 0.55
        val between = TripDetector.Fix(t0 + 3 * min, bc.lat + (ro.lat - bc.lat) * f, bc.lon + (ro.lon - bc.lon) * f, 10f)
        val (trips, state) = run(at("BC", t0), between, at("BC", t0 + 4 * min), at("BC", t0 + 8 * min))
        assertTrue(trips.isEmpty())
        assertEquals("BC", state.anchorId)
        assertFalse(state.riding)
    }

    @Test
    fun walkingToTheNextStationIsNotARide() {
        // Alameda → Arroios a pé (leituras cá fora pelo caminho): pergunta, e a próxima viagem parte de Arroios
        val (trips, state) = run(
            at("AM", t0),
            at("AM", t0 + 2 * min, eastM = -250.0), at("AM", t0 + 3 * min, eastM = -300.0),
            at("AR", t0 + 7 * min)
        )
        assertFalse(trips.single().strong)
        assertEquals("AR", state.anchorId)
        assertFalse(state.riding)
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
        // Baixa-Chiado → Rossio: dá para ir a pé; sem leituras pelo caminho, pergunta (quando se sai)
        val (trips, _) = run(
            at("BC", t0), null, at("RO", t0 + 5 * min),
            at("RO", t0 + 6 * min, eastM = 250.0), at("RO", t0 + 6 * min + min / 2, eastM = 300.0)
        )
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
        val start = TripDetector.startManual(TripDetector.State(), "AM", t0, "h_1")
        val (trips, state) = run(
            null, at("BC", t0 + 11 * min),
            at("BC", t0 + 12 * min, eastM = 350.0), at("BC", t0 + 12 * min + min / 2, eastM = 450.0),
            from = start
        )
        val t = trips.single()
        assertEquals("h_1", t.manualTripId)
        assertEquals("BC", t.to)
        assertNull(state.manualTripId)
    }

    @Test
    fun endingARideByHand() {
        // Começar outra viagem à mão acaba a que estava em curso, na última estação onde se apareceu
        val (_, riding) = run(at("PA", t0), at("MP", t0 + 2 * min), at("RE", t0 + 5 * min))
        val (state, detection) = TripDetector.endRide(riding, stops)
        assertEquals("PA" to "RE", detection!!.from to detection.to)
        assertFalse(state.riding)
        // Sem viagem em curso, nada muda
        val idle = TripDetector.State("AM", t0, t0, 0, null)
        assertEquals(idle to null, TripDetector.endRide(idle, stops))
    }

    @Test
    fun badFixesChangeNothing() {
        val start = TripDetector.State("AM", t0, t0, 0, null)
        assertEquals(start, TripDetector.step(start, at("BC", t0 + 10 * min, accuracy = 500f), stops).first)
        assertEquals(start, TripDetector.step(start, null, stops).first)
    }
}
