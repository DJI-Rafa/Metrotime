package com.example.metrotime.data

import kotlin.random.Random

interface ArrivalsSource {
    suspend fun fetchArrivals(stationId: String): StationArrivals
    suspend fun fetchLineArrivals(linha: String): LineArrivals
}

class MockArrivalsSource : ArrivalsSource {
    override suspend fun fetchArrivals(stationId: String): StationArrivals {
        // This is a mock implementation and does not use the stationId
        val arrivals = mutableListOf<Arrival>()
        arrivals.add(Arrival("Mock Destination 1", Random.nextInt(1, 10)))
        arrivals.add(Arrival("Mock Destination 2", Random.nextInt(10, 20)))
        // The station object here is a dummy one
        return StationArrivals(Station("", "Mock Station", 0.0, 0.0, "", ""), arrivals, System.currentTimeMillis())
    }

    override suspend fun fetchLineArrivals(linha: String): LineArrivals {
        return LineArrivals(linha, emptyList())
    }
}
