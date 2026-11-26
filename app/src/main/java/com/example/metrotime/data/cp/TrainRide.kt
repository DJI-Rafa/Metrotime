package com.example.metrotime.data.cp

/**
 * Quando saíste do comboio em que vais. Sem Android (testado em app/src/test).
 *
 * Decide-se por onde TU estás, e não pela posição do comboio (chega atrasada, às vezes minutos, e a viagem
 * acabava a meio): só se sai de um comboio numa estação. Em cima da linha (a andar, ou parado num sinal) ou
 * numa estação, continuas no comboio. Sais quando sais da estação para longe da linha, e a viagem acaba na
 * última estação onde estiveste com o comboio, à hora a que lá chegaste (não onde e quando a app deu por isso).
 *
 * Os casos em que só isso não chega:
 * - ficas na estação (à espera de outro comboio, ou moras ao lado): sais quando o teu comboio, com uma posição
 *   fresca, já vai longe de ti, ou ao fim de 3 min na estação longe da linha (no átrio, no café, cá fora);
 * - mudas para outro comboio na mesma linha: o teu afasta-se de ti, a mesma regra;
 * - o comboio chegou ao fim do percurso: ao deixares essa estação, saíste lá.
 */
object TrainRide {

    /** Leituras com mais erro do que isto não contam (nas estações cobertas e nos túneis o GPS salta). */
    const val GOOD_ACCURACY_M = 75f
    /** "Em cima da linha": as posições dos comboios ficam a menos de 20 m do traçado; o resto é o erro do GPS. */
    const val ON_RAIL_M = 100.0
    /** "Na estação": até esta distância dela (os cais das estações grandes têm 300 m). */
    const val STATION_M = 400.0
    /** Leituras seguidas fora da estação e longe da linha para dizer que saíste. */
    const val AWAY_FIXES = 2
    /** Leituras seguidas na estação mas longe da linha: ao fim de 3 min saíste do comboio. */
    const val STATION_OFF_RAIL_FIXES = 6
    /** O teu comboio, com posição fresca, a mais disto de ti (ou num sentido ou linha que não são os teus)... */
    const val APART_M = 8_000.0
    /** ...em tantas verificações seguidas (de 30 s): ficaste para trás, ou vais noutro comboio. */
    const val APART_CHECKS = 4
    /** Uma posição do comboio mais velha do que isto não conta para dizer que ele vai longe. */
    const val FRESH_SEC = 120.0

    /** Estação onde estiveste com o comboio e quando lá chegaste. */
    data class StationVisit(val stop: TrainMatcher.Stop, val arrivedMs: Long)

    data class State(
        /** A última estação onde estiveste com o comboio: a de saída, se saíres. */
        val lastStation: StationVisit? = null,
        /** Último ponto (e hora) em cima da linha: para a saída, se nunca se viu uma estação. */
        val lastOnRail: Pair<Double, Double>? = null,
        val lastOnRailMs: Long = 0,
        val awayFixes: Int = 0,
        val stationOffRailFixes: Int = 0,
        val apartChecks: Int = 0
    )

    /** Uma leitura tua: onde, com que erro (metros) e quando. */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long)

    /** Saíste em [stop] (null se não se sabe a estação) às [endMs]. [reason] é para o registo de depuração. */
    data class Exit(val stop: TrainMatcher.Stop?, val endMs: Long, val reason: String)

    /**
     * Uma verificação a bordo (de 30 s em 30 s).
     * @param stops paragens do comboio (sem elas, as estações à volta)
     * @param finalStop a última paragem do comboio (null se não se sabe)
     * @param railM distância à linha do comboio (sem o traçado dele, à rede)
     * @param trainGap distância ao troço onde o comboio pode estar agora ([TrainMatcher.gap]); null se ele não
     *   pode ser o teu (sentido contrário, outra linha)
     * @param trainAgeSec idade da posição do comboio; null sem posição
     */
    fun step(
        state: State,
        fix: Fix,
        stops: List<TrainMatcher.Stop>,
        finalStop: TrainMatcher.Stop?,
        railM: Double,
        trainGap: Double?,
        trainAgeSec: Double?
    ): Pair<State, Exit?> {
        // Leitura má (estação coberta, túnel): não se decide nada
        if (fix.accuracyM > GOOD_ACCURACY_M) return state to null
        val here = fix.lat to fix.lon
        var s = state

        // A estação onde estás (só as paragens do comboio: é aí que ele te pode deixar)
        val station = TrainMatcher.nearestStop(stops, here, STATION_M)
        if (station != null && s.lastStation?.stop?.code != station.code) {
            s = s.copy(lastStation = StationVisit(station, fix.timeMs))
        }
        val onRail = railM <= ON_RAIL_M
        if (onRail) s = s.copy(lastOnRail = here, lastOnRailMs = fix.timeMs)

        // O comboio chegou ao fim do percurso e tu deixaste essa estação: saíste lá
        val last = s.lastStation
        if (last != null && finalStop != null && last.stop.code == finalStop.code && station?.code != finalStop.code) {
            return State() to Exit(last.stop, last.arrivedMs, "o comboio acabou o percurso")
        }

        // O teu comboio, com uma posição fresca, vai longe de ti (ou não pode ser o teu)
        if (trainAgeSec != null && trainAgeSec <= FRESH_SEC) {
            val apart = trainGap == null || trainGap > APART_M
            s = s.copy(apartChecks = if (apart) s.apartChecks + 1 else 0)
            if (s.apartChecks >= APART_CHECKS) return State() to exit(s, stops, fix, station, "o comboio vai longe de ti")
        }

        // Fora da estação e longe da linha: saíste
        s = s.copy(awayFixes = if (station == null && !onRail) s.awayFixes + 1 else 0)
        if (s.awayFixes >= AWAY_FIXES) return State() to exit(s, stops, fix, null, "saíste da estação")

        // Na estação mas longe da linha há 3 min: já não estás no comboio
        s = s.copy(stationOffRailFixes = if (station != null && !onRail) s.stationOffRailFixes + 1 else 0)
        if (s.stationOffRailFixes >= STATION_OFF_RAIL_FIXES) return State() to exit(s, stops, fix, station, "ficaste na estação")

        return s to null
    }

    /** A saída: a última estação onde estiveste com o comboio, à hora a que lá chegaste. */
    private fun exit(s: State, stops: List<TrainMatcher.Stop>, fix: Fix, station: TrainMatcher.Stop?, reason: String): Exit {
        s.lastStation?.let { return Exit(it.stop, it.arrivedMs, reason) }
        station?.let { return Exit(it, fix.timeMs, reason) }
        val stop = s.lastOnRail?.let { TrainMatcher.nearestStop(stops, it) }
        return Exit(stop, s.lastOnRailMs.takeIf { it > 0 } ?: fix.timeMs, reason)
    }
}
