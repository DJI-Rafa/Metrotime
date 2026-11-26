package com.example.metrotime.work

import com.example.metrotime.data.bus.BusMapRepository.StopArrivalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusFollowTest {

    private val now = 1_800_000_000_000L

    private fun follow(scheduled: String? = "20:07", vehicle: String? = null, dueInMin: Int = 22, destination: String = "Portela") =
        BusFollow.Followed(
            operator = "carris", stopId = "1234", stopName = "Restelo (Torres)", stopLat = 38.7, stopLon = -9.2,
            line = "728", destination = destination, vehicleId = vehicle, scheduledTime = scheduled,
            dueAt = now + dueInMin * 60_000L
        )

    private fun arrival(line: String = "728", eta: Int, scheduled: String?, vehicle: String? = null, realtime: Boolean = vehicle != null, destination: String = "Portela") =
        StopArrivalInfo(
            routeShortName = line, routeLongName = "", destination = destination, etaMinutes = eta, vehicleId = vehicle,
            operator = "carris", realtime = realtime, scheduledTime = scheduled
        )

    @Test
    fun eachBellIsForItsOwnBus() {
        // Dois 728 para a Portela: o sino do das 20:07 não pode acender no das 19:47
        val f = follow(scheduled = "20:07")
        assertTrue(BusFollow.matches(f, arrival(eta = 22, scheduled = "20:07"), now))
        assertFalse(BusFollow.matches(f, arrival(eta = 2, scheduled = "19:47"), now))
        assertFalse(BusFollow.matches(f, arrival(line = "727", eta = 22, scheduled = "20:07"), now))
        assertFalse(BusFollow.matches(f, arrival(eta = 22, scheduled = "20:07", destination = "Restelo"), now))
    }

    @Test
    fun followsTheGpsOnceKnown() {
        // Com GPS conta o autocarro, mesmo que a hora do horário já não bata (vai muito atrasado)
        val f = follow(scheduled = "19:33", vehicle = "4321")
        assertTrue(BusFollow.matches(f, arrival(eta = 16, scheduled = "19:35", vehicle = "4321"), now))
    }

    @Test
    fun withoutScheduleMatchesNearTheExpectedTime() {
        val f = follow(scheduled = null, dueInMin = 10)
        assertTrue(BusFollow.matches(f, arrival(eta = 12, scheduled = null), now))
        assertFalse(BusFollow.matches(f, arrival(eta = 30, scheduled = null), now))
    }

    @Test
    fun idStaysTheSameWhenTheEstimateChanges() {
        val f = follow(scheduled = null, dueInMin = 10)
        assertEquals(f.id, f.copy(dueAt = f.dueAt + 5 * 60_000L, lastEta = 4).id)
        assertNotEquals(follow(scheduled = "20:07").id, follow(scheduled = "20:26").id)
    }
}
