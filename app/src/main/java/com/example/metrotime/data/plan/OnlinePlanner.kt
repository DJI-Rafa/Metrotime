package com.example.metrotime.data.plan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Planeador online: o Transitous (MOTIS, um serviço aberto e comunitário com os horários GTFS de todo o país) para
 * as viagens com os transportes que a app não calcula sozinha (Fertagus, barcos, MobiCascais, TCB, MTS, Metro e
 * autocarros do Porto, comboios fora de Lisboa…) e a pesquisa de moradas (ruas, localidades e sítios).
 * O pedido leva só as coordenadas de partida e chegada e a hora; nada do utilizador.
 */
object OnlinePlanner {

    private const val BASE = "https://api.transitous.org/api"
    private const val USER_AGENT = "Metrotime/3.9 (app de transportes)"
    private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── Moradas ─────────────────────────────────────────────────────────────────

    /** Ruas, localidades e sítios de Portugal com [query] (os mais perto de [near] primeiro, quando se sabe). */
    suspend fun geocode(query: String, near: Pair<Double, Double>? = null, max: Int = 8): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 3) return@withContext emptyList()
        // Puxado para perto de ti (ou para o centro do país): sem isto vinham sobretudo ruas do Brasil
        val (lat, lon) = near ?: (39.6 to -8.0)
        val url = "$BASE/v1/geocode?text=${enc(q)}&language=pt&place=$lat,$lon&placeBias=5&numResults=20"
        val json = runCatching { get(url) }.getOrNull() ?: return@withContext emptyList()
        runCatching { parseGeocode(json) }.getOrDefault(emptyList()).take(max)
    }

    private val LOCALITY = setOf("place", "city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter", "locality", "municipality", "borough")

    /** Coisas que não são sítios para onde se vai (contentores, bancos de jardim…). */
    private val NOISE = setOf("recycling", "waste", "bench", "vending", "drinking", "post")

    /** A resposta do /geocode em sítios: só os de Portugal, com o que é e onde fica ("Rua · Santa Maria Maior, Lisboa"). */
    internal fun parseGeocode(json: String): List<Place> {
        val arr = JSONArray(json)
        val out = ArrayList<Place>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("country") != "PT") continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val type = o.optString("type")
            val category = o.optString("category").substringBefore("_")
            if (category in NOISE) continue
            val baseName = o.optString("name").trim()
            if (baseName.isEmpty()) continue
            val house = o.optString("houseNumber").trim().takeIf { it.isNotEmpty() && it != "null" }
            val name = if (type == "ADDRESS" && house != null) "$baseName, $house" else baseName
            // As zonas: a freguesia (a "default") e o concelho; nada repetido nem igual ao nome
            val areas = o.optJSONArray("areas") ?: JSONArray()
            val zoneNames = (0 until areas.length()).mapNotNull { k -> areas.optJSONObject(k) }
                .filter { a -> a.optBoolean("default") || a.optDouble("adminLevel", 0.0).toInt() in 6..8 }
                .sortedByDescending { a -> a.optDouble("adminLevel", 0.0) }
                .map { a -> a.optString("name") }
                .filter { it.isNotBlank() && it != baseName && it != "Portugal" }
                .distinct()
                .take(2)
            val what = when {
                type == "STOP" -> "Paragem ou estação"
                type == "ADDRESS" -> if (house != null) "Morada" else "Rua"
                category in LOCALITY -> "Localidade"
                else -> "Sítio"
            }
            val detail = listOf(what, zoneNames.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · ")
            if (!seen.add("$name|$detail")) continue
            val id = o.optString("id").ifEmpty { "%.5f,%.5f".format(java.util.Locale.ROOT, lat, lon) }
            out += Place(Place.Kind.ADDRESS, id, name, lat, lon, detail)
        }
        return out
    }

    // ── Viagens ─────────────────────────────────────────────────────────────────

    private fun modesParam(modes: Set<LegMode>): String {
        val all = setOf(LegMode.METRO, LegMode.BUS, LegMode.TRAIN, LegMode.BOAT)
        if (modes.containsAll(all)) return ""
        val list = buildList {
            if (LegMode.METRO in modes) add("SUBWAY")
            if (LegMode.TRAIN in modes) addAll(listOf("SUBURBAN", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "LONG_DISTANCE", "HIGHSPEED_RAIL", "NIGHT_RAIL"))
            if (LegMode.BUS in modes) addAll(listOf("BUS", "COACH", "TRAM", "FUNICULAR", "AERIAL_LIFT"))
            if (LegMode.BOAT in modes) add("FERRY")
        }
        return "&transitModes=" + list.joinToString(",")
    }

    /**
     * Viagens de [from] para [to]: a partir de [time] ou, com [arriveBy], para chegar até [time]. Vazio se o serviço
     * não responder (a app continua com o que calcula sozinha).
     */
    suspend fun plan(from: Place, to: Place, time: LocalDateTime, arriveBy: Boolean, modes: Set<LegMode>): List<PlanOption> =
        withContext(Dispatchers.IO) {
            val t = time.atZone(ZONE).toInstant().toString()
            val url = "$BASE/v5/plan?fromPlace=${from.lat},${from.lon}&toPlace=${to.lat},${to.lon}" +
                "&time=${enc(t)}&arriveBy=$arriveBy&language=pt" + modesParam(modes)
            val json = get(url) ?: return@withContext emptyList()
            parsePlan(json, from.name, to.name)
        }

    private fun time(s: String?): LocalDateTime? =
        s?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).atZone(ZONE).toLocalDateTime() }.getOrNull() }

    private fun hex(s: String?): String? = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.let { "#" + it.uppercase() }

    private val LISBON_METRO = mapOf("Az" to "Azul", "Am" to "Amarela", "Vd" to "Verde", "Vm" to "Vermelha")

    /** O serviço da CP pelo tipo de comboio (para o mesmo sinal dos comboios da app). */
    private fun cpService(mode: String): String = when (mode) {
        "SUBURBAN" -> "U"
        "REGIONAL_FAST_RAIL" -> "IR"
        "LONG_DISTANCE" -> "IC"
        "HIGHSPEED_RAIL" -> "AP"
        else -> "R"
    }

    /** "pt-Comboios-de-Portugal(CP)_94_69005" → "94-69005" (o código da estação na CP). */
    private fun cpCode(stopId: String?): String? =
        stopId?.let { Regex("_(\\d+)_(\\d+)$").find(it) }?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

    /** "pt-Carris-Metropolitana_149996" → "149996". */
    private fun cmStop(stopId: String?): String? =
        stopId?.takeIf { it.contains("Carris-Metropolitana") }?.substringAfterLast("_")?.takeIf { it.all(Char::isDigit) }

    /** A resposta do /plan em opções do planeador (com os nomes de partida e chegada no lugar de START e END). */
    internal fun parsePlan(json: String, fromName: String, toName: String): List<PlanOption> {
        val root = JSONObject(json)
        val its = root.optJSONArray("itineraries") ?: return emptyList()
        val out = ArrayList<PlanOption>()
        for (i in 0 until its.length()) {
            val it = its.optJSONObject(i) ?: continue
            val legsJson = it.optJSONArray("legs") ?: continue
            val legs = ArrayList<PlanLeg>()
            var ok = true
            for (k in 0 until legsJson.length()) {
                val l = legsJson.optJSONObject(k) ?: continue
                val leg = parseLeg(l, fromName, toName)
                if (leg == null) { ok = false; break }
                legs += leg
            }
            if (!ok || legs.isEmpty()) continue
            // Bocados a pé seguidos juntam-se; os de poucos segundos saem
            val merged = ArrayList<PlanLeg>()
            for (leg in legs) {
                val last = merged.lastOrNull()
                if (leg.mode == LegMode.WALK && last?.mode == LegMode.WALK) {
                    merged[merged.lastIndex] = last.copy(
                        arrive = leg.arrive, toName = leg.toName, toLat = leg.toLat, toLon = leg.toLon,
                        path = last.path + leg.path, metres = (last.metres ?: 0.0) + (leg.metres ?: 0.0)
                    )
                } else merged += leg
            }
            val cleaned = merged.filter { l -> l.mode != LegMode.WALK || l.minutes >= 1 || merged.size == 1 }
            if (cleaned.isNotEmpty()) out += PlanOption(cleaned)
        }
        return out
    }

    private fun parseLeg(l: JSONObject, fromName: String, toName: String): PlanLeg? {
        val mode = l.optString("mode")
        val depart = time(l.optString("startTime")) ?: return null
        val arrive = time(l.optString("endTime")) ?: return null
        val f = l.optJSONObject("from") ?: JSONObject()
        val t = l.optJSONObject("to") ?: JSONObject()
        fun name(o: JSONObject, fallback: String): String = o.optString("name").let {
            if (it.isBlank() || it == "START" || it == "END") fallback else it
        }
        val from = name(f, fromName)
        val to = name(t, toName)
        val fLat = f.optDouble("lat", Double.NaN).takeIf { !it.isNaN() }
        val fLon = f.optDouble("lon", Double.NaN).takeIf { !it.isNaN() }
        val tLat = t.optDouble("lat", Double.NaN).takeIf { !it.isNaN() }
        val tLon = t.optDouble("lon", Double.NaN).takeIf { !it.isNaN() }
        val agency = l.optString("agencyName").trim().takeIf { it.isNotEmpty() }
        val short = l.optString("routeShortName").ifBlank { l.optString("displayName") }.trim()
        val color = hex(l.optString("routeColor"))
        val headsign = l.optString("headsign").trim().takeIf { it.isNotEmpty() }
        val stops = l.optJSONArray("intermediateStops")?.length()?.plus(1)
        val realtime = l.optBoolean("realTime", false)
        val towards = headsign?.let { "sentido $it" }
        val base = PlanLeg(
            mode = LegMode.WALK, depart = depart, arrive = arrive, fromName = from, toName = to, label = "A pé",
            realtime = realtime, agency = agency, fromLat = fLat, fromLon = fLon, toLat = tLat, toLon = tLon
        )
        return when (mode) {
            "WALK" -> {
                val metres = l.optDouble("distance", Double.NaN).takeIf { !it.isNaN() }
                val geom = l.optJSONObject("legGeometry")
                val path = geom?.let { g -> decodePolyline(g.optString("points"), g.optInt("precision", 6)) }.orEmpty()
                base.copy(metres = metres, path = path, detail = metres?.let { TripPlanner.distanceText(it) } ?: "")
            }
            "SUBWAY", "METRO" -> {
                val lisbon = agency?.contains("Metropolitano de Lisboa") == true
                val lineName = if (lisbon) LISBON_METRO[short] else null
                val count = stops?.let { if (it == 1) "1 estação" else "$it estações" }
                base.copy(
                    mode = LegMode.METRO,
                    label = if (lineName != null) "Linha $lineName" else "Metro $short".trim(),
                    color = lineName ?: (color ?: "#004F9F"),
                    metroTowards = headsign,
                    detail = listOfNotNull(if (lineName == null) agency else null, towards, count).joinToString(" · ")
                )
            }
            "SUBURBAN", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "LONG_DISTANCE", "HIGHSPEED_RAIL", "NIGHT_RAIL", "RAIL" -> {
                val cp = agency?.contains("Comboios de Portugal") == true
                val fertagus = agency?.contains("Fertagus", ignoreCase = true) == true
                val service = if (fertagus) "FT" else cpService(mode)
                val number = if (cp) l.optString("tripShortName").trim().toIntOrNull() else null
                base.copy(
                    mode = LegMode.TRAIN,
                    label = service,
                    color = service,
                    trainNumber = number,
                    runDate = if (number != null) depart.toLocalDate() else null,
                    service = service,
                    trainDestination = headsign,
                    fromCode = if (cp) cpCode(f.optString("stopId")) else null,
                    toCode = if (cp) cpCode(t.optString("stopId")) else null,
                    detail = listOfNotNull(
                        if (fertagus) "Fertagus" else short.takeIf { it.isNotEmpty() && it != service },
                        towards,
                        number?.let { "comboio $it" }
                    ).joinToString(" · ")
                )
            }
            "FERRY" -> base.copy(
                mode = LegMode.BOAT,
                label = "Barco",
                color = color ?: "#0E7490",
                detail = listOfNotNull(agency?.substringAfter(" - ")?.trim(), towards).joinToString(" · ")
            )
            "BUS", "COACH", "TRAM", "FUNICULAR", "AERIAL_LIFT", "CABLE_CAR" -> {
                val cm = agency?.contains("Carris Metropolitana") == true
                val boardStop = if (cm) cmStop(f.optString("stopId")) else null
                base.copy(
                    mode = LegMode.BUS,
                    label = short.ifEmpty { agency ?: "Autocarro" },
                    color = color ?: "#8C8C99",
                    detail = listOfNotNull(agency, towards).joinToString(" · "),
                    busOperator = if (boardStop != null) "carris_metropolitana" else null,
                    busBoardStop = boardStop,
                    busBoardLat = fLat,
                    busBoardLon = fLon,
                    busAlightStop = if (cm) cmStop(t.optString("stopId")) else null,
                    busAlightLat = tLat,
                    busAlightLon = tLon,
                    busDestination = headsign
                )
            }
            // Bicicleta, carro… não são para aqui
            else -> null
        }
    }

    /** Polilinha codificada (Google) com [precision] casas decimais (o MOTIS usa 6). */
    fun decodePolyline(encoded: String, precision: Int = 5): List<Pair<Double, Double>> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (pass in 0..1) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    if (index >= encoded.length) return out
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (pass == 0) lat += delta else lon += delta
            }
            out += (lat / factor) to (lon / factor)
        }
        return out
    }
}
