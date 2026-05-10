package com.example.metrotime.data.boat

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object BoatScheduleRepo {

    private val LISBON_ZONE = ZoneId.of("Europe/Lisbon")

    fun getNextDepartures(stationId: String, currentMillis: Long): List<BoatArrival> {
        if (stationId != "cais_do_sodre" && stationId != "terreiro_do_paco") {
            return emptyList()
        }

        val currentTime = Instant.ofEpochMilli(currentMillis).atZone(LISBON_ZONE)
        
        // A transit day typically ends around 3-4AM. If time is before 4AM, we treat it as previous day's schedule
        var adjustedTime = currentTime
        if (currentTime.hour < 4) {
            adjustedTime = currentTime.minusDays(1)
        }
        
        val dayOfWeek = adjustedTime.dayOfWeek
        val isSaturday = dayOfWeek == DayOfWeek.SATURDAY
        val isSundayOrHoliday = dayOfWeek == DayOfWeek.SUNDAY // Assuming Sunday schedule for holidays implicitly
        val isWeekend = isSaturday || isSundayOrHoliday

        val arrivals = mutableListOf<BoatArrival>()

        if (stationId == "cais_do_sodre") {
            // Cacilhas
            arrivals.addAll(getNextBoats("Cacilhas", "Transtejo", getCacilhasSchedule(isWeekend), currentTime))
            // Seixal
            arrivals.addAll(getNextBoats("Seixal", "Transtejo", getSeixalSchedule(isSaturday, isSundayOrHoliday), currentTime))
            // Montijo
            arrivals.addAll(getNextBoats("Montijo", "Transtejo", getMontijoSchedule(isSaturday, isSundayOrHoliday), currentTime))
        } else if (stationId == "terreiro_do_paco") {
            // Barreiro
            arrivals.addAll(getNextBoats("Barreiro", "Soflusa", getBarreiroSchedule(isWeekend), currentTime))
        }

        return arrivals.sortedBy { it.etaMinutes }
    }

    private fun getNextBoats(
        destination: String, 
        serviceName: String, 
        scheduleOfDay: List<TimePair>, 
        currentTime: ZonedDateTime
    ): List<BoatArrival> {
        val currentTotalMinutes = currentTime.hour * 60 + currentTime.minute
        
        // Find departures that are after current time. Notice that 00:00 to 03:59 are treated as +24h if looking from the previous evening
        return scheduleOfDay.mapNotNull { schedule ->
            var schedTotalMins = schedule.hour * 60 + schedule.minute
            // If the schedule is for past midnight (0-3), and we are currently examining a schedule from the same continuous transit day
            // We consider the schedule hour as hour + 24 so the sorting and diffing is correct.
            if (schedule.hour < 4 && currentTime.hour >= 4) {
                schedTotalMins += 24 * 60
            } else if (schedule.hour >= 4 && currentTime.hour < 4) {
                // we are at 1AM, but schedule is for 23:00. This is in the past, skip
                return@mapNotNull null
            }
            
            val diff = schedTotalMins - currentTotalMinutes
            if (diff in 0..180) { // Look ahead up to 3 hours
                val timeString = String.format("%02d:%02d", schedule.hour, schedule.minute)
                BoatArrival(destination, serviceName, timeString, diff)
            } else {
                null
            }
        }.take(3)
    }

    private data class TimePair(val hour: Int, val minute: Int)

    // Helper to generate times continuously
    private fun gen(hours: IntRange, mins: List<Int>): List<TimePair> {
        return hours.flatMap { h -> mins.map { m -> TimePair(h, m) } }
    }

    private fun getCacilhasSchedule(isWeekend: Boolean): List<TimePair> {
        return if (isWeekend) {
            val times = mutableListOf<TimePair>()
            times.addAll(gen(5..23, listOf(0, 20, 40)).filter { it.hour > 5 || it.minute >= 40 })
            times.addAll(gen(0..1, listOf(0, 20, 40)).filter { it.hour == 0 || it.minute <= 40 })
            times.sortedWith(compareBy({ if (it.hour < 4) it.hour + 24 else it.hour }, { it.minute }))
        } else {
            val t = mutableListOf<TimePair>()
            t.addAll(listOf(TimePair(0, 20), TimePair(1, 0), TimePair(1, 40), TimePair(5, 35)))
            t.addAll(gen(6..6, listOf(0, 15, 30, 45)))
            t.addAll(gen(7..9, listOf(0, 10, 20, 30, 40, 50)))
            t.addAll(gen(10..16, listOf(0, 15, 30, 45)))
            t.addAll(gen(17..19, listOf(0, 10, 20, 30, 40, 50)))
            t.addAll(gen(20..20, listOf(0, 15, 30, 45)))
            t.addAll(gen(21..23, listOf(0, 20, 40)))
            t.sortedWith(compareBy({ if (it.hour < 4) it.hour + 24 else it.hour }, { it.minute }))
        }
    }

    private fun getSeixalSchedule(isSat: Boolean, isSun: Boolean): List<TimePair> {
        if (isSat || isSun) {
            val base = listOf(
                TimePair(8, 0), TimePair(9, 30), TimePair(11, 0), TimePair(13, 0), 
                TimePair(14, 30), TimePair(16, 30), TimePair(18, 0), TimePair(19, 30), TimePair(21, 30)
            )
            return if (isSat) listOf(TimePair(7, 0)) + base else base
        }
        return listOf(
            TimePair(6, 50), TimePair(7, 10), TimePair(7, 40), TimePair(8, 10), TimePair(8, 30),
            TimePair(9, 0), TimePair(9, 30), TimePair(9, 50), TimePair(10, 20), TimePair(10, 50),
            TimePair(11, 40), TimePair(12, 35), TimePair(13, 0), TimePair(14, 40), TimePair(16, 20),
            TimePair(16, 40), TimePair(17, 10), TimePair(17, 40), TimePair(18, 0), TimePair(18, 30),
            TimePair(19, 0), TimePair(19, 20), TimePair(19, 50), TimePair(20, 50), TimePair(22, 30)
        )
    }

    private fun getMontijoSchedule(isSat: Boolean, isSun: Boolean): List<TimePair> {
        if (isSat || isSun) {
            val base = listOf(
                TimePair(8, 0), TimePair(9, 0), TimePair(11, 0), TimePair(13, 0), 
                TimePair(15, 0), TimePair(16, 30), TimePair(18, 0), TimePair(19, 30), 
                TimePair(20, 30), TimePair(22, 0)
            )
            return if (isSat) listOf(TimePair(7, 0)) + base else base
        }
        val t = mutableListOf<TimePair>()
        t.addAll(listOf(TimePair(6, 30), TimePair(7, 30)))
        t.addAll(gen(8..8, listOf(0, 30)))
        t.add(TimePair(9, 0))
        t.add(TimePair(10, 0))
        t.add(TimePair(11, 0))
        t.add(TimePair(12, 30))
        t.add(TimePair(14, 0))
        t.add(TimePair(15, 30))
        t.add(TimePair(16, 30))
        t.addAll(gen(17..19, listOf(0, 30)))
        t.add(TimePair(20, 0))
        t.add(TimePair(21, 0))
        t.add(TimePair(22, 0))
        t.add(TimePair(23, 15))
        return t
    }

    private fun getBarreiroSchedule(isWeekend: Boolean): List<TimePair> {
        if (isWeekend) {
            val times = mutableListOf<TimePair>()
            times.addAll(gen(5..23, listOf(0, 30)).filter { it.hour > 5 || it.minute >= 30 })
            times.addAll(gen(0..1, listOf(0, 30)))
            return times.sortedWith(compareBy({ if (it.hour < 4) it.hour + 24 else it.hour }, { it.minute }))
        }
        
        val t = mutableListOf<TimePair>()
        t.addAll(gen(0..1, listOf(0, 30)))
        t.add(TimePair(2, 0))
        t.addAll(listOf(TimePair(5, 30), TimePair(5, 50)))
        t.addAll(listOf(TimePair(6, 20), TimePair(6, 40)))
        t.addAll(listOf(
            TimePair(7, 0), TimePair(7, 10), TimePair(7, 20), TimePair(7, 25), TimePair(7, 35), TimePair(7, 40), TimePair(7, 50),
            TimePair(8, 0), TimePair(8, 10), TimePair(8, 15), TimePair(8, 25), TimePair(8, 30), TimePair(8, 40), TimePair(8, 50),
            TimePair(9, 0), TimePair(9, 5), TimePair(9, 15), TimePair(9, 25), TimePair(9, 40), TimePair(9, 55)
        ))
        t.addAll(gen(10..15, listOf(25, 55)))
        t.addAll(listOf(TimePair(16, 25), TimePair(16, 45), TimePair(16, 55)))
        t.addAll(gen(17..19, listOf(10, 25, 40, 55)))
        t.addAll(listOf(TimePair(20, 10), TimePair(20, 25), TimePair(20, 55)))
        t.addAll(gen(21..23, listOf(25, 55)))
        
        return t.sortedWith(compareBy({ if (it.hour < 4) it.hour + 24 else it.hour }, { it.minute }))
    }
}
