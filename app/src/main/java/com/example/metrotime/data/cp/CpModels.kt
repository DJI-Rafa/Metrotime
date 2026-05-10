package com.example.metrotime.data.cp

import com.squareup.moshi.Json

// ──────────────────────────────────────────────
// Vehicles endpoint: GET /api/vehicles
// ──────────────────────────────────────────────

data class CpVehiclesResponse(
    @Json(name = "vehicles") val vehicles: List<CpVehicle>
)

data class CpVehicle(
    @Json(name = "trainNumber") val trainNumber: Int,
    @Json(name = "runDate") val runDate: String? = null,
    @Json(name = "delay") val delay: Int? = null,                // seconds
    @Json(name = "lastStation") val lastStation: String? = null,
    @Json(name = "latitude") val latitude: String? = null,
    @Json(name = "longitude") val longitude: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "hasDisruptions") val hasDisruptions: Boolean? = false,
    @Json(name = "timestamp") val timestamp: Long? = null,
    @Json(name = "service") val service: CpGenericEntity? = null,
    @Json(name = "origin") val origin: CpGenericEntity? = null,
    @Json(name = "destination") val destination: CpGenericEntity? = null,
    @Json(name = "gtfs") val gtfs: CpVehicleGtfs? = null
)

data class CpVehicleGtfs(
    @Json(name = "tripId") val tripId: String? = null,
    @Json(name = "stopId") val stopId: String? = null,
    @Json(name = "stopSequence") val stopSequence: Int? = null
)

data class CpGenericEntity(
    @Json(name = "code") val code: String? = null,
    @Json(name = "designation") val designation: String? = null
)

// ──────────────────────────────────────────────
// Stations endpoint: GET /api/stations
// ──────────────────────────────────────────────

data class CpStationsResponse(
    @Json(name = "stations") val stations: List<CpStation>
)

data class CpStation(
    @Json(name = "code") val code: String,
    @Json(name = "designation") val designation: String,
    @Json(name = "latitude") val latitude: String? = null,
    @Json(name = "longitude") val longitude: String? = null,
    @Json(name = "region") val region: String? = null,
    @Json(name = "railways") val railways: List<String>? = null
)

// ──────────────────────────────────────────────
// Trip detail endpoint: GET /api/trips/{trainNumber}
// ──────────────────────────────────────────────

data class CpTripDetail(
    @Json(name = "trainNumber") val trainNumber: Int? = null,
    @Json(name = "serviceCode") val serviceCode: CpServiceCode? = null,
    @Json(name = "lastStationCode") val lastStationCode: String? = null,
    @Json(name = "delay") val delay: Int? = null,
    @Json(name = "occupancy") val occupancy: Int? = null,
    @Json(name = "latitude") val latitude: String? = null,
    @Json(name = "longitude") val longitude: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "hasDisruptions") val hasDisruptions: Boolean? = false,
    @Json(name = "duration") val duration: String? = null,
    @Json(name = "trainStops") val trainStops: List<CpTrainStop>? = null
)

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
    @Json(name = "delay") val delay: Int? = null,              // seconds
    @Json(name = "supression") val supression: CpGenericEntity? = null,
    @Json(name = "ETA") val eta: String? = null,               // "HH:mm" real
    @Json(name = "ETD") val etd: String? = null                // "HH:mm" real
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
