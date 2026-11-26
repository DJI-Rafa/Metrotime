package com.example.metrotime.data.metro

import com.example.metrotime.data.cp.CpTrack
import com.example.metrotime.data.regional.TransitPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** O Metro do Porto no motor do metro de Lisboa, com o pacote real (app/src/main/assets/transit_mdp.bin). */
class PortoMetroTest {

    private val pack: TransitPack =
        File("src/main/assets/transit_mdp.bin").inputStream().use { TransitPack.parse(it) }
    private val net = PortoMetro.build(pack)

    /** Uma terça-feira em que o horário do pacote está em vigor. */
    private fun serviceTuesday(): LocalDate {
        val today = LocalDate.now(ZoneId.of("Europe/Lisbon"))
        return (-60L..120L).map { today.plusDays(it) }
            .first { it.dayOfWeek == DayOfWeek.TUESDAY && pack.services.any { s -> s.runsOn(it) } }
    }

    @Test
    fun everyLineHasItsStationsInOrderOnARealTrack() {
        for (l in listOf("A", "B", "C", "D", "E", "F")) assertNotNull("linha $l", net.tracks["MP-$l"])
        net.tracks.values.forEach { t ->
            assertTrue(t.line, t.stations.size >= 5)
            assertEquals(t.stations.size, t.stationAt.size)
            for (i in 1 until t.stationAt.size) assertTrue("${t.line} recua em $i", t.stationAt[i] > t.stationAt[i - 1])
            assertTrue(t.stationAt.last() <= t.length + 1.0)
        }
        // A linha A tem curvas (antes vinha reta por usar o traçado de outra linha)
        val a = net.tracks.getValue("MP-A")
        val ends = CpTrack.metres(a.point(0.0), a.point(a.length))
        assertTrue("A: ${a.length} m de traçado para $ends m em linha reta", a.length > ends * 1.15)
        // Trindade é correspondência de várias linhas
        val trindade = net.names.entries.first { it.value == "Trindade" }.key
        assertTrue(net.linesByStation.getValue(trindade).size >= 4)
        assertTrue(net.colors.values.all { it.matches(Regex("#[0-9A-Fa-f]{6}")) })
    }

    @Test
    fun theTimetableGivesTrainsThatMoveAlongTheirLine() {
        val zone = ZoneId.of("Europe/Lisbon")
        val now = serviceTuesday().atTime(8, 30).atZone(zone)
        val nowMs = now.toInstant().toEpochMilli()
        val trains = PortoMetro.trains(pack, net, now)
        assertTrue(trains.size > 20)
        assertTrue(trains.all { PortoMetro.isPorto(it.line) && it.line in net.tracks })
        val placed = trains.mapNotNull { tr -> tr.placeAt(nowMs, net.tracks.getValue(tr.line)) }
        assertTrue("metros a andar às 8:30: ${placed.size}", placed.size > 20)
        placed.forEach { p -> assertTrue(p.s >= 0.0 && p.s <= net.tracks.getValue(p.line).length + 1.0) }
        // Cada viagem: horas que não andam para trás e parte de cada estação depois de chegar
        trains.forEach { tr ->
            val run = tr.runs.single()
            for (i in 1 until run.arrive.size) assertTrue(run.arrive[i] >= run.arrive[i - 1])
            for (i in run.arrive.indices) assertTrue(run.depart[i] >= run.arrive[i])
            assertFalse(run.destinationName.isBlank())
        }
        // Um minuto depois, um metro em andamento está mais à frente no seu sentido
        val moving = placed.first { it.atStation == null && it.alpha >= 1.0 }
        val train = trains.first { it.trainId == moving.trainId && it.line == moving.line }
        val later = train.placeAt(nowMs + 60_000L, net.tracks.getValue(train.line))
        if (later != null) assertTrue((later.s - moving.s) * moving.dir > 0)
    }
}
