package com.example.metrotime.data.cp

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Retrofit API interface for the official CP API gateway endpoints.
 */
interface CpApiService {

    @GET("services/travel-api/stations")
    suspend fun getStations(): List<CpStation>

    @GET("services/travel-api/stations/{stationId}/timetable/{date}")
    suspend fun getStationTimetable(
        @Path("stationId") stationId: String,
        @Path("date") date: String,
        @retrofit2.http.Query("start") start: String? = null
    ): CpTimetableResponse

    @GET("services/travel-api/trains/{trainNumber}/timetable/{date}")
    suspend fun getTripDetail(
        @Path("trainNumber") trainNumber: Int,
        @Path("date") date: String
    ): CpTripDetail

    @GET("services/stations-api/stations/infos/{stationId}")
    suspend fun getStationInfo(@Path("stationId") stationId: String): CpStationInfo
}

interface ComboiosLiveApiService {
    @GET("vehicles")
    suspend fun getVehicles(): CpVehiclesResponse
}

object ComboiosLiveApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://comboios.live/api/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        ))
        .build()

    val service: ComboiosLiveApiService = retrofit.create(ComboiosLiveApiService::class.java)
}

interface RuicostaApiService {
    @GET("train/{trainNumber}")
    suspend fun getTrain(@Path("trainNumber") trainNumber: Int): RuicostaTrainResponse

    @GET("stations")
    suspend fun getStations(): RuicostaStationsResponse
}

data class RuicostaStationsResponse(
    @com.squareup.moshi.Json(name = "stations") val stations: List<RuicostaStation>? = null
)

data class RuicostaStation(
    @com.squareup.moshi.Json(name = "code") val code: String,
    @com.squareup.moshi.Json(name = "name") val name: String,
    @com.squareup.moshi.Json(name = "lat") val lat: String? = null,
    @com.squareup.moshi.Json(name = "lon") val lon: String? = null
) {
    fun toCpStation(): CpStation = CpStation(
        code = code,
        designation = name,
        latitude = lat,
        longitude = lon
    )
}

object RuicostaApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://comboios.ruicosta.pt/api/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        ))
        .build()

    val service: RuicostaApiService = retrofit.create(RuicostaApiService::class.java)
}

/**
 * Singleton providing a configured CpApiService instance.
 */
object CpApi {

    private const val BASE_URL = "https://api-gateway.cp.pt/cp/"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // As chaves vêm do ficheiro de configuração público do site (cp.pt/fe-config.json).
    // Podem mudar a qualquer momento: quando a API responde 401/403, são renovadas e o pedido repetido.
    @Volatile private var cachedApiKey: String? = null
    @Volatile private var cachedXcck: String? = null
    @Volatile private var cachedXccs: String? = null
    @Volatile private var lastConfigAttemptMs = 0L
    private const val CONFIG_RETRY_MS = 30_000L
    private val configLock = ReentrantLock()

    private val configClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun hasConfig() = cachedApiKey != null && cachedXcck != null && cachedXccs != null

    private fun fetchConfigIfNeeded(force: Boolean = false) {
        configLock.withLock {
            if (!force && hasConfig()) return
            // Evita martelar o site quando está em baixo (cada pedido à API passaria por aqui)
            val now = System.currentTimeMillis()
            if (now - lastConfigAttemptMs < CONFIG_RETRY_MS && (!force || hasConfig().not())) return
            lastConfigAttemptMs = now

            try {
                val request = Request.Builder()
                    .url("https://www.cp.pt/fe-config.json")
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile) MetrotimeApp")
                    .build()
                // use {} fecha sempre a resposta (antes ficava aberta quando o pedido falhava)
                val body: String? = configClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
                if (body != null) {
                    val json = JSONObject(body)
                    // optString devolve "" quando o campo falta — tratar como ausente
                    json.optString("travelApiKey").takeIf { it.isNotBlank() }?.let { cachedApiKey = it }
                    json.optString("xcck").takeIf { it.isNotBlank() }?.let { cachedXcck = it }
                    json.optString("xccs").takeIf { it.isNotBlank() }?.let { cachedXccs = it }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun prefetchConfig() {
        if (!hasConfig()) {
            Thread { fetchConfigIfNeeded() }.start()
        }
    }

    private fun withCpHeaders(original: Request): Request {
        val builder = original.newBuilder()
            .header("Origin", "https://www.cp.pt")
            .header("Referer", "https://www.cp.pt/")
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile) MetrotimeApp")
        cachedApiKey?.let { builder.header("x-api-key", it) }
        cachedXcck?.let { builder.header("x-cp-connect-id", it) }
        cachedXccs?.let { builder.header("x-cp-connect-secret", it) }
        return builder.build()
    }

    private val authInterceptor = Interceptor { chain ->
        fetchConfigIfNeeded()
        val response = chain.proceed(withCpHeaders(chain.request()))
        if (response.code == 401 || response.code == 403) {
            // Chaves rodadas pela CP: renovar e tentar uma vez
            response.close()
            fetchConfigIfNeeded(force = true)
            chain.proceed(withCpHeaders(chain.request()))
        } else {
            response
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val service: CpApiService = retrofit.create(CpApiService::class.java)
}
