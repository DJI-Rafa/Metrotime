package com.example.metrotime.data.cp

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
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
        load(context.assets.open("cp_network.json.bin"))
    }

    /** Lê a rede do asset (comprimido). Os testes chamam-no com o ficheiro do projeto. */
    @Synchronized
    fun load(stream: InputStream) {
        if (loaded) return
        val text = InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8).use { it.readText() }
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
        stationsByCode = stationsList.associateBy { it.code }
        loaded = true
    }

    fun stations(context: Context): List<Station> {
        ensureLoaded(context)
        return stationsList
    }

    /** Quantos comboios diferentes tem o horário (GTFS), para o distintivo "Todos a bordo"; 0 antes de carregar. */
    val trainCount: Int get() = trainShape.size

    /** As estações por código, com a rede já carregada (vazio se ainda não estiver). */
    @Volatile var stationsByCode: Map<String, Station> = emptyMap()
        private set

    /** Troços da rede para desenhar por baixo dos comboios. */
    fun networkLines(context: Context): List<List<Pair<Double, Double>>> {
        ensureLoaded(context)
        return network.map(CpTrack::decode)
    }

    /** Traçado do percurso de um comboio, quando o GTFS o conhece. */
    fun trackForTrain(context: Context, trainNumber: Int): CpTrack? {
        ensureLoaded(context)
        return trackFor(trainNumber)
    }

    /** O mesmo, com a rede já carregada (null se ainda não estiver). */
    @Synchronized
    fun trackFor(trainNumber: Int): CpTrack? {
        if (!loaded) return null
        val index = trainShape[trainNumber.toString()] ?: return null
        return decoded.getOrPut(index) { CpTrack(CpTrack.decode(shapes[index])) }.takeIf { it.line.size >= 2 }
    }
}
