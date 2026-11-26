package com.example.metrotime.data.bus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Shared singleton for Carris urban GTFS static data.
 * Loaded once from compressed JSON assets.
 */
object CarrisGtfsData {

    data class StopEntry(val id: String, val name: String, val lat: Double, val lon: Double)
    data class RouteEntry(val id: String, val shortName: String, val longName: String)
    data class TripEntry(val routeId: String, val direction: Int)

    @Volatile var stops: List<StopEntry> = emptyList(); private set
    @Volatile var routes: Map<String, RouteEntry> = emptyMap(); private set
    @Volatile var stopRoutes: Map<String, List<String>> = emptyMap(); private set

    /** Horário atual da Carris (viagens, tempos entre paragens); null se não carregou. */
    @Volatile private var timetable: CarrisTimetable.Data? = null

    /** Linha e sentido de uma viagem do GPS, pelo horário atual. */
    fun trip(tripId: String): TripEntry? {
        val tt = timetable ?: return null
        val ref = tt.ref(tripId) ?: return null
        val pattern = tt.patterns[ref shr 16]
        return TripEntry(pattern.routeId, pattern.direction)
    }

    // Inverse mapping: route_id -> list of stop_ids
    @Volatile var routeStops: Map<String, List<StopEntry>> = emptyMap(); private set

    // Route shapes: "route_id_direction" -> [[lat, lon], [lat, lon], ...]
    // These follow the actual roads (from GTFS shapes.txt)
    @Volatile var shapes: Map<String, List<Pair<Double, Double>>> = emptyMap(); private set

    // Ordered stop sequences: "route_id_direction" -> [stop_id_1, stop_id_2, ...]
    // Correctly separated by direction (from GTFS stop_times.txt)
    @Volatile var stopOrder: Map<String, List<String>> = emptyMap(); private set

    // Schedule offsets: "route_id_direction" -> [[stop_id, offset_minutes], ...]
    // offset_minutes is relative to the trip's first stop departure (do horário: o percurso com mais
    // viagens e os tempos mais comuns)
    @Volatile var scheduleOffsets: Map<String, List<Pair<String, Int>>> = emptyMap(); private set

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return

        fun readGzAsset(name: String): String =
            InputStreamReader(GZIPInputStream(context.assets.open(name))).use { it.readText() }

        // 1. Load stops
        val stopsJson = readGzAsset("carris_stops.json.bin")
        val stopsArr = JSONArray(stopsJson)
        val stopsList = mutableListOf<StopEntry>()
        val stopsById = mutableMapOf<String, StopEntry>()
        for (i in 0 until stopsArr.length()) {
            val obj = stopsArr.getJSONObject(i)
            val entry = StopEntry(
                id = obj.getString("id"),
                name = obj.getString("name"),
                lat = obj.getDouble("lat"),
                lon = obj.getDouble("lon")
            )
            stopsList.add(entry)
            stopsById[entry.id] = entry
        }
        stops = stopsList

        // 2. Load routes (o horário atual tem os números e nomes mais recentes e ganha)
        val routesJson = readGzAsset("carris_routes.json.bin")
        val routesObj = JSONObject(routesJson)
        val routesMap = mutableMapOf<String, RouteEntry>()
        routesObj.keys().forEach { key ->
            val r = routesObj.getJSONObject(key)
            routesMap[key] = RouteEntry(
                id = key,
                shortName = r.getString("short_name"),
                longName = r.getString("long_name")
            )
        }
        val tt = CarrisTimetable.load(context)
        timetable = tt
        tt?.routes?.forEach { (id, names) ->
            if (names.first.isNotBlank()) routesMap[id] = RouteEntry(id, names.first, names.second)
        }
        routes = routesMap

        // 3. Load stop -> route mapping
        val srJson = readGzAsset("carris_stop_routes.json.bin")
        val srObj = JSONObject(srJson)
        val srMap = mutableMapOf<String, List<String>>()
        srObj.keys().forEach { key ->
            val arr = srObj.getJSONArray(key)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            srMap[key] = list
        }
        stopRoutes = srMap

        // 4. As viagens (trip_id -> linha e sentido) vêm do horário: ver trip()

        // 5. Build inverse mapping: route_id -> stops
        val rsMap = mutableMapOf<String, MutableSet<String>>()
        for ((stopId, routeIds) in srMap) {
            for (routeId in routeIds) {
                rsMap.getOrPut(routeId) { mutableSetOf() }.add(stopId)
            }
        }
        routeStops = rsMap.mapValues { (_, stopIds) ->
            stopIds.mapNotNull { stopsById[it] }
        }

        // 6. Load shapes (road-following geometries from GTFS shapes.txt)
        try {
            val shapesJson = readGzAsset("carris_shapes.json.bin")
            val shapesObj = JSONObject(shapesJson)
            val shapesMap = mutableMapOf<String, List<Pair<Double, Double>>>()
            shapesObj.keys().forEach { key ->
                val arr = shapesObj.getJSONArray(key)
                val points = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until arr.length()) {
                    val pt = arr.getJSONArray(i)
                    points.add(Pair(pt.getDouble(0), pt.getDouble(1)))
                }
                shapesMap[key] = points
            }
            shapes = shapesMap
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 7. Load ordered stop sequences (from GTFS stop_times.txt)
        try {
            val soJson = readGzAsset("carris_stop_order.json.bin")
            val soObj = JSONObject(soJson)
            val soMap = mutableMapOf<String, List<String>>()
            soObj.keys().forEach { key ->
                val arr = soObj.getJSONArray(key)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                soMap[key] = list
            }
            stopOrder = soMap
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 8. Tempos entre paragens por linha e sentido, do horário
        scheduleOffsets = tt?.let { offsetsFrom(it) }.orEmpty()

        loaded = true
    }

    /** Por linha e sentido, o percurso com mais viagens e o perfil de tempos mais usado nele. */
    private fun offsetsFrom(tt: CarrisTimetable.Data): Map<String, List<Pair<String, Int>>> =
        tt.patterns.groupBy { "${it.routeId}_${it.direction}" }.mapValues { (_, list) ->
            val main = list.maxBy { it.starts.size }
            val profile = main.profile.toList().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
            main.stops.mapIndexed { i, stop -> stop to main.profiles[profile][i] }
        }

    /**
     * Search routes by shortName, longName, or by stop names that routes serve.
     * Returns matching routes with the reason for the match.
     */
    fun searchRoutes(query: String): List<RouteEntry> {
        if (!loaded || query.isBlank()) return emptyList()
        val q = query.trim().lowercase()

        val matchedRouteIds = mutableSetOf<String>()

        // 1. Direct route match (by shortName or longName)
        for ((id, route) in routes) {
            if (route.shortName.lowercase().contains(q) ||
                route.longName.lowercase().contains(q)) {
                matchedRouteIds.add(id)
            }
        }

        // 2. Stop name match -> get routes serving those stops
        if (q.length >= 3) { // Only search stops for queries >= 3 chars
            for ((stopId, stop) in stops.associateBy { it.id }) {
                if (stop.name.lowercase().contains(q)) {
                    stopRoutes[stopId]?.let { matchedRouteIds.addAll(it) }
                }
            }
        }

        // Prefer the _0 variant (primary route) for each shortName
        return matchedRouteIds.mapNotNull { routes[it] }
            .groupBy { it.shortName }
            .map { (_, variants) ->
                // Sort by route id to prefer _0 variant (e.g., "204_0" before "204_3")
                variants.sortedBy { it.id }.first()
            }
            .sortedBy { it.shortName }
    }
}
