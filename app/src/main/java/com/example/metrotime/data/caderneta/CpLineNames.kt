package com.example.metrotime.data.caderneta

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Os nomes das linhas da CP. A lista de estações da CP só diz o número de cada linha ("8", "21"…), por isso cada linha
 * é reconhecida por uma estação que só ela tem (Sintra é da Linha de Sintra, Pocinho da do Douro). As que não se
 * reconhecem ficam com o nome das duas estações das pontas ("Porto São Bento – Marco de Canaveses"). Os números com
 * menos de 3 estações (ligações entre linhas) não contam.
 */
object CpLineNames {

    const val MIN_STATIONS = 3

    /** Para cada linha, estações que só ela tem (códigos da CP). */
    private val ANCHORS: List<Pair<String, List<String>>> = listOf(
        "Linha de Sintra" to listOf("94-61101"),                                  // Sintra
        "Linha de Cascais" to listOf("94-69260"),                                 // Cascais
        "Linha de Cintura" to listOf("94-66076", "94-66019"),                     // Sete Rios, Marvila
        "Linha do Norte" to listOf("94-32185", "94-33001", "94-31278", "94-39115"), // Santarém, Azambuja, Vila Franca, Valadares
        "Linha do Minho" to listOf("94-7005", "94-18002", "94-6122"),             // Valença, Viana do Castelo, Barcelos
        "Linha do Douro" to listOf("94-12005", "94-10009", "94-9001"),            // Pocinho, Régua, Marco de Canaveses
        "Linha de Guimarães" to listOf("94-24000", "94-28068"),                   // Guimarães, Santo Tirso
        "Ramal de Braga" to listOf("94-29157"),                                   // Braga
        "Linha do Vouga" to listOf("94-44339", "94-43000", "94-44255", "94-44016"), // Oliveira de Azeméis, Sernada, São João da Madeira, Espinho-Vouga
        "Linha da Beira Alta" to listOf("94-48009", "94-46672", "94-49460"),      // Mangualde, Nelas, Vilar Formoso
        "Linha da Beira Baixa" to listOf("94-53546", "94-53009", "94-54007"),     // Fundão, Castelo Branco, Covilhã
        "Linha do Oeste" to listOf("94-63008", "94-62471", "94-63560"),           // Caldas da Rainha, Torres Vedras, Leiria
        "Ramal de Tomar" to listOf("94-40154"),                                   // Tomar
        "Linha do Leste" to listOf("94-57000", "94-57497"),                       // Portalegre, Elvas
        "Linha do Sado" to listOf("94-91058"),                                    // Praias do Sado-A
        "Linha do Alentejo" to listOf("94-75002"),                                // Beja
        "Linha de Évora" to listOf("94-83006"),                                   // Évora
        "Linha do Sul" to listOf("94-92577", "94-93005"),                         // Grândola, Ermidas-Sado
        "Linha do Algarve" to listOf("94-90464", "94-73007", "94-73569")          // Lagos, Faro, Vila Real de Santo António
    )

    /**
     * As linhas com nome e as estações de cada uma. [byNumber]: o número da CP → os códigos das estações dessa linha;
     * [stations]: as estações conhecidas (para os nomes e as coordenadas das pontas).
     */
    fun name(byNumber: Map<String, List<String>>, stations: Map<String, CpStationInfo>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        for ((_, codes) in byNumber) {
            val known = codes.filter { it in stations }.distinct()
            if (known.size < MIN_STATIONS) continue
            val name = nameOf(known, stations)
            out.getOrPut(name) { LinkedHashSet() } += known
        }
        return out.mapValues { it.value.toList() }
    }

    /** O nome de uma linha pelas estações dela. */
    fun nameOf(codes: List<String>, stations: Map<String, CpStationInfo>): String {
        val set = codes.toSet()
        val best = ANCHORS
            .map { (name, anchors) -> name to anchors.count { it in set } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
        if (best != null) return best.first
        // Sem estação conhecida: as duas mais afastadas
        val pts = codes.mapNotNull { stations[it] }
        var a = pts.firstOrNull() ?: return "Linha"
        var b = a
        var far = -1.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val d = metres(pts[i], pts[j])
            if (d > far) { far = d; a = pts[i]; b = pts[j] }
        }
        // Do norte para o sul, para o nome ser sempre o mesmo
        val (first, second) = if (a.lat >= b.lat) a to b else b to a
        return "${first.name} – ${second.name}"
    }

    private fun metres(a: CpStationInfo, b: CpStationInfo): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = (a.lat - b.lat) * 111_320.0
        val dx = (a.lon - b.lon) * 111_320.0 * k
        return sqrt(dx * dx + dy * dy)
    }
}
