package com.example.metrotime.data.cp

import com.squareup.moshi.Json

// ──────────────────────────────────────────────
// Timetable endpoint: GET /services/travel-api/stations/{id}/timetable/{date}
// ──────────────────────────────────────────────

data class CpTimetableResponse(
    @Json(name = "stationStops") val stationStops: List<CpStationStop>? = null,
    // Avisos da estação. O site da CP mostra o campo "text" (HTML) de cada objeto; fica genérico
    // para um formato inesperado nunca fazer falhar o horário inteiro.
    @Json(name = "messages") val messages: List<Any?>? = null
)

/** Texto de um aviso da estação, seja um objeto {"text": ...} ou uma string. */
fun cpMessageText(raw: Any?): String? = when (raw) {
    is String -> raw
    is Map<*, *> -> (raw["text"] ?: raw["message"] ?: raw["description"]) as? String
    else -> null
}

data class CpStationStop(
    @Json(name = "trainNumber") val trainNumber: Int,
    // Nulos tolerados: um único registo incompleto já não deita abaixo a lista inteira
    @Json(name = "trainService") val trainService: CpServiceCode? = null,
    @Json(name = "trainOrigin") val trainOrigin: CpGenericEntity? = null,
    @Json(name = "trainDestination") val trainDestination: CpGenericEntity? = null,
    @Json(name = "arrivalTime") val arrivalTime: String? = null,
    @Json(name = "departureTime") val departureTime: String? = null,
    @Json(name = "platform") val platform: String? = null,
    @Json(name = "delay") val delay: Int? = null, // MINUTOS; null = sem dados em tempo real; -1 = suprimido
    @Json(name = "occupancy") val occupancy: Int? = null,
    @Json(name = "supression") val supression: CpGenericEntity? = null,
    @Json(name = "ETA") val eta: String? = null,
    @Json(name = "ETD") val etd: String? = null
)

/**
 * Paragem de horário + o pedido de onde veio (dia e hora de início), para se poder
 * calcular a data real de cada hora — a janela pode atravessar a meia-noite.
 */
data class CpTimedStop(
    val stop: CpStationStop,
    val queryDate: java.time.LocalDate,
    val queryStart: java.time.LocalTime
) {
    fun dateTimeOf(time: String?): java.time.LocalDateTime? {
        val t = CpTime.parseHm(time) ?: return null
        return java.time.LocalDateTime.of(CpTimetableWindow.resolveDate(queryDate, queryStart, t), t)
    }
}

/** Informação de uma estação (endpoint stations-api/stations/infos). */
data class CpStationInfo(
    @Json(name = "code") val code: String? = null,
    @Json(name = "designation") val designation: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "trainLine") val trainLine: String? = null,
    @Json(name = "mobilityAccess") val mobilityAccess: String? = null,
    @Json(name = "services") val services: List<String>? = null
)

data class CpVehiclesResponse(
    @Json(name = "vehicles") val vehicles: List<CpVehicle>? = null
)

/** Comboio em circulação (comboios.live / Cloud Function `cp_vehicles`). */
data class CpVehicle(
    val trainNumber: Int,
    val runDate: String? = null,
    val delay: Int? = null, // SEGUNDOS (ao contrário dos horários, que vêm em minutos) — usar delayMinutes
    val lastStation: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val status: String? = null,
    val hasDisruptions: Boolean? = false,
    val timestamp: Long? = null,
    val service: CpGenericEntity? = null,
    val origin: CpGenericEntity? = null,
    val destination: CpGenericEntity? = null,
    val gtfs: CpVehicleGtfs? = null,
    val originTime: String? = null,
    val originETA: String? = null,
    val destinationTime: String? = null,
    val destinationETA: String? = null,
    /** Rumo em graus (0 = norte), calculado pela Cloud Function a partir das duas últimas posições. */
    val bearing: Double? = null
)

/** Atraso do veículo em minutos, arredondado. */
val CpVehicle.delayMinutes: Int get() = CpDelay.secondsToMinutes(delay) ?: 0

/** Chave única: o mesmo número pode circular em dois dias diferentes perto da meia-noite. */
val CpVehicle.uniqueKey: String get() = "${trainNumber}_${runDate ?: ""}"

data class CpVehicleGtfs(
    val tripId: String? = null,
    val stopId: String? = null,
    val stopSequence: Int? = null
)

data class CpGenericEntity(
    @Json(name = "code") val code: String? = null,
    @Json(name = "designation") val designation: String? = null
)

// ──────────────────────────────────────────────
// Stations endpoint: GET /services/travel-api/stations
// ──────────────────────────────────────────────

data class CpStation(
    @Json(name = "code") val code: String,
    @Json(name = "designation") val designation: String,
    @Json(name = "latitude") val latitude: String? = null,
    @Json(name = "longitude") val longitude: String? = null,
    @Json(name = "region") val region: String? = null,
    @Json(name = "railways") val railways: List<String>? = null
)

// ──────────────────────────────────────────────
// Trip detail endpoint: GET /services/travel-api/trains/{trainNumber}/timetable/{date}
// ──────────────────────────────────────────────

data class RuicostaTrainResponse(
    @Json(name = "fixed") val fixed: CpTripDetail? = null
)

data class CpTripDetail(
    @Json(name = "trainNumber") val trainNumber: String? = null,
    @Json(name = "serviceCode") val serviceCode: CpServiceCode? = null,
    @Json(name = "trainService") val trainService: CpServiceCode? = null,
    @Json(name = "lastStationCode") val lastStationCode: String? = null,
    @Json(name = "delay") val delay: Int? = null,
    @Json(name = "occupancy") val occupancy: Int? = null,
    @Json(name = "latitude") val latitude: String? = null,
    @Json(name = "longitude") val longitude: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "hasDisruptions") val hasDisruptions: Boolean? = false,
    @Json(name = "duration") val duration: String? = null,
    @Json(name = "trainStops") val trainStops: List<CpTrainStop>? = null
) {
    val effectiveService: CpServiceCode? get() = serviceCode ?: trainService
}

data class CpServiceCode(
    @Json(name = "code") val code: String? = null,
    @Json(name = "designation") val designation: String? = null
)

data class CpTrainStop(
    @Json(name = "station") val station: CpGenericEntity? = null,
    @Json(name = "arrival") val arrival: String? = null,      // "HH:mm"
    @Json(name = "departure") val departure: String? = null,  // "HH:mm"
    @Json(name = "platform") val platform: String? = null,
    @Json(name = "latitude") val latitude: String? = null,
    @Json(name = "longitude") val longitude: String? = null,
    @Json(name = "delay") val delay: Int? = null,              // MINUTOS
    @Json(name = "supression") val supression: CpGenericEntity? = null,
    @Json(name = "ETA") val eta: String? = null,               // "HH:mm" real
    @Json(name = "ETD") val etd: String? = null,               // "HH:mm" real
    @Json(name = "passed") val passed: Boolean? = null
)

// ──────────────────────────────────────────────
// Vehicle Status enum
// ──────────────────────────────────────────────

object VehicleStatus {
    const val NOT_STARTED = "NOT_STARTED"
    const val IN_TRANSIT = "IN_TRANSIT"
    const val NEAR_NEXT = "NEAR_NEXT"
    const val AT_ORIGIN = "AT_ORIGIN"
    const val AT_STATION = "AT_STATION"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

/** Convert ALL CAPS station names to Title Case, e.g. "LISBOA - SANTA APOLONIA" → "Lisboa - Santa Apolonia" */
fun String.toTitleCase(): String {
    if (this.isBlank()) return this
    // If the string is NOT all-uppercase, leave it as-is (already formatted)
    if (this != this.uppercase()) return this
    return this.split(" ").joinToString(" ") { word ->
        if (word.length <= 2 && word.all { it.isLetter() }) word.lowercase()
        else word.lowercase().replaceFirstChar { it.uppercase() }
    }
}

fun formatStationName(name: String): String {
    if (name.isBlank()) return name
    var formatted = name.toTitleCase()
    formatted = formatted.replace("Lisboa Santa Apolonia", "Lisboa - St. Apolónia")
                         .replace("Lisboa Oriente", "Lisboa - Oriente")
                         .replace("Lisboa Entrecampos", "Lisboa - Entrecampos")
                         .replace("Lisboa Sete Rios", "Lisboa - Sete Rios")
                         .replace("Lisboa Rossio", "Lisboa - Rossio")
                         .replace("Porto Campanha", "Porto - Campanhã")
                         .replace("Porto Sao Bento", "Porto - S. Bento")
                         .replace("Vila Nova De Gaia - Devesas", "Gaia - Devesas")
                         .replace("Vila Nova de Gaia - Devesas", "Gaia - Devesas")
    return CpText.restoreAccents(formatted)
}
