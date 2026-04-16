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
    val minutes: Int
)
