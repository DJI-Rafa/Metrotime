package com.example.metrotime.data.caderneta

/**
 * O azulejo de cada estação: um desenho (6), uma rotação (4), um canto (4), um centro (3) e com ou sem moldura (2).
 * São 576 combinações; cada estação de uma rede (metro ou CP) recebe uma diferente, sempre a mesma.
 */
object Azulejos {

    /** Os desenhos, num quadrado de 60 (o mesmo do Claude Design). */
    val MOTIFS: List<String> = listOf(
        // Flor de quatro pétalas
        "M30,30 C24,22 24,12 30,8 C36,12 36,22 30,30 Z M30,30 C38,24 48,24 52,30 C48,36 38,36 30,30 Z M30,30 C36,38 36,48 30,52 C24,48 24,38 30,30 Z M30,30 C22,36 12,36 8,30 C12,24 22,24 30,30 Z M34,30 A4,4 0 1 1 26,30 A4,4 0 1 1 34,30 Z",
        // Estrela de oito pontas
        "M30,8 L33.8,20.8 L45.6,14.4 L39.2,26.2 L52,30 L39.2,33.8 L45.6,45.6 L33.8,39.2 L30,52 L26.2,39.2 L14.4,45.6 L20.8,33.8 L8,30 L20.8,26.2 L14.4,14.4 L26.2,20.8 Z M30,25 L33.5,26.5 L35,30 L33.5,33.5 L30,35 L26.5,33.5 L25,30 L26.5,26.5 Z",
        // Losangos
        "M30,8 L52,30 L30,52 L8,30 Z M30,18 L42,30 L30,42 L18,30 Z M30,26 L34,30 L30,34 L26,30 Z",
        // Ondas
        "M8,22 q5.5,-6 11,0 t11,0 t11,0 t11,0 M8,32 q5.5,-6 11,0 t11,0 t11,0 t11,0 M8,42 q5.5,-6 11,0 t11,0 t11,0 t11,0",
        // Rosácea
        "M36,30 A6,6 0 1 1 24,30 A6,6 0 1 1 36,30 Z M52,30 A5,5 0 1 1 42,30 A5,5 0 1 1 52,30 Z M47,42 A5,5 0 1 1 37,42 A5,5 0 1 1 47,42 Z M35,47 A5,5 0 1 1 25,47 A5,5 0 1 1 35,47 Z M23,42 A5,5 0 1 1 13,42 A5,5 0 1 1 23,42 Z M18,30 A5,5 0 1 1 8,30 A5,5 0 1 1 18,30 Z M23,18 A5,5 0 1 1 13,18 A5,5 0 1 1 23,18 Z M35,13 A5,5 0 1 1 25,13 A5,5 0 1 1 35,13 Z M47,18 A5,5 0 1 1 37,18 A5,5 0 1 1 47,18 Z",
        // Quadrifólio
        "M39,19 A9,9 0 1 1 21,19 A9,9 0 1 1 39,19 Z M50,30 A9,9 0 1 1 32,30 A9,9 0 1 1 50,30 Z M39,41 A9,9 0 1 1 21,41 A9,9 0 1 1 39,41 Z M28,30 A9,9 0 1 1 10,30 A9,9 0 1 1 28,30 Z M30,24 L36,30 L30,36 L24,30 Z"
    )

    const val COMBINATIONS = 576

    data class Pattern(val motif: Int, val rotation: Float, val corners: Int, val center: Int, val frame: Boolean)

    /** A combinação número [n] (0..575). */
    fun of(n: Int): Pattern {
        val k = Math.floorMod(n, COMBINATIONS)
        return Pattern(
            motif = k % 6,
            rotation = ((k / 6) % 4) * 45f,
            corners = (k / 24) % 4,
            center = (k / 96) % 3,
            frame = (k / 288) % 2 == 1
        )
    }

    /**
     * Um padrão diferente para cada estação de [keys] (até 576), sempre o mesmo para a mesma lista. Os vizinhos na lista
     * ficam com padrões bem diferentes (a combinação salta 7919 de cada vez).
     */
    fun assign(keys: Collection<String>): Map<String, Pattern> =
        keys.distinct().sorted().mapIndexed { i, key -> key to of(((i.toLong() * 7919L) % COMBINATIONS).toInt()) }.toMap()
}
