package com.example.metrotime.data.plan

import com.example.metrotime.data.MetroNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroTripTest {

    @Test
    fun oneLineIsOneLegTowardsTheTerminal() {
        // Alameda → Baixa-Chiado pela Verde, no sentido de Cais do Sodré
        val legs = MetroTrip.legs(MetroNetwork.route("AM", "BC")!!)
        assertEquals(1, legs.size)
        assertEquals("Verde", legs[0].line)
        assertEquals("CS", legs[0].towards)
        assertEquals(6, legs[0].stops)
    }

    @Test
    fun changingLinesSplitsTheLegs() {
        // Reboleira → Oriente: Azul até São Sebastião (sentido Santa Apolónia), depois Vermelha (sentido Aeroporto)
        val route = MetroNetwork.route("RB", "OR")!!
        val legs = MetroTrip.legs(route)
        assertEquals(listOf("Azul", "Vermelha"), legs.map { it.line })
        assertEquals(listOf("SP", "AP"), legs.map { it.towards })
        assertEquals("SS", legs[0].to)
        assertEquals("SS", legs[1].from)
        // Os troços mais a mudança dão o tempo do percurso
        assertEquals(route.minutes, legs.sumOf { it.minutes } + MetroNetwork.TRANSFER_MIN, 0.01)
    }

    @Test
    fun onlyTrainsGoingFarEnoughServeTheLeg() {
        // Reboleira → São Sebastião (sentido Santa Apolónia)
        val leg = MetroTrip.legs(MetroNetwork.route("RB", "SS")!!).single()
        assertTrue(MetroTrip.servesLeg(leg, "Santa Apolónia"))
        // Percurso curto que acaba antes de São Sebastião, e o sentido contrário
        assertFalse(MetroTrip.servesLeg(leg, "Laranjeiras"))
        assertFalse(MetroTrip.servesLeg(leg, "Reboleira"))
        assertFalse(MetroTrip.servesLeg(leg, "Destino 99"))
    }

    @Test
    fun lastTrainLeavesTheTerminalAtOne() {
        // Na estação de partida do último metro passa à 1:00; mais à frente, depois
        assertEquals(MetroTrip.LAST_DEPARTURE_MIN.toDouble(), MetroTrip.lastPass("Verde", "TE", "CS")!!, 0.0)
        assertTrue(MetroTrip.lastPass("Verde", "BC", "CS")!! > MetroTrip.LAST_DEPARTURE_MIN)
        assertNull(MetroTrip.lastPass("Verde", "XX", "CS"))
    }

    @Test
    fun latestDepartureLeavesTimeToChange() {
        val route = MetroNetwork.route("RB", "OR")!!
        val legs = MetroTrip.legs(route)
        val latest = MetroTrip.latestDeparture(route)!!
        // Nunca depois do último metro da primeira linha
        assertTrue(latest <= MetroTrip.lastPass(legs[0].line, legs[0].from, legs[0].towards)!!)
        // E chega a São Sebastião a tempo do último da Vermelha
        val atChange = latest + legs[0].minutes
        assertTrue(atChange <= MetroTrip.lastPass(legs[1].line, legs[1].from, legs[1].towards)!!)
        // Sem mudança: é a hora a que o último metro passa na estação
        val direct = MetroNetwork.route("AM", "BC")!!
        assertEquals(MetroTrip.lastPass("Verde", "AM", "CS")!!, MetroTrip.latestDeparture(direct)!!, 0.0)
    }

    @Test
    fun serviceDayCrossesMidnight() {
        assertEquals(24 * 60 + 30, MetroTrip.serviceMinutes(0, 30))
        assertEquals(23 * 60, MetroTrip.serviceMinutes(23, 0))
        assertEquals(6 * 60 + 30, MetroTrip.serviceMinutes(6, 30))
        assertEquals("01:07", MetroTrip.clock(25 * 60 + 7.0))
        assertEquals("23:59", MetroTrip.clock(23 * 60 + 59.2))
    }

    @Test
    fun placesSurviveSaving() {
        val p = Place(Place.Kind.CP, "94-30007", "Lisboa | St. Apolónia", 38.71, -9.12)
        val back = Place.decode(p.encode())!!
        assertEquals(p.copy(name = "Lisboa / St. Apolónia"), back)
        assertNull(Place.decode("lixo"))
        assertNull(Place.decode(null))
    }

    @Test
    fun walkingMinutes() {
        assertEquals(0, Walk.minutes(10.0))
        assertEquals(1, Walk.minutes(60.0))
        assertEquals(5, Walk.minutes(400.0))
    }
}
