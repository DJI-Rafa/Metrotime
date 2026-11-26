package com.example.metrotime.data.bus

import android.content.Context
import android.location.Location
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** A bus arrival to show in the UI */
data class BusArrival(
    val routeShortName: String,   // e.g. "728"
    val destination: String,       // e.g. "Restelo - Av. Descobertas"
    val stopName: String,          // e.g. "Cais do Sodré"
    val distanceMeters: Int,       // distance of bus to the stop
    val etaMinutes: Int,
    val vehicleId: String? = null
)

object GtfsRadarService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val VP_URL =
        "https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS/realtime/vehiclepositions"

    private data class NearbyStop(
        val stop: CarrisGtfsData.StopEntry,
        val distToMetro: Int,
        val routeIds: List<String>
    )

    /**
     * Find bus stops within [radiusMeters] of the metro station,
     * then scan live vehicle positions to find buses approaching those stops.
     */
    suspend fun getBusesNearStation(
        context: Context,
        stationLat: Double,
        stationLon: Double,
        radiusMeters: Int = 200
    ): List<BusArrival> = withContext(Dispatchers.IO) {
        try {
            // Use shared GTFS data singleton
            CarrisGtfsData.ensureLoaded(context)

            val metroLoc = Location("").apply {
                latitude = stationLat
                longitude = stationLon
            }

            // Step 1: Find bus stops near the metro station
            val nearbyStops = CarrisGtfsData.stops.mapNotNull { stop ->
                val stopLoc = Location("").apply {
                    latitude = stop.lat
                    longitude = stop.lon
                }
                val dist = metroLoc.distanceTo(stopLoc).roundToInt()
                if (dist <= radiusMeters) {
                    NearbyStop(stop, dist, CarrisGtfsData.stopRoutes[stop.id] ?: emptyList())
                } else null
            }

            if (nearbyStops.isEmpty()) return@withContext emptyList()

            // Collect ALL route_ids that serve our nearby stops
            val relevantRouteIds = nearbyStops.flatMap { it.routeIds }.toSet()
            val normalizedRelevantIds = relevantRouteIds.map { BusMapRepository.normalizeRouteId(it) }.toSet()

            // Step 2: Download live vehicle positions
            val request = Request.Builder().url(VP_URL).build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (!response.isSuccessful || response.body == null) {
                return@withContext emptyList()
            }

            val feed = response.body!!.byteStream().use { FeedMessage.parseFrom(it) }

            // Step 3: Match vehicles to nearby stops
            // Track per route+direction to show the closest bus for each
            val bestPerRouteDir = mutableMapOf<String, BusArrival>()

            for (entity in feed.entityList) {
                if (!entity.hasVehicle()) continue
                val vehicle = entity.vehicle
                if (!vehicle.hasPosition()) continue

                // Get route info - try trip first, then fallback to route_id
                val tripId = if (vehicle.hasTrip() && vehicle.trip.hasTripId()) vehicle.trip.tripId else null
                val tripInfo = tripId?.let { CarrisGtfsData.trip(it) }
                val routeId = tripInfo?.routeId
                    ?: (if (vehicle.hasTrip() && vehicle.trip.hasRouteId()) vehicle.trip.routeId else null)
                    ?: continue

                // Only care about routes that serve our nearby stops
                val normalizedRouteId = BusMapRepository.normalizeRouteId(routeId)
                if (normalizedRouteId !in normalizedRelevantIds) continue

                val routeInfo = CarrisGtfsData.routes[normalizedRouteId] ?: continue
                val direction = tripInfo?.direction ?: 0

                val pos = vehicle.position
                val vehLoc = Location("").apply {
                    latitude = pos.latitude.toDouble()
                    longitude = pos.longitude.toDouble()
                }

                // Find the closest nearby stop that this route serves
                val matchingStops = nearbyStops.filter { ns -> 
                    ns.routeIds.any { BusMapRepository.normalizeRouteId(it) == normalizedRouteId }
                }
                if (matchingStops.isEmpty()) continue

                val bestStop = matchingStops.minByOrNull { ns ->
                    val sLoc = Location("").apply {
                        latitude = ns.stop.lat
                        longitude = ns.stop.lon
                    }
                    vehLoc.distanceTo(sLoc)
                }!!

                val targetStopLoc = Location("").apply {
                    latitude = bestStop.stop.lat
                    longitude = bestStop.stop.lon
                }
                val distToStop = vehLoc.distanceTo(targetStopLoc).roundToInt()

                // Only show buses within 5km of the target stop
                if (distToStop > 5000) continue

                // Determine destination from route long_name based on direction
                val destination = extractDestination(routeInfo.longName, direction)

                // ETA: layered speed model
                val etaSeconds = when {
                    distToStop < 50 -> 0f
                    distToStop < 300 -> distToStop / 2.5f
                    distToStop < 1500 -> distToStop / 2.78f
                    else -> distToStop / 5.0f
                }
                val etaMins = (etaSeconds / 60f).roundToInt()

                val vId = if (vehicle.hasVehicle() && vehicle.vehicle.hasId()) vehicle.vehicle.id else null

                val arrival = BusArrival(
                    routeShortName = routeInfo.shortName,
                    destination = destination,
                    stopName = bestStop.stop.name,
                    distanceMeters = distToStop,
                    etaMinutes = maxOf(0, etaMins),
                    vehicleId = vId
                )

                // Keep the closest bus per route+direction combo
                val key = "${routeInfo.shortName}_${direction}"
                val existing = bestPerRouteDir[key]
                if (existing == null || arrival.distanceMeters < existing.distanceMeters) {
                    bestPerRouteDir[key] = arrival
                }
            }

            bestPerRouteDir.values.sortedBy { it.etaMinutes }

        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Extract destination from route long_name based on direction.
     * Format examples:
     *   "Cais Sodré - Outurela" -> dir 0: "Outurela", dir 1: "Cais Sodré"
     *   "Restelo - Portela"     -> dir 0: "Portela",  dir 1: "Restelo"
     */
    private fun extractDestination(longName: String, direction: Int): String {
        if (" - " !in longName) return longName
        val parts = longName.split(" - ")
        return if (parts.size >= 2) {
            if (direction == 0) parts.last().trim()
            else parts.first().trim()
        } else {
            longName
        }
    }
}
