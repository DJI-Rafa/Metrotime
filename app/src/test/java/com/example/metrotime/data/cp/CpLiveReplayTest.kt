package com.example.metrotime.data.cp

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Repete momentos reais e verifica o que o mapa faz com cada comboio. As amostras estão em
 * app/src/test/resources/cp_samples, no formato que a Cloud Function guarda em cp_samples ({ at, vehicles, routes });
 * `python tools/fetch_cp_samples.py` traz as do Firebase e `node tools/cp_sample_now.js` faz uma agora. Cada erro
 * que se encontrar no mapa deve ficar aqui como amostra.
 */
class CpLiveReplayTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadNetwork() {
            CpNetwork.load(File("src/main/assets/cp_network.json.bin").inputStream())
        }
    }

    private class Sample(val name: String, val at: Long, val vehicles: List<CpLiveInput>, val routes: Map<Int, CpRoute>)

    private fun stationPoint(code: String): Pair<Double, Double>? = CpNetwork.stationsByCode[code]?.let { it.lat to it.lon }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun samples(): List<Sample> {
        val dir = File("src/test/resources/cp_samples")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue("sem amostras em $dir", files.isNotEmpty())
        return files.map { f ->
            val j = JSONObject(f.readText())
            val at = j.getLong("at")
            val vj = j.getJSONObject("vehicles")
            val vehicles = vj.keys().asSequence().map { k ->
                val v = vj.getJSONObject(k)
                val lat = v.text("latitude")?.toDoubleOrNull()
                val lon = v.text("longitude")?.toDoubleOrNull()
                CpLiveInput(
                    number = v.getInt("trainNumber"),
                    runDate = v.text("runDate"),
                    lastStationCode = v.text("lastStation"),
                    status = v.text("status"),
                    delaySeconds = if (v.isNull("delay")) null else v.optInt("delay"),
                    gps = if (lat != null && lon != null && lat != 0.0) lat to lon else null,
                    gpsAtMs = if (v.isNull("timestamp")) null else v.optLong("timestamp").takeIf { it > 0 },
                    passCode = v.text("passCode"),
                    passTime = v.text("passTime"),
                    passLeft = if (v.isNull("passLeft") || !v.has("passLeft")) null else v.getBoolean("passLeft"),
                    dataAtMs = at
                )
            }.toList()
            val rj = j.optJSONObject("routes") ?: JSONObject()
            val routes = rj.keys().asSequence().mapNotNull { k -> CpRoute.parse(rj.optString(k))?.let { k.toInt() to it } }.toMap()
            Sample(f.name, at, vehicles, routes)
        }
    }

    /** Os comboios de uma amostra montados como o mapa os monta. */
    private fun models(sample: Sample): List<Pair<CpLiveInput, CpLiveTrain>> = sample.vehicles.mapNotNull { v ->
        val route = sample.routes[v.number] ?: return@mapNotNull null
        val geometry = CpLive.routeGeometry(v.number, route, ::stationPoint) ?: return@mapNotNull null
        CpLive.fromRoute(v, route, geometry, ::stationPoint)?.let { v to it }
    }

    @Test
    fun almostEveryTrainWithARouteGetsAPlaceOnTheTrack() {
        for (sample in samples()) {
            val withRoute = sample.vehicles.count { sample.routes.containsKey(it.number) }
            val built = models(sample).size
            println("${sample.name}: ${sample.vehicles.size} comboios, $withRoute com percurso, $built com posição calculada")
            assertTrue("${sample.name}: só $built de $withRoute", built >= withRoute * 0.8)
        }
    }

    @Test
    fun withoutNewDataNoTrainGoesBackwardsOrLeavesItsRoute() {
        for (sample in samples()) {
            for ((v, model) in models(sample)) {
                val known = model.geometry.stopAt.filterNotNull()
                val lo = known.min() - 1.0
                val hi = known.max() + 1.0
                var last = Double.NEGATIVE_INFINITY
                var t = sample.at
                while (t <= sample.at + 20 * 60_000L) {
                    val n = model.now(CpLive.lisbon(t))
                    val s = model.metersOf(n)
                    if (s != null) {
                        assertTrue("${sample.name} ${v.number}: $s fora do percurso", s.isFinite() && s in lo..hi)
                        assertTrue("${sample.name} ${v.number}: andou para trás ($last → $s) às ${CpLive.lisbon(t)}", s >= last - 0.5)
                        last = s
                    }
                    t += 5_000L
                }
            }
        }
    }

    @Test
    fun neverBehindTheLastStationOrTheRecordedPassage() {
        for (sample in samples()) {
            for ((v, model) in models(sample)) {
                for (minutes in listOf(0L, 2L, 5L)) {
                    val now = CpLive.lisbon(sample.at + minutes * 60_000L)
                    val n = model.now(now)
                    if (!n.started || n.cancelled) continue
                    val reported = v.lastStationCode?.let { c -> model.stops.indexOfLast { it.station?.code == c } } ?: -1
                    if (reported >= 0) {
                        assertTrue("${sample.name} ${v.number}: ${n.position} atrás da última estação $reported", n.position >= reported - 1e-3f)
                    }
                    val pass = model.pass
                    if (pass != null && !pass.at.isAfter(now)) {
                        assertTrue("${sample.name} ${v.number}: ${n.position} atrás da passagem ${pass.position}", n.position >= pass.position - 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun theComputedPlaceAgreesWithAFreshGps() {
        // Com um GPS de há menos de 1 min no traçado, a posição calculada tem de estar perto dele (a conta não pode
        // inventar: direção trocada, paragens no sítio errado, horas de outro dia…)
        for (sample in samples()) {
            val gaps = ArrayList<Double>()
            for ((v, model) in models(sample)) {
                val gps = v.gps ?: continue
                val gpsAt = v.gpsAtMs ?: continue
                if (sample.at - gpsAt > 60_000L) continue
                val track = model.geometry.track
                val hit = track.locate(gps)
                if (CpTrack.metres(track.pointAt(hit.s), gps) > 500.0) continue
                val n = model.now(CpLive.lisbon(sample.at))
                val s = model.metersOf(n) ?: continue
                gaps += abs(s - hit.s)
            }
            gaps.sort()
            val within = gaps.count { it <= 1_500.0 }
            println("${sample.name}: ${gaps.size} com GPS fresco; diferença mediana ${gaps.getOrNull(gaps.size / 2)?.toInt()} m, máxima ${gaps.lastOrNull()?.toInt()} m")
            assertTrue("${sample.name}: só $within de ${gaps.size} perto do GPS", gaps.isEmpty() || within >= gaps.size * 0.9)
        }
    }
}
