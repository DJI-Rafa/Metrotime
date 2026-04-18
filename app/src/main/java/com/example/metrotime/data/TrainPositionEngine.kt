package com.example.metrotime.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val destinationId: String,
    val nextStationName: String,
    val nextStationId: String,
    val secondsToNextStation: Int,
    val bearing: Double,
    val isGhost: Boolean = false
)

/**
 * Fetches arrivals from line endpoints and infers train positions
 * by tracking specific train IDs. Handles interpolation and ghost trains.
 */
class TrainPositionEngine(private val context: Context) {

    private val arrivalsSource = RemoteArrivalsSource(context)
    private val stationCoords = MapConfiguration.STATION_COORDS
    private val lineStationOrder = MapConfiguration.LINE_STATION_ORDER
    private val segmentSeconds = MapConfiguration.SEGMENT_TRAVEL_SECONDS.toDouble()

    // State retained across fetches
    private val stateMutex = Mutex()
    private val lastKnownPositions = mutableMapOf<String, TrainPosition>()
    private val ghostTimestamps = mutableMapOf<String, Long>()

    // Valid destinations that indicate a train should still be active
    private val validDestinations = setOf(
        "33", "34", "35", "36", "37", "38", "39", "40", "41", "42",
        "43", "44", "45", "46", "48", "50", "51", "52", "53", "54",
        "56", "57", "59", "60"
    )

    suspend fun fetchAllTrainPositions(): List<TrainPosition> = withContext(Dispatchers.IO) {
        val lines = listOf("Amarela", "Azul", "Verde", "Vermelha")

        // Query all lines concurrently
        val deferredResults = lines.map { linha ->
            async {
                try {
                    arrivalsSource.fetchLineArrivals(linha)
                } catch (e: Exception) {
                    null
                }
            }
        }

        val results = deferredResults.awaitAll().filterNotNull()
        
        val newPositionsMap = mutableMapOf<String, TrainPosition>()
        val currentTime = System.currentTimeMillis()

        for (lineArrivals in results) {
            val lineName = lineArrivals.line
            val defaultOrder = lineStationOrder[lineName] ?: continue

            // Group arrivals by train ID
            val trainsById = lineArrivals.arrivals.groupBy { it.trainId }

            for ((trainId, predictions) in trainsById) {
                if (predictions.isEmpty()) continue

                val validPredictions = predictions.filter { it.secondsToArrive >= 0 }
                if (validPredictions.isEmpty()) continue

                val closestPrediction = validPredictions.minByOrNull { it.secondsToArrive } ?: continue
                
                val destination = closestPrediction.destinationName
                val destinationId = closestPrediction.destinationId
                val nextStationId = closestPrediction.stationId
                val secondsToNextStation = closestPrediction.secondsToArrive

                // Deduce direction by comparing destination index with next station index
                val destStationCoord = stationCoords.values.find { it.name == destination }
                val destStationId = destStationCoord?.id ?: ""
                
                val destIndex = defaultOrder.indexOf(destStationId)
                val nextIdx = defaultOrder.indexOf(nextStationId)
                
                val orderedStops = if (destIndex >= 0 && nextIdx >= 0) {
                    if (destIndex < nextIdx || destStationId == defaultOrder.first()) {
                        defaultOrder.reversed()
                    } else {
                        defaultOrder
                    }
                } else {
                    defaultOrder
                }

                // Which index is the next station?
                val nextStationIndex = orderedStops.indexOf(nextStationId)
                if (nextStationIndex < 0) continue

                // Calculate progress between previous station and next station
                val pathIndex: Int
                val progress: Double

                if (nextStationIndex == 0) {
                    // It's approaching its very first station (probably just starting)
                    pathIndex = 0
                    progress = 0.0
                } else {
                    pathIndex = nextStationIndex - 1
                    // Normally progress = 1.0 (at next station) if time is 0.
                    // If time is segmentSeconds, progress = 0.0 (at prev station).
                    val rawProgress = 1.0 - (secondsToNextStation.toDouble() / segmentSeconds)
                    progress = max(0.0, min(1.0, rawProgress))
                }

                // If it's a terminal station and time is <= 10s, anchor it exactly to the station
                val isTerminal = nextStationIndex == orderedStops.size - 1 || nextStationIndex == 0
                val finalProgress = if (isTerminal && secondsToNextStation <= 10) 1.0 else progress

                val prevStationId = orderedStops[max(0, pathIndex)]
                val actualNextStationId = orderedStops[min(orderedStops.size - 1, pathIndex + 1)]

                val prevCoord = stationCoords[prevStationId] ?: continue
                val nextCoord = stationCoords[actualNextStationId] ?: continue

                val trainLat = prevCoord.lat + (nextCoord.lat - prevCoord.lat) * finalProgress
                val trainLon = prevCoord.lon + (nextCoord.lon - prevCoord.lon) * finalProgress

                val bearing = calculateBearing(prevCoord.lat, prevCoord.lon, nextCoord.lat, nextCoord.lon)

                val trainPos = TrainPosition(
                    id = trainId,
                    lat = trainLat,
                    lon = trainLon,
                    line = lineName,
                    destination = destination,
                    destinationId = destinationId,
                    nextStationName = stationCoords[nextStationId]?.name ?: nextStationId,
                    nextStationId = nextStationId,
                    secondsToNextStation = secondsToNextStation,
                    bearing = bearing,
                    isGhost = false
                )
                
                // If it's a terminal and secondsToNextStation == 0, we don't add it (it arrived and should be removed)
                // Wait, user request: "continua a colocar a setinha do metro nessa estação até chegar aos 00 segundos e depois retira"
                // So if it's strictly 0, we can omit it, or if it's <= 0. Let's omit if <= 0 and terminal.
                if (isTerminal && secondsToNextStation <= 0) {
                    // Remove from tracking completely
                    continue
                }

                newPositionsMap[trainId] = trainPos
            }
        }

        // Handle Ghost Trains and State Updates
        stateMutex.withLock {
            val finalTrains = mutableListOf<TrainPosition>()

            // 1. Add all newly fetched valid trains
            for ((trainId, train) in newPositionsMap) {
                finalTrains.add(train)
                lastKnownPositions[trainId] = train
                ghostTimestamps.remove(trainId) // It's alive, not a ghost
            }

            // 2. Check for missing trains from last known positions
            val iterator = lastKnownPositions.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val oldTrainId = entry.key
                val oldTrain = entry.value

                if (!newPositionsMap.containsKey(oldTrainId)) {
                    // It's missing. Is it a valid destination to be a ghost?
                    if (validDestinations.contains(oldTrain.destinationId)) {
                        
                        // It's a ghost. Have we tracked it already?
                        val ghostSince = ghostTimestamps[oldTrainId]
                        if (ghostSince == null) {
                            ghostTimestamps[oldTrainId] = currentTime
                            val ghostTrain = oldTrain.copy(isGhost = true)
                            // Update last known to ghost version
                            entry.setValue(ghostTrain)
                            finalTrains.add(ghostTrain)
                        } else {
                            // Check TTL (2 minutes = 120,000 ms)
                            if (currentTime - ghostSince > 120_000) {
                                // TTL expired, remove it
                                iterator.remove()
                                ghostTimestamps.remove(oldTrainId)
                            } else {
                                // Still a valid ghost
                                finalTrains.add(oldTrain.copy(isGhost = true))
                            }
                        }
                    } else {
                        // Not a valid destination, remove it permanently
                        iterator.remove()
                    }
                }
            }

            finalTrains
        }
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == lat2 && lon1 == lon2) return 0.0
        val dLon = Math.toRadians(lon2 - lon1)
        val l1 = Math.toRadians(lat1)
        val l2 = Math.toRadians(lat2)
        val y = Math.sin(dLon) * Math.cos(l2)
        val x = Math.cos(l1) * Math.sin(l2) - Math.sin(l1) * Math.cos(l2) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }
}
