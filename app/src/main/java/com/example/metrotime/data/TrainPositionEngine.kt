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
    val id: String,              
    val lat: Double,
    val lon: Double,
    val line: String,
    val destination: String,
    val nextStationName: String,
    val secondsToNextStation: Int,
    val bearing: Double
)

/**
 * Fetches arrivals from every station in parallel and infers train positions
 * by clustering absolute positions along the route to deduplicate the same train 
 * being reported by multiple stations.
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
        
        // 1. Collect all valid raw predictions
        // Key: "$lineName|$destination"
        val rawPredictions = mutableMapOf<String, MutableList<RawPrediction>>()

        for (stationArrivals in results) {
            val station = stationArrivals.station
            
            for (arrival in stationArrivals.arrivals) {
                val destination = arrival.direction
                val secondsToStation = arrival.minutes
                
                val (lineName, orderedStops) = MapConfiguration.DESTINATION_TO_ORDERED_STOPS[destination]
                    ?: resolveLineFromStation(station, destination) ?: continue
                    
                val stationIndexInRoute = orderedStops.indexOf(station.apiId)
                if (stationIndexInRoute < 0) continue

                // Absolute position: time from the very first station on the route
                val absoluteTime = (stationIndexInRoute * segmentSeconds) - secondsToStation
                
                val key = "$lineName|$destination"
                if (rawPredictions[key] == null) {
                    rawPredictions[key] = mutableListOf()
                }
                rawPredictions[key]?.add(
                    RawPrediction(lineName, destination, orderedStops, stationIndexInRoute, secondsToStation, absoluteTime)
                )
            }
        }

        // 2. Cluster predictions to deduplicate trains (threshold: 90 seconds)
        val finalTrains = mutableListOf<TrainPosition>()
        var trainIdCounter = 1

        for ((key, predictions) in rawPredictions) {
            if (predictions.isEmpty()) continue
            
            // Sort by absolute time (position along the route)
            predictions.sortBy { it.absoluteTime }
            
            val clusters = mutableListOf<MutableList<RawPrediction>>()
            var currentCluster = mutableListOf(predictions.first())
            clusters.add(currentCluster)
            
            for (i in 1 until predictions.size) {
                val prediction = predictions[i]
                val prevPrediction = currentCluster.last()
                
                // If it's within 120 seconds of the last prediction, it's the same train
                if (prediction.absoluteTime - prevPrediction.absoluteTime < 120.0) {
                    currentCluster.add(prediction)
                } else {
                    currentCluster = mutableListOf(prediction)
                    clusters.add(currentCluster)
                }
            }
            
            // 3. Resolve each cluster into a single TrainPosition
            for (cluster in clusters) {
                // Average the absolute time
                val avgAbsoluteTime = cluster.map { it.absoluteTime }.average()
                val lineName = cluster.first().lineName
                val destination = cluster.first().destination
                val orderedStops = cluster.first().orderedStops
                
                // Which stations is it actually between?
                var pathIndex = Math.floor(avgAbsoluteTime / segmentSeconds).toInt()
                pathIndex = max(0, min(orderedStops.size - 2, pathIndex))
                
                // Progress between pathIndex and pathIndex + 1
                val progress = max(0.0, min(1.0, (avgAbsoluteTime - (pathIndex * segmentSeconds)) / segmentSeconds))
                
                val prevStationId = orderedStops[pathIndex]
                val nextStationId = orderedStops[pathIndex + 1]
                
                val prevCoord = stationCoords[prevStationId] ?: continue
                val nextCoord = stationCoords[nextStationId] ?: continue
                
                val trainLat = prevCoord.lat + (nextCoord.lat - prevCoord.lat) * progress
                val trainLon = prevCoord.lon + (nextCoord.lon - prevCoord.lon) * progress
                
                // Calculate bearing for the arrow icon
                val bearing = calculateBearing(prevCoord.lat, prevCoord.lon, nextCoord.lat, nextCoord.lon)
                
                // Approximate time to next station
                val secondsToNext = ((1.0 - progress) * segmentSeconds).toInt()
                
                finalTrains.add(
                    TrainPosition(
                        id = "Train-${trainIdCounter++}",
                        lat = trainLat,
                        lon = trainLon,
                        line = lineName,
                        destination = destination,
                        nextStationName = nextCoord.name,
                        secondsToNextStation = max(0, secondsToNext),
                        bearing = bearing
                    )
                )
            }
        }

        finalTrains
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val l1 = Math.toRadians(lat1)
        val l2 = Math.toRadians(lat2)
        val y = Math.sin(dLon) * Math.cos(l2)
        val x = Math.cos(l1) * Math.sin(l2) - Math.sin(l1) * Math.cos(l2) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

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
    
    private data class RawPrediction(
        val lineName: String,
        val destination: String,
        val orderedStops: List<String>,
        val stationIndexInRoute: Int,
        val secondsToStation: Int,
        val absoluteTime: Double
    )
}
