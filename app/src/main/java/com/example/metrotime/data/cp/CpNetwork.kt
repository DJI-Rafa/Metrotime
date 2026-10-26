package com.example.metrotime.data.cp

import android.content.Context
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Rede ferroviária da CP com os traçados reais (asset cp_network.json.bin, gerado pelo
 * scripts/build_cp_network.py a partir do GTFS cp-gtfs-enhanced; traçados © OpenStreetMap, ODbL).
 */
object CpNetwork {

    data class Station(val code: String, val name: String, val lat: Double, val lon: Double)

    @Volatile private var loaded = false
    private var stationsList: List<Station> = emptyList()
    private var shapes: List<String> = emptyList()
    private var network: List<String> = emptyList()
    private var trainShape: Map<String, Int> = emptyMap()
    private val decoded = HashMap<Int, CpTrack>()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val text = InputStreamReader(GZIPInputStream(context.assets.open("cp_network.json.bin")), Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        val st = json.getJSONArray("stations")
        stationsList = (0 until st.length()).map { i ->
            val s = st.getJSONArray(i)
            Station(s.getString(0), s.getString(1), s.getDouble(2), s.getDouble(3))
        }
        val sh = json.getJSONArray("shapes")
        shapes = (0 until sh.length()).map { sh.getString(it) }
        val nw = json.getJSONArray("network")
        network = (0 until nw.length()).map { nw.getString(it) }
        val ts = json.getJSONObject("trainShape")
        trainShape = ts.keys().asSequence().associateWith { ts.getInt(it) }
        loaded = true
    }

    fun stations(context: Context): List<Station> {
        ensureLoaded(context)
        return stationsList
    }

    /** Troços da rede para desenhar por baixo dos comboios. */
    fun networkLines(context: Context): List<List<Pair<Double, Double>>> {
        ensureLoaded(context)
        return network.map(CpTrack::decode)
    }

    /** Traçado do percurso de um comboio, quando o GTFS o conhece. */
    @Synchronized
    fun trackForTrain(context: Context, trainNumber: Int): CpTrack? {
        ensureLoaded(context)
        val index = trainShape[trainNumber.toString()] ?: return null
        return decoded.getOrPut(index) { CpTrack(CpTrack.decode(shapes[index])) }.takeIf { it.line.size >= 2 }
    }
}
