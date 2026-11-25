package com.example.metrotime.work

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * O que o serviço em segundo plano ([WidgetUpdateService]) está a ver agora, para o ecrã de debug (admin). Só em
 * memória: o serviço corre no mesmo processo da app. Se não correu desde que a app abriu, fica tudo a null.
 */
object LiveDebug {

    /** Uma volta do ciclo do serviço (a cada 5 s). */
    data class Loop(val atMs: Long, val loops: Int, val screenOn: Boolean, val sharing: Boolean, val tracking: Boolean)

    /** A última leitura de localização (a cada 30 s com a partilha ou o registo de viagens ligados). */
    data class Fix(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Float, val speedKmh: Double?)

    /** À procura de comboio: o que deu a última verificação. */
    data class Search(
        val atMs: Long,
        /** Média das últimas 3 leituras. */
        val speedKmh: Double,
        /** Distância à linha de comboio mais perto (null se não se mediu). */
        val railM: Double? = null,
        /** Comboios a menos de 15 km. */
        val nearbyTrains: Int? = null,
        val candidateId: String? = null,
        /** Verificações seguidas em que o mesmo comboio bateu (entra-se ao chegar a TrainMatcher.LOCK_CHECKS). */
        val hits: Int = 0,
        /** Distância ao comboio candidato, pelo traçado. */
        val gapM: Double? = null,
        val verdict: String
    )

    /** A bordo: o comboio e o que o TrainRide sabe da saída. */
    data class OnBoard(
        val atMs: Long,
        val id: String,
        val name: String?,
        val dest: String?,
        val service: String?,
        val boarding: String?,
        val startMs: Long,
        val pathKm: Double,
        val topKmh: Double?,
        val stops: Int,
        val hasTrack: Boolean,
        /** Distância à linha deste comboio. */
        val railM: Double?,
        /** Distância ao comboio, pelo traçado (null sem posição dele). */
        val gapM: Double?,
        /** Idade da posição do comboio. */
        val trainAgeSec: Double?,
        val lastStation: String?,
        val awayFixes: Int,
        val stationOffRailFixes: Int,
        val apartChecks: Int
    )

    val loop = MutableStateFlow<Loop?>(null)
    val fix = MutableStateFlow<Fix?>(null)
    val search = MutableStateFlow<Search?>(null)
    val onBoard = MutableStateFlow<OnBoard?>(null)
}
