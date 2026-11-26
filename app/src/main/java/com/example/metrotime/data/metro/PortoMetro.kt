package com.example.metrotime.data.metro

import com.example.metrotime.data.cp.CpTrack
import com.example.metrotime.data.regional.RegionalParsers
import com.example.metrotime.data.regional.TransitPack
import java.time.ZonedDateTime

/**
 * O Metro do Porto no mesmo motor do metro de Lisboa: cada linha (A, B, Bx, C, D, E, F) é um [MetroLineTrack] com o
 * traçado real e as estações, e cada viagem do horário oficial é um [MetroTrain]. Assim o mapa (pontos de longe,
 * carruagens de perto), o painel do metro, o da estação, "Vou neste metro" e "Seguir" são os mesmos nas duas cidades.
 * O Metro do Porto não publica posições em direto: os metros andam pelo horário.
 *
 * Linhas: "MP-A", "MP-B"… (para não se confundirem com as de Lisboa); estações: "MP:<stop_id do GTFS>".
 * Sem dependências Android (testado em app/src/test).
 */
object PortoMetro {
    const val LINE_PREFIX = "MP-"
    const val STATION_PREFIX = "MP:"

    /** Tempo parado em cada estação. */
    private const val DWELL_MS = 20_000L

    class Network(
        /** Linha ("MP-A") → traçado e estações, pela ordem do percurso mais comprido. */
        val tracks: Map<String, MetroLineTrack>,
        val names: Map<String, String>,
        val coords: Map<String, Pair<Double, Double>>,
        /** Estação → linhas que lá passam (pela ordem A, B, Bx, C…). */
        val linesByStation: Map<String, List<String>>,
        /** Linha → "#RRGGBB". */
        val colors: Map<String, String>
    )

    /** A rede do pacote em uso (posta pelo mapa ao arrancar e quando os horários mudam). */
    @Volatile var network: Network? = null

    fun isPorto(id: String): Boolean = id.startsWith(LINE_PREFIX) || id.startsWith(STATION_PREFIX)

    /** "MP-A" → "A". */
    fun label(line: String): String = line.removePrefix(LINE_PREFIX)

    fun nameOf(stationId: String): String? = network?.names?.get(stationId)

    fun linesOf(stationId: String): List<String> = network?.linesByStation?.get(stationId).orEmpty()

    fun colorOf(line: String): String? = network?.colors?.get(line)

    fun stops(line: String): List<String> = network?.tracks?.get(line)?.stations.orEmpty()

    fun idByName(normalizedName: String, normalize: (String) -> String): String? =
        network?.names?.entries?.firstOrNull { normalize(it.value) == normalizedName }?.key

    /**
     * As linhas a partir do pacote: em cada linha, o percurso com mais estações dá a ordem delas e o traçado (os outros
     * sentidos e as viagens mais curtas andam por cima dele).
     */
    fun build(pack: TransitPack): Network {
        val tracks = LinkedHashMap<String, MetroLineTrack>()
        val colors = HashMap<String, String>()
        val names = HashMap<String, String>()
        val coords = HashMap<String, Pair<Double, Double>>()
        pack.stops.forEach { s ->
            names[STATION_PREFIX + s.id] = RegionalParsers.prettyName(s.name)
            coords[STATION_PREFIX + s.id] = s.lat to s.lon
        }
        val byRoute = pack.patterns.indices.groupBy { pack.patterns[it].route }
        for ((routeIdx, patterns) in byRoute.toSortedMap(compareBy { pack.routes[it].label })) {
            val route = pack.routes[routeIdx]
            val canonical = patterns.maxWithOrNull(compareBy({ pack.patterns[it].stops.size }, { -pack.patterns[it].direction }))
                ?.let { pack.patterns[it] } ?: continue
            val line = LINE_PREFIX + route.label
            val stations = canonical.stops.map { STATION_PREFIX + pack.stops[it].id }
            val points = canonical.stops.map { pack.stops[it].let { s -> s.lat to s.lon } }
            val shape = pack.geometry(canonical)
            val track = CpTrack(shape)
            val at = track.stopPositions(points).map { it ?: 0.0 }
            // Nunca para trás (uma estação mal projetada não pode pôr o metro a recuar)
            val fixed = DoubleArray(at.size)
            for (i in at.indices) fixed[i] = if (i == 0) at[i] else maxOf(at[i], fixed[i - 1] + 1.0)
            tracks[line] = MetroLineTrack(line, track, stations, fixed)
            colors[line] = route.color ?: "#888888"
        }
        val linesByStation = HashMap<String, MutableList<String>>()
        for ((line, t) in tracks) t.stations.forEach { linesByStation.getOrPut(it) { mutableListOf() }.add(line) }
        return Network(tracks, names, coords, linesByStation, colors)
    }

    /** Os índices (na ordem da linha) das estações de um percurso, no sentido em que ele anda; null se não couber na linha. */
    private fun indicesOn(track: MetroLineTrack, ids: List<String>): Pair<IntArray, Int>? {
        fun walk(forward: Boolean): IntArray? {
            val out = IntArray(ids.size)
            var prev = if (forward) -1 else track.stations.size
            for ((k, id) in ids.withIndex()) {
                val range = if (forward) (prev + 1 until track.stations.size) else (prev - 1 downTo 0)
                val i = range.firstOrNull { track.stations[it] == id } ?: return null
                out[k] = i
                prev = i
            }
            return out
        }
        walk(true)?.let { return it to 1 }
        walk(false)?.let { return it to -1 }
        return null
    }

    /**
     * Os metros que andam agora ou nos próximos [aheadMs] (o seletor de tempo vai até 15 min), pelo horário: as viagens
     * de hoje e as de ontem que passam da meia-noite. Cada metro aparece um pouco antes de partir e fica uns segundos
     * parado em cada estação.
     */
    fun trains(pack: TransitPack, net: Network, now: ZonedDateTime, aheadMs: Long = 20 * 60_000L): List<MetroTrain> {
        val nowMs = now.toInstant().toEpochMilli()
        val out = ArrayList<MetroTrain>()
        for (back in 0L..1L) {
            val date = now.toLocalDate().minusDays(back)
            val base = date.atStartOfDay(now.zone).toInstant().toEpochMilli()
            val runs = BooleanArray(pack.services.size) { pack.services[it].runsOn(date) }
            for (pattern in pack.patterns) {
                val line = LINE_PREFIX + pack.routes[pattern.route].label
                val track = net.tracks[line] ?: continue
                val ids = pattern.stops.map { STATION_PREFIX + pack.stops[it].id }
                val (idx, dir) = indicesOn(track, ids) ?: continue
                val last = pattern.stops.lastIndex
                val destinationName = RegionalParsers.prettyName(pattern.headsign)
                for (t in pattern.starts.indices) {
                    if (!runs[pattern.service[t]]) continue
                    val start = base + pattern.timeAt(t, 0) * 1000L
                    val end = base + pattern.timeAt(t, last) * 1000L
                    if (end < nowMs - 60_000L || start > nowMs + aheadMs) continue
                    val arrive = LongArray(ids.size) { base + pattern.timeAt(t, it) * 1000L }
                    val depart = LongArray(ids.size) { i ->
                        if (i == last) arrive[i]
                        else minOf(arrive[i] + DWELL_MS, arrive[i + 1] - 5_000L).coerceAtLeast(arrive[i])
                    }
                    val run = MetroRun(line, pattern.tripIds[t], dir, ids[last], destinationName, idx, arrive, depart)
                    out += MetroTrain(line, pattern.tripIds[t], listOf(run))
                }
            }
        }
        return out
    }
}
