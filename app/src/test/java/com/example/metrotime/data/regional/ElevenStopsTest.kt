package com.example.metrotime.data.regional

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paragens da Eleven Systems (Covilhã, Viseu, Guimarães…): as respostas da API como vêm. */
class ElevenStopsTest {

    @Test
    fun stopsKeepTheOnesWithCoordinates() {
        val json = """[
            {"id":"57","name":"ÁGUA ALTA","nameShort":"57","position":{"lat":40.26711622,"lon":-7.510030118}},
            {"id":"9","name":"SEM GPS","nameShort":"9","position":{"lat":0,"lon":0}},
            {"id":"10","name":"SEM POSIÇÃO","nameShort":"10"}
        ]"""
        val stops = RegionalParsers.elevenStops(json)
        assertEquals(1, stops.size)
        assertEquals("57", stops[0].id)
        assertEquals(40.26711622, stops[0].lat, 1e-9)
        assertEquals(-7.510030118, stops[0].lon, 1e-9)
        assertTrue(stops[0].name.isNotBlank())
    }

    @Test
    fun stopRoutesGiveTheTimeAtThisStop() {
        val json = """[{"journeys":[
            {"id":"3377","start":"21:00:00","end":"00:00:00","startTime":75600,"endTime":0,"direction":1,"isActive":false,"type":"scheduled"},
            {"id":"3380","start":"22:45:00","end":"00:00:00","direction":1,"isActive":false,"type":"scheduled"}
        ],"id":"1002","name":"POUSO - BAIRRO CABEÇO","nameShort":"N2","color":"EABE06","isActive":false}]"""
        val routes = RegionalParsers.elevenStopRoutes(json)
        assertEquals(1, routes.size)
        val r = routes[0]
        assertEquals("N2", r.label)
        assertEquals("#EABE06", r.color)
        assertEquals(listOf(75600, 81900), r.passes.map { it.seconds })
        assertEquals(listOf("3377", "3380"), r.passes.map { it.journeyId })
        assertEquals(1, r.passes[0].direction)
    }

    @Test
    fun theDestinationIsTheLastStopExceptOnACircularLine() {
        fun journey(vararg names: String) = """{"circulations":[${
            names.mapIndexed { i, n -> """{"sequence":$i,"stage":{"id":"$i","name":"$n"},"arrivalTime":0}""" }.joinToString(",")
        }]}"""
        val dest = RegionalParsers.elevenJourneyDestination(journey("UBI - POLO IV", "HOSPITAL", "GALP"))
        assertEquals(RegionalParsers.prettyName("GALP"), dest)
        assertNull(RegionalParsers.elevenJourneyDestination(journey("TERMINAL RODOVIÁRIO", "UBI", "TERMINAL RODOVIÁRIO")))
        assertNull(RegionalParsers.elevenJourneyDestination("""{"circulations":[]}"""))
    }

    @Test
    fun carrisAndCmAreOperatorsOfTheLisbonLayer() {
        assertEquals(RegionalOperators.CARRIS, RegionalOperators.byKey("lx:carris"))
        assertEquals(RegionalOperators.CM, RegionalOperators.byKey("lx:cm"))
        assertTrue(listOf(RegionalOperators.CARRIS, RegionalOperators.CM).all {
            it.area == RegionalArea.LISBOA && it.source == RegionalSource.CITY && it.pack == null
        })
        // Cada empresa uma vez só
        assertEquals(RegionalOperators.ALL.size, RegionalOperators.ALL.map { it.key }.toSet().size)
    }
}
