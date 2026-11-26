package com.example.metrotime.data.regional

import org.json.JSONArray
import org.json.JSONObject

/**
 * Veículos em direto fora da Carris e da Carris Metropolitana, de duas fontes abertas e sem chave:
 *  - o GO Hub da TML (go.tmlmobilidade.pt/hub/api/v1): MobiCascais, TC Barreiro, MTS, Fertagus e os barcos da
 *    Transtejo/Soflusa. Dados CC BY 4.0: a TML pede que se diga de onde vêm.
 *  - a plataforma da Eleven Systems (<cidade>.elevensystems.pt/api), a mesma dos mapas oficiais da Covilhã,
 *    Viseu Dão Lafões, Guimarães, Castelo Branco, Vila Real, Guarda, Lamego, Terras de Trás-os-Montes e Sabugal.
 *
 * Do GO Hub ficam de fora a Carris e a Carris Metropolitana (a app já as tem, com horários e paragens), o Metro de
 * Lisboa e a CP (têm camadas próprias no mapa). Sem dependências Android (testado em app/src/test).
 */

enum class VehicleKind { BUS, TRAM, BOAT, TRAIN, SUBWAY }

/**
 * De onde vêm as posições: GO Hub da TML, Eleven Systems, o broker FIWARE da Porto Digital (STCP), calculadas pelo
 * horário (Metro do Porto: não publica posições em direto), ou nenhuma.
 */
/** CITY: a Carris e a Carris Metropolitana, lidas pela própria app (todos os autocarros, como pontos). */
enum class RegionalSource { TML, ELEVEN, STCP, SCHEDULE, CITY, NONE }

/** Região de cada operador (para os filtros do mapa). */
enum class RegionalArea(val label: String) {
    LISBOA("Lisboa"),
    PORTO("Porto"),
    NORTE("Norte"),
    CENTRO("Centro")
}

data class RegionalOperator(
    /** "tml:HF16N", "eleven:cov". */
    val key: String,
    val name: String,
    /** Vai na pílula enquanto não se sabe a linha. */
    val shortName: String,
    /** "#RRGGBB": a cor da pílula quando a fonte não dá a da linha. */
    val color: String,
    val kind: VehicleKind,
    val source: RegionalSource,
    /** agency_id no GO Hub; subdomínio na Eleven. */
    val code: String,
    val area: RegionalArea = RegionalArea.LISBOA,
    /** Pacote de paragens e horários (assets/transit_<código>.bin); null se não houver. */
    val pack: String? = null
)

data class RegionalVehicle(
    val operator: RegionalOperator,
    val vehicleId: String,
    val lat: Double,
    val lon: Double,
    /** Número da linha ("45", "SX"); null enquanto não se sabe. */
    val line: String?,
    /** "#RRGGBB". */
    val color: String,
    /** "IN_TRANSIT_TO", "STOPPED_AT", "INCOMING_AT" (GO Hub); a Eleven não diz nada de útil aqui. */
    val status: String? = null,
    /** Hora da posição (ms); 0 se a fonte não disser. */
    val observedAt: Long = 0L,
    /** Viagem (GO Hub: "[1NN3V][HF16N]M45-1-…"; STCP: "107_0_1|340|D1|T1|N15"). */
    val tripId: String? = null,
    /** Paragem para onde vai ou onde está (GO Hub). */
    val stopId: String? = null,
    /** Sentido (STCP: 0 ou 1). */
    val direction: Int? = null,
    /** Atraso em relação ao horário (segundos; negativo = adiantado); null se não se souber. */
    val delaySeconds: Int? = null,
    /** Dia de serviço da viagem (AAAAMMDD), quando a fonte o diz. */
    val serviceDate: Int? = null,
    /** Destino (do horário). */
    val destination: String? = null
) {
    val key: String get() = "${operator.key}|$vehicleId"
    val label: String get() = line?.takeIf { it.isNotBlank() } ?: operator.shortName
}

data class RegionalStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Hora do horário ("10:42"). */
    val clock: String?,
    /** Minutos até chegar (do operador); null nas já passadas. */
    val dueMinutes: Int?,
    val passed: Boolean
)

/** A viagem que um veículo está a fazer (só a Eleven a dá). */
data class RegionalTrip(
    val line: String?,
    val lineName: String?,
    val color: String?,
    val destination: String?,
    val stops: List<RegionalStop>,
    val shape: List<Pair<Double, Double>>
)

object RegionalOperators {

    private fun tml(code: String, name: String, short: String, color: String, kind: VehicleKind) =
        RegionalOperator("tml:$code", name, short, color, kind, RegionalSource.TML, code, RegionalArea.LISBOA, pack = code)

    private fun eleven(code: String, name: String, short: String, color: String, area: RegionalArea) =
        RegionalOperator("eleven:$code", name, short, color, VehicleKind.BUS, RegionalSource.ELEVEN, code, area)

    /** Agências do GO Hub que entram na camada (agency_id → operador). */
    val TML: Map<String, RegionalOperator> = listOf(
        tml("HF16N", "MobiCascais", "MOBI", "#0093D0", VehicleKind.BUS),
        tml("A3H3M", "TC Barreiro", "TCB", "#D52B1E", VehicleKind.BUS),
        tml("KB1F6", "Metro Transportes do Sul", "MTS", "#7AB648", VehicleKind.TRAM),
        tml("7NTB1", "Fertagus", "FT", "#C74F4F", VehicleKind.TRAIN),
        tml("LTP61", "Transtejo · Soflusa", "TTSL", "#0077C8", VehicleKind.BOAT)
    ).associateBy { it.code }

    /** STCP (Porto): posições do broker FIWARE da Porto Digital; paragens e traçados do GTFS. */
    val STCP = RegionalOperator(
        "stcp:STCP", "STCP", "STCP", "#187EC2", VehicleKind.BUS, RegionalSource.STCP, "STCP", RegionalArea.PORTO, pack = "STCP"
    )

    /** Metro do Porto: sem posições em direto abertas, os metros andam pelo horário oficial (TransitSchedule). */
    val MDP = RegionalOperator(
        "pack:MDP", "Metro do Porto", "MP", "#004F9F", VehicleKind.SUBWAY, RegionalSource.SCHEDULE, "MDP", RegionalArea.PORTO, pack = "MDP"
    )

    /** Carris: todos os autocarros e elétricos em direto (GTFS-RT da Carris). */
    val CARRIS = RegionalOperator(
        "lx:carris", "Carris", "C", "#F7941E", VehicleKind.BUS, RegionalSource.CITY, "carris", RegionalArea.LISBOA
    )

    /** Carris Metropolitana: todos os autocarros em direto (API da CM). */
    val CM = RegionalOperator(
        "lx:cm", "Carris Metropolitana", "CM", "#FFDD00", VehicleKind.BUS, RegionalSource.CITY, "carris_metropolitana", RegionalArea.LISBOA
    )

    /** Redes na plataforma Eleven Systems (o subdomínio de cada uma). */
    val ELEVEN: List<RegionalOperator> = listOf(
        eleven("cov", "Covibus (Covilhã)", "COV", "#DB291C", RegionalArea.CENTRO),
        eleven("vdl", "MobiViseuDãoLafões", "VDL", "#0BA852", RegionalArea.CENTRO),
        eleven("gmr", "Guimabus (Guimarães)", "GMR", "#339933", RegionalArea.NORTE),
        eleven("mcb", "MCB (Castelo Branco)", "MCB", "#1E88E5", RegionalArea.CENTRO),
        eleven("uvr", "UVR (Vila Real)", "UVR", "#E53935", RegionalArea.NORTE),
        eleven("etug", "STUG (Guarda)", "STUG", "#00897B", RegionalArea.CENTRO),
        eleven("lam", "Urbanos de Lamego", "LAM", "#8E24AA", RegionalArea.NORTE),
        eleven("ttm", "TTM (Terras de Trás-os-Montes)", "TTM", "#6D4C41", RegionalArea.NORTE),
        eleven("tps", "TPS (Sabugal)", "TPS", "#F4511E", RegionalArea.CENTRO)
    )

    /** Todos os operadores da camada, pela ordem dos filtros. */
    val ALL: List<RegionalOperator> = listOf(CARRIS, CM) + TML.values.toList() + STCP + MDP + ELEVEN

    /** Operadores com pacote de paragens e horários. */
    val WITH_PACK: List<RegionalOperator> = ALL.filter { it.pack != null }

    fun byKey(key: String): RegionalOperator? = ALL.firstOrNull { it.key == key }

    fun byPack(code: String): RegionalOperator? = WITH_PACK.firstOrNull { it.pack == code }
}

object RegionalParsers {

    /** Texto do campo, ou null se faltar, for null ou vazio (o optString do Android devolve "null"). */
    private fun JSONObject.str(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    /** Uma paragem da Eleven (`/api/stops`). */
    data class ElevenStop(val id: String, val name: String, val lat: Double, val lon: Double)

    /** Uma passagem por uma paragem: a viagem, o sentido e a hora a que passa (segundos do dia). */
    data class ElevenPass(val journeyId: String, val direction: Int, val seconds: Int)

    /** Uma linha que passa numa paragem, com as próximas passagens (`/api/stops/{id}/routes`). */
    data class ElevenStopRoute(val id: String, val label: String, val name: String, val color: String?, val passes: List<ElevenPass>)

    /** As paragens de uma rede da Eleven (sem as que não têm coordenadas). */
    fun elevenStops(json: String): List<ElevenStop> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val pos = o.optJSONObject("position") ?: return@mapNotNull null
            val lat = pos.optDouble("lat", Double.NaN)
            val lon = pos.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
            ElevenStop(id, prettyName(o.str("name") ?: id), lat, lon)
        }
    }

    /** As linhas e as próximas passagens numa paragem; a hora é a desta paragem ("start" / "startTime"). */
    fun elevenStopRoutes(json: String): List<ElevenStopRoute> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = r.str("id") ?: return@mapNotNull null
            val journeys = r.optJSONArray("journeys") ?: org.json.JSONArray()
            val passes = (0 until journeys.length()).mapNotNull { k ->
                val j = journeys.optJSONObject(k) ?: return@mapNotNull null
                val jid = j.str("id") ?: return@mapNotNull null
                val secs = if (j.has("startTime") && !j.isNull("startTime")) j.optInt("startTime")
                else clockSeconds(j.str("start")) ?: return@mapNotNull null
                ElevenPass(jid, j.optInt("direction", 0), secs)
            }
            ElevenStopRoute(id, r.str("nameShort") ?: id, prettyName(r.str("name") ?: ""), color(r.str("color")), passes)
        }
    }

    /** Uma passagem de uma viagem numa paragem: onde e a que horas (segundos do dia). */
    data class ElevenCall(val stopId: String, val name: String, val lat: Double, val lon: Double, val arrival: Int, val departure: Int)

    /** As paragens de uma viagem pela ordem (`/api/routes/{r}/journeys/{j}` → "circulations"). */
    fun elevenCirculations(json: String): List<ElevenCall> {
        val c = org.json.JSONObject(json).optJSONArray("circulations") ?: return emptyList()
        return (0 until c.length()).mapNotNull { i ->
            val o = c.optJSONObject(i) ?: return@mapNotNull null
            val stage = o.optJSONObject("stage") ?: return@mapNotNull null
            val id = stage.str("id") ?: return@mapNotNull null
            val pos = stage.optJSONObject("position")
            ElevenCall(
                id, prettyName(stage.str("name") ?: id),
                pos?.optDouble("lat", Double.NaN) ?: Double.NaN, pos?.optDouble("lon", Double.NaN) ?: Double.NaN,
                o.optInt("arrivalTime"), o.optInt("departureTime", o.optInt("arrivalTime"))
            )
        }
    }

    /** O destino de uma viagem (`/api/routes/{r}/journeys/{j}`): a última paragem; numa circular, null. */
    fun elevenJourneyDestination(json: String): String? {
        val c = org.json.JSONObject(json).optJSONArray("circulations") ?: return null
        if (c.length() == 0) return null
        fun name(i: Int) = c.optJSONObject(i)?.optJSONObject("stage")?.str("name")
        val first = name(0)
        val last = name(c.length() - 1) ?: return null
        return if (first != null && first == last) null else prettyName(last)
    }

    /** "21:05:00" → 75900. */
    private fun clockSeconds(text: String?): Int? {
        val p = text?.split(":") ?: return null
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        val s = p.getOrNull(2)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    /** "0ba852", "#0BA852" → "#0BA852"; null se não for uma cor. */
    fun color(hex: String?): String? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        return if (h.length == 6 && h.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) "#" + h.uppercase() else null
    }

    /**
     * Posições do GO Hub (`vehicles/positions`). Só as agências de [operators]; as posições com mais de [maxAgeMs]
     * ficam de fora (veículo recolhido ou sem GPS). O id vem como "[HF16N]170": fica só "170".
     */
    fun tmlPositions(
        json: String,
        operators: Map<String, RegionalOperator>,
        nowMs: Long,
        maxAgeMs: Long = 3 * 60_000L
    ): List<RegionalVehicle> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<RegionalVehicle>()
        for (i in 0 until data.length()) {
            val v = data.optJSONObject(i) ?: continue
            val op = operators[v.str("agency_id")] ?: continue
            val lat = v.optDouble("latitude", Double.NaN)
            val lon = v.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
            val at = v.optLong("created_at", 0L).takeIf { it > 0 } ?: v.optLong("received_at", 0L)
            if (at > 0 && nowMs - at > maxAgeMs) continue
            val id = v.str("vehicle_id")?.substringAfter("]") ?: continue
            out += RegionalVehicle(
                operator = op,
                vehicleId = id,
                lat = lat,
                lon = lon,
                line = v.str("route_short_name"),
                color = op.color,
                status = v.str("current_status"),
                observedAt = at,
                tripId = v.str("trip_id"),
                stopId = v.str("stop_id"),
                serviceDate = v.optInt("operational_date", 0).takeIf { it > 0 }
            )
        }
        return out
    }

    /**
     * Autocarros da STCP no broker FIWARE da Porto Digital (`/v2/entities?…&options=keyValues`): a linha, o sentido e
     * a viagem vêm nas anotações ("stcp:route:107", "stcp:sentido:0", "stcp:nr_viagem:107_0_1|…"). Posições com mais de
     * [maxAgeMs] ficam de fora.
     */
    fun stcpVehicles(
        json: String,
        operator: RegionalOperator,
        nowMs: Long,
        maxAgeMs: Long = 5 * 60_000L
    ): List<RegionalVehicle> {
        val arr = JSONArray(json)
        val out = ArrayList<RegionalVehicle>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val coords = v.optJSONObject("location")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
            val notes = v.optJSONArray("annotations")
            fun note(prefix: String): String? {
                if (notes == null) return null
                for (k in 0 until notes.length()) {
                    val s = notes.optString(k)
                    if (s.startsWith(prefix)) return s.removePrefix(prefix).takeIf { it.isNotBlank() && it != "na" }
                }
                return null
            }
            val at = v.str("observationDateTime")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            if (at > 0 && nowMs - at > maxAgeMs) continue
            val id = v.str("fleetVehicleId") ?: v.str("id")?.substringAfterLast(":") ?: continue
            out += RegionalVehicle(
                operator = operator,
                vehicleId = id,
                lat = lat,
                lon = lon,
                line = note("stcp:route:"),
                color = operator.color,
                observedAt = at,
                tripId = note("stcp:nr_viagem:"),
                direction = note("stcp:sentido:")?.toIntOrNull()
            )
        }
        return out
    }

    /**
     * Veículos de uma rede Eleven (`/api/locations`). A lista só traz a posição e a cor da linha; o número da linha
     * vem de [lineFor] (o que já se sabe de cada veículo, ou a linha que é a única com aquela cor).
     */
    fun elevenLocations(
        json: String,
        operator: RegionalOperator,
        nowMs: Long,
        lineFor: (vehicleId: String, color: String) -> String?
    ): List<RegionalVehicle> {
        val arr = JSONArray(json)
        val out = ArrayList<RegionalVehicle>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.str("id") ?: continue
            val pos = v.optJSONObject("position") ?: continue
            val lat = pos.optDouble("lat", Double.NaN)
            val lon = pos.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
            val color = color(v.str("color")) ?: operator.color
            out += RegionalVehicle(
                operator = operator,
                vehicleId = id,
                lat = lat,
                lon = lon,
                line = lineFor(id, color),
                color = color,
                observedAt = nowMs
            )
        }
        return out
    }

    /** Linhas de uma rede Eleven (`/api/routes`): cor → número, só das cores que são de uma linha só. */
    fun elevenLinesByColor(json: String): Map<String, String> {
        val arr = JSONArray(json)
        val byColor = HashMap<String, MutableSet<String>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val color = color(r.str("color")) ?: continue
            val line = r.str("nameShort") ?: continue
            byColor.getOrPut(color) { HashSet() } += line
        }
        return byColor.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    /**
     * Viagem de um veículo Eleven (`/api/locations/{id}`): a linha, as paragens com a hora e os minutos que faltam,
     * e o traçado (polyline do Google, precisão 5). As paragens antes da atual já passaram.
     */
    fun elevenTrip(json: String): RegionalTrip? {
        val root = JSONObject(json)
        val route = root.optJSONObject("route")
        val journey = root.optJSONObject("journey") ?: return null
        val current = root.optInt("currentStopSequence", 0)
        val circulations = journey.optJSONArray("circulations") ?: JSONArray()
        val stops = ArrayList<RegionalStop>(circulations.length())
        for (i in 0 until circulations.length()) {
            val c = circulations.optJSONObject(i) ?: continue
            val stage = c.optJSONObject("stage") ?: continue
            val pos = stage.optJSONObject("position")
            val due = if (c.has("dueInMinutes") && !c.isNull("dueInMinutes")) c.optInt("dueInMinutes") else null
            val seq = c.optInt("sequence", i + 1)
            val seconds = if (c.has("arrivalTime")) c.optInt("arrivalTime") else c.optInt("departureTime", -1)
            stops += RegionalStop(
                name = prettyName(stage.str("name") ?: continue),
                lat = pos?.optDouble("lat") ?: 0.0,
                lon = pos?.optDouble("lon") ?: 0.0,
                clock = clock(seconds),
                dueMinutes = due,
                passed = if (current > 0) seq < current else due == null
            )
        }
        val shape = journey.str("shape")?.let { runCatching { decodePolyline(it) }.getOrNull() }.orEmpty()
        return RegionalTrip(
            line = route?.str("nameShort"),
            lineName = route?.str("name")?.let(::prettyName),
            color = color(route?.str("color")),
            destination = stops.lastOrNull()?.name,
            stops = stops,
            shape = shape
        )
    }

    /** Segundos desde a meia-noite → "HH:mm" (as viagens depois da meia-noite passam das 24 h). */
    fun clock(seconds: Int): String? {
        if (seconds < 0) return null
        val minutes = (seconds / 60) % (24 * 60)
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    private val SMALL_WORDS = setOf("de", "da", "do", "das", "dos", "e", "via", "a", "o", "em")

    /** "MANGUALDE - VISEU VIA ALCAFACHE" → "Mangualde - Viseu via Alcafache". Nomes já em minúsculas ficam. */
    fun prettyName(name: String): String {
        if (name.any { it.isLowerCase() }) return name.trim()
        return name.trim().lowercase().split(" ").mapIndexed { i, w ->
            when {
                w.isEmpty() -> w
                i > 0 && w in SMALL_WORDS -> w
                // Siglas curtas sem vogais (EB, ZI…) e números ficam em maiúsculas
                w.length <= 3 && w.none { it in "aeiouáéíóúâêôãõ" } -> w.uppercase()
                else -> w.replaceFirstChar { it.titlecase() }
            }
        }.joinToString(" ")
    }

    /** Google "encoded polyline" (precisão 5) → (lat, lon). */
    fun decodePolyline(text: String): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>(text.length / 4)
        var i = 0
        var lat = 0
        var lon = 0
        fun next(): Int {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = text[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && i < text.length)
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        while (i < text.length) {
            lat += next()
            if (i >= text.length) break
            lon += next()
            points += lat / 1e5 to lon / 1e5
        }
        return points
    }
}
