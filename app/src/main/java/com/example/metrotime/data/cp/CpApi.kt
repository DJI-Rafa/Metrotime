package com.example.metrotime.data.cp

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * Retrofit API interface for the comboios.live public endpoints.
 * These endpoints proxy CP's internal real-time train data.
 */
interface CpApiService {

    @GET("stations")
    suspend fun getStations(): CpStationsResponse

    @GET("vehicles")
    suspend fun getVehicles(): CpVehiclesResponse

    @GET("trips/{trainNumber}")
    suspend fun getTripDetail(@Path("trainNumber") trainNumber: Int): CpTripDetail
}

/**
 * Singleton providing a configured CpApiService instance.
 */
object CpApi {

    private const val BASE_URL = "https://comboios.live/api/"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)
        )
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 429) {
                throw java.io.IOException("RateLimitExceeded")
            }
            response
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val service: CpApiService = retrofit.create(CpApiService::class.java)
}
