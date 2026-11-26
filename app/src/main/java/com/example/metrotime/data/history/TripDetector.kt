package com.example.metrotime.data.history

import com.example.metrotime.data.MetroNetwork

/**
 * Deteta viagens de metro pela localização, sem Android (testado em app/src/test).
 *
 * No metro não há GPS: o telemóvel "desaparece" perto da estação A e volta a aparecer perto da estação B
 * algum tempo depois. Se esse tempo bater com o que o metro leva de A a B (pela rede, com mudanças de
 * linha) e não houve leituras pelo caminho à superfície, foi uma viagem de metro.
 *
 * Nalgumas estações o telemóvel apanha uma localização (pelas redes Wi-Fi) mesmo dentro do comboio. Por isso
 * aparecer numa estação não acaba a viagem: ficas "em viagem", e as estações seguintes, se baterem com o andar
 * do metro, são de passagem. Ficar parado numa estação também não (o metro pode lá ficar uns minutos, ou estás
 * a mudar de linha). A viagem só acaba quando a localização sai da estação: aparece cá fora, longe das estações.
 * Por segurança, acaba também se ficares meia hora na mesma estação (moras ao lado?) ou se passar uma hora sem
 * notícias; em todos os casos, na última estação onde se apareceu e à hora a que lá se chegou.
 * - Mais rápido do que se faria a pé: viagem certa, conta logo.
 * - Podia ter sido a pé (estações perto, ou tempo que dava para ir a pé): fica à espera de confirmação.
 * - Leituras à superfície pelo caminho (autocarro, carro, a pé): não é viagem de metro.
 */
object TripDetector {

    /** Leitura de localização (a cada ~30 s com o registo de viagens ligado). */
    data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

    data class MetroStop(val apiId: String, val lat: Double, val lon: Double)

    /** O que o detetor sabe entre leituras (guardado para sobreviver a reinícios do serviço). */
    data class State(
        /** Estação ao pé da qual estávamos (a origem de uma possível viagem). */
        val anchorId: String? = null,
        /** Última vez que estivemos ao pé dela: a hora de partida se a viagem se confirmar. */
        val anchorLastMs: Long = 0,
        val lastGoodFixMs: Long = 0,
        /** Leituras boas longe da estação desde que saímos dela: andámos à superfície. */
        val surfaceFixes: Int = 0,
        /** Viagem começada à mão (Iniciar viagem) nesta estação, à espera do destino. */
        val manualTripId: String? = null,
        /** Em viagem: a estação de onde se partiu e a hora da partida. */
        val rideFromId: String? = null,
        val rideStartMs: Long = 0,
        /** Em viagem: a última estação onde se apareceu; quando se apareceu lá pela primeira e pela última vez. */
        val rideLastId: String? = null,
        val rideLastFirstMs: Long = 0,
        val rideLastMs: Long = 0,
        /** Em viagem: leituras seguidas cá fora, longe das estações (à segunda, saíste). */
        val rideOutside: Int = 0
    ) {
        val riding: Boolean get() = rideFromId != null && rideLastId != null
    }

    enum class Kind { STRONG, WEAK, NONE }

    /** Viagem detetada de [from] para [to]; [strong] = conta logo, senão pede confirmação. */
    data class Detection(val from: String, val to: String, val startMs: Long, val endMs: Long, val strong: Boolean, val manualTripId: String?)

    /** Leituras com mais erro do que isto não contam (no subterrâneo vêm da rede, com centenas de metros). */
    const val GOOD_ACCURACY_M = 100f
    /** "Ao pé da estação". */
    const val NEAR_M = 200.0
    /** Ao voltar a aparecer depois de um tempo sem sinal, a estação de saída pode estar um pouco mais longe. */
    const val REAPPEAR_M = 600.0
    /** Sem leituras boas durante isto: estivemos sem sinal (no metro). */
    const val GAP_MS = 4 * 60 * 1000L
    /** Ao fim disto ao pé de nada, a estação de origem já não conta. */
    const val ANCHOR_TTL_MS = 3 * 60 * 60 * 1000L
    /**
     * Em viagem, tanto tempo na mesma estação: já não estás no metro (nenhum fica parado tanto tempo). Ficar uns
     * minutos não conta como saída: o metro pode lá estar retido, ou estás a mudar de linha.
     */
    const val STATION_TTL_MS = 30 * 60 * 1000L
    /** Em viagem sem aparecer em estação nenhuma durante isto: a viagem acabou na última onde se apareceu. */
    const val RIDE_TTL_MS = 60 * 60 * 1000L
    /** Leituras seguidas cá fora para dar a viagem por acabada (uma só pode ser um salto da localização). */
    const val OUTSIDE_FIXES = 2
    private const val WALK_MPS = 1.4

    /** Uma leitura: o estado seguinte e as viagens que ficaram acabadas com ela (quase sempre nenhuma ou uma). */
    fun step(state: State, fix: Fix?, stops: List<MetroStop>): Pair<State, List<Detection>> {
        if (fix == null || fix.accuracyM > GOOD_ACCURACY_M) return state to emptyList()
        val byId = stops.associateBy { it.apiId }
        if (state.riding) {
            // Muito tempo sem nada de novo: a viagem acabou onde se apareceu por último; a leitura conta de novo
            if (fix.timeMs - state.rideLastMs > RIDE_TTL_MS) {
                val (closed, done) = close(state, byId, state.rideLastFirstMs)
                val (next, more) = idle(closed, fix, stops, byId)
                return next to listOfNotNull(done) + more
            }
            return ride(state, fix, stops, byId)
        }
        return idle(state, fix, stops, byId)
    }

    /**
     * Sem leitura boa (no subterrâneo, ou dentro de um prédio): uma viagem sem notícias há uma hora acaba na última
     * estação onde se apareceu, para não ficar à espera da próxima localização.
     */
    fun tick(state: State, nowMs: Long, stops: List<MetroStop>): Pair<State, List<Detection>> {
        if (!state.riding || nowMs - state.rideLastMs <= RIDE_TTL_MS) return state to emptyList()
        val (closed, done) = close(state, stops.associateBy { it.apiId }, state.rideLastFirstMs)
        return closed to listOfNotNull(done)
    }

    /** Fora de viagem: ao pé de uma estação, a caminho dela, ou numa estação nova (começa uma viagem). */
    private fun idle(state: State, fix: Fix, stops: List<MetroStop>, byId: Map<String, MetroStop>): Pair<State, List<Detection>> {
        val now = fix.timeMs
        // A origem caduca se passou muito tempo
        val anchor = state.anchorId?.takeIf { now - state.anchorLastMs <= ANCHOR_TTL_MS }?.let { byId[it] }
        val gap = state.lastGoodFixMs > 0 && now - state.lastGoodFixMs >= GAP_MS
        val near = near(stops, fix, anchor)

        // Ao voltar a aparecer depois de um tempo sem sinal, já cá fora e um pouco longe da estação: saíste nela
        if (anchor != null && near == null && gap && metres(fix, anchor) > REAPPEAR_M) {
            val out = nearest(stops, fix, REAPPEAR_M)?.takeIf { it.apiId != anchor.apiId }
            if (out != null) {
                val kind = classify(anchor, out, now - state.anchorLastMs, state.surfaceFixes)
                val detection = if (kind == Kind.NONE) null else
                    Detection(anchor.apiId, out.apiId, state.anchorLastMs, now, kind == Kind.STRONG, state.manualTripId)
                return State(out.apiId, now, now, 1, null) to listOfNotNull(detection)
            }
        }

        if (anchor != null && near != null && near.apiId != anchor.apiId) {
            val kind = classify(anchor, near, now - state.anchorLastMs, state.surfaceFixes)
            return when {
                // Não bate com o metro: a origem passa a ser esta
                kind == Kind.NONE -> State(near.apiId, now, now, 0, null) to emptyList()
                // Com leituras cá fora pelo caminho (foste a pé?): conta até aqui e pergunta; a próxima parte daqui
                state.surfaceFixes > 0 -> State(near.apiId, now, now, 0, null) to listOf(
                    Detection(anchor.apiId, near.apiId, state.anchorLastMs, now, kind == Kind.STRONG, state.manualTripId)
                )
                // Bate com o metro e não se viu ninguém cá fora: em viagem (esta pode ser só de passagem)
                else -> state.copy(
                    lastGoodFixMs = now,
                    rideFromId = anchor.apiId, rideStartMs = state.anchorLastMs,
                    rideLastId = near.apiId, rideLastFirstMs = now, rideLastMs = now, rideOutside = 0
                ) to emptyList()
            }
        }

        val next = when {
            near != null && anchor != null && near.apiId == anchor.apiId ->
                state.copy(anchorLastMs = now, lastGoodFixMs = now, surfaceFixes = 0)
            near != null -> State(near.apiId, now, now, 0, null)
            anchor != null -> state.copy(
                lastGoodFixMs = now,
                surfaceFixes = state.surfaceFixes + if (metres(fix, anchor) > NEAR_M) 1 else 0
            )
            else -> State(lastGoodFixMs = now)
        }
        return next to emptyList()
    }

    /** Em viagem: a mesma estação (talvez saíste), outra estação (de passagem) ou cá fora (saíste). */
    private fun ride(state: State, fix: Fix, stops: List<MetroStop>, byId: Map<String, MetroStop>): Pair<State, List<Detection>> {
        val now = fix.timeMs
        val from = byId[state.rideFromId]
        val last = byId[state.rideLastId]
        if (from == null || last == null) return idle(State(lastGoodFixMs = state.lastGoodFixMs), fix, stops, byId)
        val near = near(stops, fix, last)

        // Ainda na mesma estação: o metro pode estar lá parado, ou estás a mudar de linha; continua em viagem.
        // Só ao fim de meia hora (moras ao lado da estação?) é que a viagem acaba aqui.
        if (near != null && near.apiId == last.apiId) {
            val here = state.copy(rideLastMs = now, lastGoodFixMs = now, rideOutside = 0)
            if (now - state.rideLastFirstMs < STATION_TTL_MS) return here to emptyList()
            val (closed, done) = close(here, byId, state.rideLastFirstMs)
            return closed to listOfNotNull(done)
        }

        // Outra estação: se o metro lá chegava (desde a última e desde a origem), é de passagem
        if (near != null) {
            val passing = near.apiId != from.apiId &&
                classify(last, near, now - state.rideLastMs, state.rideOutside) != Kind.NONE &&
                classify(from, near, now - state.rideStartMs, state.surfaceFixes) != Kind.NONE
            if (passing) {
                return state.copy(
                    rideLastId = near.apiId, rideLastFirstMs = now, rideLastMs = now, lastGoodFixMs = now, rideOutside = 0
                ) to emptyList()
            }
            // Não bate (ou voltou-se à origem): a viagem acabou na última; esta leitura conta a partir de lá
            val (closed, done) = close(state, byId, state.rideLastFirstMs)
            val (next, more) = idle(closed, fix, stops, byId)
            return next to listOfNotNull(done) + more
        }

        // Cá fora. Depois de muito tempo sem sinal, perto de uma estação mais à frente: saíste nela
        if (now - state.rideLastMs >= GAP_MS) {
            val exit = nearest(stops, fix, REAPPEAR_M)?.takeIf {
                it.apiId != last.apiId && it.apiId != from.apiId &&
                    classify(last, it, now - state.rideLastMs, 0) != Kind.NONE &&
                    classify(from, it, now - state.rideStartMs, state.surfaceFixes) != Kind.NONE
            }
            if (exit != null) {
                val (closed, done) = close(state.copy(rideLastId = exit.apiId, rideLastFirstMs = now, rideLastMs = now), byId, now)
                return closed.copy(surfaceFixes = 1, lastGoodFixMs = now) to listOfNotNull(done)
            }
        }
        // À segunda leitura seguida cá fora, saíste na última estação (uma só pode ser um salto da localização)
        val outside = state.rideOutside + 1
        if (outside < OUTSIDE_FIXES) return state.copy(rideOutside = outside, lastGoodFixMs = now) to emptyList()
        val (closed, done) = close(state, byId, state.rideLastFirstMs)
        return closed.copy(surfaceFixes = outside, lastGoodFixMs = now) to listOfNotNull(done)
    }

    /**
     * Acaba a viagem em curso na última estação onde se apareceu ([endMs]: quando lá se chegou); a próxima parte
     * dali. Sem viagem em curso, fica tudo igual.
     */
    private fun close(state: State, byId: Map<String, MetroStop>, endMs: Long): Pair<State, Detection?> {
        if (!state.riding) return state to null
        val from = byId[state.rideFromId]
        val to = byId[state.rideLastId]
        // As leituras cá fora desde a última estação contam como caminho à superfície a partir dela
        val after = State(state.rideLastId, state.rideLastMs, maxOf(state.lastGoodFixMs, state.rideLastMs), state.rideOutside, null)
        if (from == null || to == null || from.apiId == to.apiId) return after to null
        val kind = classify(from, to, endMs - state.rideStartMs, state.surfaceFixes)
        val detection = if (kind == Kind.NONE) null else
            Detection(from.apiId, to.apiId, state.rideStartMs, endMs, kind == Kind.STRONG, state.manualTripId)
        return after to detection
    }

    /** Acaba já a viagem em curso (ex.: começaste outra à mão): a viagem até à última estação onde se apareceu. */
    fun endRide(state: State, stops: List<MetroStop>): Pair<State, Detection?> =
        close(state, stops.associateBy { it.apiId }, state.rideLastFirstMs)

    /** Começar à mão em [apiId] (Iniciar viagem): a viagem acaba onde saíres e fica com esse destino. */
    fun startManual(state: State, apiId: String, nowMs: Long, tripId: String): State =
        State(apiId, nowMs, maxOf(state.lastGoodFixMs, nowMs), 0, tripId)

    /**
     * Classifica uma ida de [from] a [to] em [dtMs]: tem de bater com o tempo do metro (de 60% menos 1 min
     * até ao dobro mais 15 min) e não pode ter havido leituras à superfície pelo caminho.
     */
    fun classify(from: MetroStop, to: MetroStop, dtMs: Long, surfaceFixes: Int): Kind {
        if (surfaceFixes >= 3) return Kind.NONE
        val route = MetroNetwork.route(from.apiId, to.apiId) ?: return Kind.NONE
        val minutes = dtMs / 60_000.0
        if (minutes < route.minutes * 0.6 - 1 || minutes > route.minutes * 2 + 15) return Kind.NONE
        val straight = metres(from.lat, from.lon, to.lat, to.lon)
        val walkMinutes = straight / WALK_MPS / 60
        // Sem nenhuma leitura pelo caminho e mais depressa do que a pé: só pode ter sido de metro
        return if (surfaceFixes == 0 && straight >= 600 && minutes < walkMinutes * 0.8) Kind.STRONG else Kind.WEAK
    }

    /**
     * A estação ao pé da leitura. Se a leitura ainda está ao pé de [current], fica essa: nas estações perto umas
     * das outras (Baixa-Chiado, Rossio, Restauradores) um salto da localização não muda de estação.
     */
    private fun near(stops: List<MetroStop>, fix: Fix, current: MetroStop?): MetroStop? =
        current?.takeIf { metres(fix, it) <= NEAR_M } ?: nearest(stops, fix, NEAR_M)

    private fun nearest(stops: List<MetroStop>, fix: Fix, maxM: Double): MetroStop? =
        stops.map { it to metres(fix, it) }
            .filter { it.second <= maxM }
            .minByOrNull { it.second }?.first

    private fun metres(fix: Fix, stop: MetroStop) = metres(fix.lat, fix.lon, stop.lat, stop.lon)

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        MetroNetwork.haversineKm(lat1, lon1, lat2, lon2) * 1000
}
