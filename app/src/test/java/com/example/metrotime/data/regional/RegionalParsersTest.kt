package com.example.metrotime.data.regional

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amostras reduzidas de respostas reais (GO Hub da TML e Eleven Systems, novembro de 2026). */
class RegionalParsersTest {

    private val now = 1_795_514_960_000L

    private val tmlJson = """
        {"data":[
          {"agency_id":"HF16N","latitude":38.72555,"longitude":-9.437629,"created_at":1795514928000,
           "current_status":"IN_TRANSIT_TO","route_short_name":"45","vehicle_id":"[HF16N]170"},
          {"agency_id":"LTP61","latitude":38.70435,"longitude":-9.145687,"created_at":1795514972000,
           "current_status":"STOPPED_AT","route_short_name":"SX","vehicle_id":"[LTP61]77"},
          {"agency_id":"IA9T6","latitude":38.71,"longitude":-9.14,"created_at":1795514928000,
           "route_short_name":"728","vehicle_id":"[IA9T6]1234"},
          {"agency_id":"7NTB1","latitude":38.74,"longitude":-9.15,"created_at":1795500000000,
           "route_short_name":"Fertagus","vehicle_id":"[7NTB1]14029"},
          {"agency_id":"KB1F6","latitude":0,"longitude":0,"created_at":1795514928000,"vehicle_id":"[KB1F6]C001"},
          {"agency_id":"A3H3M","latitude":38.66,"longitude":-9.07,"created_at":1795514950000,
           "route_short_name":null,"vehicle_id":"[A3H3M]55"}
        ],"error":null,"status_code":200}
    """.trimIndent()

    @Test
    fun tmlKeepsOnlyTheChosenAgenciesWithFreshPositions() {
        val vehicles = RegionalParsers.tmlPositions(tmlJson, RegionalOperators.TML, now)
        // Carris (IA9T6) fica de fora; a Fertagus tem a posição velha; o MTS está em (0, 0)
        assertEquals(listOf("170", "77", "55"), vehicles.map { it.vehicleId })
        val boat = vehicles[1]
        assertEquals("SX", boat.line)
        assertEquals(VehicleKind.BOAT, boat.operator.kind)
        assertEquals("STOPPED_AT", boat.status)
        assertEquals("tml:LTP61|77", boat.key)
    }

    @Test
    fun withoutALineTheLabelIsTheOperator() {
        val tcb = RegionalParsers.tmlPositions(tmlJson, RegionalOperators.TML, now).last()
        assertNull(tcb.line)
        assertEquals("TCB", tcb.label)
    }

    private val cov = RegionalOperators.ELEVEN.first { it.code == "cov" }

    @Test
    fun elevenListTakesTheColourAndAskForTheLine() {
        val json = """
            [{"id":"11010288","position":{"lat":40.293538,"lon":-7.535006},"speed":0,"status":"ontime",
              "color":"DB291C","busStatus":"incomingAt","delay":0},
             {"id":"11010290","position":{"lat":40.275079,"lon":-7.504515},"color":null},
             {"id":"11010299","position":{"lat":0,"lon":0},"color":"009AB2"}]
        """.trimIndent()
        val vehicles = RegionalParsers.elevenLocations(json, cov, now) { id, color ->
            if (id == "11010288" && color == "#DB291C") "SERRA" else null
        }
        assertEquals(2, vehicles.size)
        assertEquals("SERRA", vehicles[0].line)
        assertEquals("#DB291C", vehicles[0].color)
        // Sem cor na lista: a do operador
        assertEquals(cov.color, vehicles[1].color)
        assertEquals("COV", vehicles[1].label)
    }

    @Test
    fun onlyColoursOfASingleLineIdentifyTheLine() {
        val json = """
            [{"id":"11","name":"URBANA MANGUALDE (CIRCULAR)","nameShort":"11","color":"017EBD","isActive":true},
             {"id":"12","name":"URBANA MANGUALDE (ZONA INDUSTRIAL)","nameShort":"12","color":"017EBD","isActive":true},
             {"id":"705","name":"MANGUALDE - VISEU","nameShort":"705","color":"0ba852","isActive":true}]
        """.trimIndent()
        assertEquals(mapOf("#0BA852" to "705"), RegionalParsers.elevenLinesByColor(json))
    }

    @Test
    fun elevenTripHasStopsMinutesAndShape() {
        val json = """
            {"position":{"lat":40.293538,"lon":-7.535006},
             "route":{"id":"200","name":"SERRA SHUTTLE","nameShort":"SERRA","color":"DB291C","isActive":false},
             "journey":{"shape":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","circulations":[
               {"sequence":1,"stage":{"id":"9681","name":"TORRE","position":{"lat":40.32,"lon":-7.61}},
                "departureTime":36000,"arrivalTime":36000},
               {"sequence":2,"stage":{"id":"85","name":"POUSADA DA JUVENTUDE","position":{"lat":40.31,"lon":-7.55}},
                "departureTime":36840,"arrivalTime":36840,"dueInMinutes":0},
               {"sequence":3,"stage":{"id":"7204","name":"PRAÇA DO MUNICÍPIO","position":{"lat":40.28,"lon":-7.50}},
                "departureTime":90060,"arrivalTime":90060,"dueInMinutes":14}]},
             "currentStopSequence":2,"id":"11010288"}
        """.trimIndent()
        val trip = RegionalParsers.elevenTrip(json)
        assertNotNull(trip)
        trip!!
        assertEquals("SERRA", trip.line)
        assertEquals("Serra Shuttle", trip.lineName)
        assertEquals("#DB291C", trip.color)
        assertEquals("Praça do Município", trip.destination)
        assertTrue(trip.stops[0].passed)
        assertFalse(trip.stops[1].passed)
        assertEquals("10:14", trip.stops[1].clock)
        // Depois da meia-noite (25:01) volta às 01:01
        assertEquals("01:01", trip.stops[2].clock)
        assertEquals(14, trip.stops[2].dueMinutes)
        // O exemplo da documentação do Google
        assertEquals(3, trip.shape.size)
        assertEquals(38.5, trip.shape[0].first, 1e-6)
        assertEquals(-120.2, trip.shape[0].second, 1e-6)
        assertEquals(43.252, trip.shape[2].first, 1e-6)
    }

    @Test
    fun capitalisedNamesBecomeReadable() {
        assertEquals("Mangualde - Viseu via Alcafache", RegionalParsers.prettyName("MANGUALDE - VISEU VIA ALCAFACHE"))
        assertEquals("Pousada da Juventude", RegionalParsers.prettyName("POUSADA DA JUVENTUDE"))
        assertEquals("Já Escrito Assim", RegionalParsers.prettyName("Já Escrito Assim"))
    }
}
