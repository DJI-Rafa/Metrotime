package com.example.metrotime.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Represents the estimated real-world position of a single train.
 */
data class TrainPosition(
    val id: String,              // Unique key: "stationId-destination"
    val lat: Double,
    val lon: Double,
    val line: String,
    val destination: String,
    val nextStationName: String,
    val secondsToNextStation: Int
)

/**
 * Fetches arrivals from every station in parallel and infers train positions
 * by interpolating backwards along the route based on arrival time.
 */
class TrainPositionEngine(private val context: Context) {

    private val arrivalsSource = RemoteArrivalsSource(context)
    private val stationCoords = MapConfiguration.STATION_COORDS
    private val lineStationOrder = MapConfiguration.LINE_STATION_ORDER
    private val segmentSeconds = MapConfiguration.SEGMENT_TRAVEL_SECONDS.toDouble()

    suspend fun fetchAllTrainPositions(): List<TrainPosition> = withContext(Dispatchers.IO) {
        val allStations = StationsRepo.loadStations(context)

        // Query all stations concurrently
        val deferredResults = allStations.map { station ->
            async {
                try {
                    arrivalsSource.fetchArrivals(station.apiId)
                } catch (e: Exception) {
                    null
                }
            }
        }

        val results = deferredResults.awaitAll().filterNotNull()
        val trains = mutableMapOf<String, TrainPosition>()

        for (stationArrivals in results) {
            val station = stationArrivals.station
            val stationCoord = stationCoords[station.apiId] ?: continue

            for (arrival in stationArrivals.arrivals) {
                val destination = arrival.direction
                val secondsToStation = arrival.minutes
                val trainId = "${station.apiId}-$destination"

                // Resolve the line and ordered stops for this destination
                val (lineName, orderedStops) = MapConfiguration.DESTINATION_TO_ORDERED_STOPS[destination]
                    ?: resolveLineFromStation(station, destination) ?: continue

                val stationIndexInRoute = orderedStops.indexOf(station.apiId)
                if (stationIndexInRoute < 0) continue // Station not on this route direction

                // Estimate position: train is between prevStation and thisStation
                val progress = max(0.0, min(1.0, 1.0 - (secondsToStation / segmentSeconds)))
                val prevIndex = stationIndexInRoute - 1

                val trainLat: Double
                val trainLon: Double

                if (prevIndex < 0) {
                    // Terminal — train is at or before the terminus
                    trainLat = stationCoord.lat
                    trainLon = stationCoord.lon
                } else {
                    val prevStationId = orderedStops[prevIndex]
                    val prevCoord = stationCoords[prevStationId]
                    if (prevCoord != null) {
                        trainLat = prevCoord.lat + (stationCoord.lat - prevCoord.lat) * progress
                        trainLon = prevCoord.lon + (stationCoord.lon - prevCoord.lon) * progress
                    } else {
                        trainLat = stationCoord.lat
                        trainLon = stationCoord.lon
                    }
                }

                val existingTrain = trains[trainId]
                // Keep the train that has the smallest positive time to next station
                if (existingTrain == null || secondsToStation < existingTrain.secondsToNextStation) {
                    trains[trainId] = TrainPosition(
                        id = trainId,
                        lat = trainLat,
                        lon = trainLon,
                        line = lineName,
                        destination = destination,
                        nextStationName = stationCoord.name,
                        secondsToNextStation = secondsToStation
                    )
                }
            }
        }

        trains.values.toList()
    }

    /**
     * Fallback: try to resolve line from the station's own line field if not in DESTINATION map.
     */
    private fun resolveLineFromStation(
        station: Station,
        destination: String
    ): Pair<String, List<String>>? {
        val lineNames = station.line.split(" ").map { it.trim() }
        for (lineName in lineNames) {
            val orderedStops = lineStationOrder[lineName] ?: continue
            if (orderedStops.contains(station.apiId)) {
                return Pair(lineName, orderedStops)
            }
        }
        return null
    }
}
