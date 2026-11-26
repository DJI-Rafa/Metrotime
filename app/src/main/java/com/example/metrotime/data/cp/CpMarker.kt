package com.example.metrotime.data.cp

/**
 * Onde pôr no mapa um comboio que não está aberto.
 *
 * O GPS do feed chega atrasado (30 s a 1 min, às vezes mais) e a última estação da CP chega antes dele. O comboio
 * aberto já usava a estação ([CpTrainNow]); os outros ficavam no GPS e, ao tocar, o comboio saltava para a frente
 * (ia "a chegar" a Santa Apolónia no mapa e, aberto, já lá tinha chegado). A regra passa a ser a mesma para todos:
 * nunca atrás da última estação da CP.
 */
object CpMarker {

    /** Quanto o GPS pode ficar atrás da estação sem contar (ruído do GPS e o tamanho da estação). */
    const val BEHIND_MARGIN_M = 250.0

    /** Mais longe do traçado do que isto, o traçado não serve (outro percurso): compara-se pelo destino. */
    private const val OFF_TRACK_M = 1_000.0

    /** [atStation]: está parado na estação (sem seta de direção). */
    data class Place(val point: Pair<Double, Double>, val atStation: Boolean)

    /**
     * @param gps posição do feed.
     * @param lastStation coordenadas da última estação da CP (null se não se souber).
     * @param lastIsDestination a última estação é o destino: já chegou.
     * @param destination coordenadas do destino, para saber o sentido.
     * @param track o traçado do comboio (GTFS), quando há.
     */
    fun place(
        gps: Pair<Double, Double>,
        status: String?,
        lastStation: Pair<Double, Double>?,
        lastIsDestination: Boolean,
        destination: Pair<Double, Double>?,
        track: CpTrack?
    ): Place {
        if (lastStation == null) return Place(gps, false)
        // Chegou ao fim ou a CP diz que está parado nela
        if (lastIsDestination || status == VehicleStatus.AT_STATION || status == VehicleStatus.COMPLETED) {
            return Place(lastStation, true)
        }
        if (destination == null) return Place(gps, false)
        return if (isBehind(gps, lastStation, destination, track)) Place(lastStation, false) else Place(gps, false)
    }

    /** O GPS ainda está antes da última estação, no sentido em que o comboio vai. */
    internal fun isBehind(
        gps: Pair<Double, Double>,
        lastStation: Pair<Double, Double>,
        destination: Pair<Double, Double>,
        track: CpTrack?
    ): Boolean {
        if (track != null && track.line.size >= 2) {
            val g = track.locate(gps).s
            val l = track.locate(lastStation).s
            val d = track.locate(destination).s
            val near = listOf(gps to g, lastStation to l).all { (p, s) -> CpTrack.metres(p, track.pointAt(s)) <= OFF_TRACK_M }
            if (near && d != l) {
                return if (d > l) g < l - BEHIND_MARGIN_M else g > l + BEHIND_MARGIN_M
            }
        }
        // Sem traçado: mais longe do destino do que a estação por onde já passou
        return CpTrack.metres(gps, destination) > CpTrack.metres(lastStation, destination) + BEHIND_MARGIN_M
    }
}
