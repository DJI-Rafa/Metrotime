package com.example.metrotime.data

import android.annotation.SuppressLint
import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RemoteArrivalsSource(private val context: Context) : ArrivalsSource {

    interface Api {
        @GET("tempoEspera/Estacao/{station_id}")
        suspend fun getStationArrivals(@Path("station_id") stationId: String): MetroLisboaArrivalResponse
    }

    private val api: Api by lazy {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("Authorization", "Bearer 10eb3b0b-6a6a-3114-a7f7-76752c8b6d64")
                .header("accept", "application/json")
            val request = requestBuilder.build()
            chain.proceed(request)
        }

        val insecureTrustManager = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, arrayOf<TrustManager>(insecureTrustManager), java.security.SecureRandom())

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, insecureTrustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.metrolisboa.pt:8243/estadoServicoML/1.0.1/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        retrofit.create(Api::class.java)
    }

    private val directionNameMap = mapOf(
        "33" to "Reboleira", "34" to "Amadora Este", "35" to "Pontinha",
        "36" to "Colégio Militar/Luz", "37" to "Laranjeiras", "38" to "São Sebastião",
        "39" to "Avenida", "40" to "Baixa-Chiado", "41" to "Terreiro do Paço",
        "42" to "Santa Apolónia", "43" to "Odivelas", "44" to "Lumiar",
        "45" to "Campo Grande", "46" to "Campo Pequeno", "48" to "Rato",
        "50" to "Telheiras", "51" to "Alvalade", "52" to "Alameda",
        "53" to "Martim Moniz", "54" to "Cais do Sodré", "56" to "Bela Vista",
        "57" to "Chelas", "59" to "Moscavide", "60" to "Aeroporto"
    )

    override suspend fun fetchArrivals(stationId: String): StationArrivals {
        val response = api.getStationArrivals(stationId)
        val arrivalsFromApi = response.arrivals ?: emptyList()

        val stationSpecificArrivals = arrivalsFromApi.flatMap { platformArrival ->
            val arrivals = mutableListOf<Arrival>()
            val destinationCode = platformArrival.destination

            if (destinationCode != null) {
                val directionName = directionNameMap[destinationCode] ?: "Destino $destinationCode"

                platformArrival.time1?.takeIf { it.isNotBlank() && it != "--" }?.toIntOrNull()?.let {
                    arrivals.add(Arrival(directionName, it))
                }
                platformArrival.time2?.takeIf { it.isNotBlank() && it != "--" }?.toIntOrNull()?.let {
                    arrivals.add(Arrival(directionName, it))
                }
                platformArrival.time3?.takeIf { it.isNotBlank() && it != "--" }?.toIntOrNull()?.let {
                    arrivals.add(Arrival(directionName, it))
                }
            }
            arrivals
        }

        // Find the full station object from the repo to return complete data
        val station = StationsRepo.loadStations(context).find { it.apiId == stationId } 
            ?: Station(stationId, stationId, 0.0, 0.0, "", stationId) // Fallback

        return StationArrivals(station, stationSpecificArrivals, System.currentTimeMillis())
    }
}
