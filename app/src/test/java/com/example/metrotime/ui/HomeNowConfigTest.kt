package com.example.metrotime.ui

import com.example.metrotime.ui.HomeNowConfig.Card
import com.example.metrotime.ui.HomeNowConfig.CpCard
import com.example.metrotime.ui.HomeNowConfig.Entry
import com.example.metrotime.ui.HomeNowConfig.TrainRow
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNowConfigTest {

    @Test
    fun nothingSavedShowsEverythingInTheDefaultOrder() {
        assertEquals(HomeNowConfig.DEFAULT, HomeNowConfig.parse(null))
        assertEquals(HomeNowConfig.DEFAULT, HomeNowConfig.parse(""))
        assertEquals(
            listOf(Card.METRO, Card.CP_TRAINS, Card.BUS_STOPS, Card.MY_STATIONS, Card.HOME, Card.FAVORITES),
            HomeNowConfig.DEFAULT.map { it.card }
        )
    }

    @Test
    fun savedOrderAndVisibilitySurvive() {
        val entries = listOf(
            Entry(Card.CP_TRAINS, true), Entry(Card.HOME, true), Entry(Card.METRO, false),
            Entry(Card.MY_STATIONS, true), Entry(Card.BUS_STOPS, true), Entry(Card.FAVORITES, false)
        )
        assertEquals(entries, HomeNowConfig.parse(HomeNowConfig.encode(entries)))
    }

    @Test
    fun unknownCardsAreDroppedAndNewOnesAddedVisible() {
        // Guardado numa versão com só quatro cartões, e um que já não existe
        val parsed = HomeNowConfig.parse("home:1,velho:1,metro:0,favorites:1,bus_stops:0")
        assertEquals(
            listOf(
                Entry(Card.HOME, true), Entry(Card.METRO, false), Entry(Card.FAVORITES, true), Entry(Card.BUS_STOPS, false),
                Entry(Card.CP_TRAINS, true), Entry(Card.MY_STATIONS, true)
            ),
            parsed
        )
    }

    @Test
    fun movingCarriesTheItemAndTheRestCloseUp() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), HomeNowConfig.move(list, 2, 0))
        assertEquals(listOf("b", "c", "a", "d"), HomeNowConfig.move(list, 0, 2))
        assertEquals(list, HomeNowConfig.move(list, 0, 9))
        assertEquals(list, HomeNowConfig.move(list, 1, 1))
    }

    @Test
    fun cpCardSettingsSurviveAndBadOnesFallBack() {
        val cfg = CpCard(services = setOf("IC", "U", "FT"), byService = true, onlyLate = true, count = 8)
        assertEquals(cfg, CpCard.parse(cfg.encode()))
        assertEquals(CpCard(), CpCard.parse(null))
        assertEquals(CpCard(), CpCard.parse("lixo"))
        // Número fora dos limites fica nos limites; serviços desconhecidos saem
        assertEquals(CpCard(services = setOf("AP"), count = CpCard.MAX_COUNT), CpCard.parse("AP,XX;delay;0;99"))
    }

    private val trains = listOf(
        TrainRow("U", 18234, null, "Sintra → Rossio", null, 7),
        TrainRow("IC", 523, null, "Lisboa → Porto", null, 18),
        TrainRow("AP", 133, null, "Porto → Faro", null, 4),
        TrainRow("R", 4420, null, "Tomar → Lisboa", null, 9),
        TrainRow("U", 16012, null, "Azambuja → Alcântara", null, 0),
        TrainRow("AP", 131, null, "Lisboa → Braga", null, 0)
    )

    @Test
    fun byDelayPutsTheMostLateFirstOnlyForChosenServices() {
        val out = HomeNowConfig.arrange(trains, CpCard(services = setOf("AP", "IC", "U"), count = 10))
        assertEquals(listOf(523, 18234, 133, 131, 16012), out.map { it.number })
    }

    @Test
    fun byServiceGroupsInServiceOrder() {
        val out = HomeNowConfig.arrange(trains, CpCard(services = setOf("AP", "IC", "U", "R"), byService = true, count = 10))
        assertEquals(listOf("AP", "AP", "IC", "R", "U", "U"), out.map { it.service })
        assertEquals(listOf(133, 131), out.take(2).map { it.number }) // dentro do grupo, o mais atrasado primeiro
    }

    @Test
    fun onlyLateAndCountLimitTheList() {
        val out = HomeNowConfig.arrange(trains, CpCard(services = HomeNowConfig.SERVICES.toSet(), onlyLate = true, count = 3))
        assertEquals(listOf(523, 4420, 18234), out.map { it.number })
    }

    @Test
    fun stationsAreLimitedToFiveWithoutRepeats() {
        val four = listOf("a", "b", "c", "d")
        assertEquals(four + "e", HomeNowConfig.addStation(four, "e"))
        assertEquals(four, HomeNowConfig.addStation(four, "b"))
        assertEquals(four + "e", HomeNowConfig.addStation(four + "e", "f"))
    }
}
