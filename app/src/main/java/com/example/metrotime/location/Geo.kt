package com.example.metrotime.location

import com.example.metrotime.data.Station
import kotlin.math.*

object Geo {
    private const val R = 6371000.0
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
    fun nearestStation(lat: Double, lon: Double, list: List<Station>): Station? =
        list.minByOrNull { haversine(lat, lon, it.lat, it.lon) }
}
