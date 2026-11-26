package com.example.metrotime.data.plan

import com.example.metrotime.data.bus.CarrisTimetable
import com.example.metrotime.data.bus.CmTimetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** O planeador com o horário verdadeiro da Carris Metropolitana (assets/cm_timetable.bin) junto com a Carris e o metro. */
class CmRealTest {

    companion object {
        private val cm: CarrisTimetable.Data by lazy { File("src/main/assets/cm_timetable.bin").inputStream().use { CarrisTimetable.parse(it) } }
        private val static: TransitBuilder.Static by lazy {
            val tt = File("src/main/assets/carris_timetable.bin").inputStream().use { CarrisTimetable.parse(it) }
            TransitBuilder.buildStatic(tt, emptyList(), cm)
        }
        private val monday = LocalDate.of(2026, 10, 26)
    }

    private fun describe(net: TransitNetwork, j: Journey): String = j.legs.joinToString(" | ") { leg ->
        fun clock(s: Int) = "%02d:%02d".format(Math.floorMod(s / 3600, 24), (s / 60) % 60)
        when (leg) {
            is JLeg.Walk -> "a pé ${(leg.arr - leg.dep) / 60}min"
            is JLeg.Ride -> {
                val r = net.routes[leg.route]
                "${r.label} ${clock(leg.dep)} ${net.stops[r.stops[leg.fromPos]].name} → ${clock(leg.arr)} ${net.stops[r.stops[leg.toPos]].name}"
            }
        }
    }

    @Test
    fun timetableHasStopsWithNamesAndPlaces() {
        assertTrue(cm.stopMeta.size > 10_000)
        assertTrue(cm.patterns.size > 1_000)
        val named = cm.stopMeta.values.count { it.name.isNotBlank() && it.lat in 38.0..39.5 && it.lon in -9.6..-8.4 }
        assertTrue("$named", named > cm.stopMeta.size * 0.99)
        assertTrue(cm.colors.values.all { it.length == 6 })
        assertEquals("A2L1N", CmTimetable.agencyOf("[ZLLEW][A2L1N]1000"))
    }

    @Test
    fun cacilhasToSeixalByCmBus() {
        val t0 = System.nanoTime()
        val net = TransitBuilder.network(static, monday, 9 * 3600, 21 * 3600, setOf(LegMode.BUS, LegMode.METRO))
        val built = (System.nanoTime() - t0) / 1_000_000
        // Terminal fluvial de Cacilhas → centro do Seixal: só há autocarros da Carris Metropolitana
        val access = TransitBuilder.accessFrom(static, 38.6875, -9.1480)
        val egress = TransitBuilder.accessFrom(static, 38.6405, -9.1005)
        val t1 = System.nanoTime()
        val alts = Alternatives.find(net, access, egress, 10 * 3600, 5 * 3600, 8, 16)
        val searched = (System.nanoTime() - t1) / 1_000_000
        val out = StringBuilder("rede $built ms · pesquisa $searched ms\n")
        alts.forEach { a -> out.appendLine(a.journeys.joinToString("\n   ") { describe(net, it) }) }
        File("build/raptor-cm-cacilhas-seixal.txt").writeText(out.toString())
        assertTrue(out.toString(), alts.isNotEmpty())
        val first = alts.first().journeys.first()
        val ride = first.rides.first()
        assertTrue(describe(net, first), net.stops[net.routes[ride.route].stops[ride.fromPos]].id.startsWith(TransitBuilder.CM_PREFIX))
        // Várias partidas nas próximas 5 horas
        assertTrue(out.toString(), alts.sumOf { it.journeys.size } >= 5)
    }

    @Test
    fun lisbonToAlmadaMixesMetroAndCm() {
        val net = TransitBuilder.network(static, monday, 9 * 3600, 21 * 3600, setOf(LegMode.BUS, LegMode.METRO))
        // Saldanha → Almada (Fórum): metro e a ponte de autocarro (Carris Metropolitana)
        val access = TransitBuilder.accessFrom(static, 38.7350, -9.1453)
        val egress = TransitBuilder.accessFrom(static, 38.6594, -9.1782)
        val all = Raptor.run(net, access, egress, 10 * 3600)
        val text = all.joinToString("\n") { describe(net, it) }
        File("build/raptor-cm-saldanha-almada.txt").writeText(text)
        assertTrue(text, all.isNotEmpty())
        assertTrue(text, all.any { j -> j.rides.any { net.stops[net.routes[it.route].stops[it.fromPos]].id.startsWith(TransitBuilder.CM_PREFIX) } })
    }

    @Test
    fun operatorsWithoutScheduleYetUseTheLastSameWeekday() {
        // Daqui a umas semanas um dos operadores ainda não publicou o horário: o planeador usa o da última
        // segunda-feira que tinha, em vez de ficar sem autocarros nessa zona
        val later = monday.plusWeeks(5)
        val active = TransitBuilder.cmServicesOn(cm, later)
        val agencies = cm.services.indices.filter { active[it] }.map { CmTimetable.agencyOf(cm.services[it].name) }.toSet()
        val all = cm.services.map { CmTimetable.agencyOf(it.name) }.toSet()
        assertEquals(all, agencies)
    }
}
