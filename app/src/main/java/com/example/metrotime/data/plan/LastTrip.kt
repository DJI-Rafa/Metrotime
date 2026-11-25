package com.example.metrotime.data.plan

import android.content.Context
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.MetroNetwork
import com.example.metrotime.data.cp.CpJourney
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.prefs.Prefs
import java.time.Duration
import java.time.LocalDateTime

/**
 * O último metro e o último comboio de onde estás para casa. O do metro é estimado pela rede (os últimos
 * metros saem dos terminais à 1:00); o da CP vem dos horários.
 */
object LastTrip {

    data class Metro(val fromName: String, val toName: String, val serviceMinutes: Double) {
        val clock: String get() = MetroTrip.clock(serviceMinutes)
    }

    data class Train(val fromName: String, val toName: String, val departure: LocalDateTime, val label: String)

    fun home(context: Context): Place? = Place.decode(Prefs.getHome(context))

    /** Último metro de [from] para [home]; null se um dos dois não tiver uma estação perto ou for a mesma. */
    fun lastMetro(from: Place, home: Place): Metro? {
        val o = TripPlanner.nearestMetro(from)?.first ?: return null
        val d = TripPlanner.nearestMetro(home)?.first ?: return null
        if (o == d) return null
        val route = MetroNetwork.route(o, d) ?: return null
        val latest = MetroTrip.latestDeparture(route) ?: return null
        return Metro(MetroLines.nameOf(o), MetroLines.nameOf(d), latest)
    }

    /** Último comboio direto de [from] para [home] até ao fim do dia de serviço (3h). */
    suspend fun lastTrain(context: Context, from: Place, home: Place, now: LocalDateTime): Train? {
        val a = TripPlanner.nearestCp(context, from)?.first ?: return null
        val b = TripPlanner.nearestCp(context, home)?.first ?: return null
        if (a.code == b.code) return null
        val end = (if (now.hour < 3) now.toLocalDate() else now.toLocalDate().plusDays(1)).atTime(3, 0)
        val hours = Duration.between(now, end).toHours().coerceIn(1, 22)
        val origin = CpRepository.getTimetableWindow(a.code, now, aheadHours = hours).stops
        val dest = CpRepository.getTimetableWindow(b.code, now, aheadHours = hours + 2).stops
        val last = CpJourney.match(origin, dest, now)
            .filter { !it.suppressed && it.departure.isBefore(end) }
            .maxByOrNull { it.departure } ?: return null
        return Train(
            fromName = formatStationName(a.name),
            toName = formatStationName(b.name),
            departure = last.departure.plusMinutes((last.delayMinutes ?: 0).toLong()),
            label = "${CpService.shortName(last.serviceCode)} ${last.trainNumber}"
        )
    }
}
