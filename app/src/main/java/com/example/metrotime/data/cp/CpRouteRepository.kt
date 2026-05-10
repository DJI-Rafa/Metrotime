package com.example.metrotime.data.cp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private val Context.routeDataStore: DataStore<Preferences> by preferencesDataStore(name = "cp_routes")

object CpRouteRepository {

    private var isInitialized = false
    private lateinit var dataStore: DataStore<Preferences>
    
    // In-memory cache: trainNumber -> List of station codes
    private val routeCache = mutableMapOf<Int, List<String>>()
    private val queue = mutableSetOf<Int>() // Pending train numbers
    private var workerJob: Job? = null

    fun initialize(context: Context) {
        if (isInitialized) return
        dataStore = context.applicationContext.routeDataStore
        isInitialized = true
        
        // Load existing routes into memory on startup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = dataStore.data.first()
                prefs.asMap().forEach { (key, value) ->
                    if (key.name.startsWith("route_") && value is String) {
                        val trainNumber = key.name.removePrefix("route_").toIntOrNull()
                        if (trainNumber != null) {
                            routeCache[trainNumber] = value.split(",")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getRoute(trainNumber: Int): List<String>? {
        return routeCache[trainNumber]
    }

    fun getCacheSnapshot(): Map<Int, List<String>> {
        return routeCache.toMap()
    }

    fun queueUnknownVehicles(vehicles: List<CpVehicle>) {
        if (!isInitialized) return
        
        var added = false
        vehicles.forEach { v ->
            if (!routeCache.containsKey(v.trainNumber) && !queue.contains(v.trainNumber)) {
                queue.add(v.trainNumber)
                added = true
            }
        }
        
        if (added && (workerJob == null || workerJob?.isActive != true)) {
            startWorker()
        }
    }

    private fun startWorker() {
        workerJob = CoroutineScope(Dispatchers.IO).launch {
            while (queue.isNotEmpty() && isActive) {
                val trainNumber = queue.first()
                queue.remove(trainNumber)
                
                try {
                    // Fetch from API
                    val trip = CpApi.service.getTripDetail(trainNumber)
                    val stops = trip.trainStops?.mapNotNull { it.station?.code } ?: emptyList()
                    
                    // Update memory cache even if empty so we don't loop/retry forever in this session
                    routeCache[trainNumber] = stops
                    
                    if (stops.isNotEmpty()) {
                        // Persist to DataStore
                        dataStore.edit { prefs ->
                            prefs[stringPreferencesKey("route_$trainNumber")] = stops.joinToString(",")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // If it failed (e.g. rate limit), don't get stuck. We just ignore it.
                    // The next time SettingsActivity fetches vehicles, it will be re-queued.
                }
                
                // Be very polite to the API: wait 3 seconds before next request
                delay(3000)
            }
        }
    }
}
