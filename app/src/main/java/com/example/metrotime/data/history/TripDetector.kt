package com.example.metrotime.data.history

import com.example.metrotime.data.MetroNetwork

/**
 * Deteta viagens de metro pela localização, sem Android (testado em app/src/test).
 *
 * No metro não há GPS: o telemóvel "desaparece" perto da estação A e volta a aparecer perto da estação B
 * algum tempo depois. Se esse tempo bater com o que o metro leva de A a B (pela rede, com mudanças de
 * linha) e não houve leituras pelo caminho à superfície, foi uma viagem de metro.
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
        val manualTripId: String? = null
    )

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
    private const val WALK_MPS = 1.4

    fun step(state: State, fix: Fix?, stops: List<MetroStop>): Pair<State, Detection?> {
        if (fix == null || fix.accuracyM > GOOD_ACCURACY_M) return state to null
        val now = fix.timeMs
        val byId = stops.associateBy { it.apiId }
        // A origem caduca se passou muito tempo
        val anchor = state.anchorId?.takeIf { now - state.anchorLastMs <= ANCHOR_TTL_MS }?.let { byId[it] }
        val gap = state.lastGoodFixMs > 0 && now - state.lastGoodFixMs >= GAP_MS
        val near = nearest(stops, fix, NEAR_M)

        // Uma estação diferente da origem: ao pé dela, ou a primeira leitura depois de um tempo sem sinal
        val candidate = when {
            anchor == null -> null
            near != null && near.apiId != anchor.apiId -> near
            near == null && gap && metres(fix.lat, fix.lon, anchor.lat, anchor.lon) > REAPPEAR_M ->
                nearest(stops, fix, REAPPEAR_M)?.takeIf { it.apiId != anchor.apiId }
            else -> null
        }

        if (anchor != null && candidate != null) {
            val dt = now - state.anchorLastMs
            val kind = classify(anchor, candidate, dt, state.surfaceFixes)
            val detection = if (kind == Kind.NONE) null else Detection(
                anchor.apiId, candidate.apiId, state.anchorLastMs, now, kind == Kind.STRONG, state.manualTripId
            )
            // A estação onde chegámos passa a ser a origem da próxima
            return State(candidate.apiId, now, now, 0, null) to detection
        }

        val next = when {
            near != null && anchor != null && near.apiId == anchor.apiId ->
                state.copy(anchorLastMs = now, lastGoodFixMs = now, surfaceFixes = 0)
            near != null -> State(near.apiId, now, now, 0, null)
            anchor != null -> state.copy(
                lastGoodFixMs = now,
                surfaceFixes = state.surfaceFixes + if (metres(fix.lat, fix.lon, anchor.lat, anchor.lon) > NEAR_M) 1 else 0
            )
            else -> State(lastGoodFixMs = now)
        }
        return next to null
    }

    /** Começar à mão em [apiId] (Iniciar viagem): a próxima estação detetada fecha esta viagem. */
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

    private fun nearest(stops: List<MetroStop>, fix: Fix, maxM: Double): MetroStop? =
        stops.map { it to metres(fix.lat, fix.lon, it.lat, it.lon) }
            .filter { it.second <= maxM }
            .minByOrNull { it.second }?.first

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        MetroNetwork.haversineKm(lat1, lon1, lat2, lon2) * 1000
}
