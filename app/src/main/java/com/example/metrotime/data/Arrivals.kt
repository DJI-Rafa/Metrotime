package com.example.metrotime.data

// Simplified, clean data class for use in the app
data class StationArrivals(
    val station: Station,
    val arrivals: List<Arrival>,
    val fetchedAtMillis: Long
)

// Simplified, clean data class for a single arrival
data class Arrival(
    val direction: String,
    val minutes: Int,
    val line: String = ""
)

data class LineArrivals(
    val line: String,
    val arrivals: List<LineArrival>
)

data class LineArrival(
    val stationId: String,
    val trainId: String,
    val destinationId: String,
    val destinationName: String,
    val secondsToArrive: Int
)
