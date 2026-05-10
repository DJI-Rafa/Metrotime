package com.example.metrotime.data.boat

data class BoatArrival(
    val destination: String,
    val serviceName: String, // e.g. "Transtejo" or "Soflusa"
    val scheduledTime: String, // e.g. "10:30"
    val etaMinutes: Int
)
