package com.example.metrotime.data

import java.text.Normalizer

/**
 * As quatro linhas do metro: ordem das estações, terminais, ligações e em que linha se viaja.
 * Sem dependências Android (testado em app/src/test). Ids = os da API ("RB", "AM", …).
 * As linhas e estações do Metro do Porto ("MP-A", "MP:5737") respondem pelo [PortoMetro], para o mapa, o painel do
 * metro e o da estação serem os mesmos nas duas cidades.
 */
object MetroLines {
    val NAMES = listOf("Azul", "Amarela", "Verde", "Vermelha")

    /** Estações da linha, por ordem (Reboleira → Santa Apolónia…). */
    fun stops(line: String): List<String> =
        if (com.example.metrotime.data.metro.PortoMetro.isPorto(line)) com.example.metrotime.data.metro.PortoMetro.stops(line)
        else MapConfiguration.LINE_STATION_ORDER[line].orEmpty()

    fun nameOf(apiId: String): String =
        MapConfiguration.STATION_COORDS[apiId]?.name ?: com.example.metrotime.data.metro.PortoMetro.nameOf(apiId) ?: apiId

    /** "Reboleira ↔ Santa Apolónia". */
    fun terminals(line: String): String? {
        val s = stops(line)
        return if (s.isEmpty()) null else "${nameOf(s.first())} ↔ ${nameOf(s.last())}"
    }

    /** Linhas que passam numa estação, pela ordem de NAMES. */
    fun linesOf(apiId: String): List<String> =
        if (com.example.metrotime.data.metro.PortoMetro.isPorto(apiId)) com.example.metrotime.data.metro.PortoMetro.linesOf(apiId)
        else NAMES.filter { apiId in stops(it) }

    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^a-z0-9]+")

    /** "Baixa-Chiado" → "baixa chiado", "São Sebastião" → "sao sebastiao". */
    fun normalize(s: String): String =
        NON_WORD.replace(MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(), " ").trim()

    fun idByName(name: String): String? {
        val n = normalize(name)
        return MapConfiguration.STATION_COORDS.values.firstOrNull { normalize(it.name) == n }?.id
            ?: com.example.metrotime.data.metro.PortoMetro.idByName(n, ::normalize)
    }

    /**
     * Linha em que se viaja de [fromApiId] até ao destino [destinationName]: a que tem as duas estações,
     * de preferência aquela em que o destino é terminal (Campo Grande é da Amarela e da Verde).
     */
    fun lineOfTravel(fromApiId: String, destinationName: String): String? {
        // No Porto a linha vem com a chegada (várias linhas partilham os mesmos destinos)
        if (com.example.metrotime.data.metro.PortoMetro.isPorto(fromApiId)) return null
        val dest = idByName(destinationName) ?: return null
        val both = NAMES.filter { fromApiId in stops(it) && dest in stops(it) }
        return both.firstOrNull { stops(it).first() == dest || stops(it).last() == dest } ?: both.firstOrNull()
    }

    /**
     * Troço afetado por uma perturbação: posições (na ordem da linha) entre as estações da linha que a
     * mensagem do Metro refere ("Circulação interrompida entre Cais do Sodré e Baixa-Chiado").
     * null se a mensagem não referir pelo menos duas.
     */
    fun affectedRange(line: String, message: String): IntRange? {
        val text = " ${normalize(message)} "
        val hits = stops(line).withIndex()
            .filter { (_, id) -> text.contains(" ${normalize(nameOf(id))} ") }
            .map { it.index }
        return if (hits.size >= 2) hits.min()..hits.max() else null
    }

    /** Um sentido numa estação: destino e segundos até aos próximos metros (do mais próximo). */
    data class Direction(val destination: String, val seconds: List<Int>)

    /** Os tempos de uma estação agrupados por linha (pela ordem das linhas da estação) e por sentido. */
    fun groupByLine(stationApiId: String, arrivals: List<Arrival>): List<Pair<String, List<Direction>>> {
        val order = linesOf(stationApiId)
        return arrivals
            .groupBy { lineOfTravel(stationApiId, it.direction) ?: it.line }
            .map { (line, list) ->
                line to list.groupBy { it.direction }
                    .map { (dest, times) -> Direction(dest, times.map { it.minutes }.sorted().take(3)) }
                    .sortedBy { it.seconds.firstOrNull() ?: Int.MAX_VALUE }
            }
            .sortedBy { (line, _) -> order.indexOf(line).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
