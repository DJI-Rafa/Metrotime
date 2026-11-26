package com.example.metrotime.data.plan

import com.example.metrotime.data.bus.CarrisTimetable
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/** O RAPTOR com os horários verdadeiros da Carris e o metro (assets da app). */
class RaptorRealTest {

    companion object {
        private val stops: List<TransitBuilder.StopInfo> by lazy {
            val json = GZIPInputStream(File("src/main/assets/carris_stops.json.bin").inputStream()).bufferedReader(Charsets.UTF_8).readText()
            Regex("\"id\": \"([^\"]+)\", \"name\": \"((?:[^\"\\\\]|\\\\.)*)\", \"lat\": ([-\\d.]+), \"lon\": ([-\\d.]+)")
                .findAll(json).map { m -> TransitBuilder.StopInfo(m.groupValues[1], m.groupValues[2], m.groupValues[3].toDouble(), m.groupValues[4].toDouble()) }
                .toList()
        }
        private val static: TransitBuilder.Static by lazy {
            val tt = File("src/main/assets/carris_timetable.bin").inputStream().use { CarrisTimetable.parse(it) }
            TransitBuilder.buildStatic(tt, stops)
        }
        private val monday = LocalDate.of(2026, 10, 26)
    }

    private fun describe(net: TransitNetwork, j: Journey): String = j.legs.joinToString(" | ") { leg ->
        fun clock(s: Int) = "%02d:%02d".format(Math.floorMod(s / 3600, 24), (s / 60) % 60)
        when (leg) {
            is JLeg.Walk -> "a pé ${if (leg.from < 0) "partida" else net.stops[leg.from].name} → ${if (leg.to < 0) "chegada" else net.stops[leg.to].name} ${(leg.arr - leg.dep) / 60}min"
            is JLeg.Ride -> {
                val r = net.routes[leg.route]
                "${r.label} ${clock(leg.dep)} ${net.stops[r.stops[leg.fromPos]].name} → ${clock(leg.arr)} ${net.stops[r.stops[leg.toPos]].name}"
            }
        }
    }

    @Test
    fun santaApoloniaToRestelo() {
        assertTrue(stops.size > 2000)
        val net = TransitBuilder.network(static, monday, 14 * 3600, 20 * 3600, setOf(LegMode.BUS, LegMode.METRO))
        // A entrada da estação (onde está o metro de Santa Apolónia) e a paragem Restelo (EPUL)
        val restelo = stops.first { it.name.startsWith("Restelo (EPUL)") }
        val access = TransitBuilder.accessFrom(static, 38.7138, -9.12256)
        val egress = TransitBuilder.accessFrom(static, restelo.lat, restelo.lon)
        val all = Raptor.run(net, access, egress, 14 * 3600 + 20 * 60)
        val out = StringBuilder("TODOS\n")
        all.forEach { out.appendLine(describe(net, it)) }
        // Sem o 728: tem de haver outra forma (metro + 714, 728 + 714…)
        val no728 = Raptor.run(net, access, egress, 14 * 3600 + 20 * 60, allowRoute = { net.routes[it].label != "728" })
        out.appendLine("SEM 728")
        no728.forEach { out.appendLine(describe(net, it)) }
        File("build/raptor-santa-apolonia.txt").writeText(out.toString())

        assertTrue(all.isNotEmpty())
        val direct = all.first()
        assertTrue(describe(net, direct), direct.rides.size == 1 && net.routes[direct.rides[0].route].label == "728")
        // Entra na paragem à porta da estação, não na das Mercadorias
        val board = net.stops[net.routes[direct.rides[0].route].stops[direct.rides[0].fromPos]]
        assertTrue(board.name, !board.name.contains("Mercadorias"))
        assertTrue(no728.isNotEmpty())
    }

    @Test
    fun alternativesAndDeparturesForTheNextFiveHours() {
        val t0 = 14 * 3600 + 20 * 60
        val restelo = stops.first { it.name.startsWith("Restelo (EPUL)") }
        val access = TransitBuilder.accessFrom(static, 38.7138, -9.12256)
        val egress = TransitBuilder.accessFrom(static, restelo.lat, restelo.lon)
        val built = System.nanoTime()
        val net = TransitBuilder.network(static, monday, t0, t0 + 12 * 3600, setOf(LegMode.BUS, LegMode.METRO))
        val startFind = System.nanoTime()
        val found = Alternatives.find(net, access, egress, t0, 5 * 3600)
        val end = System.nanoTime()

        val out = StringBuilder("rede ${(startFind - built) / 1_000_000} ms, alternativas ${(end - startFind) / 1_000_000} ms\n")
        found.forEach { f ->
            out.appendLine("== ${f.signature} (${f.journeys.size} partidas${if (f.frequent) ", frequente" else ""})")
            f.journeys.forEach { out.appendLine("   " + describe(net, it)) }
        }
        File("build/raptor-alternativas.txt").writeText(out.toString())

        // Várias maneiras, cada uma com várias partidas nas próximas 5 horas
        assertTrue(out.toString(), found.size >= 3)
        val bus728 = found.first { it.signature == "BUS:728" }
        assertTrue(out.toString(), bus728.journeys.size >= 5)
        assertTrue(bus728.journeys.zipWithNext().all { (a, b) -> b.dep > a.dep })
        assertTrue(bus728.journeys.all { it.dep < t0 + 5 * 3600 })
        // Pelo menos uma maneira com metro e uma com mudança
        assertTrue(out.toString(), found.any { f -> f.signature.contains("METRO:") })
        assertTrue(out.toString(), found.any { f -> f.signature.contains(">") })
    }

    @Test
    fun onlyMetroOrOnlyBusFollowTheFilters() {
        val t0 = 9 * 3600
        val access = TransitBuilder.accessFrom(static, 38.7138, -9.12256)   // Santa Apolónia
        val egress = TransitBuilder.accessFrom(static, 38.7599, -9.15794)   // Campo Grande
        val metro = TransitBuilder.network(static, monday, t0, t0 + 12 * 3600, setOf(LegMode.METRO))
        val bus = TransitBuilder.network(static, monday, t0, t0 + 12 * 3600, setOf(LegMode.BUS))
        val m = Alternatives.find(metro, access, egress, t0, 5 * 3600)
        val b = Alternatives.find(bus, access, egress, t0, 5 * 3600)
        assertTrue(m.isNotEmpty() && m.all { f -> f.signature.split(">").all { it.startsWith("METRO:") } })
        assertTrue(b.isNotEmpty() && b.all { f -> f.signature.split(">").all { it.startsWith("BUS:") } })
        // O metro passa a toda a hora: poucas partidas, marcadas como frequentes
        assertTrue(m.first().frequent && m.first().journeys.size <= 3)
    }

    @Test
    fun metroClosesAtNight() {
        // Às 2h de segunda não há metro: o primeiro sai às 6h30
        val t0 = 2 * 3600
        val net = TransitBuilder.network(static, monday, t0, t0 + 8 * 3600, setOf(LegMode.METRO))
        val access = TransitBuilder.accessFrom(static, 38.7138, -9.12256)
        val egress = TransitBuilder.accessFrom(static, 38.7599, -9.15794)
        val j = Raptor.run(net, access, egress, t0).first()
        assertTrue(j.rides.first().dep >= 6 * 3600 + 30 * 60)
    }

    @Test
    fun departuresAreDistinct() {
        val t0 = 14 * 3600 + 20 * 60
        val restelo = stops.first { it.name.startsWith("Restelo (EPUL)") }
        val net = TransitBuilder.network(static, monday, t0, t0 + 12 * 3600, setOf(LegMode.BUS, LegMode.METRO))
        val found = Alternatives.find(
            net, TransitBuilder.accessFrom(static, 38.7138, -9.12256),
            TransitBuilder.accessFrom(static, restelo.lat, restelo.lon), t0, 5 * 3600
        )
        found.forEach { f ->
            val firstRides = f.journeys.map { it.rides.first().dep }
            assertTrue(f.signature, firstRides.zipWithNext().all { (a, b) -> b > a })
            assertTrue(f.signature, f.journeys.map { it.arr }.zipWithNext().all { (a, b) -> b > a })
        }
    }
}
