package com.example.metrotime.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StatsCalcTest {

    private val zone = ZoneId.of("Europe/Lisbon")
    private val today = LocalDate.of(2026, 10, 25) // domingo

    private fun ms(day: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** Viagem de metro de [from] para [to] (ids da API), a começar a [hour] e a durar [minutes]. */
    private fun metro(day: LocalDate, hour: Int, from: String = "AM", to: String? = "BC", minutes: Long = 12) =
        if (to == null) Trip("o$day$hour$from", "metro", from, com.example.metrotime.data.MetroLines.nameOf(from), lines = listOf("Verde"), startMs = ms(day, hour), source = "manual")
        else TripRules.metroTrip("m$day$hour$from", from, to, ms(day, hour), ms(day, hour) + minutes * 60_000, "auto")

    private fun cp(day: LocalDate, hour: Int, km: Double, service: String? = null, number: String? = null) =
        Trip("c$day$hour", "cp", "94-31039", "Lisboa - Oriente", "94-30007", "Lisboa - St. Apolónia", listOf(service ?: "CP"), ms(day, hour), ms(day, hour) + 20 * 60_000, km, "train", service = service, trainNumber = number)

    @Test
    fun weekCountsTheLastSevenDaysAndComparesWithTheSevenBefore() {
        val trips = listOf(
            metro(today, 8), metro(today.minusDays(1), 18),
            metro(today.minusDays(6), 9),
            metro(today.minusDays(7), 9) // já é da semana anterior
        )
        val s = StatsCalc.summary(trips, StatsCalc.Period.WEEK, today, zone)
        assertEquals(3, s.total)
        assertEquals(StatsCalc.Change("+2 que na semana anterior", 1), s.change)
        assertEquals(listOf("S", "T", "Q", "Q", "S", "S", "D"), s.bars.map { it.label })
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 1), s.bars.map { it.value })
        assertTrue(s.bars.last().current)
        assertEquals(36, s.travelMinutes)
    }

    @Test
    fun monthComparesWithTheSameDaysOfLastMonth() {
        val trips = listOf(
            metro(LocalDate.of(2026, 10, 2), 8), metro(LocalDate.of(2026, 10, 20), 8), metro(LocalDate.of(2026, 10, 24), 8),
            metro(LocalDate.of(2026, 9, 3), 8), metro(LocalDate.of(2026, 9, 10), 8),
            metro(LocalDate.of(2026, 9, 28), 8) // depois do dia 25: não conta
        )
        val s = StatsCalc.summary(trips, StatsCalc.Period.MONTH, today, zone)
        assertEquals(3, s.total)
        assertEquals(StatsCalc.Change("+50% que em setembro", 1), s.change)
        assertEquals(25, s.bars.size)
        assertEquals(listOf("1", "8", "15", "22"), s.bars.map { it.label }.filter { it.isNotEmpty() })
    }

    @Test
    fun allTimeGoesByMonthAndAddsTheOldTrainKm() {
        val trips = listOf(metro(LocalDate.of(2026, 5, 10), 8), cp(LocalDate.of(2026, 10, 1), 8, 7.5))
        val s = StatsCalc.summary(trips, StatsCalc.Period.ALL, today, zone, legacyKm = 12.0)
        assertEquals(listOf("mai", "jun", "jul", "ago", "set", "out"), s.bars.map { it.label })
        assertEquals(StatsCalc.Change("desde maio", 0), s.change)
        // km pela linha do metro + comboio medido + os km antigos (só em "sempre")
        assertEquals(trips[0].km + 7.5 + 12.0, s.km, 0.01)
        val month = StatsCalc.summary(trips, StatsCalc.Period.MONTH, today, zone, legacyKm = 12.0)
        assertEquals(7.5, month.km, 0.01)
    }

    @Test
    fun linesAndStationsComeFromTheTrips() {
        val trips = listOf(
            metro(today, 8, "AM", "BC"),            // Verde
            metro(today, 9, "RB", "OR", 40),        // Azul e Vermelha
            metro(today, 18, "AM", null),           // só a origem (Iniciar viagem)
            cp(today, 10, 5.0)
        )
        val lines = StatsCalc.lineCounts(trips)
        assertEquals("Verde", lines.first().key)
        assertEquals(2, lines.first().count)
        assertTrue(lines.any { it.key == "CP" && it.name == "Comboios CP" })
        assertTrue(lines.any { it.key == "Vermelha" && it.count == 1 })
        val stations = StatsCalc.stationCounts(trips)
        // Alameda: origem duas vezes; cada ponta conta
        assertEquals("Alameda", stations.first().name)
        assertEquals(2, stations.first().count)
        assertEquals(listOf("Verde", "Vermelha"), stations.first().lines)
        assertEquals(listOf("CP"), stations.first { it.type == "cp" }.lines)
        // Alameda, Baixa-Chiado, Reboleira, Oriente (metro), Lisboa - Oriente e Lisboa - St. Apolónia
        assertEquals(6, StatsCalc.summary(trips, StatsCalc.Period.WEEK, today, zone).distinctStations)
    }

    @Test
    fun streakCountsUntilYesterdayAndShowsThisWeek() {
        val trips = listOf(1L, 2, 3, 10, 11, 12, 13).map { metro(today.minusDays(it), 8) }
        val s = StatsCalc.streak(trips, today, zone)
        assertEquals(3, s.current)
        assertEquals(4, s.longest)
        assertEquals(listOf(false, false, false, true, true, true, false), s.week)
        assertEquals(6, s.todayIndex)
        assertEquals(0, StatsCalc.streak(listOf(metro(today.minusDays(2), 8)), today, zone).current)
        assertEquals(9, StatsCalc.streak(trips, today, zone, storedLongest = 9).longest)
    }

    @Test
    fun peakHoursFindTheMorningAndTheEvening() {
        val hours = MutableList(24) { 0 }.apply { this[8] = 9; this[9] = 6; this[18] = 8; this[13] = 3 }
        assertEquals(listOf(8, 18), StatsCalc.peakHours(hours))
        assertEquals("Mais vezes por volta das 8h e das 18h", StatsCalc.peakText(listOf(8, 18)))
        assertEquals(listOf(8), StatsCalc.peakHours(MutableList(24) { 0 }.apply { this[8] = 5; this[18] = 1 }))
        assertEquals(emptyList<Int>(), StatsCalc.peakHours(List(24) { 0 }))
    }

    @Test
    fun formats() {
        assertEquals("45 min", StatsCalc.duration(45))
        assertEquals("6 h 05", StatsCalc.duration(365))
        assertEquals("33 h", StatsCalc.duration(33 * 60 + 20))
        assertEquals("0 km", StatsCalc.distance(0.0))
        assertEquals("850 m", StatsCalc.distance(0.85))
        assertEquals("4,2 km", StatsCalc.distance(4.2))
        assertEquals("86 km", StatsCalc.distance(86.4))
    }

    @Test
    fun trainsAreSplitByService() {
        val trips = listOf(
            cp(today, 7, 337.0, "AP", "131"), cp(today.minusDays(1), 8, 25.0, "U", "18035"),
            cp(today.minusDays(2), 8, 24.0, "U", "18037"), cp(today.minusDays(3), 9, 10.0) // antigo, sem serviço
        )
        val lines = StatsCalc.lineCounts(trips)
        val u = lines.first()
        assertEquals("U", u.key)
        assertEquals("Urbanos", u.name)
        assertEquals(2, u.count)
        assertEquals(49.0, u.km, 0.01)
        assertEquals(40, u.minutes)
        assertTrue(u.isTrain)
        assertEquals("Alfa Pendular", lines.first { it.key == "AP" }.name)
        assertEquals("Comboios CP", lines.first { it.key == "CP" }.name)
    }

    @Test
    fun recentTripsTellWhatWhenAndHowFar() {
        val ap = cp(today, 7, 337.0, "AP", "131")
        assertEquals("Lisboa - Oriente → Lisboa - St. Apolónia", StatsCalc.tripTitle(ap))
        assertEquals("AP 131 · 20 min · 337 km", StatsCalc.tripDetail(ap))
        assertEquals("hoje, 07:00", StatsCalc.whenText(ap, today, zone))
        val m = metro(today.minusDays(1), 18, "RB", "OR", 40)
        assertEquals("Linhas Azul → Vermelha · 40 min · ${StatsCalc.distance(m.km)}", StatsCalc.tripDetail(m))
        assertEquals("ontem, 18:00", StatsCalc.whenText(m, today, zone))
        val origin = metro(today.minusDays(5), 9, "AM", null)
        assertEquals("Alameda", StatsCalc.tripTitle(origin))
        assertEquals("Linha Verde", StatsCalc.tripDetail(origin))
        assertEquals("20 out, 09:00", StatsCalc.whenText(origin, today, zone))
        assertEquals("Comboio · 20 min · 10 km", StatsCalc.tripDetail(cp(today, 9, 10.0)))
    }
}
