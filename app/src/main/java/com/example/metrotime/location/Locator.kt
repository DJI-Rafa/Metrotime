package com.example.metrotime.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    /** Erro provável em metros (no subterrâneo vem da rede, com centenas de metros). */
    val accuracyM: Float = 0f,
    val timeMs: Long = System.currentTimeMillis()
)

object Locator {
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? {
        val loc = getRawLocation(context) ?: return null
        return loc.latitude to loc.longitude
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationWithSpeed(context: Context): LocationResult? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() ?: return null
        return LocationResult(
            loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else -1f,
            if (loc.hasAccuracy()) loc.accuracy else 9_999f, System.currentTimeMillis()
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun getRawLocation(context: Context): android.location.Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
    }
}
