package com.example.metrotime.data.cp

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp

/**
 * Percurso de um comboio no mapa: o traçado real (GTFS, como no CP Bot) ou, sem ele, linhas retas entre as paragens,
 * e onde cada paragem cai nesse traçado (metros desde o início, sempre para a frente).
 */
class CpRouteGeometry(val track: CpTrack, val stopAt: List<Double?>) {

    /** Metros pelo traçado de uma posição em paragens (null se as paragens à volta não tiverem posição). */
    fun metersAt(position: Float): Double? = CpTrainNow.metersAt(stopAt, position)

    companion object {
        /** [points]: as coordenadas de cada paragem (null sem elas); [track]: o traçado do GTFS, se houver. */
        fun of(track: CpTrack?, points: List<Pair<Double, Double>?>): CpRouteGeometry? {
            val line = track ?: points.filterNotNull().takeIf { it.size >= 2 }?.let { CpTrack(it) } ?: return null
            if (line.line.size < 2) return null
            val stopAt = line.stopPositions(points)
            return if (stopAt.count { it != null } >= 2) CpRouteGeometry(line, stopAt) else null
        }
    }
}

/**
 * Um comboio com tudo o que é preciso para o pôr no mapa e no painel com a MESMA conta ([CpTrainNow]): as paragens e
 * as horas, o percurso no traçado e os sítios conhecidos (o GPS com a hora dele e a última passagem registada pela CP).
 * O mapa pergunta-lhe onde está a cada imagem; o painel, a cada poucos segundos — e dão sempre o mesmo.
 */
class CpLiveTrain(
    val number: Int,
    val stops: List<CpTrainStop>,
    val moments: List<CpTripClock.StopMoment>,
    val geometry: CpRouteGeometry,
    val lastStationCode: String?,
    val status: String?,
    val gps: CpTrainNow.Fix?,
    val pass: CpTrainNow.Fix?
) {
    fun now(at: LocalDateTime): CpTrainNow =
        CpTrainNow.of(stops, moments, lastStationCode, status, gps, pass, geometry.stopAt, at)

    /** Metros pelo traçado onde [n] o põe (null antes de partir ou suprimido). */
    fun metersOf(n: CpTrainNow): Double? = if (n.cancelled || !n.started) null else geometry.metersAt(n.position)
}

/** O que veio de um comboio em cp_vehicles e conta para a posição (o mapa e os testes montam-no da mesma forma). */
data class CpLiveInput(
    val number: Int,
    val runDate: String?,
    val lastStationCode: String?,
    val status: String?,
    val delaySeconds: Int?,
    val gps: Pair<Double, Double>?,
    val gpsAtMs: Long?,
    val passCode: String?,
    val passTime: String?,
    val passLeft: Boolean?,
    /** A hora dos dados (ms): a da Cloud Function, ou a da leitura direta. */
    val dataAtMs: Long
)

object CpLive {

    /** O percurso de um comboio de cp_routes no traçado do GTFS (ou em linhas retas entre as estações). */
    fun routeGeometry(number: Int, route: CpRoute, stationPoint: (String) -> Pair<Double, Double>?): CpRouteGeometry? =
        CpRouteGeometry.of(CpNetwork.trackFor(number), route.stops.map { s -> s.station?.code?.let(stationPoint) })

    /**
     * Um comboio de cp_routes com o que veio dele em cp_vehicles: a conta que o mapa faz para todos os comboios.
     * null se o percurso for de outro dia.
     */
    fun fromRoute(
        input: CpLiveInput,
        route: CpRoute,
        geometry: CpRouteGeometry,
        stationPoint: (String) -> Pair<Double, Double>?
    ): CpLiveTrain? {
        if (input.runDate != null && input.runDate.take(10) != route.runDate.toString()) return null
        return build(
            number = input.number,
            stops = route.withDelay(input.delaySeconds),
            runDate = route.runDate,
            geometry = geometry,
            lastStationCode = input.lastStationCode,
            status = input.status,
            gpsPoint = input.gps,
            gpsAt = input.gpsAtMs?.takeIf { it > 0 }?.let(::lisbon),
            passCode = input.passCode,
            passTime = input.passTime,
            passLeft = input.passLeft,
            dataAt = lisbon(input.dataAtMs),
            stationPoint = stationPoint
        )
    }

    /**
     * Monta um comboio. [stops]: as paragens com as horas (do percurso da CP, ou de cp_routes já com o atraso de agora);
     * [gpsPoint]/[gpsAt]: a posição e a hora dela (sem hora, a dos dados, [dataAt]); [passCode]/[passTime]/[passLeft]:
     * a última passagem registada pela CP; [stationPoint]: coordenadas de uma estação pelo código.
     */
    fun build(
        number: Int,
        stops: List<CpTrainStop>,
        runDate: LocalDate,
        geometry: CpRouteGeometry,
        lastStationCode: String?,
        status: String?,
        gpsPoint: Pair<Double, Double>?,
        gpsAt: LocalDateTime?,
        passCode: String?,
        passTime: String?,
        passLeft: Boolean?,
        dataAt: LocalDateTime,
        stationPoint: (String) -> Pair<Double, Double>?
    ): CpLiveTrain {
        val moments = CpTripClock.moments(runDate, stops)
        val gps = gpsPoint?.let { p ->
            geometry.track.progressOf(geometry.stopAt, p)?.let { CpTrainNow.Fix(it, gpsAt ?: dataAt) }
        }
        val pass = passFix(stops, geometry, lastStationCode, passCode, passTime, passLeft, dataAt, stationPoint)
        return CpLiveTrain(number, stops, moments, geometry, lastStationCode, status, gps, pass)
    }

    /**
     * A última passagem registada pela CP, em paragens: exata se foi numa paragem do comboio; num apeadeiro onde ele não
     * para, pelo traçado (a menos de 500 m dele). null sem dados ou se a estação não estiver na rede.
     */
    fun passFix(
        stops: List<CpTrainStop>,
        geometry: CpRouteGeometry,
        lastStationCode: String?,
        passCode: String?,
        passTime: String?,
        passLeft: Boolean?,
        dataAt: LocalDateTime,
        stationPoint: (String) -> Pair<Double, Double>?
    ): CpTrainNow.Fix? {
        val code = passCode ?: return null
        val time = parseTime(passTime) ?: return null
        val at = nearest(dataAt, time)
        // Se passar duas vezes na mesma estação, a passagem que conta é a que não está antes da última estação da CP
        val reported = lastStationCode?.let { c -> stops.indexOfLast { it.station?.code == c } } ?: -1
        val index = stops.indices.firstOrNull { it >= reported && stops[it].station?.code == code }
            ?: stops.indexOfLast { it.station?.code == code }.takeIf { it >= 0 }
        val position = index?.toFloat()
            ?: stationPoint(code)?.let { geometry.track.progressOf(geometry.stopAt, it, maxOffTrack = 500.0) }
            ?: return null
        return CpTrainNow.Fix(position, at, passLeft ?: true)
    }

    /** "HH:mm:ss" ou "HH:mm". */
    fun parseTime(s: String?): LocalTime? = if (s.isNullOrBlank()) null else runCatching { LocalTime.parse(s.trim()) }.getOrNull()

    /** A hora [time] no dia mais perto de [ref] (perto da meia-noite pode ser a do dia antes ou a do seguinte). */
    fun nearest(ref: LocalDateTime, time: LocalTime): LocalDateTime {
        val same = LocalDateTime.of(ref.toLocalDate(), time)
        return listOf(same.minusDays(1), same, same.plusDays(1)).minBy { abs(Duration.between(ref, it).seconds) }
    }

    /** Milissegundos (relógio do mundo) → hora de Lisboa. */
    fun lisbon(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), CpTime.LISBON)
}

/**
 * Os comboios no mapa nunca andam para trás nem saltam. A posição mostrada segue a calculada; quando a conta muda de
 * repente (chegaram dados novos, partiu da estação, chegou à seguinte), a diferença desfaz-se em cerca de 1,5 s.
 * Se a conta ficar atrás do que se mostra (o GPS novo ficou atrás do que se estimava), o comboio espera parado até ela
 * o apanhar; só volta para trás (devagar) se a diferença passar de 1,5 km, e vai logo se for enorme (outro percurso).
 */
class CpSmoother {
    /** [backing]: estava muito à frente da conta e está a voltar para trás até ela. */
    private class State(var shown: Double, var offset: Double, var lastTarget: Double, var backing: Boolean = false)

    private val states = HashMap<Int, State>()

    /** Metros a mostrar para [key], a caminho de [target]; [newData]: os dados deste comboio acabaram de mudar. */
    fun step(key: Int, target: Double, dtSec: Double, newData: Boolean = false): Double {
        val st = states[key]
        if (st == null) {
            states[key] = State(target, 0.0, target)
            return target
        }
        st.offset *= exp(-dtSec / TAU_S)
        if (newData || abs(target - st.lastTarget) > JUMP_M) {
            if (abs(target - st.shown) > SNAP_M) {
                st.shown = target
                st.offset = 0.0
                st.lastTarget = target
                st.backing = false
                return target
            }
            // Continua de onde estava e desfaz a diferença aos poucos; para trás só se estiver muito à frente
            st.offset = st.shown - target
            st.backing = st.offset >= HOLD_BACK_M
        }
        st.lastTarget = target
        val candidate = target + st.offset
        st.shown = if (candidate < st.shown && !st.backing) st.shown else candidate
        if (st.backing && abs(st.offset) < 1.0) st.backing = false
        return st.shown
    }

    fun forget(key: Int) {
        states.remove(key)
    }

    /** Esquece os comboios que já não estão no mapa. */
    fun retain(keys: Set<Int>) {
        states.keys.retainAll(keys)
    }

    companion object {
        const val TAU_S = 0.5
        /** Mais do que isto de uma imagem para a outra não é andar: é a conta que mudou. */
        const val JUMP_M = 60.0
        const val HOLD_BACK_M = 1_500.0
        const val SNAP_M = 5_000.0
    }
}
