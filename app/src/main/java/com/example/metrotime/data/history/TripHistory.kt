package com.example.metrotime.data.history

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a confirmed station visit.
 */
data class StationVisit(
    val stationId: String,
    val stationName: String,
    val type: String,           // "metro" or "cp"
    val lineName: String,       // e.g. "Azul", "Urbano", etc.
    val lat: Double,
    val lon: Double,
    val timestampMs: Long,
    val confirmed: Boolean = true
)

/**
 * Represents a pending station detection awaiting user confirmation.
 */
data class PendingDetection(
    val stationId: String,
    val stationName: String,
    val type: String,           // "metro" or "cp"
    val lineName: String,
    val lat: Double,
    val lon: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val hitCount: Int           // number of location pings near this station
)

/**
 * Computed statistics from trip history.
 */
data class TripStats(
    val totalVisits: Int,
    val totalDistanceKm: Double,
    val timeSavedMinutes: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val topStations: List<Triple<String, String, Int>>,      // name, type, count
    val topLines: List<Pair<String, Int>>,          // line name to count
    val monthlyVisits: Int,
    val previousMonthVisits: Int
)

/**
 * Persistent trip history manager using SharedPreferences + JSON.
 * Avoids adding Room/kapt dependency to keep APK lean.
 */
object TripHistoryManager {

    private const val PREFS_FILE = "trip_history"
    private const val KEY_VISITS = "confirmed_visits"
    private const val KEY_PENDING = "pending_detections"
    private const val KEY_STREAK = "streak_data"
    private const val KEY_LAST_NOTIFIED_STATION = "last_notified_station"
    private const val KEY_LAST_USER_LAT = "last_user_lat"
    private const val KEY_LAST_USER_LON = "last_user_lon"
    private const val KEY_LAST_USER_TIME = "last_user_time"

    // Average speeds for time-saved calculation
    private const val METRO_AVG_SPEED_KMH = 33.0
    private const val CAR_AVG_SPEED_LISBON_KMH = 15.0

    // Station proximity threshold in meters
    const val STATION_PROXIMITY_METERS = 250.0

    // Minimum time near station to trigger detection (in ms) = 3 minutes
    const val MIN_DWELL_TIME_MS = 3 * 60 * 1000L

    // Max time for a detection to remain pending before auto-confirm (1 day)
    const val AUTO_CONFIRM_TIMEOUT_MS = 24 * 60 * 60 * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ──────────────────────────────────────────────
    // Confirmed Visits
    // ──────────────────────────────────────────────

    fun getConfirmedVisits(context: Context): List<StationVisit> {
        val json = prefs(context).getString(KEY_VISITS, "[]") ?: "[]"
        return parseVisits(json)
    }

    fun addConfirmedVisit(context: Context, visit: StationVisit) {
        val visits = getConfirmedVisits(context).toMutableList()
        visits.add(visit)
        saveVisits(context, visits)
        updateStreak(context, visit.timestampMs)
        syncStatsToFirebase(context)
    }

    private fun saveVisits(context: Context, visits: List<StationVisit>) {
        val arr = JSONArray()
        for (v in visits) {
            arr.put(JSONObject().apply {
                put("stationId", v.stationId)
                put("stationName", v.stationName)
                put("type", v.type)
                put("lineName", v.lineName)
                put("lat", v.lat)
                put("lon", v.lon)
                put("timestampMs", v.timestampMs)
                put("confirmed", v.confirmed)
            })
        }
        prefs(context).edit().putString(KEY_VISITS, arr.toString()).apply()
    }

    private fun parseVisits(json: String): List<StationVisit> {
        val arr = JSONArray(json)
        val list = mutableListOf<StationVisit>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(StationVisit(
                stationId = obj.getString("stationId"),
                stationName = obj.getString("stationName"),
                type = obj.optString("type", "metro"),
                lineName = obj.optString("lineName", ""),
                lat = obj.optDouble("lat", 0.0),
                lon = obj.optDouble("lon", 0.0),
                timestampMs = obj.getLong("timestampMs"),
                confirmed = obj.optBoolean("confirmed", true)
            ))
        }
        return list
    }

    // ──────────────────────────────────────────────
    // Pending Detections
    // ──────────────────────────────────────────────

    fun getPendingDetections(context: Context): List<PendingDetection> {
        val json = prefs(context).getString(KEY_PENDING, "[]") ?: "[]"
        return parsePending(json)
    }

    fun getLastUserLocation(context: Context): Pair<Double, Double>? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_LAST_USER_LAT)) return null
        val lat = prefs.getFloat(KEY_LAST_USER_LAT, 0f).toDouble()
        val lon = prefs.getFloat(KEY_LAST_USER_LON, 0f).toDouble()
        return Pair(lat, lon)
    }

    fun isLocationNearLisbon(lat: Double, lon: Double): Boolean {
        // Lisbon coords
        val lisbonLat = 38.7223
        val lisbonLon = -9.1393
        return haversineMeters(lat, lon, lisbonLat, lisbonLon) < 50_000 // 50km
    }

    fun savePendingDetections(context: Context, pending: List<PendingDetection>) {
        val arr = JSONArray()
        for (p in pending) {
            arr.put(JSONObject().apply {
                put("stationId", p.stationId)
                put("stationName", p.stationName)
                put("type", p.type)
                put("lineName", p.lineName)
                put("lat", p.lat)
                put("lon", p.lon)
                put("firstSeenMs", p.firstSeenMs)
                put("lastSeenMs", p.lastSeenMs)
                put("hitCount", p.hitCount)
            })
        }
        prefs(context).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private fun parsePending(json: String): List<PendingDetection> {
        val arr = JSONArray(json)
        val list = mutableListOf<PendingDetection>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(PendingDetection(
                stationId = obj.getString("stationId"),
                stationName = obj.getString("stationName"),
                type = obj.optString("type", "metro"),
                lineName = obj.optString("lineName", ""),
                lat = obj.optDouble("lat", 0.0),
                lon = obj.optDouble("lon", 0.0),
                firstSeenMs = obj.getLong("firstSeenMs"),
                lastSeenMs = obj.getLong("lastSeenMs"),
                hitCount = obj.optInt("hitCount", 1)
            ))
        }
        return list
    }

    /**
     * Called from background service with current user location.
     * Checks proximity to all metro & CP stations and updates pending detections.
     */
    fun onLocationUpdate(context: Context, userLat: Double, userLon: Double, allStations: List<NearbyStation>) {
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        
        // Anti-spoofing / Teleport check
        if (prefs.contains(KEY_LAST_USER_LAT)) {
            val lastLat = prefs.getFloat(KEY_LAST_USER_LAT, 0f).toDouble()
            val lastLon = prefs.getFloat(KEY_LAST_USER_LON, 0f).toDouble()
            val lastTime = prefs.getLong(KEY_LAST_USER_TIME, 0L)
            
            val timeDiffSecs = (now - lastTime) / 1000.0
            if (timeDiffSecs > 0) {
                val distanceMeters = haversineMeters(userLat, userLon, lastLat, lastLon)
                val speedMs = distanceMeters / timeDiffSecs
                // Se a velocidade for superior a 300 km/h (83 m/s), é teleport! Ignorar.
                if (speedMs > 85.0 && timeDiffSecs < 600) {
                    return // Hacker detectado
                }
            }
        }
        
        // Save valid location
        prefs.edit()
            .putFloat(KEY_LAST_USER_LAT, userLat.toFloat())
            .putFloat(KEY_LAST_USER_LON, userLon.toFloat())
            .putLong(KEY_LAST_USER_TIME, now)
            .apply()

        val pending = getPendingDetections(context).toMutableList()

        // Find nearest station within threshold
        var nearestStation: NearbyStation? = null
        var nearestDist = Double.MAX_VALUE

        for (station in allStations) {
            val dist = haversineMeters(userLat, userLon, station.lat, station.lon)
            if (dist < STATION_PROXIMITY_METERS && dist < nearestDist) {
                nearestDist = dist
                nearestStation = station
            }
        }

        if (nearestStation != null) {
            val existing = pending.find { it.stationId == nearestStation.id && it.type == nearestStation.type }
            if (existing != null) {
                // Update hit count and last seen
                val updated = existing.copy(
                    lastSeenMs = now,
                    hitCount = existing.hitCount + 1
                )
                pending.remove(existing)
                pending.add(updated)
            } else {
                // New detection — don't create pending if we were already there recently
                val lastNotified = prefs(context).getString(KEY_LAST_NOTIFIED_STATION, null)
                val timeSinceFormat = "${nearestStation.id}_${nearestStation.type}"
                if (lastNotified != timeSinceFormat) {
                    pending.add(PendingDetection(
                        stationId = nearestStation.id,
                        stationName = nearestStation.name,
                        type = nearestStation.type,
                        lineName = nearestStation.lineName,
                        lat = nearestStation.lat,
                        lon = nearestStation.lon,
                        firstSeenMs = now,
                        lastSeenMs = now,
                        hitCount = 1
                    ))
                }
            }
        }

        // Auto-confirm detections older than 24 hours
        val toAutoConfirm = pending.filter { now - it.lastSeenMs > AUTO_CONFIRM_TIMEOUT_MS && it.dwellTimeMs() >= MIN_DWELL_TIME_MS }
        for (detection in toAutoConfirm) {
            confirmDetection(context, detection)
            pending.remove(detection)
        }

        // Remove stale detections that never reached dwell time threshold
        pending.removeAll { now - it.lastSeenMs > AUTO_CONFIRM_TIMEOUT_MS && it.dwellTimeMs() < MIN_DWELL_TIME_MS }

        savePendingDetections(context, pending)
    }

    /**
     * Get detections that have met the dwell time threshold and are ready for user confirmation.
     */
    fun getReadyDetections(context: Context): List<PendingDetection> {
        return getPendingDetections(context).filter { it.dwellTimeMs() >= MIN_DWELL_TIME_MS }
    }

    /**
     * User confirms a detection → becomes a confirmed visit.
     */
    fun confirmDetection(context: Context, detection: PendingDetection) {
        val visit = StationVisit(
            stationId = detection.stationId,
            stationName = detection.stationName,
            type = detection.type,
            lineName = detection.lineName,
            lat = detection.lat,
            lon = detection.lon,
            timestampMs = detection.firstSeenMs,
            confirmed = true
        )
        addConfirmedVisit(context, visit)

        // Mark as last notified to avoid immediate re-detection
        prefs(context).edit()
            .putString(KEY_LAST_NOTIFIED_STATION, "${detection.stationId}_${detection.type}")
            .apply()

        // Remove from pending
        val pending = getPendingDetections(context).toMutableList()
        pending.removeAll { it.stationId == detection.stationId && it.type == detection.type && it.firstSeenMs == detection.firstSeenMs }
        savePendingDetections(context, pending)
    }

    /**
     * User dismisses a detection.
     */
    fun dismissDetection(context: Context, detection: PendingDetection) {
        val pending = getPendingDetections(context).toMutableList()
        pending.removeAll { it.stationId == detection.stationId && it.type == detection.type && it.firstSeenMs == detection.firstSeenMs }
        savePendingDetections(context, pending)
    }

    // ──────────────────────────────────────────────
    // Streak Management
    // ──────────────────────────────────────────────

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun getStreakData(context: Context): Pair<Int, Int> {
        val json = prefs(context).getString(KEY_STREAK, null) ?: return Pair(0, 0)
        val obj = JSONObject(json)
        return Pair(obj.optInt("current", 0), obj.optInt("longest", 0))
    }

    private fun updateStreak(context: Context, visitTimeMs: Long) {
        val today = dateFormat.format(Date(visitTimeMs))
        val streakJson = prefs(context).getString(KEY_STREAK, null)
        val obj = if (streakJson != null) JSONObject(streakJson) else JSONObject()

        val lastDate = obj.optString("lastDate", "")
        var current = obj.optInt("current", 0)
        var longest = obj.optInt("longest", 0)

        if (lastDate == today) {
            // Already counted today
            return
        }

        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(today) ?: return
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dateFormat.format(cal.time)

        current = if (lastDate == yesterday) {
            current + 1
        } else {
            1
        }

        if (current > longest) longest = current

        obj.put("current", current)
        obj.put("longest", longest)
        obj.put("lastDate", today)
        prefs(context).edit().putString(KEY_STREAK, obj.toString()).apply()
    }

    // ──────────────────────────────────────────────
    // Statistics Computation
    // ──────────────────────────────────────────────

    fun computeStats(context: Context): TripStats {
        val visits = getConfirmedVisits(context)
        val (currentStreak, longestStreak) = getStreakData(context)

        // Total distance: sum consecutive visit distances
        var totalDistanceKm = 0.0
        for (i in 1 until visits.size) {
            val prev = visits[i - 1]
            val curr = visits[i]
            // Only compute distance for visits within the same day (likely same trip)
            val timeDiff = curr.timestampMs - prev.timestampMs
            if (timeDiff < 4 * 60 * 60 * 1000L) { // within 4 hours
                totalDistanceKm += haversineMeters(prev.lat, prev.lon, curr.lat, curr.lon) / 1000.0
            }
        }

        // Time saved: distance / metro speed vs distance / car speed (in minutes)
        val timeMetroMin = (totalDistanceKm / METRO_AVG_SPEED_KMH) * 60
        val timeCarMin = (totalDistanceKm / CAR_AVG_SPEED_LISBON_KMH) * 60
        val timeSavedMin = (timeCarMin - timeMetroMin).toInt()

        // Top stations
        val stationCounts = visits.groupBy { Pair(it.stationName, it.type) }
            .mapValues { it.value.size }
            .map { Triple(it.key.first, it.key.second, it.value) }
            .sortedByDescending { it.third }
            .take(5)

        // Top lines
        val lineCounts = visits.filter { it.lineName.isNotEmpty() }
            .groupBy { it.lineName }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(5)

        // Monthly visits
        val cal = Calendar.getInstance()
        val thisMonth = cal.get(Calendar.MONTH)
        val thisYear = cal.get(Calendar.YEAR)
        val monthlyVisits = visits.count {
            val visitCal = Calendar.getInstance().apply { timeInMillis = it.timestampMs }
            visitCal.get(Calendar.MONTH) == thisMonth && visitCal.get(Calendar.YEAR) == thisYear
        }
        val prevMonth = if (thisMonth == 0) 11 else thisMonth - 1
        val prevYear = if (thisMonth == 0) thisYear - 1 else thisYear
        val previousMonthVisits = visits.count {
            val visitCal = Calendar.getInstance().apply { timeInMillis = it.timestampMs }
            visitCal.get(Calendar.MONTH) == prevMonth && visitCal.get(Calendar.YEAR) == prevYear
        }

        return TripStats(
            totalVisits = visits.size,
            totalDistanceKm = totalDistanceKm,
            timeSavedMinutes = maxOf(0, timeSavedMin),
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            topStations = stationCounts,
            topLines = lineCounts,
            monthlyVisits = monthlyVisits,
            previousMonthVisits = previousMonthVisits
        )
    }

    fun syncStatsToFirebase(context: Context) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        val stats = computeStats(context)
        val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        
        val mappedStations = stats.topStations.map {
            mapOf("name" to it.first, "type" to it.second, "count" to it.third)
        }
        val mappedLines = stats.topLines.map {
            mapOf("name" to it.first, "count" to it.second)
        }
        
        val updates = mapOf(
            "totalVisits" to stats.totalVisits,
            "totalDistanceKm" to stats.totalDistanceKm,
            "timeSavedMinutes" to stats.timeSavedMinutes,
            "currentStreak" to stats.currentStreak,
            "longestStreak" to stats.longestStreak,
            "topStations" to mappedStations,
            "topLines" to mappedLines,
            "monthlyVisits" to stats.monthlyVisits,
            "previousMonthVisits" to stats.previousMonthVisits
        )
        
        db.getReference("user_stats").child(user.uid).setValue(updates)
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().clear().apply()
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
            db.getReference("user_stats").child(user.uid).removeValue()
        }
    }

    // ──────────────────────────────────────────────
    // Haversine Helper
    // ──────────────────────────────────────────────

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
}

/**
 * Helper function to compute dwell time from a PendingDetection.
 */
fun PendingDetection.dwellTimeMs(): Long = lastSeenMs - firstSeenMs

/**
 * Generic station representation for proximity checks.
 */
data class NearbyStation(
    val id: String,
    val name: String,
    val type: String,       // "metro" or "cp"
    val lineName: String,
    val lat: Double,
    val lon: Double
)
