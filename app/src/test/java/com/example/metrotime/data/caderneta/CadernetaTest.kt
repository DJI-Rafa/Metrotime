package com.example.metrotime.data.caderneta

import com.example.metrotime.data.history.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CadernetaTest {

    private val zone = ZoneId.of("Europe/Lisbon")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Uma rede pequena: a Azul com 4 estações e a Verde com 3, a cruzarem-se em BC
    private val lines = mapOf("Azul" to listOf("RB", "JZ", "MP", "BC"), "Verde" to listOf("TE", "BC", "CS"), "Amarela" to listOf("OD", "MP"), "Vermelha" to listOf("SS", "AP"))
    private fun path(a: String, b: String): List<String>? {
        val line = lines.values.firstOrNull { a in it && b in it } ?: return null
        val i = line.indexOf(a)
        val j = line.indexOf(b)
        return if (i <= j) line.subList(i, j + 1) else line.subList(j, i + 1).reversed()
    }

    private fun metro(from: String, to: String, start: Long, lines: List<String> = listOf("Azul"), km: Double = 3.0) =
        Trip("m_$start", "metro", from, from, to, to, lines, start, start + 15 * 60_000L, km, "auto")

    private fun cp(
        from: String, to: String, start: Long, service: String = "U", number: String? = null, top: Double? = null, delay: Int? = null, km: Double = 20.0,
        strike: Boolean = false
    ) = Trip(
        "c_$start", "cp", from, from, to, to, listOf(service), start, start + 40 * 60_000L, km, "train",
        service = service, trainNumber = number, topKmh = top, delayMin = delay, strikeDay = strike
    )

    private val cpStations = listOf(
        CpStationInfo("94-7005", "Valença", 42.03, -8.64, listOf("Linha do Minho"), "Norte"),
        CpStationInfo("94-66076", "Sete Rios", 38.741, -9.167, listOf("Linha de Cintura"), "Lisboa"),
        CpStationInfo("94-31039", "Lisboa Oriente", 38.768, -9.099, listOf("Linha de Cintura"), "Lisboa"),
        CpStationInfo("94-12005", "Pocinho", 41.13, -7.11, listOf("Linha do Douro"), "Norte"),
        CpStationInfo("94-73569", "Vila Real de Santo António", 37.19, -7.42, listOf("Linha do Algarve"), "Algarve"),
        // Em Espanha, mais a norte do que Valença: está na lista da CP mas não é de nenhuma região
        CpStationInfo("71-22308", "Vigo-Guixar", 42.24, -8.83, emptyList(), null)
    )

    private fun book(trips: List<Trip>, passed: (Trip) -> List<String> = { emptyList() }) = Stamps.collect(trips, zone, ::path, passed)

    private fun input(
        trips: List<Trip>, passed: (Trip) -> List<String> = { emptyList() }, streak: Int = 0, disruptions: List<Disruption> = emptyList()
    ) = BadgeInput(
        trips = trips, book = book(trips, passed), metroLines = lines,
        metroCoords = mapOf("JZ" to (38.742 to -9.168), "RB" to (38.752 to -9.224), "MP" to (38.725 to -9.150), "BC" to (38.711 to -9.139)),
        cpStations = cpStations, cpLineOf = { t -> cpStations.firstOrNull { it.code == t.fromId }?.lines?.firstOrNull() },
        longestStreak = streak, extraCpKm = 0.0, timetableTrains = 1628, disruptions = disruptions, zone = zone,
        metroNames = mapOf("RB" to "Reboleira", "JZ" to "Jardim Zoológico", "MP" to "Marquês de Pombal", "BC" to "Baixa-Chiado")
    )

    private fun why(trips: List<Trip>, id: String, passed: (Trip) -> List<String> = { emptyList() }): String? =
        Badges.evaluate(input(trips, passed)).first { it.def.id == id }.why

    private fun earned(trips: List<Trip>, id: String, passed: (Trip) -> List<String> = { emptyList() }): Boolean =
        Badges.evaluate(input(trips, passed)).first { it.def.id == id }.earned

    /** Iniciar viagem sem destino: guarda as linhas todas da estação. */
    private fun open(at: String, start: Long, lines: List<String>) =
        Trip("h_$start", "metro", at, at, lines = lines, startMs = start, source = "manual")

    @Test
    fun aTripStampsBothEndsFullAndTheMiddleAsPassing() {
        val b = book(listOf(metro("RB", "BC", ms(2026, 11, 23, 8, 0))))
        assertTrue(b.isFull(Stamps.metro("RB")))
        assertTrue(b.isFull(Stamps.metro("BC")))
        assertEquals(StampKind.PASSED, b.stamps[Stamps.metro("JZ")]?.kind)
        assertEquals(StampKind.PASSED, b.stamps[Stamps.metro("MP")]?.kind)
        assertFalse(b.has(Stamps.metro("TE")))
    }

    @Test
    fun leavingAtAStationLaterMakesThePassingStampFull() {
        val b = book(listOf(metro("RB", "BC", ms(2026, 11, 23, 8, 0)), metro("JZ", "RB", ms(2026, 11, 24, 8, 0))))
        val jz = b.stamps[Stamps.metro("JZ")]!!
        assertEquals(StampKind.FULL, jz.kind)
        assertEquals(1, jz.full)
        assertEquals(1, jz.passed)
        assertNotNull(jz.firstFullMs)
    }

    @Test
    fun trainTripsStampThePassedStations() {
        val b = book(listOf(cp("94-31039", "94-7005", ms(2026, 11, 23, 9, 0), number = "521"))) { listOf("94-66076") }
        assertTrue(b.isFull(Stamps.cp("94-7005")))
        assertEquals(StampKind.PASSED, b.stamps[Stamps.cp("94-66076")]?.kind)
    }

    @Test
    fun aLineIsCompleteWithFullOrPassingStamps() {
        val trips = listOf(metro("RB", "BC", ms(2026, 11, 23, 8, 0)))
        assertTrue(earned(trips, "linhaAzul"))
        assertFalse(earned(trips, "linhaVerde"))
        val verde = Badges.evaluate(input(trips)).first { it.def.id == "linhaVerde" }
        assertEquals("1 de 3 estações", verde.progressText)
    }

    @Test
    fun nightEarlyAndNewYear() {
        assertTrue(earned(listOf(metro("RB", "JZ", ms(2026, 11, 23, 0, 40))), "noctivago"))
        assertFalse(earned(listOf(metro("RB", "JZ", ms(2026, 11, 23, 0, 10))), "noctivago"))
        assertTrue(earned(listOf(cp("94-31039", "94-66076", ms(2026, 11, 23, 0, 10))), "noctivago"))
        assertTrue(earned(listOf(metro("RB", "JZ", ms(2026, 11, 23, 6, 5))), "madrugador"))
        assertTrue(earned(listOf(metro("RB", "JZ", ms(2026, 11, 23, 6, 31))), "oPrimeiro"))
        assertTrue(earned(listOf(metro("RB", "JZ", ms(2026, 12, 31, 23, 50))), "ressacaAnoNovo"))
        assertFalse(earned(listOf(metro("RB", "JZ", ms(2026, 12, 31, 22, 0))), "ressacaAnoNovo"))
        assertTrue(earned(listOf(metro("RB", "JZ", ms(2026, 12, 29, 10, 0))), "parabensMetro"))
    }

    @Test
    fun speedDelayAndTrainNumbers() {
        val day = ms(2026, 11, 23, 10, 0)
        assertTrue(earned(listOf(cp("94-31039", "94-7005", day, "AP", top = 205.0)), "relampago"))
        assertFalse(earned(listOf(cp("94-31039", "94-7005", day, "AP", top = 190.0)), "relampago"))
        assertTrue(earned(listOf(cp("94-31039", "94-7005", day, "IC", top = 201.0)), "relampagoIc"))
        assertTrue(earned(listOf(cp("94-31039", "94-7005", day, delay = 65)), "pacienciaSanto"))
        assertFalse(earned(listOf(cp("94-31039", "94-7005", day, delay = 65)), "pacienciaMonge"))
        assertTrue(earned(listOf(cp("94-31039", "94-7005", day, delay = -2)), "adiantado"))
        assertTrue(earned(listOf(cp("94-31039", "94-7005", day, number = "16061")), "capicua"))
        assertFalse(earned(listOf(cp("94-31039", "94-7005", day, number = "16062")), "capicua"))
    }

    @Test
    fun allFourLinesInOneDay() {
        val d = ms(2026, 11, 23, 9, 0)
        val trips = listOf(
            metro("RB", "MP", d, listOf("Azul")), metro("MP", "OD", d + 3_600_000, listOf("Amarela")),
            metro("TE", "CS", d + 7_200_000, listOf("Verde")), metro("SS", "AP", d + 10_800_000, listOf("Vermelha"))
        )
        assertTrue(earned(trips, "voltaLisboa"))
        assertFalse(earned(trips.dropLast(1), "voltaLisboa"))
    }

    @Test
    fun startingAtAConnectionDoesNotTellTheLine() {
        val d = ms(2026, 11, 23, 9, 0)
        // MP (Azul e Amarela) e BC (Azul e Verde) sem destino: não se sabe em que linha se foi
        val hubs = listOf(open("MP", d, listOf("Azul", "Amarela")), open("BC", d + 3_600_000, listOf("Azul", "Verde")), metro("SS", "AP", d + 7_200_000, listOf("Vermelha")))
        assertFalse(earned(hubs, "voltaLisboa"))
        // Numa estação de uma linha só, sabe-se
        val single = listOf(open("OD", d, listOf("Amarela")), open("RB", d + 1, listOf("Azul")), open("TE", d + 2, listOf("Verde")), open("AP", d + 3, listOf("Vermelha")))
        assertTrue(earned(single, "voltaLisboa"))
    }

    @Test
    fun survivorOnlyOnTheLineRidden() {
        val d = ms(2026, 11, 23, 9, 0)
        val amarela = listOf(Disruption("Amarela", d - 600_000, d + 600_000))
        fun survivor(trips: List<Trip>) = Badges.evaluate(input(trips, disruptions = amarela)).first { it.def.id == "sobrevivente" }.earned
        assertFalse(survivor(listOf(open("MP", d, listOf("Azul", "Amarela")))))
        assertTrue(survivor(listOf(metro("MP", "OD", d, listOf("Amarela")))))
        assertFalse(survivor(listOf(metro("MP", "RB", d, listOf("Azul")))))
    }

    @Test
    fun fromMinhoToAlgarveIsInPortugal() {
        val d = ms(2026, 11, 23, 9, 0)
        // Vigo-Guixar fica mais a norte do que Valença, mas é em Espanha: as pontas são Valença e Vila Real de Santo António
        val trips = listOf(cp("94-31039", "94-7005", d), cp("94-31039", "94-73569", d + 86_400_000L))
        assertTrue(earned(trips, "minhoAlgarve"))
        assertFalse(earned(trips.take(1), "minhoAlgarve"))
    }

    @Test
    fun strikeDayShowsTheTrainAndTheDay() {
        val normal = cp("94-66076", "94-31039", ms(2026, 11, 20, 9, 0))
        assertFalse(earned(listOf(normal), "diaDeGreve"))
        val strike = cp("94-31039", "94-12005", ms(2026, 11, 24, 18, 0), service = "IC", number = "521", strike = true)
        val state = Badges.evaluate(input(listOf(normal, strike))).first { it.def.id == "diaDeGreve" }
        assertTrue(state.earned)
        assertEquals("IC 521 · 24 NOV", state.label)
        assertTrue(Badges.trusted("diaDeGreve", state.label))
        assertEquals(
            "Intercidades 521, de 94-31039 para 94-12005, a 24 de novembro de 2026: um dos poucos comboios que circularam num dia de greve na CP.",
            state.story
        )
        // Os outros não têm história: a imagem de partilhar diz como se ganham
        assertNull(Badges.evaluate(input(listOf(strike))).first { it.def.id == "cpBronze" }.story)
    }

    @Test
    fun strikeLabelFitsWhatIsKnownOfTheTrain() {
        val day = LocalDate.of(2026, 11, 24)
        assertEquals("U 18012 · 24 NOV", Badges.strikeLabel("U", "18012", day))
        // Serviço desconhecido ("?") ou que não é da CP: fica só o número
        assertEquals("521 · 24 NOV", Badges.strikeLabel("?", "521", day))
        assertEquals("521 · 24 NOV", Badges.strikeLabel(null, "521", day))
        // Sem número: só o dia, com o ano
        assertEquals("24 NOV 2026", Badges.strikeLabel("IC", null, day))
        listOf("U 18012 · 24 NOV", "521 · 24 NOV", "24 NOV 2026").forEach { assertTrue(it, Badges.trusted("diaDeGreve", it)) }
        assertFalse(Badges.trusted("diaDeGreve", "GREVE"))
    }

    @Test
    fun unearnedBadgesSayWhy() {
        // Linha Azul: só falta uma estação, pelo nome
        val azul = metro("RB", "MP", ms(2026, 11, 23, 8, 0))
        assertEquals("Faltam 1 sem carimbo: Baixa-Chiado.", why(listOf(azul), "linhaAzul"))
        // Relâmpago: o Alfa mais rápido
        val alfa = cp("94-31039", "94-73569", ms(2026, 11, 23, 9, 0), service = "AP", top = 187.4)
        assertEquals("O teu Alfa Pendular mais rápido: 187 km/h mantidos 20 s seguidos (precisa de 200).", why(listOf(alfa), "relampago"))
        assertEquals("Ainda não tens viagens de Intercidades registadas.", why(listOf(alfa), "relampagoIc"))
        // Bicho: o Jardim Zoológico só de passagem
        assertEquals("Jardim Zoológico: só de passagem (precisa de cheio: sair lá).", why(listOf(azul), "bicho"))
        // Madrugador: a viagem mais cedo no dia
        assertTrue(why(listOf(azul, alfa), "madrugador")!!.endsWith("A tua mais cedo no dia: 8:00 (23/11/2026)."))
        // Os ganhos não têm porquê; os que ainda não se ganham dizem-no
        val early = metro("RB", "MP", ms(2026, 11, 23, 6, 0))
        val states = Badges.evaluate(input(listOf(early)))
        assertTrue(states.first { it.def.id == "madrugador" }.earned)
        assertNull(states.first { it.def.id == "madrugador" }.why)
        assertEquals("Ainda não se ganha nesta versão.", states.first { it.def.id == "mesmaCarruagem" }.why)
    }

    @Test
    fun keptBadgesSayWhereTheyCameFrom() {
        val states = Badges.evaluate(input(emptyList()))
        val kept = Badges.withKept(states, mapOf("fiel" to "365 DIAS", "regiao" to "COIMBRA"))
        assertTrue(kept.first { it.def.id == "fiel" }.why!!.startsWith("Ganho antes e guardado"))
        assertTrue(kept.first { it.def.id == "regiao" }.why!!.endsWith("dado por uma regra antiga que estava errada: já não vale."))
    }

    @Test
    fun strikeDayFromTheOldRuleIsNotKept() {
        // A regra antiga dava-o sem greve e sem o dia na fita: guardado assim (aqui ou no Firebase) já não vale
        val states = Badges.evaluate(input(emptyList()))
        assertFalse(Badges.withKept(states, mapOf("diaDeGreve" to "")).first { it.def.id == "diaDeGreve" }.earned)
        assertFalse(Badges.trusted("diaDeGreve", null))
        val kept = Badges.withKept(states, mapOf("diaDeGreve" to "IC 521 · 25 NOV")).first { it.def.id == "diaDeGreve" }
        assertTrue(kept.earned)
        assertEquals("IC 521 · 25 NOV", kept.label)
    }

    @Test
    fun keptBadgesStayEarned() {
        val states = Badges.evaluate(input(emptyList()))
        val kept = Badges.withKept(states, mapOf("linhaCp" to "SINTRA", "regiao" to "COIMBRA", "mesmaCarruagem" to ""))
        val linha = kept.first { it.def.id == "linhaCp" }
        assertTrue(linha.earned)
        assertEquals("SINTRA", linha.label)
        assertNull(linha.progress)
        // Dada por uma regra errada (uma zona da CP em vez de uma região): não vale
        assertFalse(kept.first { it.def.id == "regiao" }.earned)
        // Os que ainda não se conseguem ganhar também não
        assertFalse(kept.first { it.def.id == "mesmaCarruagem" }.earned)
        // Ganho agora: fica o texto da fita de agora
        val now = Badges.evaluate(input(listOf(cp("94-31039", "94-12005", ms(2026, 11, 23, 9, 0)))))
        assertEquals("DOURO", Badges.withKept(now, mapOf("linhaCp" to "SINTRA")).first { it.def.id == "linhaCp" }.label)
    }

    @Test
    fun metroAndTrainInTheSameJourney() {
        val d = ms(2026, 11, 23, 9, 0)
        // Metro até ao Jardim Zoológico (acaba às 9:15) e comboio de Sete Rios às 9:22
        val trips = listOf(metro("RB", "JZ", d), cp("94-66076", "94-31039", d + 22 * 60_000L))
        assertTrue(earned(trips, "doisMundos"))
        // Uma hora depois já não é a mesma viagem
        assertFalse(earned(listOf(metro("RB", "JZ", d), cp("94-66076", "94-31039", d + 80 * 60_000L)), "doisMundos"))
    }

    @Test
    fun farAwayStationsAndTheSecretZoo() {
        val d = ms(2026, 11, 23, 9, 0)
        assertTrue(earned(listOf(cp("94-31039", "94-12005", d)), "fimDoMundo"))
        assertTrue(earned(listOf(cp("94-31039", "94-12005", d)), "douroAteAoFim"))
        assertTrue(earned(listOf(cp("94-7005", "94-73569", d)), "portugalNumDia"))
        assertTrue(earned(listOf(metro("RB", "JZ", d)), "bicho"))
    }

    @Test
    fun kilometresAndMilestones() {
        val d = ms(2026, 11, 23, 9, 0)
        val trips = (0 until 6).map { cp("94-31039", "94-7005", d + it * 86_400_000L, km = 200.0) }
        assertTrue(earned(trips, "milKm"))
        assertFalse(earned(trips, "dezMil"))
        val st = Badges.evaluate(input(trips)).first { it.def.id == "dezMil" }
        assertEquals("1.200 de 10.000 km", st.progressText)
        assertFalse(earned(trips, "maratonista"))
        assertTrue(earned(trips.take(2).map { it.copy(startMs = d) }.mapIndexed { i, t -> t.copy(id = "x$i", startMs = d + i * 3_600_000L) }, "maratonista"))
    }

    @Test
    fun cpLinesGiveTheLabelOfTheLineCompleted() {
        val d = ms(2026, 11, 23, 9, 0)
        val trips = listOf(cp("94-31039", "94-12005", d))
        val st = Badges.evaluate(input(trips)).first { it.def.id == "linhaCp" }
        assertTrue(st.earned)
        assertEquals("DOURO", st.label)
    }

    @Test
    fun soonBadgesCannotBeEarnedYet() {
        val st = Badges.evaluate(input(emptyList())).first { it.def.id == "pioneiro" }
        assertFalse(st.earned)
        assertNotNull(st.progressText)
    }

    @Test
    fun catalogHas57UniqueBadges() {
        assertEquals(57, BadgeCatalog.ALL.size)
        assertEquals(57, BadgeCatalog.ALL.map { it.id }.toSet().size)
        assertEquals(11, BadgeCatalog.ALL.count { it.tier == Tier.COMUM })
        assertEquals(20, BadgeCatalog.ALL.count { it.tier == Tier.RARO })
        assertEquals(15, BadgeCatalog.ALL.count { it.tier == Tier.LENDARIO })
        assertEquals(8, BadgeCatalog.ALL.count { it.tier == Tier.IMPOSSIVEL })
        assertEquals(3, BadgeCatalog.ALL.count { it.tier == Tier.SECRETO })
    }

    @Test
    fun everyStationGetsItsOwnTile() {
        val keys = (1..456).map { "c:94-$it" }
        val patterns = Azulejos.assign(keys)
        assertEquals(456, patterns.values.toSet().size)
        // Sempre o mesmo para a mesma lista
        assertEquals(patterns, Azulejos.assign(keys.shuffled()))
    }

    @Test
    fun newBadgesAreTheEarnedOnesNotYetSeen() {
        val states = Badges.evaluate(input(listOf(metro("RB", "JZ", ms(2026, 12, 29, 10, 0)))))
        val fresh = Badges.newlyEarned(states, setOf("bicho"))
        assertTrue(fresh.any { it.def.id == "parabensMetro" })
        assertTrue(fresh.none { it.def.id == "bicho" })
    }

    @Test
    fun sunsetInLisbon() {
        val sunset = Badges.sunsetUtcMs(LocalDate.of(2026, 11, 23), 38.71, -9.14)!!
        val t = java.time.Instant.ofEpochMilli(sunset).atZone(zone).toLocalTime()
        // Em Lisboa, a 23 de novembro, o sol põe-se pelas 17:15
        assertTrue("pôr do sol às $t", t.isAfter(java.time.LocalTime.of(17, 5)) && t.isBefore(java.time.LocalTime.of(17, 25)))
        val summer = java.time.Instant.ofEpochMilli(Badges.sunsetUtcMs(LocalDate.of(2026, 6, 21), 38.71, -9.14)!!).atZone(zone).toLocalTime()
        assertTrue("pôr do sol às $summer", summer.isAfter(java.time.LocalTime.of(20, 55)) && summer.isBefore(java.time.LocalTime.of(21, 15)))
        assertNull(Badges.sunsetUtcMs(LocalDate.of(2026, 6, 21), 89.0, 0.0))
    }

    @Test
    fun labelsAndNumbers() {
        assertEquals("SINTRA", Badges.shortLabel("Linha de Sintra"))
        assertEquals("NORTE", Badges.shortLabel("Linha do Norte"))
        assertEquals("1.000", Badges.fmt(1000.0))
        assertEquals("40.075", Badges.fmt(40075.0))
        assertEquals("950", Badges.fmt(950.4))
    }
}
