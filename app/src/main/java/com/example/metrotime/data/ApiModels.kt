package com.example.metrotime.data

import com.squareup.moshi.Json

/**
 * The root of the API response. It's a single object with a key "resposta".
 */
data class MetroLisboaArrivalResponse(
    @Json(name = "resposta") val arrivals: List<ApiPlatformArrival>?
)

/** estadoLinha/todos: {"resposta": {"verde": " Ok", "verde_curta": "normal", "tipo_msg_vd": "0", ...}}. */
data class MetroLineStatusResponse(
    @Json(name = "resposta") val status: Map<String, String?>?
)

/**
 * Represents the arrival times for a single platform of a station.
 * All fields are nullable to prevent crashes if the API response is inconsistent.
 */
data class ApiPlatformArrival(
    @Json(name = "stop_id") val stationId: String?,
    @Json(name = "destino") val destination: String?,
    @Json(name = "comboio") val comboio1: String?,
    @Json(name = "tempoChegada1") val time1: String?,
    @Json(name = "comboio2") val comboio2: String?,
    @Json(name = "tempoChegada2") val time2: String?,
    @Json(name = "comboio3") val comboio3: String?,
    @Json(name = "tempoChegada3") val time3: String?,
)
