package com.example.metrotime.data.cp

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.floor
import kotlin.math.max

/**
 * Onde está um comboio e quanto vai atrasado — UMA conta para o mapa, o painel do mapa, o ecrã do comboio e os
 * avisos (antes cada um fazia a sua e diziam coisas diferentes: o painel "entre Caria e Covilhã", o detalhe já
 * depois da Covilhã, o atraso +9 num sítio e +8 ou +10 noutro).
 *
 * As fontes, pela ordem em que se confia nelas:
 *  1. A última estação da CP ([lastStationCode]) e a última passagem registada pela CP ([pass]: a hora real a que
 *     passou num ponto do percurso, também entre estações, e se já saiu de lá). O comboio nunca fica atrás delas.
 *  2. As horas de cada paragem ([CpTripClock.moments]: as reais da CP, ou as previstas com o atraso conhecido):
 *     enquanto a partida dessa estação não passou, o comboio está lá parado; depois vai a caminho da seguinte.
 *  3. O GPS ([gps], com a hora dele): dá o ponto do troço. Um GPS de há 40 s não diz onde o comboio está agora, por
 *     isso anda-se a partir dele o tempo que passou, ao ritmo do horário. Bem para lá de uma estação (mais de 400 m
 *     pelo traçado, [stopMeters]), também diz que o comboio já saiu dela — e isso não deixa de ser verdade com o
 *     tempo: o GPS antigo continua a valer como sítio por onde o comboio já passou, por isso o comboio nunca recua
 *     sozinho entre atualizações.
 *  4. Sem nada disto, o relógio pelas horas das paragens.
 *
 * O atraso é o do próximo acontecimento, com as mesmas horas que a lista mostra: parado numa estação, o da partida
 * dela; a caminho, o da chegada à seguinte; ainda sem partir, o da partida da origem; no fim, o da chegada.
 */
data class CpTrainNow(
    /** -1 = ainda não partiu; i = na paragem i; i + f = a caminho entre a paragem i e a i + 1. */
    val position: Float,
    /** Parado (ou a chegar) numa estação: [position] é um número inteiro. */
    val atStation: Boolean,
    /** A CP já o dá nesta estação mas a hora de chegada ainda não passou. */
    val arriving: Boolean,
    val finished: Boolean,
    /** Minutos de atraso no próximo acontecimento; null sem horas. */
    val delayMinutes: Int?,
    /** Suprimido (greve, avaria…): não anda, não tem atraso nem posição. */
    val cancelled: Boolean = false
) {
    val started: Boolean get() = position >= 0f
    /** A paragem onde está ou a última por onde passou (-1 = ainda não partiu). */
    val stopIndex: Int get() = if (position < 0f) -1 else position.toInt()
    /** A próxima paragem onde vai parar ou partir (a da partida, se ainda está parado). */
    val nextIndex: Int get() = when {
        position < 0f -> 0
        atStation -> stopIndex
        else -> stopIndex + 1
    }

    /**
     * Um sítio conhecido do comboio: [position] em paragens (2.4 = 40 % do caminho entre a 3.ª e a 4.ª), a hora a que
     * lá estava e, numa passagem registada pela CP, se já tinha saído de lá ([left]; false = chegou e ainda lá estava).
     */
    data class Fix(val position: Float, val at: LocalDateTime, val left: Boolean = true)

    companion object {
        /** Fração mínima e máxima de um troço quando vai a caminho (para não parecer parado numa estação). */
        private const val EDGE = 0.05f
        /** GPS com hora mais do que isto no futuro (relógios trocados) não conta. */
        private const val GPS_FUTURE_S = 60L
        /** Quanto o GPS tem de ir para lá de uma estação para contar como saído (ruído e tamanho da estação). */
        const val LEFT_M = 400.0
        /** Uma chegada registada sem partida deixa de contar quando a partida prevista já passou há mais disto. */
        const val STALE_ARRIVAL_S = 180L

        /** A conta antiga, só com o ponto do GPS no troço (sem hora): o ecrã do comboio e os testes antigos. */
        fun of(
            stops: List<CpTrainStop>,
            moments: List<CpTripClock.StopMoment>,
            lastStationCode: String?,
            status: String?,
            gpsPosition: Float?,
            now: LocalDateTime
        ): CpTrainNow = of(stops, moments, lastStationCode, status, gpsPosition?.let { Fix(it, now) }, null, null, now)

        /**
         * @param gps o GPS em paragens e a hora dele.
         * @param pass a última passagem registada pela CP, em paragens.
         * @param stopMeters onde cada paragem cai no traçado (metros); sem eles o GPS não diz que o comboio saiu.
         */
        fun of(
            stops: List<CpTrainStop>,
            moments: List<CpTripClock.StopMoment>,
            lastStationCode: String?,
            status: String?,
            gps: Fix?,
            pass: Fix?,
            stopMeters: List<Double?>?,
            now: LocalDateTime
        ): CpTrainNow {
            if (stops.isEmpty() || moments.size != stops.size) return CpTrainNow(-1f, false, false, false, null)
            // Suprimido: não anda (antes andava pelo relógio e dizia "Na hora", num dia de greve)
            val running = stops.indices.filter { stops[it].supression == null }
            if (status == VehicleStatus.CANCELLED || running.isEmpty()) return CpTrainNow(-1f, false, false, false, null, cancelled = true)
            val result = running(stops, moments, lastStationCode, status, gps, pass, stopMeters, now)
            // Suprimido só num troço: só anda entre a primeira e a última paragem que se fazem
            val first = running.first()
            val last = running.last()
            return when {
                result.position >= 0f && result.position < first -> CpTrainNow(-1f, false, false, false, result.delayMinutes)
                result.position > last -> CpTrainNow(last.toFloat(), true, false, true, result.delayMinutes)
                else -> result
            }
        }

        /** Metros pelo traçado de uma posição em paragens (null se as paragens à volta não tiverem posição). */
        fun metersAt(stopMeters: List<Double?>, position: Float): Double? {
            if (stopMeters.isEmpty() || position < 0f) return null
            val i = position.toInt().coerceIn(0, stopMeters.lastIndex)
            val j = minOf(stopMeters.lastIndex, i + 1)
            val a = stopMeters[i]
            val b = stopMeters[j]
            return when {
                a == null || b == null -> a ?: b
                else -> a + (b - a) * (position - i)
            }
        }

        private fun running(
            stops: List<CpTrainStop>,
            moments: List<CpTripClock.StopMoment>,
            lastStationCode: String?,
            status: String?,
            gps: Fix?,
            pass: Fix?,
            stopMeters: List<Double?>?,
            now: LocalDateTime
        ): CpTrainNow {
            val lastIndex = stops.lastIndex
            val clock = CpTripClock.progress(moments, now)
            val reportedStation = lastStationCode?.let { code -> stops.indexOfLast { it.station?.code == code } } ?: -1

            fun minutes(a: LocalDateTime?, b: LocalDateTime?): Int? =
                if (a != null && b != null) Duration.between(a, b).toMinutes().toInt() else null
            fun departureDelay(i: Int) = moments[i].let { m ->
                minutes(m.scheduledDeparture, m.departure) ?: minutes(m.scheduledArrival, m.arrival)
            }
            fun arrivalDelay(i: Int) = moments[i].let { m ->
                minutes(m.scheduledArrival, m.arrival) ?: minutes(m.scheduledDeparture, m.departure)
            }
            /** Metros que [position] fica para lá da paragem [i] (null sem traçado). */
            fun metersPast(i: Int, position: Float): Double? {
                val meters = stopMeters ?: return null
                val at = metersAt(meters, position) ?: return null
                val stop = meters.getOrNull(i) ?: return null
                return at - stop
            }

            // A passagem registada pela CP (só as que já aconteceram): o comboio já esteve ali
            val passed = pass?.takeIf { it.position >= 0f && !it.at.isAfter(now.plusSeconds(5)) }
            val passIndex = passed?.let { floor(it.position + 1e-3f).toInt().coerceAtMost(lastIndex) } ?: -1
            // O GPS (sem hora no futuro); com o traçado, também diz que já saiu de uma estação
            val usableGps = gps?.takeIf { Duration.between(now, it.at).seconds <= GPS_FUTURE_S }
            val placedGps = usableGps?.takeIf { stopMeters != null }

            // A última estação a que se sabe que chegou: a da CP, a da passagem registada ou, com o GPS bem para lá
            // dela, a estação que o GPS já deixou para trás
            var reported = max(reportedStation, passIndex)
            placedGps?.let { g ->
                val k = floor(g.position).toInt().coerceIn(0, lastIndex)
                if (k > reported && (metersPast(k, g.position) ?: 0.0) > LEFT_M) reported = k
            }

            if (status == VehicleStatus.COMPLETED || reported == lastIndex) {
                return CpTrainNow(lastIndex.toFloat(), true, false, true, arrivalDelay(lastIndex))
            }
            // A estação de onde se parte: a última conhecida ou, sem ela, a do relógio
            val from = when {
                reported >= 0 -> reported
                status == VehicleStatus.AT_ORIGIN -> 0
                status == VehicleStatus.NOT_STARTED || clock < 0f -> return CpTrainNow(-1f, false, false, false, departureDelay(0))
                clock >= lastIndex -> return CpTrainNow(lastIndex.toFloat(), true, false, true, arrivalDelay(lastIndex))
                else -> clock.toInt()
            }
            val m = moments[from]
            val departure = m.departure ?: m.arrival
            // Já saiu? A CP registou a partida (ou a passagem num ponto depois da estação), ou um GPS fresco já vai longe
            val passLeft = passed != null && (passed.position > from + 1e-3f || (passIndex == from && passed.left))
            val gpsLeft = placedGps != null && (metersPast(from, placedGps.position) ?: 0.0) > LEFT_M
            // Chegou e a CP ainda não registou a partida: está lá (até a partida prevista ter passado há muito)
            val passHeld = passed != null && passIndex == from && !passed.left && passed.position < from + 1e-3f
            val heldTooLong = departure != null && Duration.between(departure, now).seconds > STALE_ARRIVAL_S
            val stopped = !passLeft && !gpsLeft && (
                (passHeld && !heldTooLong) ||
                    status == VehicleStatus.AT_STATION || status == VehicleStatus.AT_ORIGIN ||
                    (departure != null && now.isBefore(departure))
                )
            if (stopped) {
                val arriving = !passHeld && m.arrival?.let { now.isBefore(it) } == true
                return CpTrainNow(from.toFloat(), true, arriving, false, departureDelay(from))
            }

            // A caminho da seguinte
            val next = from + 1
            val arrival = moments[next].arrivalOrDeparture
            val span = if (departure != null && arrival != null) Duration.between(departure, arrival).seconds.takeIf { it > 0 } else null
            val byClock = if (departure != null && span != null) Duration.between(departure, now).seconds.toFloat() / span else 0.5f
            /** A fração do troço onde [f] põe o comboio agora: anda o tempo que passou desde então, ao ritmo do horário. */
            fun projected(f: Fix, moving: Boolean): Float {
                val share = f.position - from
                if (!moving || span == null) return share
                return share + Duration.between(f.at, now).seconds.coerceAtLeast(0L).toFloat() / span
            }
            val passShare = passed?.takeIf { it.position > from - 1e-3f && it.position < next + 1e-3f }?.let { it.position - from }
            // Uma chegada sem partida registada há demasiado tempo já não prende o comboio: anda pelo relógio
            val passAnchor = passed?.takeIf { passShare != null && !(passHeld && heldTooLong) }
            val anchors = buildList {
                if (passAnchor != null) add(passAnchor.at to projected(passAnchor, passAnchor.left))
                usableGps?.takeIf { it.position - from > -0.5f && it.position - from < 1.5f }?.let { add(it.at to projected(it, true)) }
            }
            // O ponto conhecido mais recente; nunca atrás do sítio por onde a CP já o deu a passar
            var share = anchors.maxByOrNull { it.first }?.second ?: byClock
            if (passShare != null) share = max(share, passShare)
            share = share.coerceIn(EDGE, 1f - EDGE)
            return CpTrainNow(from + share, false, false, false, arrivalDelay(next))
        }
    }
}
