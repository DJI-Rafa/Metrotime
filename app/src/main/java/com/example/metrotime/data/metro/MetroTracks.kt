package com.example.metrotime.data.metro

import android.content.Context
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.cp.CpTrack
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Traçado real de uma linha do metro (asset metro_tracks.json.bin, gerado pelo tools/build_metro_tracks.py a partir
 * do OpenStreetMap, ODbL) e onde cada estação cai nele, em metros desde a primeira estação da ordem da linha
 * (MapConfiguration.LINE_STATION_ORDER). Sem o traçado, linhas retas entre as estações.
 */
class MetroLineTrack(val line: String, val track: CpTrack, val stations: List<String>, val stationAt: DoubleArray) {

    val length: Double get() = track.along.last()

    fun indexOf(stationId: String): Int = stations.indexOf(stationId)

    /** Ponto (lat, lon) a [s] metros do início. */
    fun point(s: Double): Pair<Double, Double> = track.pointAt(s.coerceIn(0.0, length))

    /** Rumo (graus, 0 = norte) da linha em [s], no sentido da ordem das estações ([dir] = -1: ao contrário). */
    fun bearing(s: Double, dir: Int): Double {
        val a = point(s - 15.0)
        val b = point(s + 15.0)
        val forward = CpTrack.bearing(a, b)
        return if (dir >= 0) forward else (forward + 180.0) % 360.0
    }

    /** Pedaço da linha entre duas distâncias (em qualquer ordem). */
    fun slice(s0: Double, s1: Double): List<Pair<Double, Double>> {
        val a = minOf(s0, s1).coerceIn(0.0, length)
        val b = maxOf(s0, s1).coerceIn(0.0, length)
        return if (b - a < 0.5) emptyList() else track.slice(a, b)
    }

    /** Distância pela linha entre duas estações (índices na ordem da linha). */
    fun between(i: Int, j: Int): Double = kotlin.math.abs(stationAt[j] - stationAt[i])
}

object MetroTracks {

    /** As linhas a partir dos traçados (lat, lon) e da ordem e coordenadas das estações. */
    fun build(
        shapes: Map<String, List<Pair<Double, Double>>>,
        order: Map<String, List<String>> = MapConfiguration.LINE_STATION_ORDER,
        coords: Map<String, Pair<Double, Double>> = MapConfiguration.STATION_COORDS.mapValues { it.value.lat to it.value.lon }
    ): Map<String, MetroLineTrack> = order.mapNotNull { (line, stations) ->
        val points = stations.map { coords[it] }
        if (points.any { it == null }) return@mapNotNull null
        val shape = shapes[line]?.takeIf { it.size >= 2 }
        val track = CpTrack(shape ?: points.filterNotNull())
        // Onde cada estação cai no traçado, sempre para a frente; sem traçado, as próprias estações
        val at = if (shape != null) track.stopPositions(points).map { it ?: 0.0 } else track.along.toList()
        // Nunca para trás (uma estação mal projetada não pode pôr o metro a recuar)
        val fixed = DoubleArray(at.size)
        for (i in at.indices) fixed[i] = if (i == 0) at[i] else maxOf(at[i], fixed[i - 1] + 1.0)
        line to MetroLineTrack(line, track, stations, fixed)
    }.toMap()

    /** "Nome": "texto" dentro do objeto das linhas (o texto da polyline só escapa a barra invertida). */
    private val ENTRY = Regex("\"([A-Za-z]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Os traçados do asset: {"lines": {"Azul": "<polyline>"}} (sem org.json, para também correr nos testes). */
    fun parse(input: InputStream): Map<String, List<Pair<Double, Double>>> {
        val text = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val start = text.indexOf("\"lines\"").takeIf { it >= 0 } ?: return emptyMap()
        val body = text.substring(start + 7)
        return ENTRY.findAll(body).associate { m ->
            m.groupValues[1] to CpTrack.decode(m.groupValues[2].replace("\\\\", "\\"))
        }
    }

    @Volatile private var cache: Map<String, MetroLineTrack>? = null

    /** As quatro linhas, com o traçado real quando o asset o tem (carregadas uma vez). */
    @Synchronized
    fun load(context: Context): Map<String, MetroLineTrack> = cache ?: run {
        val shapes = runCatching { context.assets.open("metro_tracks.json.bin").use { parse(it) } }.getOrDefault(emptyMap())
        build(shapes).also { cache = it }
    }
}
