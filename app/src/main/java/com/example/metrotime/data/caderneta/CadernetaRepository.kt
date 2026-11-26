package com.example.metrotime.data.caderneta

import android.content.Context
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.MetroNetwork
import com.example.metrotime.data.alerts.DisruptionLog
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpTrack
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.data.history.Trip
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.data.history.TripRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** A caderneta montada: os carimbos, as estações das duas redes, os padrões e os distintivos. */
class Caderneta(
    val book: StampBook,
    val metroLines: Map<String, List<String>>,
    val metroNames: Map<String, String>,
    val cpStations: List<CpStationInfo>,
    val patterns: Map<String, Azulejos.Pattern>,
    val badges: List<BadgeState>,
    /** As linhas da CP com as estações de cada uma (da CP); vazio se não se souber. */
    val cpLines: Map<String, List<String>>,
    val networkLines: List<List<Pair<Double, Double>>>
) {
    val metroIds: List<String> = metroLines.values.flatten().distinct()
    val earnedCount: Int get() = badges.count { it.earned }
}

/**
 * Monta a caderneta a partir do histórico de viagens (também as antigas): carimbos, padrões de azulejo e distintivos.
 * Guarda os distintivos já festejados, para o ecrã de "Distintivo novo" aparecer uma vez para cada um.
 */
object CadernetaRepository {

    private val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")
    private const val PREFS = "caderneta"
    private const val KEY_SEEN = "seen"
    private const val KEY_SEEN_AT = "seenAt"
    /**
     * Distintivos que ficam ganhos (id → texto da fita): os ganhos neste telemóvel e os que vieram do Firebase (ganhos
     * noutro telemóvel ou antes de apagar a app). A chave ficou do tempo em que só guardava os do Firebase.
     */
    private const val KEY_KEPT = "restored"
    private const val NEAR_TRACK_M = 300.0

    /** Estações da CP perto do traçado de cada comboio, com a distância ao início (por número). */
    private val nearTrack = HashMap<Int, List<Pair<String, Double>>>()

    suspend fun load(context: Context): Caderneta = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        CpNetwork.ensureLoaded(app)
        // Os bocados do mesmo comboio (a app perdeu-o e voltou a apanhá-lo) contam como uma viagem: onde se perdeu o
        // comboio não se entrou nem saiu, por isso não é carimbo cheio
        val trips = TripRules.mergeTrainLegs(TripHistoryManager.trips(app))
        val metroLines = MapConfiguration.LINE_STATION_ORDER
        val metroNames = MapConfiguration.STATION_COORDS.mapValues { it.value.name }
        val metroCoords = MapConfiguration.STATION_COORDS.mapValues { it.value.lat to it.value.lon }

        // As linhas de cada estação vêm da própria CP. Se a rede demorar, a cópia em disco, mesmo antiga (o getStations só
        // cai nela quando a rede falha, não quando se esgota o tempo); sem nenhuma, ficam vazias
        val cpInfo = (withTimeoutOrNull(6_000L) { runCatching { CpRepository.getStations(app) }.getOrNull() }
            ?: CpRepository.cachedStations(app)).orEmpty()
            .associateBy { it.code }
        val rawStations = CpNetwork.stations(app).map { s ->
            val info = cpInfo[s.code]
            CpStationInfo(
                code = s.code, name = formatStationName(s.name), lat = s.lat, lon = s.lon,
                lines = info?.railways.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                // A "região" da CP é uma zona pequena (ex.: Coimbra); o distintivo é das 5 regiões, tiradas das coordenadas.
                // As de Espanha (Vigo, Badajoz) não são de nenhuma
                region = if (Regions.inPortugal(s.code)) Regions.of(s.lat, s.lon) else null
            )
        }
        // A CP só dá o número de cada linha: o nome vem das estações dela (CpLineNames); as ligações curtas saem
        val byNumber = rawStations.flatMap { s -> s.lines.map { it to s.code } }.groupBy({ it.first }, { it.second })
        val named = CpLineNames.name(byNumber, rawStations.associateBy { it.code })
        val linesOfStation = HashMap<String, MutableList<String>>()
        named.forEach { (name, codes) -> codes.forEach { linesOfStation.getOrPut(it) { mutableListOf() } += name } }
        val cpStations = rawStations.map { it.copy(lines = linesOfStation[it.code].orEmpty()) }
        val cpByCode = cpStations.associateBy { it.code }

        val book = Stamps.collect(
            trips, ZONE,
            metroPath = { a, b -> MetroNetwork.route(a, b)?.stations },
            cpPassed = { t -> cpPassed(app, t, cpStations) }
        )
        val cpLines = named.toSortedMap()
        val cpLineOf: (Trip) -> String? = { t ->
            val a = cpByCode[t.fromId]?.lines.orEmpty()
            val b = t.toId?.let { cpByCode[it]?.lines }.orEmpty()
            a.firstOrNull { it in b } ?: a.firstOrNull() ?: b.firstOrNull()
        }
        val stats = runCatching { TripHistoryManager.computeStats(app) }.getOrNull()
        val input = BadgeInput(
            trips = trips,
            book = book,
            metroLines = metroLines,
            metroCoords = metroCoords,
            cpStations = cpStations,
            cpLineOf = cpLineOf,
            longestStreak = stats?.longestStreak ?: 0,
            extraCpKm = runCatching { TripHistoryManager.legacyKm(app) }.getOrDefault(0.0),
            timetableTrains = CpNetwork.trainCount,
            disruptions = DisruptionLog.windows(app).map { Disruption(it.line, it.startMs, it.endMs) },
            zone = ZONE,
            metroNames = metroNames
        )
        val patterns = Azulejos.assign(metroLines.values.flatten().map(Stamps::metro)) +
            Azulejos.assign(cpStations.map { Stamps.cp(it.code) })
        Caderneta(
            book = book,
            metroLines = metroLines,
            metroNames = metroNames,
            cpStations = cpStations,
            patterns = patterns,
            badges = keep(app, Badges.evaluate(input)),
            cpLines = cpLines,
            networkLines = runCatching { CpNetwork.networkLines(app) }.getOrDefault(emptyList())
        )
    }

    /**
     * Os distintivos ganhos que ainda não foram festejados. Os ganhos ficam guardados ([keep]), por isso um festejado só
     * deixa de estar ganho se foi dado por uma regra errada ([Badges.trusted]): sai da lista, para ser festejado quando
     * se ganhar a sério.
     */
    fun unseen(context: Context, caderneta: Caderneta): List<BadgeState> {
        val seen = seen(context)
        val earned = caderneta.badges.filter { it.earned }.map { it.def.id }.toSet()
        val stale = seen - earned
        if (stale.isNotEmpty()) {
            save(context, seen - stale)
            // Sem o dia também: se voltar a ser ganho (a sério), conta o dia novo
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            runCatching { JSONObject(prefs.getString(KEY_SEEN_AT, null) ?: "{}") }.getOrNull()?.let { at ->
                stale.forEach { at.remove(it) }
                prefs.edit().putString(KEY_SEEN_AT, at.toString()).apply()
            }
        }
        return Badges.newlyEarned(caderneta.badges, seen - stale)
    }

    fun markSeen(context: Context, ids: Collection<String>) {
        save(context, seen(context) + ids)
        // O dia em que se ganhou (a primeira vez que se festejou), para a imagem de partilhar
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = runCatching { JSONObject(prefs.getString(KEY_SEEN_AT, null) ?: "{}") }.getOrDefault(JSONObject())
        val today = LocalDate.now(ZONE).toEpochDay()
        ids.forEach { if (!at.has(it)) at.put(it, today) }
        prefs.edit().putString(KEY_SEEN_AT, at.toString()).apply()
    }

    /**
     * Guarda os distintivos ganhos agora (com o texto da fita de agora) e devolve-os com os guardados antes, que ficam
     * ganhos mesmo que as regras de agora não os deem (dados que faltam por um tempo, viagens noutro telemóvel).
     */
    private fun keep(context: Context, badges: List<BadgeState>): List<BadgeState> {
        val now = badges.filter { it.earned && Badges.trusted(it.def.id, it.label) }.associate { it.def.id to it.label }
        val kept = synchronized(this) {
            val before = keptOf(context)
            if (now.any { (id, label) -> before[id] != label }) {
                val o = JSONObject()
                (before + now).forEach { (id, label) -> o.put(id, label) }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_KEPT, o.toString()).apply()
            }
            before + now
        }
        return Badges.withKept(badges, kept)
    }

    private fun keptOf(context: Context): Map<String, String> = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_KEPT, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    /**
     * Guarda o que veio do Firebase: [days] (id → dia em que foi ganho) e [labels]. Os que não estão ganhos aqui
     * ([localEarned]) passam a estar; todos ficam com o dia mais antigo e dados como festejados. true se houve novos.
     */
    fun restore(context: Context, days: Map<String, Long>, labels: Map<String, String>, localEarned: Set<String>): Boolean {
        val known = days.filterKeys { it in BadgeCatalog.byId }
        if (known.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val added = synchronized(this) {
            val at = runCatching { JSONObject(prefs.getString(KEY_SEEN_AT, null) ?: "{}") }.getOrDefault(JSONObject())
            known.forEach { (id, day) -> if (!at.has(id) || at.optLong(id) > day) at.put(id, day) }
            val kept = runCatching { JSONObject(prefs.getString(KEY_KEPT, null) ?: "{}") }.getOrDefault(JSONObject())
            var added = false
            known.keys.forEach { id ->
                if (id !in localEarned && !kept.has(id)) {
                    kept.put(id, labels[id] ?: "")
                    added = true
                }
            }
            prefs.edit().putString(KEY_SEEN_AT, at.toString()).putString(KEY_KEPT, kept.toString()).apply()
            added
        }
        save(context, seen(context) + known.keys)
        return added
    }

    /** Esquece tudo o que a caderneta guardou neste telemóvel (festejados, dias, os que vieram do Firebase). */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** O dia em que o distintivo [id] foi ganho; null se foi antes de se guardar isto. */
    fun earnedDate(context: Context, id: String): LocalDate? = runCatching {
        val at = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN_AT, null) ?: return null)
        if (at.has(id)) LocalDate.ofEpochDay(at.getLong(id)) else null
    }.getOrNull()

    private fun save(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SEEN, JSONArray(ids.toList()).toString()).apply()
    }

    private fun seen(context: Context): Set<String> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, null) ?: return emptySet())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /** As estações da CP por onde passou um comboio entre a origem e o destino da viagem (pelo traçado do GTFS). */
    private fun cpPassed(context: Context, trip: Trip, stations: List<CpStationInfo>): List<String> {
        val number = trip.trainNumber?.toIntOrNull() ?: return emptyList()
        val to = trip.toId ?: return emptyList()
        val near = synchronized(nearTrack) {
            nearTrack.getOrPut(number) {
                val track = runCatching { CpNetwork.trackForTrain(context, number) }.getOrNull()
                if (track == null) emptyList() else stationsNear(track, stations)
            }
        }
        if (near.isEmpty()) return emptyList()
        val sFrom = near.firstOrNull { it.first == trip.fromId }?.second ?: return emptyList()
        val sTo = near.firstOrNull { it.first == to }?.second ?: return emptyList()
        val lo = minOf(sFrom, sTo)
        val hi = maxOf(sFrom, sTo)
        return near.filter { (code, s) -> s > lo && s < hi && code != trip.fromId && code != to }.map { it.first }
    }

    private fun stationsNear(track: CpTrack, stations: List<CpStationInfo>): List<Pair<String, Double>> {
        val lats = track.line.map { it.first }
        val lons = track.line.map { it.second }
        val minLat = lats.min() - 0.01
        val maxLat = lats.max() + 0.01
        val minLon = lons.min() - 0.013
        val maxLon = lons.max() + 0.013
        return stations.mapNotNull { s ->
            if (s.lat !in minLat..maxLat || s.lon !in minLon..maxLon) return@mapNotNull null
            val p = s.lat to s.lon
            val hit = track.locate(p)
            if (CpTrack.metres(p, track.pointAt(hit.s)) <= NEAR_TRACK_M) s.code to hit.s else null
        }.sortedBy { it.second }
    }

    /** Nome de uma estação para mostrar. */
    fun stationName(caderneta: Caderneta, key: String): String = when {
        key.startsWith("m:") -> caderneta.metroNames[key.removePrefix("m:")] ?: key.removePrefix("m:")
        else -> caderneta.cpStations.firstOrNull { it.code == key.removePrefix("c:") }?.name ?: key.removePrefix("c:")
    }
}
