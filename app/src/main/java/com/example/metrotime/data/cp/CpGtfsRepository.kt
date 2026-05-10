package com.example.metrotime.data.cp

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.zip.GZIPInputStream

data class CpGtfsStop(
    @Json(name = "name") val name: String,
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double
)

data class CpGtfsStopTime(
    @Json(name = "id") val id: String,
    @Json(name = "arr") val arr: String,
    @Json(name = "dep") val dep: String
)

object CpGtfsRepository {
    private var isInitialized = false
    private val loadingComplete = CompletableDeferred<Unit>()
    var gtfsLoadError: String? = null

    // stop_id -> details
    private var stops: Map<String, CpGtfsStop> = emptyMap()
    // trip_id -> list of stops with schedule
    private var trips: Map<String, List<CpGtfsStopTime>> = emptyMap()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("CpGtfs", "Loading CP GTFS data...")
                
                // Load Stops
                val stopsType = Types.newParameterizedType(Map::class.java, String::class.java, CpGtfsStop::class.java)
                val stopsAdapter: JsonAdapter<Map<String, CpGtfsStop>> = moshi.adapter(stopsType)
                context.assets.open("cp_stops.json.bin").use { inputStream ->
                    GZIPInputStream(inputStream).bufferedReader().use { reader ->
                        val jsonStr = reader.readText()
                        stops = stopsAdapter.fromJson(jsonStr) ?: emptyMap()
                    }
                }

                // Load Trips
                val tripsType = Types.newParameterizedType(
                    Map::class.java, 
                    String::class.java, 
                    Types.newParameterizedType(List::class.java, CpGtfsStopTime::class.java)
                )
                val tripsAdapter: JsonAdapter<Map<String, List<CpGtfsStopTime>>> = moshi.adapter(tripsType)
                context.assets.open("cp_trips.json.bin").use { inputStream ->
                    GZIPInputStream(inputStream).bufferedReader().use { reader ->
                        val jsonStr = reader.readText()
                        trips = tripsAdapter.fromJson(jsonStr) ?: emptyMap()
                    }
                }

                Log.d("CpGtfs", "Loaded ${stops.size} stops and ${trips.size} trips.")
                Log.d("CpGtfs", "Loaded ${stops.size} stops and ${trips.size} trips.")
            } catch (e: Exception) {
                gtfsLoadError = e.stackTraceToString()
                Log.e("CpGtfs", "Error loading GTFS", e)
            } finally {
                loadingComplete.complete(Unit)
            }
        }
    }

    /** Suspends until GTFS data has been fully loaded into memory. */
    suspend fun awaitReady() {
        loadingComplete.await()
    }

    /**
     * Looks up a trip by its exact GTFS tripId or by a prefix match on trainNumber.
     * Often tripIds are formatted like "120_20251214".
     */
    fun getTripSchedule(trainNumber: Int, tripIdHint: String? = null): List<CpGtfsStopTime>? {
        if (tripIdHint != null && trips.containsKey(tripIdHint)) {
            return trips[tripIdHint]
        }
        
        // Fallback: search for trip starting with "trainNumber_"
        val prefix = "${trainNumber}_"
        val exactMatch = trips.keys.firstOrNull { it.startsWith(prefix) }
        if (exactMatch != null) {
            return trips[exactMatch]
        }
        
        return null
    }

    fun getStopDetails(stopId: String): CpGtfsStop? {
        return stops[stopId]
    }
}
