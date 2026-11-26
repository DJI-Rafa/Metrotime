package com.example.metrotime.data.cp

import kotlin.math.cos
import kotlin.math.sin

/**
 * Em que comboio vais: compara o teu GPS com as posições dos comboios da CP. Sem dependências Android
 * (testado em app/src/test). O serviço (WidgetUpdateService) lê as posições e decide quando entras e sais.
 *
 * As posições dos comboios chegam com 1–2 min de atraso. Em vez de alargar a tolerância com a idade
 * (antes chegava a 8 km, e um carro numa autoestrada ao lado da linha "ia no comboio"), o comboio só pode
 * estar entre onde foi visto e onde estaria agora à tua velocidade: tens de estar nesse troço.
 */
object TrainMatcher {

    data class Train(val id: String, val lat: Double, val lon: Double, val ageSec: Double, val bearing: Double?)
    data class User(val lat: Double, val lon: Double, val speedKmh: Double, val bearing: Double?)
    /** Paragem do comboio; [departureMs] = quando parte (ou partiu) de lá, se se sabe. */
    data class Stop(val code: String, val name: String, val lat: Double, val lon: Double, val departureMs: Long? = null)

    /** Abaixo disto não se procura comboio (a pé, de bicicleta, parado). */
    const val MIN_SPEED_KMH = 30.0
    /** Para começar a seguir um comboio tens de estar a menos disto de uma linha de comboio. */
    const val MAX_RAIL_DISTANCE_M = 150.0
    /** Ao traçado do próprio comboio, quando se conhece. */
    const val MAX_TRACK_OFFSET_M = 200.0
    /** Verificações seguidas (de 30 s) com o mesmo comboio antes de dizer que vais nele. */
    const val LOCK_CHECKS = 3
    /** Até onde pode estar a estação de embarque/saída, se for uma paragem do comboio. */
    const val STOP_MAX_M = 1_500.0

    private const val MAX_AGE_SEC = 180.0

    /**
     * Onde embarcaste quando só se deu pelo comboio já a meio do percurso (as posições chegam atrasadas e
     * ele pode só bater muito depois de partir): a paragem dele ao pé de onde acabou a tua viagem anterior
     * ([previousEnd], às [previousEndMs]), se ficar antes de onde estás agora ([here]) e, quando se sabe a
     * hora, se o comboio partiu de lá depois de lá chegares. null se não houver uma assim.
     */
    fun boardingFromPrevious(
        stops: List<Stop>,
        track: CpTrack?,
        previousEnd: Pair<Double, Double>,
        previousEndMs: Long,
        here: Pair<Double, Double>,
        nowMs: Long,
        maxM: Double = 600.0
    ): Stop? {
        val stop = nearestStop(stops, previousEnd, maxM) ?: return null
        // Tinhas de lá estar antes de o comboio partir (com 2 min de folga), e ele já partiu
        stop.departureMs?.let { if (it < previousEndMs - 2 * 60_000L || it > nowMs) return null }
        val before = if (track != null && track.line.size >= 2) {
            track.locate(stop.lat to stop.lon).s < track.locate(here).s - 500.0
        } else {
            val hereIndex = stops.indices.minByOrNull { CpTrack.metres(here, stops[it].lat to stops[it].lon) } ?: return null
            stops.indexOf(stop) < hereIndex
        }
        return stop.takeIf { before }
    }

    /** Quanto o comboio pode ter andado desde a posição que temos (à tua velocidade, que é a dele). */
    fun advance(train: Train, speedKmh: Double): Double = speedKmh / 3.6 * train.ageSec.coerceIn(0.0, MAX_AGE_SEC)

    /**
     * Distância (metros) entre ti e o troço onde o comboio pode estar agora; null se não pode ser o teu
     * (sentido contrário, ou fora do traçado dele).
     * - Com o traçado do comboio: ao longo da linha, entre onde foi visto e esse ponto mais o que andou.
     * - Sem traçado: em linha reta até ao segmento entre onde foi visto e onde estaria agora pelo rumo.
     */
    fun gap(user: User, train: Train, track: CpTrack?): Double? {
        if (user.bearing != null && train.bearing != null && CpTrack.angleBetween(user.bearing, train.bearing) > 60.0) return null
        val ahead = advance(train, user.speedKmh)
        if (track != null && track.line.size >= 2) {
            val u = track.locate(user.lat to user.lon)
            if (CpTrack.metres(user.lat to user.lon, track.pointAt(u.s)) > MAX_TRACK_OFFSET_M) return null
            val t = track.locate(train.lat to train.lon).s
            // O traçado vai no sentido do comboio: só pode ter andado para a frente
            return when {
                u.s < t -> t - u.s
                u.s > t + ahead -> u.s - (t + ahead)
                else -> 0.0
            }
        }
        val from = train.lat to train.lon
        val to = train.bearing?.let { move(from, it, ahead) } ?: from
        val slack = if (train.bearing == null) ahead else 0.0
        return (distanceToSegment(user.lat to user.lon, from, to) - slack).coerceAtLeast(0.0)
    }

    /** Para começar a seguir: mais apertado com traçado (a distância é pela linha). */
    fun lockTolerance(hasTrack: Boolean): Double = if (hasTrack) 600.0 else 1_000.0

    /** Para continuar a bordo: mais largo, para paragens e GPS parado dentro do comboio. */
    fun keepTolerance(hasTrack: Boolean): Double = if (hasTrack) 1_500.0 else 2_500.0

    /** O melhor comboio para ti nesta leitura (o de menor distância dentro da tolerância de entrada). */
    fun best(user: User, trains: List<Train>, trackOf: (String) -> CpTrack?): Pair<Train, Double>? =
        trains.mapNotNull { t ->
            val track = trackOf(t.id)
            gap(user, t, track)?.takeIf { it <= lockTolerance(track != null) }?.let { t to it }
        }.minByOrNull { it.second }

    /** Paragem do comboio mais perto de um ponto, até [maxM]. */
    fun nearestStop(stops: List<Stop>, at: Pair<Double, Double>, maxM: Double = STOP_MAX_M): Stop? =
        stops.map { it to CpTrack.metres(at, it.lat to it.lon) }.filter { it.second <= maxM }.minByOrNull { it.second }?.first

    /** Km pelo traçado entre duas paragens; null sem traçado ou paragens. */
    fun trackKm(track: CpTrack?, a: Stop?, b: Stop?): Double? {
        if (track == null || a == null || b == null || a.code == b.code) return null
        val sa = track.locate(a.lat to a.lon).s
        val sb = track.locate(b.lat to b.lon).s
        return kotlin.math.abs(sb - sa) / 1000.0
    }

    /** Distância à linha de comboio mais perto (os troços da rede), com um filtro rápido por caixa. */
    fun distanceToRail(lines: List<CpTrack>, at: Pair<Double, Double>, maxM: Double = MAX_RAIL_DISTANCE_M * 4): Double {
        val pad = maxM / 111_000.0
        var best = Double.MAX_VALUE
        for (track in lines) {
            if (track.line.size < 2) continue
            if (track.line.none { kotlin.math.abs(it.first - at.first) < pad * 20 && kotlin.math.abs(it.second - at.second) < pad * 20 }) continue
            val p = track.pointAt(track.locate(at).s)
            best = minOf(best, CpTrack.metres(at, p))
        }
        return best
    }

    /** Ponto a [metres] de [from] no rumo [bearing] (graus). */
    fun move(from: Pair<Double, Double>, bearing: Double, metres: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearing)
        val dLat = metres * cos(b) / 111_320.0
        val dLon = metres * sin(b) / (111_320.0 * cos(Math.toRadians(from.first)))
        return (from.first + dLat) to (from.second + dLon)
    }

    private fun distanceToSegment(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        if (a == b) return CpTrack.metres(p, a)
        return CpTrack.metres(p, CpTrack(listOf(a, b)).let { it.pointAt(it.locate(p).s) })
    }
}
