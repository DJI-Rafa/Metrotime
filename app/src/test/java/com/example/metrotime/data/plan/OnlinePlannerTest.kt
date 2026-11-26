package com.example.metrotime.data.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** O planeador online, a pesquisa de moradas e o caminho a pé, com respostas reais guardadas (src/test/resources/plan). */
class OnlinePlannerTest {

    private fun res(name: String): String = javaClass.getResource("/plan/$name")!!.readText()

    @Test
    fun lisbonMetroAndCpLegsLookLikeTheAppsOwn() {
        val options = OnlinePlanner.parsePlan(res("plan_lx.json"), "Rossio (aqui)", "Cascais")
        assertTrue(options.isNotEmpty())
        val o = options.first()
        // A pé no início e no fim, com os nomes de partida e chegada no lugar de START e END
        assertEquals(LegMode.WALK, o.legs.first().mode)
        assertEquals("Rossio (aqui)", o.legs.first().fromName)
        assertEquals("Cascais", o.legs.last().toName)
        val metro = o.legs.first { it.mode == LegMode.METRO }
        assertEquals("Linha Verde", metro.label)
        assertEquals("Verde", metro.color)
        val train = o.legs.first { it.mode == LegMode.TRAIN }
        assertEquals("U", train.label)
        assertEquals(19211, train.trainNumber)
        assertEquals("94-69005", train.fromCode)
        assertEquals("94-69260", train.toCode)
        // Horas de Lisboa (em novembro = UTC)
        assertEquals(LocalDateTime.of(2026, 11, 25, 8, 15), train.depart)
        // Cada bocado sabe onde começa e acaba
        o.legs.forEach { assertNotNull(it.toLat); assertNotNull(it.toLon) }
    }

    @Test
    fun fertagusAndCarrisMetropolitanaComeWithTheirOperator() {
        val o = OnlinePlanner.parsePlan(res("plan_ft.json"), "Saldanha", "Amora").first()
        val ft = o.legs.first { it.mode == LegMode.TRAIN }
        assertEquals("FT", ft.label)
        assertNull(ft.trainNumber)
        val bus = o.legs.first { it.mode == LegMode.BUS }
        assertEquals("3116", bus.label)
        assertEquals("#C61D23", bus.color)
        assertEquals("carris_metropolitana", bus.busOperator)
        assertEquals("149996", bus.busBoardStop)
        assertNotNull(bus.busAlightLat)
        assertTrue(bus.detail.contains("Carris Metropolitana"))
        // Uma viagem com Fertagus não é das que a app calcula sozinha
        assertFalse(o.transit.all { JourneyPlanner.coveredLocally(it) })
    }

    @Test
    fun ferriesAreBoats() {
        val o = OnlinePlanner.parsePlan(res("plan_ferry.json"), "Cais do Sodré", "Cacilhas").first()
        val boat = o.legs.first { it.mode == LegMode.BOAT }
        assertEquals("Barco", boat.label)
        assertTrue(boat.detail.contains("Transtejo"))
        assertTrue(boat.detail.contains("sentido Cacilhas"))
    }

    @Test
    fun walkingLegsKeepTheirPathAndDistance() {
        val o = OnlinePlanner.parsePlan(res("plan_ft.json"), "Saldanha", "Amora").first()
        val walk = o.legs.first()
        assertEquals(LegMode.WALK, walk.mode)
        assertTrue(walk.path.size >= 2)
        assertTrue((walk.metres ?: 0.0) > 100)
        // O caminho começa perto do Saldanha
        assertEquals(38.73, walk.path.first().first, 0.02)
        assertEquals(-9.14, walk.path.first().second, 0.02)
    }

    @Test
    fun geocodeKeepsOnlyPortugalAndSaysWhatAndWhere() {
        val places = OnlinePlanner.parseGeocode(res("geocode.json"))
        assertTrue(places.isNotEmpty())
        assertTrue(places.all { it.kind == Place.Kind.ADDRESS })
        assertFalse(places.any { it.detail.contains("São José dos Campos") })
        val street = places.first { it.name == "Rua Augusta" && it.detail.startsWith("Rua") }
        assertTrue(street.detail, street.detail.contains("Lisboa"))
        // Guardar e voltar a ler (casa, recentes) mantém o "onde fica"
        assertEquals(street, Place.decode(street.encode()))
    }

    @Test
    fun oldSavedPlacesStillDecode() {
        val p = Place.decode("METRO|RO|Rossio|38.714|-9.139")
        assertNotNull(p)
        assertEquals("", p!!.detail)
        assertEquals(Place.Kind.METRO, p.kind)
    }

    @Test
    fun polylineWithSixDecimals() {
        // O exemplo da documentação do Google, com precisão 5
        val pts = OnlinePlanner.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].first, 1e-6)
        assertEquals(-120.2, pts[0].second, 1e-6)
        assertEquals(43.252, pts[2].first, 1e-6)
    }

    @Test
    fun footRouteInPortugueseWithTheStreetNames() {
        val r = WalkRouter.parse(res("osrm_foot.json"), "Rossio")!!
        assertTrue(r.streets)
        assertEquals(683.0, r.metres, 5.0)
        assertTrue(r.path.size > 10)
        assertEquals("Segue pela Rua Augusta", r.steps.first().text)
        assertTrue(r.steps.any { it.turn == WalkStep.Turn.LEFT && it.text.startsWith("Vira à esquerda") })
        assertEquals(WalkStep.Turn.ARRIVE, r.steps.last().turn)
        assertEquals("Chegaste a Rossio", r.steps.last().text)
    }

    @Test
    fun turnTexts() {
        assertEquals(WalkStep.Turn.RIGHT to "Vira à direita para o Largo do Rato", WalkRouter.instruction("turn", "right", "Largo do Rato", "X"))
        assertEquals(WalkStep.Turn.SLIGHT_LEFT to "Ligeiramente à esquerda para a Avenida da Liberdade",
            WalkRouter.instruction("turn", "slight left", "Avenida da Liberdade", "X"))
        assertEquals(WalkStep.Turn.STRAIGHT to "Segue em frente", WalkRouter.instruction("continue", "straight", "", "X"))
        assertEquals(WalkStep.Turn.ROUNDABOUT, WalkRouter.instruction("roundabout", "right", "Rua A", "X").first)
        val straight = WalkRouter.straight(38.70 to -9.14, 38.71 to -9.14, "Rossio")
        assertFalse(straight.streets)
        assertEquals(2, straight.path.size)
        assertEquals(1112.0, straight.metres, 15.0)
    }
}
