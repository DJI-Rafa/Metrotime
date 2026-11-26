package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A conta com a hora do GPS e a última passagem registada pela CP. Percurso de teste: A (parte 10:00) → B (10:10 /
 * 10:11) → C (10:21 / 10:22) → D (10:32), com 10 km entre cada duas estações.
 */
class CpTrainNowLiveTest {

    private val day = LocalDate.of(2026, 11, 23)
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(day, LocalTime.of(h, m, s))
    private fun stop(code: String, arr: String?, dep: String?) =
        CpTrainStop(station = CpGenericEntity(code, code), arrival = arr, departure = dep)

    private val plan = listOf(stop("A", null, "10:00"), stop("B", "10:10", "10:11"), stop("C", "10:21", "10:22"), stop("D", "10:32", null))
    private val meters = listOf(0.0, 10_000.0, 20_000.0, 30_000.0)

    private fun now(
        last: String?, status: String?, gps: CpTrainNow.Fix?, pass: CpTrainNow.Fix?, t: LocalDateTime,
        stopMeters: List<Double?>? = meters
    ) = CpTrainNow.of(plan, CpTripClock.moments(day, plan), last, status, gps, pass, stopMeters, t)

    @Test
    fun theGpsMovesOnForTheTimeThatPassedSinceIt() {
        // GPS às 10:14 a 30 % do troço B → C; às 10:14:30 andou mais 30 s de 600 s: 35 %
        val n = now("B", VehicleStatus.IN_TRANSIT, CpTrainNow.Fix(1.3f, at(10, 14)), CpTrainNow.Fix(1f, at(10, 11)), at(10, 14, 30))
        assertFalse(n.atStation)
        assertEquals(1.35f, n.position, 0.001f)
    }

    @Test
    fun theMostRecentKnownPlaceWins() {
        // A partida de B (10:11) é mais antiga do que o GPS: conta o GPS
        val withGps = now("B", VehicleStatus.IN_TRANSIT, CpTrainNow.Fix(1.2f, at(10, 15)), CpTrainNow.Fix(1f, at(10, 11)), at(10, 15))
        assertEquals(1.2f, withGps.position, 0.001f)
        // Uma passagem mais recente do que o GPS: conta a passagem
        val withPass = now("B", VehicleStatus.IN_TRANSIT, CpTrainNow.Fix(1.2f, at(10, 15)), CpTrainNow.Fix(1.5f, at(10, 16)), at(10, 16))
        assertEquals(1.5f, withPass.position, 0.001f)
    }

    @Test
    fun neverBehindThePlaceTheCpSawItPass() {
        // Passou a 60 % às 10:17; o GPS de 10:17:30 diz 40 % (está errado ou atrasado): fica nos 60 %
        val n = now("B", VehicleStatus.IN_TRANSIT, CpTrainNow.Fix(1.4f, at(10, 17, 30)), CpTrainNow.Fix(1.6f, at(10, 17)), at(10, 17, 40))
        assertEquals(1.6f, n.position, 0.001f)
    }

    @Test
    fun arrivedAndNoDepartureYetMeansItIsStillThere() {
        // A CP registou a chegada a C às 10:23 e ainda não a partida (prevista 10:22): está em C, mesmo com a última
        // estação do GPS ainda em B
        val n = now("B", VehicleStatus.IN_TRANSIT, null, CpTrainNow.Fix(2f, at(10, 23), left = false), at(10, 23, 30))
        assertTrue(n.atStation)
        assertFalse(n.arriving)
        assertEquals(2f, n.position)
    }

    @Test
    fun aRecordedArrivalWithoutDepartureStopsHoldingAfterAWhile() {
        // 4 min depois da partida prevista sem a CP registar a saída: anda pelo relógio (240 s de 600 s)
        val n = now("B", VehicleStatus.IN_TRANSIT, null, CpTrainNow.Fix(2f, at(10, 23), left = false), at(10, 26))
        assertFalse(n.atStation)
        assertEquals(2.4f, n.position, 0.001f)
    }

    @Test
    fun aRecordedDepartureMovesItEvenBeforeThePredictedOne() {
        // Saiu de B às 10:10:30, antes da partida prevista (10:11)
        val n = now("B", VehicleStatus.AT_STATION, null, CpTrainNow.Fix(1f, at(10, 10, 30)), at(10, 10, 50))
        assertFalse(n.atStation)
        assertEquals(1.05f, n.position, 0.001f)
    }

    @Test
    fun aGpsWellPastTheStationMeansItLeft() {
        // A CP ainda o dá parado em B; o GPS de há 10 s já está 600 m depois
        val left = now("B", VehicleStatus.AT_STATION, CpTrainNow.Fix(1.06f, at(10, 10, 30)), null, at(10, 10, 40))
        assertFalse(left.atStation)
        assertTrue(left.position > 1.06f)
        // 300 m é ruído do GPS e o tamanho da estação: continua parado
        val stays = now("B", VehicleStatus.AT_STATION, CpTrainNow.Fix(1.03f, at(10, 10, 30)), null, at(10, 10, 40))
        assertTrue(stays.atStation)
        assertEquals(1f, stays.position)
    }

    @Test
    fun aGpsPastTheNextStationMovesTheTrainOn() {
        // A última estação da CP ainda é B, mas o GPS já vai 2 km depois de C
        val n = now("B", VehicleStatus.IN_TRANSIT, CpTrainNow.Fix(2.2f, at(10, 23)), null, at(10, 23, 10))
        assertEquals(2, n.stopIndex)
        assertEquals(2.2f + 10f / 600f, n.position, 0.001f)
        // Sem o traçado (a conta antiga) fica antes de C, como dantes
        val old = now("B", VehicleStatus.IN_TRANSIT, CpTrainNow.Fix(2.2f, at(10, 23)), null, at(10, 23, 10), stopMeters = null)
        assertEquals(1.95f, old.position, 0.001f)
    }

    @Test
    fun anOldGpsStillCountsAsWhereItWas() {
        // A CP continua a dá-lo parado em B, mas o GPS das 10:12 já estava 1 km depois: dez minutos mais tarde
        // continua a contar como saído (antes de haver dados novos)
        val n = now("B", VehicleStatus.AT_STATION, CpTrainNow.Fix(1.1f, at(10, 12)), null, at(10, 22))
        assertFalse(n.atStation)
    }

    @Test
    fun withoutNewDataItNeverGoesBackwards() {
        val gps = CpTrainNow.Fix(1.3f, at(10, 14))
        val pass = CpTrainNow.Fix(1f, at(10, 11))
        var last = -1f
        var t = at(10, 9)
        while (t.isBefore(at(10, 40))) {
            val p = now("B", VehicleStatus.IN_TRANSIT, gps.takeIf { !it.at.isAfter(t) }, pass.takeIf { !it.at.isAfter(t) }, t).position
            assertTrue("às $t: $p < $last", p >= last - 1e-4f)
            last = p
            t = t.plusSeconds(5)
        }
    }

    @Test
    fun theOldCallStaysTheSame() {
        // Sem hora nem traçado: a conta de antes (o GPS diz o ponto do troço, sem sair da estação)
        val n = CpTrainNow.of(plan, CpTripClock.moments(day, plan), "B", VehicleStatus.IN_TRANSIT, 1.2f, at(10, 10, 30))
        assertTrue(n.atStation)
        assertEquals(1f, n.position)
    }
}
