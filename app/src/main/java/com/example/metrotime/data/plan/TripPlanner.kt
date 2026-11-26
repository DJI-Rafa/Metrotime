package com.example.metrotime.data.plan

import android.content.Context
import com.example.metrotime.data.MapConfiguration
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.MetroNetwork
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.bus.CarrisGtfsData
import com.example.metrotime.data.bus.CarrisTimetable
import com.example.metrotime.data.bus.CmTimetable
import com.example.metrotime.data.cp.CpJourney
import com.example.metrotime.data.cp.CpJourneyOption
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.formatStationName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Planeador de viagens. Em Lisboa calcula as viagens com mudanças entre metro e autocarros da Carris (o [Raptor]
 * sobre os horários), procura várias maneiras diferentes de fazer a viagem e, para cada uma, as partidas das
 * próximas 5 horas. A CP entra nos comboios diretos e, nas viagens longas, no comboio até uma estação de Lisboa
 * (e daí de metro ou autocarro), ou de Lisboa até ao destino.
 */
object TripPlanner {

    /** Partidas mostradas para cada alternativa: as das próximas 5 horas. */
    const val WINDOW_SECS = 5 * 3600
    private const val MAX_ALTERNATIVES = 8
    private const val MAX_DEPARTURES = 16
    private const val CP_WALK_M = 1_200.0
    private const val METRO_WALK_M = 900.0
    private const val WALK_ONLY_M = 2_500.0
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Estações da CP com comboios de longo curso e regionais em Lisboa (Oriente, Santa Apolónia, Entrecampos, Sete Rios, Roma-Areeiro). */
    private val MAJOR_HUBS = listOf("94-31039", "94-30007", "94-66050", "94-66076", "94-66035")

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        MetroNetwork.haversineKm(lat1, lon1, lat2, lon2) * 1000

    private fun metres(a: Place, b: Place): Double = metres(a.lat, a.lon, b.lat, b.lon)

    fun distanceText(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(m / 1000).replace('.', ',')

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    // ── Rede (carregada uma vez) ────────────────────────────────────────────────

    private val staticMutex = Mutex()
    private val netMutex = Mutex()
    @Volatile private var staticNet: TransitBuilder.Static? = null
    @Volatile private var urbanCp: List<CpNetwork.Station>? = null
    @Volatile private var netCache: Pair<String, TransitNetwork>? = null

    private suspend fun static(context: Context): TransitBuilder.Static = staticMutex.withLock {
        staticNet ?: withContext(Dispatchers.Default) {
            CarrisGtfsData.ensureLoaded(context)
            val tt = CarrisTimetable.load(context)
            TransitBuilder.buildStatic(
                tt, CarrisGtfsData.stops.map { TransitBuilder.StopInfo(it.id, it.name, it.lat, it.lon) },
                CmTimetable.load(context)
            )
        }.also { staticNet = it }
    }

    /** A rede com os horários de [date], das [fromSec] às [toSec] (guardada para a pesquisa seguinte igual). */
    private suspend fun network(st: TransitBuilder.Static, date: LocalDate, fromSec: Int, toSec: Int, modes: Set<LegMode>): TransitNetwork {
        val from = Math.floorDiv(fromSec, 3600) * 3600
        val to = (Math.floorDiv(toSec, 3600) + 1) * 3600
        val key = "$date|$from|$to|${modes.sorted()}"
        // Várias partes da pesquisa pedem a mesma rede ao mesmo tempo: constrói-se uma vez
        return netMutex.withLock {
            netCache?.takeIf { it.first == key }?.second
                ?: withContext(Dispatchers.Default) { TransitBuilder.network(st, date, from, to, modes) }.also { netCache = key to it }
        }
    }

    /** Estações da CP dentro da rede de Lisboa (com metro ou autocarro à porta). */
    private fun urbanCpStations(context: Context, st: TransitBuilder.Static): List<CpNetwork.Station> = urbanCp
        ?: CpNetwork.stations(context).filter { TransitBuilder.accessFrom(st, it.lat, it.lon, busM = 400.0, metroM = 500.0, max = 1).isNotEmpty() }
            .also { urbanCp = it }

    // ── Sítios ──────────────────────────────────────────────────────────────────

    private data class NearStation(val id: String, val name: String, val metres: Double) {
        val walk: Int get() = Walk.minutes(metres)
    }

    private fun metroNear(p: Place, max: Int = 2): List<NearStation> =
        if (p.kind == Place.Kind.METRO) listOf(NearStation(p.id, p.name, 0.0))
        else MapConfiguration.STATION_COORDS.values
            .map { NearStation(it.id, it.name, metres(p.lat, p.lon, it.lat, it.lon)) }
            .filter { it.metres <= METRO_WALK_M }
            .sortedBy { it.metres }
            .take(max)

    private fun cpNear(context: Context, p: Place): NearStation? =
        if (p.kind == Place.Kind.CP) NearStation(p.id, p.name, 0.0)
        else CpNetwork.stations(context)
            .map { NearStation(it.code, it.name, metres(p.lat, p.lon, it.lat, it.lon)) }
            .filter { it.metres <= CP_WALK_M }
            .minByOrNull { it.metres }

    /**
     * As estações da CP nos dados da CP ficam às vezes longe da entrada (Santa Apolónia fica do lado das
     * mercadorias): quando há uma estação de metro com o mesmo nome perto (ou uma qualquer a menos de 300 m),
     * usa-se a posição do metro, que é a da entrada.
     */
    private fun snap(p: Place): Place {
        if (p.kind != Place.Kind.CP) return p
        val n = MetroLines.normalize(p.name)
        val stations = MapConfiguration.STATION_COORDS.values
        val m = stations.firstOrNull { n.contains(MetroLines.normalize(it.name)) && metres(p.lat, p.lon, it.lat, it.lon) < 450 }
            ?: stations.minByOrNull { metres(p.lat, p.lon, it.lat, it.lon) }?.takeIf { metres(p.lat, p.lon, it.lat, it.lon) < 300 }
            ?: return p
        return p.copy(lat = m.lat, lon = m.lon)
    }

    /** Segundos desde a meia-noite de [date] (pode passar das 24 h). */
    private fun secs(date: LocalDate, t: LocalDateTime): Int = Duration.between(date.atStartOfDay(), t).seconds.toInt()

    private fun at(date: LocalDate, secs: Int): LocalDateTime = date.atStartOfDay().plusSeconds(secs.toLong())

    // ── Planear ─────────────────────────────────────────────────────────────────

    /**
     * Alternativas de [from] para [to] com as partidas das próximas 5 horas a partir de [at] (null = agora, com
     * tempo real no primeiro metro ou autocarro). [modes]: os transportes que se podem usar.
     */
    suspend fun plan(
        context: Context,
        from: Place,
        to: Place,
        at: LocalDateTime? = null,
        modes: Set<LegMode> = setOf(LegMode.METRO, LegMode.BUS, LegMode.TRAIN)
    ): List<PlanAlternative> = coroutineScope {
        val start = at ?: CpTime.now()
        val live = at == null
        val a = snap(from)
        val b = snap(to)
        val st = static(context)
        val urbanModes = modes.filter { it == LegMode.METRO || it == LegMode.BUS }.toSet()
        val (accessA, accessB, cps) = withContext(Dispatchers.Default) {
            Triple(
                TransitBuilder.accessFrom(st, a.lat, a.lon),
                TransitBuilder.accessFrom(st, b.lat, b.lon),
                if (LegMode.TRAIN in modes) cpNear(context, a) to cpNear(context, b) else null to null
            )
        }
        val (cpA, cpB) = cps
        val urbanA = accessA.isNotEmpty()
        val urbanB = accessB.isNotEmpty()

        val tasks = listOf(
            async { listOfNotNull(walkAlternative(a, b, start)) },
            async {
                safe { if (urbanModes.isNotEmpty() && urbanA && urbanB) urbanAlternatives(st, a, b, start, urbanModes, accessA, accessB) else emptyList() }
            },
            async {
                safe { if (cpA != null && cpB != null && cpA.id != cpB.id) listOfNotNull(cpDirect(a, b, cpA, cpB, start)) else emptyList() }
            },
            async {
                safe {
                    if (cpA != null && !urbanA && urbanB && urbanModes.isNotEmpty()) cpThenUrban(context, st, a, b, cpA, start, urbanModes, accessB)
                    else emptyList()
                }
            },
            async {
                safe {
                    if (cpB != null && urbanA && !urbanB && urbanModes.isNotEmpty()) urbanThenCp(context, st, a, b, cpB, start, urbanModes, accessA)
                    else emptyList()
                }
            }
        )
        val all = tasks.awaitAll().flatten()
            .groupBy { it.signature }.values.map { group -> group.minBy { it.first.arrive } }
            .sortedWith(compareBy<PlanAlternative>({ it.first.arrive }, { it.first.transit.size }, { it.first.walkMinutes }))
            .take(MAX_ALTERNATIVES)
        if (live) all.map { async { orNull { withRealtime(context, it) } ?: it } }.awaitAll() else all
    }

    // ── A pé ────────────────────────────────────────────────────────────────────

    private fun walkLeg(depart: LocalDateTime, minutes: Int, from: String, to: String, metres: Double? = null) =
        PlanLeg(
            mode = LegMode.WALK,
            depart = depart,
            arrive = depart.plusMinutes(minutes.toLong()),
            fromName = from,
            toName = to,
            label = "A pé",
            detail = metres?.let { distanceText(it) } ?: ""
        )

    private fun walkAlternative(from: Place, to: Place, start: LocalDateTime): PlanAlternative? {
        val m = metres(from, to)
        if (m > WALK_ONLY_M) return null
        return PlanAlternative(listOf(PlanOption(listOf(walkLeg(start, Walk.minutes(m).coerceAtLeast(1), from.name, to.name, m)))))
    }

    // ── Metro e autocarros (RAPTOR) ─────────────────────────────────────────────

    private fun stopName(net: TransitNetwork, s: Int, place: Place): String = if (s < 0) place.name else net.stops[s].name

    /** Uma viagem do RAPTOR nos bocados do ecrã (com os andares a pé seguidos juntos e sem os de segundos). */
    private fun toOption(net: TransitNetwork, j: Journey, date: LocalDate, from: Place, to: Place): PlanOption {
        val merged = ArrayList<JLeg>()
        for (leg in j.legs) {
            val last = merged.lastOrNull()
            if (leg is JLeg.Walk && last is JLeg.Walk) merged[merged.lastIndex] = JLeg.Walk(last.from, leg.to, last.dep, leg.arr)
            else merged += leg
        }
        val legs = merged.mapNotNull { leg ->
            when (leg) {
                is JLeg.Walk -> {
                    if (leg.arr - leg.dep < 30) return@mapNotNull null
                    val minutes = ((leg.arr - leg.dep + 59) / 60)
                    PlanLeg(
                        mode = LegMode.WALK,
                        depart = at(date, leg.dep),
                        arrive = at(date, leg.dep + minutes * 60),
                        fromName = stopName(net, leg.from, from),
                        toName = stopName(net, leg.to, to),
                        label = "A pé"
                    )
                }
                is JLeg.Ride -> {
                    val route = net.routes[leg.route]
                    val board = net.stops[route.stops[leg.fromPos]]
                    val alight = net.stops[route.stops[leg.toPos]]
                    val count = leg.toPos - leg.fromPos
                    if (route.mode == LegMode.METRO) {
                        val ids = (leg.fromPos..leg.toPos).map { net.stops[route.stops[it]].id.removePrefix("M:") }
                        PlanLeg(
                            mode = LegMode.METRO,
                            depart = at(date, leg.dep),
                            arrive = at(date, leg.arr),
                            fromName = board.name,
                            toName = alight.name,
                            label = route.label,
                            detail = "sentido ${route.headsign} · ${if (count == 1) "1 estação" else "$count estações"} · estimado",
                            color = route.color,
                            metroStations = ids,
                            metroTowards = net.stops[route.stops.last()].id.removePrefix("M:")
                        )
                    } else {
                        // Carris ou Carris Metropolitana (as paragens desta têm "CM:" na rede)
                        val (operator, boardId) = TransitBuilder.operatorOf(board.id)
                        PlanLeg(
                            mode = LegMode.BUS,
                            depart = at(date, leg.dep),
                            arrive = at(date, leg.arr),
                            fromName = board.name,
                            toName = alight.name,
                            label = route.label,
                            detail = "para ${route.headsign} · ${if (count == 1) "1 paragem" else "$count paragens"} · previsto",
                            color = route.color,
                            busOperator = operator,
                            busBoardStop = boardId,
                            busBoardLat = board.lat.takeIf { !it.isNaN() },
                            busBoardLon = board.lon.takeIf { !it.isNaN() },
                            busAlightStop = TransitBuilder.operatorOf(alight.id).second,
                            busAlightLat = alight.lat.takeIf { !it.isNaN() },
                            busAlightLon = alight.lon.takeIf { !it.isNaN() },
                            busDestination = route.headsign
                        )
                    }
                }
            }
        }
        // Sem nada (origem = destino): um bocado a pé de zero minutos
        return PlanOption(legs.ifEmpty { listOf(walkLeg(at(date, j.dep), 0, from.name, to.name)) })
    }

    /** A melhor das viagens de um RAPTOR: a que chega mais cedo e, em empate, com menos veículos. */
    private fun bestOf(journeys: List<Journey>): Journey? = journeys.minWithOrNull(compareBy<Journey>({ it.arr }, { it.rides.size }))

    /**
     * Maneiras diferentes de fazer a viagem: a melhor e as que aparecem quando se tira uma linha de cada vez (e
     * também só de metro e só de autocarro). Para cada maneira, as partidas das próximas 5 horas.
     */
    private suspend fun urbanAlternatives(
        st: TransitBuilder.Static, from: Place, to: Place, start: LocalDateTime, modes: Set<LegMode>,
        access: List<Access>, egress: List<Access>
    ): List<PlanAlternative> = withContext(Dispatchers.Default) {
        val date = start.toLocalDate()
        val t0 = secs(date, start)
        // A mesma janela do [urbanBest]: as duas partes de uma pesquisa usam a mesma rede já construída
        val net = network(st, date, t0, t0 + 12 * 3600, modes)
        Alternatives.find(net, access, egress, t0, WINDOW_SECS, MAX_ALTERNATIVES, MAX_DEPARTURES).map { f ->
            PlanAlternative(f.journeys.map { toOption(net, it, date, from, to) }, frequent = f.frequent)
        }
    }

    /** A viagem que chega mais cedo de [from] para [to] saindo a [start] (para os bocados em Lisboa das viagens de comboio). */
    private suspend fun urbanBest(
        st: TransitBuilder.Static, from: Place, to: Place, start: LocalDateTime, modes: Set<LegMode>,
        egress: List<Access>? = null, base: LocalDateTime
    ): PlanOption? = withContext(Dispatchers.Default) {
        val walkM = metres(from, to)
        val walk = if (walkM <= CP_WALK_M) PlanOption(listOf(walkLeg(start, Walk.minutes(walkM).coerceAtLeast(1), from.name, to.name, walkM))) else null
        val date = base.toLocalDate()
        val t0 = secs(date, base)
        val net = network(st, date, t0, t0 + 12 * 3600, modes)
        val access = TransitBuilder.accessFrom(st, from.lat, from.lon)
        val eg = egress ?: TransitBuilder.accessFrom(st, to.lat, to.lon)
        val ride = bestOf(Raptor.run(net, access, eg, secs(date, start)))?.let { toOption(net, it, date, from, to) }
        listOfNotNull(walk, ride).minByOrNull { it.arrive }
    }

    // ── Comboio ─────────────────────────────────────────────────────────────────

    private fun trainLeg(j: CpJourneyOption, fromName: String, toName: String, fromCode: String, toCode: String): PlanLeg {
        val delay = j.delayMinutes
        val service = CpService.shortName(j.serviceCode)
        val platform = j.departureStop.platform?.takeIf { it.isNotBlank() && it != "0" }
        val destination = j.departureStop.trainDestination?.designation?.let(::formatStationName)
        return PlanLeg(
            mode = LegMode.TRAIN,
            depart = j.realDeparture,
            arrive = j.realArrival,
            fromName = formatStationName(fromName),
            toName = formatStationName(toName),
            label = "$service ${j.trainNumber}",
            detail = listOfNotNull(
                destination?.let { "para $it" },
                platform?.let { "linha $it" },
                when {
                    delay == null -> "previsto"
                    delay >= 2 -> "+$delay min"
                    else -> "a horas"
                }
            ).joinToString(" · "),
            color = service,
            realtime = delay != null,
            trainNumber = j.trainNumber,
            runDate = j.runDate,
            service = service,
            trainDestination = destination,
            fromCode = fromCode,
            toCode = toCode
        )
    }

    /** Comboios diretos das próximas 5 horas (todos na mesma alternativa, cada um uma partida). */
    private suspend fun cpDirect(from: Place, to: Place, a: NearStation, b: NearStation, start: LocalDateTime): PlanAlternative? {
        val end = start.plusSeconds(WINDOW_SECS.toLong())
        val options = CpRepository.findJourneys(a.id, b.id, start.plusMinutes(a.walk.toLong()))
            .filter { !it.suppressed && !it.departure.isAfter(end) }
            .take(MAX_DEPARTURES + 2)
            .map { j ->
                val train = trainLeg(j, a.name, b.name, a.id, b.id)
                val legs = mutableListOf<PlanLeg>()
                if (a.walk > 0) legs += walkLeg(train.depart.minusMinutes(a.walk.toLong()), a.walk, from.name, train.fromName, a.metres)
                legs += train
                if (b.walk > 0) legs += walkLeg(train.arrive, b.walk, train.toName, to.name, b.metres)
                PlanOption(legs)
            }
        return if (options.isEmpty()) null else PlanAlternative(options)
    }

    /** Estações da CP em Lisboa por onde pode passar a ligação: as mais perto de [near] e as grandes. */
    private fun hubs(context: Context, st: TransitBuilder.Static, near: Place, exclude: String): List<CpNetwork.Station> {
        val urban = urbanCpStations(context, st)
        val all = CpNetwork.stations(context)
        return (urban.sortedBy { metres(near.lat, near.lon, it.lat, it.lon) }.take(4) + MAJOR_HUBS.mapNotNull { c -> all.find { it.code == c } })
            .distinctBy { it.code }
            .filter { it.code != exclude }
            .take(8)
    }

    private fun hubPlace(h: CpNetwork.Station) = snap(Place(Place.Kind.CP, h.code, formatStationName(h.name), h.lat, h.lon))

    /** Longe de Lisboa para Lisboa: o comboio até uma estação de Lisboa e daí de metro ou autocarro. */
    private suspend fun cpThenUrban(
        context: Context, st: TransitBuilder.Static, from: Place, to: Place, a: NearStation,
        start: LocalDateTime, modes: Set<LegMode>, egress: List<Access>
    ): List<PlanAlternative> = coroutineScope {
        val hubs = withContext(Dispatchers.Default) { hubs(context, st, to, a.id) }
        val leave = start.plusMinutes(a.walk.toLong())
        val end = start.plusSeconds(WINDOW_SECS.toLong())
        val originTimes = orNull { CpRepository.getTimetableWindow(a.id, leave, aheadHours = 6).stops } ?: return@coroutineScope emptyList()
        val perHub = hubs.map { h ->
            async {
                orNull {
                    h to CpJourney.match(originTimes, CpRepository.getTimetableWindow(h.code, start, aheadHours = 12).stops, leave)
                        .filter { !it.suppressed && !it.departure.isAfter(end) }
                }
            }
        }.awaitAll().filterNotNull()
        val trains = perHub.flatMap { (h, js) -> js.map { h to it } }
            .groupBy { it.second.trainNumber to it.second.departure }.values
            .sortedBy { it.first().second.departure }
            .take(MAX_DEPARTURES)
        val options = trains.mapNotNull { cands ->
            // Até duas estações de chegada (as mais perto do destino); fica a que deixa lá mais cedo
            cands.sortedBy { (h, _) -> metres(to.lat, to.lon, h.lat, h.lon) }.take(2).mapNotNull { (h, j) ->
                val train = trainLeg(j, a.name, h.name, a.id, h.code)
                val tail = urbanBest(st, hubPlace(h).copy(name = train.toName), to, train.arrive.plusMinutes(2), modes, egress, base = start)
                    ?: return@mapNotNull null
                val legs = mutableListOf<PlanLeg>()
                if (a.walk > 0) legs += walkLeg(train.depart.minusMinutes(a.walk.toLong()), a.walk, from.name, train.fromName, a.metres)
                legs += train
                legs += tail.legs
                PlanOption(legs)
            }.minByOrNull { it.arrive }
        }
        options.groupBy { it.signature }.values.map { PlanAlternative(it.sortedBy { o -> o.depart }) }
    }

    /** De Lisboa para longe: metro ou autocarro até uma estação da CP e daí o comboio. */
    private suspend fun urbanThenCp(
        context: Context, st: TransitBuilder.Static, from: Place, to: Place, b: NearStation,
        start: LocalDateTime, modes: Set<LegMode>, access: List<Access>
    ): List<PlanAlternative> = coroutineScope {
        val hubs = withContext(Dispatchers.Default) { hubs(context, st, from, b.id) }
        val end = start.plusSeconds(WINDOW_SECS.toLong())
        val destTimes = orNull { CpRepository.getTimetableWindow(b.id, start, aheadHours = 14).stops } ?: return@coroutineScope emptyList()
        val perHub = hubs.map { h ->
            async {
                orNull {
                    val place = hubPlace(h)
                    // Quanto se leva até à estação, saindo já
                    val first = urbanBest(st, from, place, start, modes, base = start) ?: return@orNull null
                    val trains = CpJourney.match(
                        CpRepository.getTimetableWindow(h.code, start, aheadHours = 7).stops, destTimes, first.arrive.plusMinutes(3)
                    ).filter { !it.suppressed && !it.departure.isAfter(end) }
                    Triple(h, first, trains)
                }
            }
        }.awaitAll().filterNotNull()
        // Cada comboio pela estação de onde se pode sair de casa mais tarde
        val trains = perHub.flatMap { (h, first, js) -> js.map { Triple(h, first, it) } }
            .groupBy { it.third.trainNumber to it.third.runDate }.values
            .map { c -> c.maxBy { (_, first, j) -> Duration.between(first.depart, first.arrive).let { d -> j.departure.minus(d) } } }
            .sortedBy { it.third.departure }
            .take(MAX_DEPARTURES)
        val options = trains.map { (h, first, j) ->
            val place = hubPlace(h)
            val train = trainLeg(j, h.name, b.name, h.code, b.id)
            // Sair o mais tarde possível: tentar chegar à estação ~5 min antes do comboio
            val duration = Duration.between(first.depart, first.arrive)
            val later = train.depart.minus(duration).minusMinutes(8)
            val head = if (later.isAfter(start)) {
                urbanBest(st, from, place, later, modes, base = start)?.takeIf { !it.arrive.isAfter(train.depart.minusMinutes(2)) } ?: first
            } else first
            val legs = head.legs.toMutableList()
            legs += train
            if (b.walk > 0) legs += walkLeg(train.arrive, b.walk, train.toName, to.name, b.metres)
            PlanOption(legs)
        }
        options.groupBy { it.signature }.values.map { PlanAlternative(it.sortedBy { o -> o.depart }) }
    }

    // ── Tempo real no primeiro veículo ──────────────────────────────────────────

    /** Na partida seguinte de cada alternativa: o autocarro com GPS ou o próximo metro, quando partem em breve. */
    private suspend fun withRealtime(context: Context, alt: PlanAlternative): PlanAlternative {
        val option = alt.options.first()
        val i = option.legs.indexOfFirst { it.mode == LegMode.BUS || it.mode == LegMode.METRO }
        if (i < 0) return alt
        val leg = option.legs[i]
        val now = CpTime.now()
        if (Duration.between(now, leg.depart).toMinutes() > 40) return alt
        val updated = when (leg.mode) {
            LegMode.BUS -> {
                val stop = leg.busBoardStop ?: return alt
                val a = BusMapRepository.getStopArrivals(context, stop, leg.busOperator ?: "carris")
                    .firstOrNull { it.realtime && it.routeShortName == leg.label && it.scheduledTime == leg.depart.format(HM) } ?: return alt
                leg.copy(realtime = true, vehicleId = a.vehicleId, detail = leg.detail.replace("previsto", "tempo real · ${a.status.text}"))
            }
            LegMode.METRO -> {
                val from = leg.metroStations.firstOrNull() ?: return alt
                val towards = leg.metroTowards ?: return alt
                val trip = MetroTrip.Leg(leg.color, leg.metroStations, towards, 0.0)
                val data = withContext(Dispatchers.IO) { RemoteArrivalsSource(context).fetchArrivals(from) }
                if (data.isEstimated) return alt
                val ready = Duration.between(now, leg.depart).seconds - 120
                val next = data.arrivals.filter { MetroTrip.servesLeg(trip, it.direction) && it.minutes >= ready }.minByOrNull { it.minutes } ?: return alt
                leg.copy(realtime = true, detail = leg.detail.replace("estimado", "próximo metro em ${(next.minutes + 30) / 60} min (tempo real)"))
            }
            else -> return alt
        }
        val legs = option.legs.toMutableList().also { it[i] = updated }
        return alt.copy(options = listOf(PlanOption(legs)) + alt.options.drop(1))
    }

    // ── Pesquisa de sítios ──────────────────────────────────────────────────────

    @Volatile private var cmPlaces: List<Pair<Place, String>>? = null

    /**
     * Paragens da Carris Metropolitana para a pesquisa (id "CM:…"), com o nome sem acentos para procurar e o
     * concelho também (escrever "escola seixal" encontra a Escola do Seixal). Uma por nome e concelho.
     */
    private fun cmSearchPlaces(context: Context): List<Pair<Place, String>> = cmPlaces ?: run {
        val tt = CmTimetable.load(context) ?: return emptyList()
        tt.stopMeta.entries
            .filter { !it.value.lat.isNaN() && it.value.lat != 0.0 }
            .distinctBy { it.value.name to it.value.area }
            .map { (id, m) -> Place(Place.Kind.BUS, TransitBuilder.cmStopId(id), m.name, m.lat, m.lon) to MetroLines.normalize("${m.name} ${m.area}") }
            .also { cmPlaces = it }
    }

    /** O concelho de uma paragem da Carris Metropolitana ("Seixal"), para a mostrar na pesquisa. */
    fun cmArea(context: Context, place: Place): String? =
        if (place.kind == Place.Kind.BUS && place.id.startsWith(TransitBuilder.CM_PREFIX))
            CmTimetable.load(context)?.stopMeta?.get(place.id.removePrefix(TransitBuilder.CM_PREFIX))?.area?.takeIf { it.isNotBlank() }
        else null

    /**
     * Estações de metro, estações da CP e paragens da Carris e da Carris Metropolitana cujo nome tem [query] (sem
     * acentos; nas da Carris Metropolitana conta também o concelho).
     */
    suspend fun searchPlaces(context: Context, query: String, max: Int = 10): List<Place> = withContext(Dispatchers.IO) {
        val q = MetroLines.normalize(query)
        if (q.length < 2) return@withContext emptyList()
        CarrisGtfsData.ensureLoaded(context)
        val words = q.split(" ").filter { it.isNotBlank() }
        val metro = MapConfiguration.STATION_COORDS.values.map { Place(Place.Kind.METRO, it.id, it.name, it.lat, it.lon) }
        val cp = CpNetwork.stations(context).map { Place(Place.Kind.CP, it.code, formatStationName(it.name), it.lat, it.lon) }
        val bus = CarrisGtfsData.stops.distinctBy { it.name }.map { Place(Place.Kind.BUS, it.id, it.name, it.lat, it.lon) }
        val named = (metro + cp + bus).map { it to MetroLines.normalize(it.name) } + cmSearchPlaces(context)
        named
            .filter { (_, text) -> text.contains(q) || words.size > 1 && words.all { text.contains(it) } }
            .sortedWith(compareBy({ if (MetroLines.normalize(it.first.name).startsWith(q)) 0 else 1 }, { it.first.kind.ordinal }))
            .take(max)
            .map { it.first }
    }

    /** A estação de metro e a da CP mais perto de um sítio (para "casa"), até à distância a que se vai a pé. */
    fun nearestMetro(place: Place): Pair<String, Double>? =
        metroNear(place, 1).firstOrNull()?.let { it.id to it.metres }

    fun nearestCp(context: Context, place: Place): Pair<CpNetwork.Station, Double>? {
        val near = cpNear(context, place) ?: return null
        val st = CpNetwork.stations(context).find { it.code == near.id } ?: return null
        return st to near.metres
    }
}
