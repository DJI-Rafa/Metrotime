package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CpRailTest {

    private val day = LocalDate.of(2026, 10, 24)

    // ── Serviços ────────────────────────────────────────────────────────────

    @Test
    fun serviceCodesFromBothApis() {
        assertEquals("AP", CpService.shortName("1"))
        assertEquals("IC", CpService.shortName("2"))
        assertEquals("IR", CpService.shortName("3"))
        assertEquals("R", CpService.shortName("4"))
        assertEquals("R", CpService.shortName("40"))
        assertEquals("U", CpService.shortName("45"))
        assertEquals("U", CpService.shortName("55"))
        assertEquals("IN", CpService.shortName("12"))
        assertEquals("?", CpService.shortName(null))
    }

    @Test
    fun delayTonesMatchCpBot() {
        assertEquals(CpTone.IDLE, CpTone.ofSeconds(null))
        assertEquals(CpTone.ON_TIME, CpTone.ofSeconds(89))
        assertEquals(CpTone.LATE, CpTone.ofSeconds(90))
        assertEquals(CpTone.LATE, CpTone.ofSeconds(300))
        assertEquals(CpTone.VERY_LATE, CpTone.ofSeconds(301))
        assertEquals(CpTone.IDLE, CpTone.ofSeconds(600, hasRealtime = false))
    }

    // ── Relógio das viagens ─────────────────────────────────────────────────

    @Test
    fun timesAfterMidnightMoveToNextDay() {
        val dates = CpTripClock.datesFor(day, listOf("23:40", "23:58", "00:12", null, "00:40"))
        assertEquals(LocalDateTime.of(day, LocalTime.of(23, 40)), dates[0])
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(0, 12)), dates[2])
        assertNull(dates[3])
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(0, 40)), dates[4])
    }

    @Test
    fun earlyMorningTrainStaysOnSameDay() {
        // A regra antiga ("antes das 4h é +24h") punha este comboio no dia seguinte
        val dates = CpTripClock.datesFor(day, listOf("03:50", "04:10", "05:00"))
        assertEquals(LocalDateTime.of(day, LocalTime.of(3, 50)), dates[0])
        assertEquals(LocalDateTime.of(day, LocalTime.of(5, 0)), dates[2])
    }

    private fun stop(code: String, arr: String?, dep: String?, eta: String? = null, etd: String? = null) =
        CpTrainStop(station = CpGenericEntity(code, code), arrival = arr, departure = dep, eta = eta, etd = etd)

    @Test
    fun progressBetweenStopsUsesRealTimes() {
        val stops = listOf(
            stop("A", null, "10:00", etd = "10:00"),
            stop("B", "10:10", "10:11", eta = "10:20", etd = "10:21"),
            stop("C", "10:30", null, eta = "10:40")
        )
        val m = CpTripClock.moments(day, stops)
        assertEquals(-1f, CpTripClock.progress(m, LocalDateTime.of(day, LocalTime.of(9, 50))), 0.001f)
        // 10 minutos depois da partida, a meio do caminho até B (chegada real às 10:20)
        assertEquals(0.5f, CpTripClock.progress(m, LocalDateTime.of(day, LocalTime.of(10, 10))), 0.01f)
        // Parado em B (chegou às 10:20, parte às 10:21)
        assertEquals(1f, CpTripClock.progress(m, LocalDateTime.of(day, LocalTime.of(10, 20, 30))), 0.001f)
        assertEquals(2f, CpTripClock.progress(m, LocalDateTime.of(day, LocalTime.of(11, 0))), 0.001f)
    }

    @Test
    fun stopWithoutRealTimeTakesNeighbourDelay() {
        // Como o AP 180 em Lisboa - Oriente: sem ETA, entre paragens com ~20 min de atraso.
        // Antes ficava com a hora prevista (08:23) e o comboio aparecia já depois de Oriente.
        val stops = listOf(
            stop("SANTAREM", "07:40", "07:41", eta = "08:00", etd = "08:01"), // +20
            stop("ORIENTE", "08:23", "08:24"),                                 // sem hora real
            stop("ENTRECAMPOS", "08:31", "08:32", eta = "08:53", etd = "08:54") // +22
        )
        val m = CpTripClock.moments(day, stops)
        assertTrue(m[1].estimated)
        assertEquals(LocalDateTime.of(day, LocalTime.of(8, 44)), m[1].arrival) // 08:23 + 21
        assertFalse(m[0].estimated)
        // Às 08:35 ainda vai antes de Oriente
        val pos = CpTripClock.progress(m, LocalDateTime.of(day, LocalTime.of(8, 35)))
        assertTrue("pos = $pos", pos > 0f && pos < 1f)
    }

    @Test
    fun onTimeTrainWithoutRealTimesKeepsSchedule() {
        val stops = listOf(stop("A", null, "10:00"), stop("B", "10:10", null))
        val m = CpTripClock.moments(day, stops)
        assertEquals(LocalDateTime.of(day, LocalTime.of(10, 10)), m[1].arrival)
        assertFalse(m[1].estimated)
    }

    // ── Pesquisa A → B ──────────────────────────────────────────────────────

    private fun timed(train: Int, arr: String?, dep: String?, delay: Int? = null, start: String = "09:55") =
        CpTimedStop(CpStationStop(trainNumber = train, arrivalTime = arr, departureTime = dep, delay = delay), day, LocalTime.parse(start))

    @Test
    fun journeyJoinsBothTimetablesByTrain() {
        val origin = listOf(timed(1, null, "10:00"), timed(2, null, "10:30"), timed(3, null, "11:00"))
        val destination = listOf(timed(1, "10:45", null), timed(3, "11:50", null), timed(9, "10:40", null))
        val result = CpJourney.match(origin, destination, LocalDateTime.of(day, LocalTime.of(9, 55)))
        assertEquals(listOf(1, 3), result.map { it.trainNumber })
        assertEquals(45, result[0].duration.toMinutes())
    }

    @Test
    fun journeyIgnoresTrainsGoingTheOtherWay() {
        // O comboio 1 passa no "destino" antes de passar na origem: é do sentido contrário
        val origin = listOf(timed(1, "10:30", "10:31"))
        val destination = listOf(timed(1, "10:00", "10:01"))
        assertTrue(CpJourney.match(origin, destination, LocalDateTime.of(day, LocalTime.of(9, 55))).isEmpty())
    }

    @Test
    fun journeyKeepsDelayedTrainThatHasNotLeft() {
        val origin = listOf(timed(5, null, "10:00", delay = 15))
        val destination = listOf(timed(5, "10:40", null))
        val result = CpJourney.match(origin, destination, LocalDateTime.of(day, LocalTime.of(10, 10)))
        assertEquals(1, result.size)
        assertEquals(15, result[0].delayMinutes)
    }

    // ── Traçados ────────────────────────────────────────────────────────────

    @Test
    fun polylineDecodesGoogleExample() {
        val points = CpTrack.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].first, 1e-5)
        assertEquals(-120.2, points[0].second, 1e-5)
        assertEquals(43.252, points[2].first, 1e-5)
        assertEquals(-126.453, points[2].second, 1e-5)
    }

    @Test
    fun trackLocatesAndSlices() {
        val track = CpTrack(listOf(38.70 to -9.10, 38.71 to -9.10, 38.72 to -9.10))
        val total = track.along.last()
        assertEquals(2226.0, total, 5.0) // 0.02° de latitude ≈ 2,2 km
        val hit = track.locate(38.705 to -9.1005)
        assertEquals(total / 4, hit.s, 10.0)
        val part = track.slice(0.0, total / 2)
        assertEquals(38.71, part.last().first, 1e-4)
    }

    @Test
    fun gpsProgressFollowsTheMap() {
        // Três paragens numa linha reta N-S, ~1,1 km entre cada uma
        val stops = listOf(38.70 to -9.10, 38.71 to -9.10, 38.72 to -9.10)
        val track = CpTrack(stops)
        val at = track.stopPositions(stops)
        assertEquals(0.5f, track.progressOf(at, 38.705 to -9.1003)!!, 0.02f)
        assertEquals(1.5f, track.progressOf(at, 38.715 to -9.0997)!!, 0.02f)
        // Antes da primeira / depois da última paragem
        assertEquals(0f, track.progressOf(at, 38.6995 to -9.10)!!, 0.001f)
        assertEquals(2f, track.progressOf(at, 38.7205 to -9.10)!!, 0.001f)
        // GPS a quilómetros do percurso: não serve
        assertNull(track.progressOf(at, 38.705 to -9.05))
    }

    @Test
    fun bearingsAndAngles() {
        assertEquals(0.0, CpTrack.bearing(38.7 to -9.1, 38.8 to -9.1), 0.5)
        assertEquals(90.0, CpTrack.bearing(38.7 to -9.1, 38.7 to -9.0), 0.5)
        assertEquals(20.0, CpTrack.angleBetween(350.0, 10.0), 1e-9)
        assertEquals(180.0, CpTrack.angleBetween(0.0, 180.0), 1e-9)
    }

    // ── Avisos ──────────────────────────────────────────────────────────────

    @Test
    fun noticeConcernsLineOrStation() {
        val notice = CpNotice(
            title = "Linha de Cascais - Obras de modernização",
            categories = listOf("Urbanos de Lisboa", "Linha de Cascais"),
            end = LocalDate.of(2026, 11, 8)
        )
        assertTrue(notice.concerns("Oeiras", "Linha de Cascais"))
        assertFalse(notice.concerns("Coimbra-B", "Linha do Norte"))
        assertTrue(CpNotice(title = "Transbordo em Coimbra-B").concerns("Coimbra-B", null))
        assertTrue(notice.isCurrent(LocalDate.of(2026, 11, 8)))
        assertFalse(notice.isCurrent(LocalDate.of(2026, 11, 9)))
    }

    // ── Janela de horários (já existente, sem testes até agora) ─────────────

    @Test
    fun timetableWindowAsksNextDayNearMidnight() {
        val queries = CpTimetableWindow.queriesFor(LocalDateTime.of(day, LocalTime.of(22, 0)))
        assertEquals(2, queries.size)
        assertEquals(day.plusDays(1), queries[1].date)
        assertEquals(1, CpTimetableWindow.queriesFor(LocalDateTime.of(day, LocalTime.of(10, 0))).size)
    }

    @Test
    fun resolveDateMovesEarlyTimesForward() {
        assertEquals(day.plusDays(1), CpTimetableWindow.resolveDate(day, LocalTime.of(21, 0), LocalTime.of(0, 30)))
        assertEquals(day, CpTimetableWindow.resolveDate(day, LocalTime.of(9, 0), LocalTime.of(8, 0)))
    }

    @Test
    fun ghostTrainsAreFiltered() {
        assertFalse(CpVehicleFilter.isActive("COMPLETED", day.toString(), 0, true, day))
        assertFalse(CpVehicleFilter.isActive("AT_STATION", day.minusDays(2).toString(), 0, true, day))
        assertTrue(CpVehicleFilter.isActive("IN_TRANSIT", day.minusDays(1).toString(), 60, true, day))
        assertFalse(CpVehicleFilter.isActive("NOT_STARTED", day.toString(), 0, false, day))
    }
}
