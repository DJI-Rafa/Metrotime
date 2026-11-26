package com.example.metrotime.data.plan

import android.content.Context
import com.example.metrotime.data.regional.RegionalOperator
import com.example.metrotime.data.regional.RegionalParsers
import com.example.metrotime.data.regional.RegionalRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Viagens diretas nas redes da Eleven Systems (Covilhã, Viseu, Guimarães, Castelo Branco, Vila Real, Guarda, Lamego,
 * Trás-os-Montes, Sabugal), que o planeador online não tem: as paragens perto da partida e da chegada da mesma rede,
 * as próximas passagens nas de partida e, em cada viagem, se passa depois numa paragem perto da chegada.
 * A API só dá as próximas passagens de hoje: serve para as próximas horas.
 */
object ElevenPlanner {

    private const val NEAR_M = 700.0
    private const val MAX_ORIGIN_STOPS = 4
    private const val MAX_JOURNEYS = 14
    private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

    private class Candidate(
        val op: RegionalOperator,
        val stop: RegionalParsers.ElevenStop,
        val walkM: Double,
        val route: RegionalParsers.ElevenStopRoute,
        val pass: RegionalParsers.ElevenPass
    )

    private fun metres(a: Place, lat: Double, lon: Double) = TripPlanner.metres(a.lat, a.lon, lat, lon)

    suspend fun plan(context: Context, from: Place, to: Place, at: LocalDateTime?, arriveBy: Boolean): List<PlanOption> = coroutineScope {
        val now = LocalDateTime.now(ZONE)
        val target = at ?: now
        // Só as próximas horas (a API não tem o horário de amanhã)
        val ahead = Duration.between(now, target).toMinutes()
        if (ahead > (if (arriveBy) 240 else 180)) return@coroutineScope emptyList()

        RegionalRepository.init(context)
        val stops = RegionalRepository.elevenStops()
        if (stops.isEmpty()) return@coroutineScope emptyList()
        val nearA = stops.map { (op, s) -> Triple(op, s, metres(from, s.lat, s.lon)) }.filter { it.third <= NEAR_M }
        if (nearA.isEmpty()) return@coroutineScope emptyList()
        val nearB = stops.map { (op, s) -> Triple(op, s, metres(to, s.lat, s.lon)) }.filter { it.third <= NEAR_M }
        val codes = nearA.map { it.first.code }.toSet() intersect nearB.map { it.first.code }.toSet()
        if (codes.isEmpty()) return@coroutineScope emptyList()
        // Paragem de chegada → metros a pé até ao destino, por rede
        val destinations = nearB.filter { it.first.code in codes }.groupBy { it.first.code }
            .mapValues { (_, list) -> list.associate { it.second.id to it.third } }

        val origins = nearA.filter { it.first.code in codes }.sortedBy { it.third }.distinctBy { it.first.code to it.second.id }.take(MAX_ORIGIN_STOPS)
        val nowSec = now.toLocalTime().toSecondOfDay()
        fun relSecs(sec: Int): Int { var d = sec - nowSec; if (d < -12 * 3600) d += 24 * 3600; return d }

        val candidates = origins.map { (op, s, m) ->
            async { RegionalRepository.elevenStopPasses(op.code, s.id).flatMap { r -> r.passes.map { p -> Candidate(op, s, m, r, p) } } }
        }.awaitAll().flatten()
            .filter { relSecs(it.pass.seconds) >= -60 }
            .distinctBy { "${it.op.code}|${it.route.id}|${it.pass.journeyId}" }
            .sortedBy { relSecs(it.pass.seconds) }
            .take(MAX_JOURNEYS)

        candidates.map { c ->
            async {
                val calls = RegionalRepository.elevenJourney(c.op.code, c.route.id, c.pass.journeyId)
                val dests = destinations[c.op.code] ?: return@async null
                val bi = calls.indexOfFirst { it.stopId == c.stop.id }.takeIf { it >= 0 } ?: return@async null
                // A melhor paragem para sair: a que deixa chegar mais cedo ao destino (autocarro + a pé)
                val ai = (bi + 1 until calls.size).filter { calls[it].stopId in dests }
                    .minByOrNull { calls[it].arrival + Walk.minutes(dests.getValue(calls[it].stopId)) * 60 } ?: return@async null
                option(c, calls, bi, ai, dests.getValue(calls[ai].stopId), from, to, now, nowSec)
            }
        }.awaitAll().filterNotNull()
            .filter { o -> if (arriveBy) !o.arrive.isAfter(target) else !o.transit.first().depart.isBefore(target.minusMinutes(1)) }
            .distinctBy { it.key }
    }

    private fun option(
        c: Candidate, calls: List<RegionalParsers.ElevenCall>, bi: Int, ai: Int, walkEndM: Double,
        from: Place, to: Place, now: LocalDateTime, nowSec: Int
    ): PlanOption {
        val today = now.toLocalDate().atStartOfDay()
        fun at(sec: Int): LocalDateTime {
            var t = today.plusSeconds(sec.toLong())
            if (sec - nowSec < -12 * 3600) t = t.plusDays(1)
            return t
        }
        val board = calls[bi]
        val alight = calls[ai]
        val depart = at(board.departure)
        val arrive = at(alight.arrival).let { if (it.isBefore(depart)) it.plusDays(1) else it }
        val last = calls.last()
        val circular = calls.first().stopId == last.stopId
        val towards = if (circular) c.route.name.ifEmpty { c.route.label } else last.name
        val color = c.route.color ?: c.op.color
        val legs = ArrayList<PlanLeg>()
        val walkA = Walk.minutes(c.walkM)
        if (walkA > 0) {
            legs += PlanLeg(
                mode = LegMode.WALK, depart = depart.minusMinutes(walkA.toLong()), arrive = depart,
                fromName = from.name, toName = board.name, label = "A pé", detail = TripPlanner.distanceText(c.walkM),
                fromLat = from.lat, fromLon = from.lon, toLat = board.lat, toLon = board.lon, metres = c.walkM
            )
        }
        val count = ai - bi
        legs += PlanLeg(
            mode = LegMode.BUS, depart = depart, arrive = arrive, fromName = board.name, toName = alight.name,
            label = c.route.label, color = color,
            detail = listOf(c.op.name, "sentido $towards", if (count == 1) "1 paragem" else "$count paragens").joinToString(" · "),
            agency = c.op.name,
            busBoardLat = board.lat, busBoardLon = board.lon, busAlightStop = alight.stopId,
            busAlightLat = alight.lat, busAlightLon = alight.lon, busDestination = towards,
            fromLat = board.lat, fromLon = board.lon, toLat = alight.lat, toLon = alight.lon
        )
        val walkB = Walk.minutes(walkEndM)
        if (walkB > 0) {
            legs += PlanLeg(
                mode = LegMode.WALK, depart = arrive, arrive = arrive.plusMinutes(walkB.toLong()),
                fromName = alight.name, toName = to.name, label = "A pé", detail = TripPlanner.distanceText(walkEndM),
                fromLat = alight.lat, fromLon = alight.lon, toLat = to.lat, toLon = to.lon, metres = walkEndM
            )
        }
        return PlanOption(legs)
    }
}
