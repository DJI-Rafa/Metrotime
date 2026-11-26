package com.example.metrotime.data.bus

import android.content.Context
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// ── Data models ─────────────────────────────────────────────────────────────────

data class BusRoute(
    val routeId: String,
    val shortName: String,        // "728" or "1633"
    val longName: String,         // "Cais Sodré - Restelo"
    val color: String = BusColors.FALLBACK, // cor oficial da linha
    val operator: String,          // "carris" or "carris_metropolitana"
    val patterns: List<String> = emptyList() // Added patterns
)

data class LiveBus(
    val vehicleId: String,
    val routeId: String,
    val routeShortName: String,
    val lineId: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speed: Float,
    val destination: String,
    val tripId: String?,
    val operator: String,          // "carris" or "carris_metropolitana"
    val currentStatus: String = "", // "INCOMING_AT", "STOPPED_AT", "IN_TRANSIT_TO"
    val direction: Int = -1,        // GTFS direction_id: 0=outbound, 1=inbound, -1=unknown
    val patternId: String = "",
    val currentStopId: String = ""
)

data class BusScheduleStop(
    val stopId: String,
    val stopName: String,
    val arrivalTime: String,  // e.g. "18:28"
    val isPassed: Boolean,    // true if bus already passed this stop
    /** Hora do horário desta viagem nesta paragem (segundos do dia de serviço; 25:10 = 90600); null se não se souber. */
    val scheduledSec: Int? = null
)

/** "18:28:00" ou "25:10" → segundos do dia de serviço. */
internal fun scheduleSeconds(text: String?): Int? {
    val p = text?.trim()?.split(":") ?: return null
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 3600 + m * 60 + (p.getOrNull(2)?.toIntOrNull() ?: 0)
}

/**
 * Atraso de um autocarro em segundos (negativo = adiantado) pela paragem onde está ou para onde vai, como nas
 * paragens: o horário da viagem contra a hora de agora. null sem horário desta viagem.
 */
fun busScheduleDelay(schedule: List<BusScheduleStop>, stopped: Boolean, nowSec: Int): Int? {
    val cur = schedule.indexOfFirst { !it.isPassed }.takeIf { it >= 0 } ?: return null
    val next = schedule[cur].scheduledSec ?: return null
    val prev = schedule.getOrNull(cur - 1)?.scheduledSec
    // Viagens que passam da meia-noite: 00:30 conta como 24:30 do dia anterior
    val now = if (next - nowSec > 12 * 3600) nowSec + 24 * 3600 else nowSec
    return BusTiming.delaySeconds(prev, next, now, stopped)
}

/** A mesma viagem com o autocarro noutra paragem (posição nova): as anteriores ficam passadas. */
fun reanchorSchedule(schedule: List<BusScheduleStop>, currentStopId: String): List<BusScheduleStop> {
    if (currentStopId.isEmpty()) return schedule
    val oldCur = schedule.indexOfFirst { !it.isPassed }.coerceAtLeast(0)
    // A partir de onde ia (os circulares repetem paragens): nunca para trás
    val idx = (oldCur until schedule.size).firstOrNull { schedule[it].stopId == currentStopId } ?: return schedule
    return schedule.mapIndexed { i, s -> if (s.isPassed == (i < idx)) s else s.copy(isPassed = i < idx) }
}

data class BusStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val operator: String = ""
)

// ── Repository ──────────────────────────────────────────────────────────────────

/**
 * Fecha logo as respostas sem sucesso (antes ficavam abertas nos "return" antecipados) — com
 * polling a cada 10–15 s isso esgotava ligações. As de sucesso fecham-se ao ler o corpo.
 */
internal fun okhttp3.Response.closeIfFailed(): okhttp3.Response = also { if (!it.isSuccessful) it.close() }

object BusMapRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Carris urban GTFS-RT
    private const val CARRIS_VP_URL =
        "https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS/realtime/vehiclepositions"

    // Carris Metropolitana REST API
    private const val CM_LINES_URL = "https://api.carrismetropolitana.pt/lines"
    private const val CM_VEHICLES_URL = "https://api.carrismetropolitana.pt/vehicles"
    private const val CM_STOPS_URL = "https://api.carrismetropolitana.pt/stops"
    private const val CM_PATTERN_URL = "https://api.carrismetropolitana.pt/patterns/"
    /** Percurso na API nova: 200 KB em vez de 2,7 MB, com as viagens e os horários. */
    private const val CM_PATTERN_V2_URL = "https://api.carrismetropolitana.pt/v2/patterns/"
    private const val CM_SHAPES_URL = "https://api.carrismetropolitana.pt/shapes/"

    // Cached CM lines (loaded once)
    @Volatile private var cmLines: List<BusRoute>? = null
    @Volatile private var cmLinesLoading = false
    
    private val cmPatternDirectionCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private suspend fun getCmPatternDirection(patternId: String): Int = withContext(Dispatchers.IO) {
        cmPatternDirectionCache[patternId]?.let { return@withContext it }
        try {
            val request = Request.Builder().url("$CM_PATTERN_V2_URL${patternId.substringAfterLast("]")}").build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (response.isSuccessful && response.body != null) {
                val json = response.body!!.string()
                val obj = JSONArray(json).optJSONObject(0) ?: org.json.JSONObject()
                val dir = obj.optInt("direction_id", -1)
                if (dir != -1) {
                    cmPatternDirectionCache[patternId] = dir
                    return@withContext dir
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback if API fails or parsing fails: guess from string parts
        val fallback = patternId.split("_").getOrNull(1)?.toIntOrNull() ?: -1
        cmPatternDirectionCache[patternId] = fallback
        return@withContext fallback
    }

    /**
     * Normalizes live route IDs to match static GTFS keys.
     * Handles both single-digit and double-digit trailing zeros:
     * e.g., "204_00" -> "204_0", "103_00" -> "103_0"
     * But preserves meaningful suffixes like "204_40" (not a trailing zero).
     */
    fun normalizeRouteId(id: String): String {
        if (id.contains("_")) {
            val parts = id.split("_")
            if (parts.size >= 2) {
                val prefix = parts[0]
                val suffix = parts[1]
                // Only normalize if the suffix is exactly "X0" where X is 0-9
                // i.e., the suffix looks like a zero-padded single digit
                if (suffix.length == 2 && suffix[0] != '0' && suffix.endsWith("0")) {
                    // Check if this is actually a trailing zero (e.g., "00" -> "0")
                    // or a meaningful number (e.g., "40" stays "40")
                    // Only treat as padded if the non-padded version exists in static data
                    // For safety, keep the original logic but handle "00" specially
                }
                // Only normalize "X0" -> "X" if it's genuinely zero-padded
                if (suffix == "00") return "${prefix}_0"
                if (suffix.length == 2 && suffix.endsWith("0")) {
                    val stripped = "${prefix}_${suffix[0]}"
                    // Return stripped if it looks like padding
                    return stripped
                }
            }
        }
        return id
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    /**
     * Search bus routes by number or destination across both operators.
     * Searches route short names, long names, and stop names (intermediate stops).
     */
    suspend fun searchRoutes(context: Context, query: String): List<BusRoute> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val results = mutableListOf<BusRoute>()
            val q = query.trim().lowercase()

            // 1. Search Carris urban (GTFS assets)
            try {
                CarrisGtfsData.ensureLoaded(context)
                val carrisResults = CarrisGtfsData.searchRoutes(query)
                results.addAll(carrisResults.map { route ->
                    BusRoute(
                        routeId = route.id,
                        shortName = route.shortName,
                        longName = route.longName,
                        color = BusColors.carris(route.shortName),
                        operator = "carris"
                    )
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Search Carris Metropolitana (REST API, cached)
            try {
                val cmRoutes = getCmLines()
                results.addAll(cmRoutes.filter { route ->
                    route.shortName.lowercase().contains(q) ||
                    route.longName.lowercase().contains(q)
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Deduplicate by shortName, prefer CM if both match (CM has more data)
            results
                .groupBy { it.shortName }
                .map { (_, routes) ->
                    routes.firstOrNull { it.operator == "carris_metropolitana" } ?: routes.first()
                }
                .sortedWith(compareBy { 
                    // Exact matches first, then partial
                    if (it.shortName.lowercase() == q) 0 else 1
                })
                .take(20)
        }

    /**
     * Get live vehicle positions for specific line IDs from Carris Metropolitana.
     */
    suspend fun getLiveBusesForLines(lineIds: Set<String>?): List<LiveBus> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<LiveBus>()
            
            try {
                val request = Request.Builder().url(CM_VEHICLES_URL).build()
                val response = client.newCall(request).execute().closeIfFailed()
                if (!response.isSuccessful || response.body == null) return@withContext emptyList()

                val json = response.body!!.string()
                val arr = JSONArray(json)

                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    val lineId = v.optString("line_id", "")
                    // null: todos (os pontos do mapa)
                    if (lineIds != null && lineId !in lineIds) continue
                    if (!v.has("lat") || !v.has("lon")) continue

                    val lat = v.optDouble("lat", 0.0)
                    val lon = v.optDouble("lon", 0.0)
                    if (lat == 0.0 && lon == 0.0) continue

                    // Sentido: vem em cada autocarro; o percurso (um download) só se faltar
                    val patternId = v.optString("pattern_id", "")
                    val cmDirection = v.optInt("direction_id", -1).takeIf { it >= 0 }
                        ?: if (lineIds != null) getCmPatternDirection(patternId) else -1

                    results.add(LiveBus(
                        vehicleId = v.optString("id", ""),
                        routeId = v.optString("route_id", ""),
                        routeShortName = lineId,
                        lineId = lineId,
                        lat = lat,
                        lon = lon,
                        bearing = v.optDouble("bearing", 0.0).toFloat(),
                        speed = v.optDouble("speed", 0.0).toFloat(),
                        destination = "",
                        tripId = v.optString("trip_id", "").ifEmpty { null },
                        operator = "carris_metropolitana",
                        currentStatus = v.optString("current_status", ""),
                        direction = cmDirection,
                        patternId = patternId,
                        currentStopId = v.optString("stop_id", "")
                    ))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            results
        }

    /**
     * Get live vehicle positions from Carris urban GTFS-RT for specific route IDs.
     */
    suspend fun getLiveBusesCarrisUrban(
        context: Context,
        /** null: todos (os pontos do mapa). */
        routeIds: Set<String>?
    ): List<LiveBus> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LiveBus>()
        try {
            CarrisGtfsData.ensureLoaded(context)

            val request = Request.Builder().url(CARRIS_VP_URL).build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (!response.isSuccessful || response.body == null) return@withContext emptyList()

            val feed = response.body!!.byteStream().use { FeedMessage.parseFrom(it) }

            for (entity in feed.entityList) {
                if (!entity.hasVehicle()) continue
                val vehicle = entity.vehicle
                if (!vehicle.hasPosition()) continue

                val tripId = if (vehicle.hasTrip() && vehicle.trip.hasTripId()) vehicle.trip.tripId else null
                val tripInfo = tripId?.let { CarrisGtfsData.trip(it) }
                val routeId = tripInfo?.routeId
                    ?: (if (vehicle.hasTrip() && vehicle.trip.hasRouteId()) vehicle.trip.routeId else null)
                    ?: continue

                val normalizedRouteId = normalizeRouteId(routeId)
                if (routeIds != null && normalizedRouteId !in routeIds) continue

                val routeInfo = CarrisGtfsData.routes[normalizedRouteId] ?: continue
                val pos = vehicle.position

                results.add(LiveBus(
                    vehicleId = if (vehicle.hasVehicle() && vehicle.vehicle.hasId()) vehicle.vehicle.id else "?",
                    routeId = routeId,
                    routeShortName = routeInfo.shortName,
                    lineId = routeInfo.shortName,
                    lat = pos.latitude.toDouble(),
                    lon = pos.longitude.toDouble(),
                    bearing = if (pos.hasBearing()) pos.bearing else 0f,
                    speed = if (pos.hasSpeed()) pos.speed else 0f,
                    destination = extractDestination(routeInfo.longName, tripInfo?.direction ?: 0),
                    tripId = tripId,
                    operator = "carris",
                    direction = tripInfo?.direction ?: -1,
                    currentStopId = if (vehicle.hasStopId()) vehicle.stopId else ""
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }

    /**
     * Get the scheduled stops for a specific CM bus based on its pattern and trip.
     * Returns all stops with arrival times, marking which ones the bus has already passed.
     */
    suspend fun getCmBusSchedule(
        patternId: String,
        tripId: String?,
        currentStopId: String
    ): List<BusScheduleStop> = withContext(Dispatchers.IO) {
        try {
            // "[6CJAL][BNA17]2776_0_2" → "2776_0_2" (com os prefixos a API devolvia {} e não havia horário)
            val request = Request.Builder().url("$CM_PATTERN_V2_URL${patternId.substringAfterLast("]")}").build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (!response.isSuccessful || response.body == null) return@withContext emptyList()

            val obj = JSONArray(response.body!!.string()).optJSONObject(0) ?: return@withContext emptyList()
            // Nomes das paragens: a lista da Carris Metropolitana (já carregada para o mapa)
            val stopNames = (cmStops ?: getAllCmStops()).associate { it.id to it.name }

            // Get trips and find the matching one
            val trips = obj.optJSONArray("trips") ?: return@withContext emptyList()
            var schedule: org.json.JSONArray? = null

            // A viagem do autocarro: o trip_id sem prefixos ("2776_0_2|170|1|2000")
            val tripKey = tripId?.substringAfterLast("]")
            // Só com a viagem certa se pode dizer o atraso (sem ela, é a viagem mais parecida pela hora)
            var exactTrip = false
            if (tripKey != null) {
                for (i in 0 until trips.length()) {
                    val trip = trips.getJSONObject(i)
                    val ids = trip.optJSONArray("trip_ids") ?: continue
                    if ((0 until ids.length()).any { ids.optString(it).substringAfterLast("]") == tripKey }) {
                        schedule = trip.optJSONArray("schedule")
                        exactTrip = schedule != null
                        break
                    }
                }
            }

            // Fallback: find the trip whose schedule most closely matches current time
            if (schedule == null && trips.length() > 0) {
                val now = java.time.LocalTime.now()
                var bestDiff = Long.MAX_VALUE
                for (i in 0 until trips.length()) {
                    val trip = trips.getJSONObject(i)
                    val sched = trip.optJSONArray("schedule") ?: continue
                    if (sched.length() == 0) continue
                    val firstTime = sched.getJSONObject(0).optString("arrival_time", "")
                    val lastTime = sched.getJSONObject(sched.length() - 1).optString("arrival_time", "")
                    try {
                        val startTime = java.time.LocalTime.parse(firstTime)
                        val endTime = java.time.LocalTime.parse(lastTime)
                        // Trip should be in progress: start <= now <= end
                        if (!now.isBefore(startTime) && !now.isAfter(endTime)) {
                            val diff = java.time.Duration.between(startTime, now).toMinutes()
                            if (diff < bestDiff) {
                                bestDiff = diff
                                schedule = sched
                            }
                        }
                    } catch (_: Exception) {}
                }
                // If no trip is in progress, just pick the next upcoming one
                if (schedule == null) {
                    var bestFutureDiff = Long.MAX_VALUE
                    for (i in 0 until trips.length()) {
                        val trip = trips.getJSONObject(i)
                        val sched = trip.optJSONArray("schedule") ?: continue
                        if (sched.length() == 0) continue
                        val firstTime = sched.getJSONObject(0).optString("arrival_time", "")
                        try {
                            val startTime = java.time.LocalTime.parse(firstTime)
                            val diff = java.time.Duration.between(now, startTime).toMinutes()
                            if (diff >= 0 && diff < bestFutureDiff) {
                                bestFutureDiff = diff
                                schedule = sched
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            if (schedule == null) return@withContext emptyList()

            // Find current stop index
            val currentStopIdx = if (currentStopId.isNotEmpty()) {
                var foundIdx = -1
                for (j in 0 until schedule.length()) {
                    if (schedule.getJSONObject(j).optString("stop_id", "") == currentStopId) {
                        foundIdx = j
                        break
                    }
                }
                foundIdx
            } else -1

            // Build the result list
            val result = mutableListOf<BusScheduleStop>()
            var foundCurrentStop = currentStopIdx == -1 || currentStopId.isEmpty()

            for (i in 0 until schedule.length()) {
                val entry = schedule.getJSONObject(i)
                val arrivalTime = entry.optString("arrival_time", "")
                val stopId = entry.optString("stop_id", "")

                val stopName = stopNames[stopId] ?: stopId

                val timeFormatted = if (arrivalTime.length >= 5) arrivalTime.substring(0, 5) else arrivalTime

                if (stopId == currentStopId) foundCurrentStop = true
                val isPassed = !foundCurrentStop

                result.add(BusScheduleStop(
                    stopId = stopId,
                    stopName = stopName,
                    arrivalTime = timeFormatted,
                    isPassed = isPassed,
                    scheduledSec = if (exactTrip) scheduleSeconds(arrivalTime) else null
                ))
            }

            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Get the scheduled stops for a Carris urban bus based on GTFS static schedule offsets.
     * Uses the lightweight 27KB asset with relative time offsets per route+direction.
     */
    suspend fun getCarrisUrbanBusSchedule(
        context: Context,
        routeId: String,
        tripId: String?,
        currentStopId: String
    ): List<BusScheduleStop> = withContext(Dispatchers.IO) {
        try {
            CarrisGtfsData.ensureLoaded(context)

            // A viagem no horário da Carris (o das paragens): as horas certas de cada paragem, para o atraso
            val tt = runCatching { CarrisTimetable.load(context) }.getOrNull()
            val ref = tripId?.let { tt?.ref(it) }
            if (tt != null && ref != null) {
                val pattern = tt.patterns[ref shr 16]
                val trip = ref and 0xFFFF
                val names = CarrisGtfsData.stops.associate { it.id to it.name }
                val cur = if (currentStopId.isEmpty()) -1 else pattern.stops.indexOf(currentStopId)
                return@withContext pattern.stops.mapIndexed { i, stopId ->
                    val sec = pattern.timeAt(trip, i)
                    BusScheduleStop(
                        stopId = stopId,
                        stopName = names[stopId] ?: stopId,
                        arrivalTime = "%02d:%02d".format((sec / 3600) % 24, (sec / 60) % 60),
                        isPassed = cur >= 0 && i < cur,
                        scheduledSec = sec
                    )
                }
            }

            // Find the trip info to get direction
            val tripInfo = tripId?.let { CarrisGtfsData.trip(it) }
            val direction = tripInfo?.direction ?: 0
            val resolvedRouteId = tripInfo?.routeId ?: routeId

            // Look up the schedule offsets for this route+direction
            val key = "${resolvedRouteId}_${direction}"
            val offsets = CarrisGtfsData.scheduleOffsets[key] ?: return@withContext emptyList()

            // Build stop name lookup
            val stopsById = CarrisGtfsData.stops.associateBy { it.id }

            // Find the current stop index in the offsets
            val currentStopIdx = if (currentStopId.isNotEmpty()) {
                offsets.indexOfFirst { it.first == currentStopId }
            } else -1

            // Calculate the "start time" of this trip
            // If we know the current stop and its offset, we can back-calculate start time
            val now = java.time.LocalTime.now()
            val startTime = if (currentStopIdx >= 0) {
                val currentOffset = offsets[currentStopIdx].second
                now.minusMinutes(currentOffset.toLong())
            } else {
                // Fallback: estimate from the first stop offset
                now
            }

            val result = mutableListOf<BusScheduleStop>()
            var foundCurrentStop = currentStopIdx == -1 || currentStopId.isEmpty()

            for ((stopId, offsetMinutes) in offsets) {
                val arrivalTime = startTime.plusMinutes(offsetMinutes.toLong())
                val timeStr = String.format("%02d:%02d", arrivalTime.hour, arrivalTime.minute)
                val stopName = stopsById[stopId]?.name ?: stopId

                if (stopId == currentStopId) foundCurrentStop = true
                val isPassed = !foundCurrentStop

                result.add(BusScheduleStop(
                    stopId = stopId,
                    stopName = stopName,
                    arrivalTime = timeStr,
                    isPassed = isPassed
                ))
            }

            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Get stops for a Carris Metropolitana route/pattern via API.
     */
    suspend fun getCmPatternStops(patternId: String): List<BusStop> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$CM_PATTERN_URL$patternId").build()
                val response = client.newCall(request).execute().closeIfFailed()
                if (!response.isSuccessful || response.body == null) return@withContext emptyList()

                val json = response.body!!.string()
                val obj = org.json.JSONObject(json)
                
                // Pattern has "path" array with stop info
                val path = obj.optJSONArray("path") ?: return@withContext emptyList()
                val stops = mutableListOf<BusStop>()
                
                for (i in 0 until path.length()) {
                    val point = path.getJSONObject(i)
                    val stop = point.optJSONObject("stop") ?: continue
                    stops.add(BusStop(
                        id = stop.optString("id", ""),
                        name = stop.optString("name", ""),
                        lat = stop.optDouble("lat", 0.0),
                        lon = stop.optDouble("lon", 0.0),
                        operator = "carris_metropolitana"
                    ))
                }
                stops
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    /**
     * Get stops for a Carris urban route from GTFS static data.
     */
    fun getCarrisRouteStops(context: Context, routeId: String): List<BusStop> {
        CarrisGtfsData.ensureLoaded(context)
        return CarrisGtfsData.routeStops[routeId]?.map { entry ->
            BusStop(
                id = entry.id,
                name = entry.name,
                lat = entry.lat,
                lon = entry.lon,
                operator = "carris"
            )
        } ?: emptyList()
    }

    /**
     * Get CM route shape (polyline) from the dedicated shapes endpoint.
     * The pattern response contains shape_id (string), NOT a shape object.
     * We must fetch from /shapes/{shape_id} which returns GeoJSON with coordinates.
     */
    suspend fun getCmPatternShape(patternId: String): List<Pair<Double, Double>>? =
        withContext(Dispatchers.IO) {
            try {
                // Step 1: Fetch pattern to get shape_id
                val patternReq = Request.Builder().url("$CM_PATTERN_URL$patternId").build()
                val patternResp = client.newCall(patternReq).execute().closeIfFailed()
                if (!patternResp.isSuccessful || patternResp.body == null) return@withContext null

                val patternJson = patternResp.body!!.string()
                val patternObj = org.json.JSONObject(patternJson)
                val shapeId = patternObj.optString("shape_id", "")

                if (shapeId.isNotEmpty()) {
                    // Step 2: Fetch shape from /shapes/{shape_id}
                    val shapeReq = Request.Builder().url("$CM_SHAPES_URL$shapeId").build()
                    val shapeResp = client.newCall(shapeReq).execute().closeIfFailed()
                    if (shapeResp.isSuccessful && shapeResp.body != null) {
                        val shapeJson = shapeResp.body!!.string()
                        val shapeObj = org.json.JSONObject(shapeJson)
                        val geojson = shapeObj.optJSONObject("geojson")
                        val geometry = geojson?.optJSONObject("geometry")
                        val coords = geometry?.optJSONArray("coordinates")
                            ?: geojson?.optJSONArray("coordinates") // Direct coordinates if Feature

                        if (coords != null && coords.length() > 0) {
                            val points = mutableListOf<Pair<Double, Double>>()
                            for (i in 0 until coords.length()) {
                                val c = coords.getJSONArray(i)
                                points.add(Pair(c.getDouble(1), c.getDouble(0))) // lat, lon
                            }
                            return@withContext points
                        }
                    }
                }

                // Fallback: build shape from stop coordinates in path array
                val path = patternObj.optJSONArray("path")
                if (path != null && path.length() > 0) {
                    return@withContext buildShapeFromPath(path)
                }

                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * Builds a polyline shape from the pattern's path array (stop coordinates).
     * Used as a fallback when shape endpoint is not available.
     */
    private fun buildShapeFromPath(path: org.json.JSONArray): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until path.length()) {
            val point = path.optJSONObject(i) ?: continue
            val stop = point.optJSONObject("stop") ?: continue
            val lat = stop.optDouble("lat", 0.0)
            val lon = stop.optDouble("lon", 0.0)
            if (lat != 0.0 && lon != 0.0) {
                points.add(Pair(lat, lon))
            }
        }
        return points
    }

    /**
     * Build a route shape polyline from a list of bus stops.
     * Uses a nearest-neighbor greedy algorithm to order stops geographically,
     * since Carris urban GTFS data doesn't provide stop sequences.
     * Starts from the westernmost stop and greedily connects to nearest unvisited.
     */
    fun buildShapeFromStops(stops: List<BusStop>): List<Pair<Double, Double>> {
        val filtered = stops.filter { it.lat != 0.0 && it.lon != 0.0 }
        if (filtered.size <= 2) return filtered.map { Pair(it.lat, it.lon) }

        val remaining = filtered.toMutableList()
        val ordered = mutableListOf<BusStop>()

        // Start from the westernmost stop (smallest longitude) as heuristic
        var current = remaining.minByOrNull { it.lon } ?: return emptyList()
        ordered.add(current)
        remaining.remove(current)

        while (remaining.isNotEmpty()) {
            val nearest = remaining.minByOrNull { stop ->
                val dlat = stop.lat - current.lat
                val dlon = stop.lon - current.lon
                dlat * dlat + dlon * dlon // squared distance (no sqrt needed)
            } ?: break
            ordered.add(nearest)
            remaining.remove(nearest)
            current = nearest
        }

        return ordered.map { Pair(it.lat, it.lon) }
    }

    /**
     * Data class for stop arrival information.
     */
    data class StopArrivalInfo(
        val routeShortName: String,
        val routeLongName: String,
        val destination: String,
        val etaMinutes: Int,
        val vehicleId: String?,
        val operator: String,
        /** Cor da linha (oficial da Carris ou da Carris Metropolitana). */
        val color: String = BusColors.FALLBACK,
        /** Com GPS (tempo real) ou só pelo horário (previsto). */
        val realtime: Boolean = true,
        /** Atraso em relação ao horário, em minutos (negativo = adiantado); null se não houver horário. */
        val delayMinutes: Int? = null,
        /** Hora do horário nesta paragem ("19:58"). */
        val scheduledTime: String? = null
    ) {
        /** "a horas", "atrasado 4 min", "previsto"… */
        val status: BusTiming.Status get() = BusTiming.status(realtime, delayMinutes)
        /** "4 min" / "a chegar" em tempo real; a hora ("19:58") nos previstos. */
        val etaText: String get() = BusTiming.eta(etaMinutes, realtime, scheduledTime)
    }

    /**
     * Próximas chegadas a uma paragem, em tempo real (com GPS, e o atraso em relação ao horário) ou
     * previstas (só pelo horário), como no Google Maps.
     * - Carris Metropolitana: chegadas da API, com a hora prevista e a estimada.
     * - Carris: horário do GTFS (CarrisTimetable) cruzado com a posição de cada autocarro.
     */
    suspend fun getStopArrivals(
        context: Context,
        stopId: String,
        operator: String,
        /** Até quando (segundos à frente): 2 h por omissão; o painel da paragem no mapa pede 5 h. */
        aheadSecs: Int = AHEAD_WINDOW_S,
        /** Quantas por linha e destino: 3 por omissão (widgets, listas); o mapa pede todas. */
        perLine: Int = 3
    ): List<StopArrivalInfo> = withContext(Dispatchers.IO) {
        when {
            operator == "carris_metropolitana" -> cmArrivals(stopId, aheadSecs)
            // Outras redes (MobiCascais, TCB, MTS, Fertagus, barcos, STCP, Metro do Porto): horário do pacote + direto
            // Redes da Eleven (Covilhã, Viseu, Guimarães…): as próximas passagens pelo horário
            operator.startsWith("eleven:") ->
                com.example.metrotime.data.regional.RegionalRepository.elevenStopArrivals(operator.removePrefix("eleven:"), stopId, aheadSecs)
            operator.startsWith("pack:") -> {
                com.example.metrotime.data.regional.RegionalRepository.init(context)
                com.example.metrotime.data.regional.RegionalRepository.stopArrivals(operator.removePrefix("pack:"), stopId, aheadSecs)
            }
            else -> carrisArrivals(context, stopId, aheadSecs, perLine)
        }
    }

    private val LISBON: java.time.ZoneId = java.time.ZoneId.of("Europe/Lisbon")
    /** Janela das chegadas: autocarros até 45 min atrasados e até 2 h à frente. */
    private const val PAST_WINDOW_S = 45 * 60
    const val AHEAD_WINDOW_S = 2 * 60 * 60
    /** O painel de uma paragem no mapa: todos os autocarros das próximas 5 horas. */
    const val STOP_SHEET_WINDOW_S = 5 * 60 * 60

    /** "19:58:30" ou "25:10:00" → "19:58" / "01:10". */
    private fun clockOf(text: String?): String? {
        val parts = text?.split(":")?.takeIf { it.size >= 2 } ?: return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return BusTiming.clock(h * 3600 + m * 60)
    }

    // ── Carris Metropolitana ────────────────────────────────────────────────

    private suspend fun cmArrivals(stopId: String, aheadSecs: Int): List<StopArrivalInfo> {
        val cmColors = runCatching { getCmLines() }.getOrDefault(emptyList()).associate { it.shortName to it.color }
        return try {
            val request = Request.Builder().url("https://api.carrismetropolitana.pt/v2/arrivals/by_stop/$stopId").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return cmEstimates(stopId, cmColors)
                val arr = JSONArray(response.body!!.string())
                val now = System.currentTimeMillis() / 1000
                val out = mutableListOf<StopArrivalInfo>()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (!a.isNull("observed_arrival_unix")) continue // já passou
                    val scheduled = if (a.isNull("scheduled_arrival_unix")) null else a.optLong("scheduled_arrival_unix").takeIf { it > 0 }
                    val estimated = if (a.isNull("estimated_arrival_unix")) null else a.optLong("estimated_arrival_unix").takeIf { it > 0 }
                    val at = estimated ?: scheduled ?: continue
                    if (at < now - 60 || at > now + aheadSecs) continue
                    // "[BNA17]2725" → "2725"
                    val line = a.optString("line_id").substringAfter("]").trim()
                    if (line.isEmpty()) continue
                    val headsign = a.optString("headsign")
                    out += StopArrivalInfo(
                        routeShortName = line,
                        routeLongName = headsign,
                        destination = headsign,
                        etaMinutes = ((at - now) / 60).toInt().coerceAtLeast(0),
                        vehicleId = a.optString("vehicle_id").takeIf { it.isNotEmpty() && it != "null" },
                        operator = "carris_metropolitana",
                        color = cmColors[line] ?: BusColors.FALLBACK,
                        realtime = estimated != null,
                        delayMinutes = if (estimated != null && scheduled != null) BusTiming.roundMinutes((estimated - scheduled).toInt()) else null,
                        scheduledTime = clockOf(a.optString("scheduled_arrival"))
                    )
                }
                out.sortedBy { it.etaMinutes }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            cmEstimates(stopId, cmColors)
        }
    }

    /** Alternativa: estimativas dos painéis (só algumas paragens). "INFO" é o aviso "sem tempo real", não um autocarro. */
    private fun cmEstimates(stopId: String, cmColors: Map<String, String>): List<StopArrivalInfo> = try {
        val body = """{"stops": ["$stopId"]}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://api.carrismetropolitana.pt/v2/pips/estimates").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val arr = JSONArray(response.body!!.string())
            val now = System.currentTimeMillis() / 1000
            (0 until arr.length()).mapNotNull { i ->
                val est = arr.getJSONObject(i)
                val line = est.optString("lineId")
                if (line.isEmpty() || line == "INFO") return@mapNotNull null
                val at = est.optLong("estimatedTimeUnixSeconds", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                val vehicle = est.optString("observedVehicleId").takeIf { it.isNotEmpty() && it != "null" }
                val planned = clockOf(est.optString("timetabledArrivalTime"))
                val expected = clockOf(est.optString("estimatedArrivalTime"))
                val delay = if (vehicle != null && planned != null && expected != null) {
                    val (ph, pm) = planned.split(":").map(String::toInt)
                    val (eh, em) = expected.split(":").map(String::toInt)
                    Math.floorMod(eh * 60 + em - (ph * 60 + pm) + 720, 1440) - 720
                } else null
                StopArrivalInfo(
                    routeShortName = line,
                    routeLongName = est.optString("stopHeadsign"),
                    destination = est.optString("stopHeadsign"),
                    etaMinutes = ((at - now) / 60).toInt().coerceAtLeast(0),
                    vehicleId = vehicle,
                    operator = "carris_metropolitana",
                    color = cmColors[line] ?: BusColors.FALLBACK,
                    realtime = vehicle != null,
                    delayMinutes = delay,
                    scheduledTime = planned
                )
            }.sortedBy { it.etaMinutes }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    // ── Carris ──────────────────────────────────────────────────────────────

    /** Um autocarro da Carris no GPS: viagem, onde vai no percurso e quando foi visto. */
    private class CarrisVehicle(
        val tripId: String?,
        val routeId: String?,
        val direction: Int?,
        val stopId: String?,
        val stopSequence: Int?,
        val stopped: Boolean,
        val timestamp: Long,
        val vehicleId: String?
    )

    @Volatile private var carrisVehiclesCache: Pair<Long, List<CarrisVehicle>>? = null
    private val carrisVehiclesLock = Any()

    /** Posições da Carris, guardadas 10 s: as paragens pedidas ao mesmo tempo usam o mesmo download. */
    private fun carrisVehicles(): List<CarrisVehicle>? = synchronized(carrisVehiclesLock) {
        carrisVehiclesCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 10_000) return list }
        val response = client.newCall(Request.Builder().url(CARRIS_VP_URL).build()).execute().closeIfFailed()
        if (!response.isSuccessful || response.body == null) return null
        val feed = response.body!!.byteStream().use { com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(it) }
        val list = feed.entityList.filter { it.hasVehicle() && it.vehicle.hasPosition() }.map { e ->
            val v = e.vehicle
            CarrisVehicle(
                tripId = if (v.hasTrip() && v.trip.hasTripId()) v.trip.tripId else null,
                routeId = if (v.hasTrip() && v.trip.hasRouteId()) v.trip.routeId else null,
                direction = if (v.hasTrip() && v.trip.hasDirectionId()) v.trip.directionId else null,
                stopId = if (v.hasStopId()) v.stopId else null,
                stopSequence = if (v.hasCurrentStopSequence()) v.currentStopSequence else null,
                stopped = v.hasCurrentStatus() &&
                    v.currentStatus == com.google.transit.realtime.GtfsRealtime.VehiclePosition.VehicleStopStatus.STOPPED_AT,
                timestamp = if (v.hasTimestamp()) v.timestamp else 0L,
                vehicleId = if (v.hasVehicle() && v.vehicle.hasId()) v.vehicle.id else null
            )
        }
        carrisVehiclesCache = System.currentTimeMillis() to list
        list
    }

    /** Onde vai o autocarro no percurso: pela sequência da paragem, confirmada pelo id (os circulares repetem paragens). */
    private fun positionIn(pattern: CarrisTimetable.Pattern, v: CarrisVehicle): Int {
        val seqIdx = v.stopSequence?.minus(1)
        if (seqIdx != null && seqIdx in pattern.stops.indices && pattern.stops[seqIdx] == v.stopId) return seqIdx
        val hits = pattern.stops.indices.filter { pattern.stops[it] == v.stopId }
        if (hits.isEmpty()) return seqIdx?.takeIf { it in pattern.stops.indices } ?: -1
        return if (seqIdx == null) hits.first() else hits.minBy { kotlin.math.abs(it - seqIdx) }
    }

    private fun carrisArrivals(context: Context, stopId: String, aheadSecs: Int, perLine: Int): List<StopArrivalInfo> {
        try {
            CarrisGtfsData.ensureLoaded(context)
            val tt = CarrisTimetable.load(context)
            val nowEpoch = System.currentTimeMillis() / 1000
            // GPS com mais de 5 min não conta como tempo real
            val vehicles = carrisVehicles().orEmpty().filter { it.timestamp == 0L || nowEpoch - it.timestamp < 300 }
            val liveByTrip = vehicles.filter { it.tripId != null }.associateBy { it.tripId!! }
            val now = java.time.ZonedDateTime.now(LISBON)
            val nowSec = now.toLocalTime().toSecondOfDay()
            val out = mutableListOf<StopArrivalInfo>()

            fun info(route: String, direction: Int, eta: Int, realtime: Boolean, delayMin: Int?, sched: Int?, vehicleId: String?): StopArrivalInfo? {
                val names = CarrisGtfsData.routes[normalizeRouteId(route)] ?: return null
                return StopArrivalInfo(
                    routeShortName = names.shortName,
                    routeLongName = names.longName,
                    destination = extractDestination(names.longName, direction),
                    etaMinutes = eta.coerceAtLeast(0),
                    vehicleId = vehicleId,
                    operator = "carris",
                    color = BusColors.carris(names.shortName),
                    realtime = realtime,
                    delayMinutes = delayMin,
                    scheduledTime = sched?.let { BusTiming.clock(it) }
                )
            }

            if (tt != null) {
                // Hoje, e as viagens de ontem que passam da meia-noite (horas como 25:10)
                val days = listOf(0 to now.toLocalDate(), 86_400 to now.toLocalDate().minusDays(1))
                    .map { (shift, date) -> shift to tt.services.map { it.runsOn(date) } }
                for ((p, stopIdx) in tt.byStop[stopId].orEmpty()) {
                    val pattern = tt.patterns[p]
                    // No fim do percurso ninguém apanha o autocarro (exceto nos circulares)
                    if (stopIdx == pattern.stops.lastIndex && pattern.stops.size > 1 && pattern.stops.first() != stopId) continue
                    for ((shift, active) in days) {
                        for (i in pattern.starts.indices) {
                            if (!active[pattern.service[i]]) continue
                            val sched = pattern.timeAt(i, stopIdx) - shift
                            if (sched < nowSec - PAST_WINDOW_S || sched > nowSec + aheadSecs) continue
                            val live = liveByTrip[tt.tripId(pattern, i)]
                            val cur = live?.let { positionIn(pattern, it) } ?: -1
                            if (live != null && cur > stopIdx) continue // já passou nesta paragem
                            if (live != null && cur >= 0) {
                                val delay = BusTiming.delaySeconds(
                                    if (cur > 0) pattern.timeAt(i, cur - 1) - shift else null,
                                    pattern.timeAt(i, cur) - shift, nowSec, live.stopped
                                )
                                val eta = if (cur == stopIdx && live.stopped) 0
                                else BusTiming.roundMinutes(sched + delay - nowSec)
                                info(pattern.routeId, pattern.direction, eta, true, BusTiming.roundMinutes(delay), sched, live.vehicleId)
                                    ?.let { out += it }
                            } else if (sched >= nowSec - 60) {
                                // Sem GPS, ou com GPS mas sem se saber onde vai no percurso: só o horário
                                info(pattern.routeId, pattern.direction, (sched - nowSec) / 60, false, null, sched, null)?.let { out += it }
                            }
                        }
                    }
                }

                // Autocarros com GPS que não estão no horário (reforços, viagens novas): no percurso da linha e
                // do sentido deles, pela paragem onde vão. Os que já passaram, ou que não se sabe onde vão,
                // ficam de fora (pela distância em linha reta aparecia um 728 que já tinha passado).
                for (v in vehicles) {
                    if (v.tripId != null && tt.ref(v.tripId) != null) continue
                    val place = tt.placeUnlisted(
                        v.routeId ?: continue, v.direction, v.stopId, v.stopSequence, v.stopped, stopId, nowSec
                    ) ?: continue
                    val eta = BusTiming.roundMinutes(place.etaSeconds)
                    if (eta * 60 > aheadSecs) continue
                    val pattern = tt.patterns[place.pattern]
                    val a = info(pattern.routeId, pattern.direction, eta, true, null, null, v.vehicleId) ?: continue
                    // Se for uma viagem do horário com outro número, é o mesmo autocarro que o previsto àquela hora
                    out.filter { !it.realtime && it.routeShortName == a.routeShortName && it.destination == a.destination }
                        .filter { kotlin.math.abs(it.etaMinutes - eta) <= 3 }
                        .minByOrNull { kotlin.math.abs(it.etaMinutes - eta) }
                        ?.let { out.remove(it) }
                    out += a
                }
            }

            // Até [perLine] por linha e destino, do mais perto para o mais longe
            return out.sortedBy { it.etaMinutes }
                .groupBy { it.routeShortName to it.destination }.values
                .flatMap { it.take(perLine) }
                .sortedBy { it.etaMinutes }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    // Cached CM stops
    @Volatile private var cmStops: List<BusStop>? = null
    @Volatile private var cmStopsLoading = false

    /**
     * Get all Carris Metropolitana stops.
     * Caches the result in memory since it's a large payload (~12k stops).
     */
    suspend fun getAllCmStops(): List<BusStop> = withContext(Dispatchers.IO) {
        cmStops?.let { return@withContext it }
        if (cmStopsLoading) return@withContext emptyList()

        cmStopsLoading = true
        try {
            val request = Request.Builder().url(CM_STOPS_URL).build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (!response.isSuccessful || response.body == null) {
                cmStopsLoading = false
                return@withContext emptyList()
            }

            val json = response.body!!.string()
            val arr = JSONArray(json)
            val stops = mutableListOf<BusStop>()

            for (i in 0 until arr.length()) {
                val stop = arr.getJSONObject(i)
                stops.add(BusStop(
                    id = stop.optString("id", ""),
                    name = stop.optString("name", ""),
                    lat = stop.optDouble("lat", 0.0),
                    lon = stop.optDouble("lon", 0.0),
                    operator = "carris_metropolitana"
                ))
            }
            cmStops = stops
            cmStopsLoading = false
            return@withContext stops
        } catch (e: Exception) {
            cmStopsLoading = false
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /**
     * Find a live bus across all running buses by its vehicleId.
     * Looks in both Carris Metropolitana and Carris (Lisboa).
     */
    /**
     * Um autocarro com GPS pelo número do veículo. [operator] e [line] (das chegadas a uma paragem) evitam
     * confundir veículos com o mesmo número; nas chegadas da Carris Metropolitana o número vem sem o
     * prefixo do operador ("2504" em vez de "[BNA17]2504").
     */
    suspend fun findLiveBusByVehicleId(
        context: Context,
        vehicleId: String,
        operator: String? = null,
        line: String? = null
    ): LiveBus? = withContext(Dispatchers.IO) {
        // 1. Try Carris Metropolitana (REST API)
        if (operator != "carris") try {
            val request = Request.Builder().url(CM_VEHICLES_URL).build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (response.isSuccessful && response.body != null) {
                val json = response.body!!.string()
                val arr = JSONArray(json)
                val matches = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { v ->
                    val id = v.optString("id", "")
                    id == vehicleId || id.substringAfter("]") == vehicleId
                }
                // Com o mesmo número em dois operadores, o da linha certa
                val v = matches.firstOrNull { line != null && it.optString("line_id", "").substringAfter("]") == line }
                    ?: matches.firstOrNull()
                if (v != null) {
                    val id = v.optString("id", "")
                    run {
                        val lineId = v.optString("line_id", "")
                        val lat = v.optDouble("lat", 0.0)
                        val lon = v.optDouble("lon", 0.0)
                        val patternId = v.optString("pattern_id", "")
                        val cmDirection = v.optInt("direction_id", -1).takeIf { it >= 0 } ?: getCmPatternDirection(patternId)
                        return@withContext LiveBus(
                            vehicleId = id,
                            routeId = v.optString("route_id", ""),
                            routeShortName = lineId,
                            lineId = lineId,
                            lat = lat,
                            lon = lon,
                            bearing = v.optDouble("bearing", 0.0).toFloat(),
                            speed = v.optDouble("speed", 0.0).toFloat(),
                            destination = "",
                            tripId = v.optString("trip_id", "").ifEmpty { null },
                            operator = "carris_metropolitana",
                            currentStatus = v.optString("current_status", ""),
                            direction = cmDirection,
                            patternId = patternId,
                            currentStopId = v.optString("stop_id", "")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Try Carris (Lisboa) (GTFS-RT protobuf)
        if (operator != "carris_metropolitana") try {
            CarrisGtfsData.ensureLoaded(context)
            val request = Request.Builder().url(CARRIS_VP_URL).build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (response.isSuccessful && response.body != null) {
                val feed = response.body!!.byteStream().use { FeedMessage.parseFrom(it) }
                for (entity in feed.entityList) {
                    if (!entity.hasVehicle()) continue
                    val vehicle = entity.vehicle
                    val vId = if (vehicle.hasVehicle() && vehicle.vehicle.hasId()) vehicle.vehicle.id else ""
                    if (vId == vehicleId) {
                        if (!vehicle.hasPosition()) continue
                        val pos = vehicle.position
                        val tripId = if (vehicle.hasTrip() && vehicle.trip.hasTripId()) vehicle.trip.tripId else null
                        val tripInfo = tripId?.let { CarrisGtfsData.trip(it) }
                        val routeId = tripInfo?.routeId
                            ?: (if (vehicle.hasTrip() && vehicle.trip.hasRouteId()) vehicle.trip.routeId else null)
                            ?: continue
                        val normalizedRouteId = normalizeRouteId(routeId)
                        val routeInfo = CarrisGtfsData.routes[normalizedRouteId] ?: continue
                        return@withContext LiveBus(
                            vehicleId = vId,
                            routeId = routeId,
                            routeShortName = routeInfo.shortName,
                            lineId = routeInfo.shortName,
                            lat = pos.latitude.toDouble(),
                            lon = pos.longitude.toDouble(),
                            bearing = if (pos.hasBearing()) pos.bearing else 0f,
                            speed = if (pos.hasSpeed()) pos.speed else 0f,
                            destination = extractDestination(routeInfo.longName, tripInfo?.direction ?: 0),
                            tripId = tripId,
                            operator = "carris",
                            direction = tripInfo?.direction ?: -1,
                            currentStopId = if (vehicle.hasStopId()) vehicle.stopId else ""
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        null
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    internal suspend fun getCmLines(): List<BusRoute> {
        cmLines?.let { return it }
        if (cmLinesLoading) return emptyList()

        cmLinesLoading = true
        try {
            val request = Request.Builder().url(CM_LINES_URL).build()
            val response = client.newCall(request).execute().closeIfFailed()
            if (!response.isSuccessful || response.body == null) {
                cmLinesLoading = false
                return emptyList()
            }

            val json = response.body!!.string()
            val arr = JSONArray(json)
            val lines = mutableListOf<BusRoute>()

            for (i in 0 until arr.length()) {
                val line = arr.getJSONObject(i)
                val patterns = mutableListOf<String>()
                val patternsArr = line.optJSONArray("patterns")
                if (patternsArr != null) {
                    for (j in 0 until patternsArr.length()) {
                        patterns.add(patternsArr.getString(j))
                    }
                }
                lines.add(BusRoute(
                    routeId = line.optString("id", ""),
                    shortName = line.optString("short_name", ""),
                    longName = line.optString("long_name", ""),
                    color = BusColors.normalize(line.optString("color")) ?: BusColors.FALLBACK,
                    operator = "carris_metropolitana",
                    patterns = patterns
                ))
            }

            cmLines = lines
            cmLinesLoading = false
            return lines
        } catch (e: Exception) {
            cmLinesLoading = false
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun extractDestination(longName: String, direction: Int): String {
        if (" - " !in longName) return longName
        val parts = longName.split(" - ")
        return if (parts.size >= 2) {
            if (direction == 0) parts.last().trim()
            else parts.first().trim()
        } else longName
    }
}
