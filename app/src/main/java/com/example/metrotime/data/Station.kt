package com.example.metrotime.data

data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val line: String,
    val apiId: String
)
