package com.example.metrotime.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.alerts.MetroLineStatus
import com.example.metrotime.data.metro.MetroLineTrack
import com.example.metrotime.data.metro.MetroMotion
import com.example.metrotime.data.metro.MetroTrain
import com.example.metrotime.data.metro.MetroTracks
import com.example.metrotime.work.ExitAlarm
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.bus.BusRoute
import com.example.metrotime.data.bus.BusStop
import com.example.metrotime.data.bus.LiveBus
import com.example.metrotime.data.bus.BusScheduleStop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationArrivals
import com.example.metrotime.data.UserDirectory
import com.example.metrotime.data.doubleOrNull
import com.example.metrotime.data.longOrNull
import com.example.metrotime.data.stringOrNull

class LiveMapViewModel(application: Application) : AndroidViewModel(application) {

    private val arrivalsSource = RemoteArrivalsSource(application)

    // ── Metro: os metros em movimento (modelo em data/metro; o mapa desenha-os a cada imagem) ─────────────

    /** As quatro linhas com o traçado real dos túneis e onde cada estação cai nele. */
    val metroTracks: Map<String, MetroLineTrack> by lazy {
        // Lisboa (traçado do OpenStreetMap na app) e o Metro do Porto (horário oficial + traçado, do pacote MDP)
        val lisbon = MetroTracks.load(application)
        com.example.metrotime.data.regional.RegionalRepository.init(application)
        val porto = runCatching {
            com.example.metrotime.data.regional.RegionalRepository.pack("MDP")?.let { com.example.metrotime.data.metro.PortoMetro.build(it) }
        }.getOrNull()
        porto?.let { com.example.metrotime.data.metro.PortoMetro.network = it }
        lisbon + porto?.tracks.orEmpty()
    }

    // As linhas (Lisboa e Porto) preparam-se fora da thread principal: o mapa usa-as logo no primeiro desenho
    init { viewModelScope.launch(Dispatchers.Default) { metroTracks } }

    /** Os metros do Porto pelo horário (os dos próximos 20 min também, para o seletor de tempo). */
    private fun portoTrains(): List<MetroTrain> {
        metroTracks // garante a rede do Porto
        val net = com.example.metrotime.data.metro.PortoMetro.network ?: return emptyList()
        val pack = com.example.metrotime.data.regional.RegionalRepository.pack("MDP") ?: return emptyList()
        return runCatching {
            com.example.metrotime.data.metro.PortoMetro.trains(pack, net, java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Lisbon")))
        }.getOrElse { it.printStackTrace(); emptyList() }
    }

    /** As chegadas a uma estação do Porto, pelos metros do horário: segundos até chegar, por linha e destino. */
    private fun portoStationArrivals(id: String, name: String): StationArrivals {
        val now = System.currentTimeMillis()
        val arrivals = _metroTrains.value.filter { com.example.metrotime.data.metro.PortoMetro.isPorto(it.line) }.flatMap { tr ->
            val track = metroTracks[tr.line] ?: return@flatMap emptyList()
            // Os que acabam aqui não levam ninguém a lado nenhum
            if (tr.runs.lastOrNull()?.destinationId == id) return@flatMap emptyList()
            tr.upcoming(now).filter { (idx, _) -> track.stations.getOrNull(idx) == id }.map { (_, at) ->
                com.example.metrotime.data.Arrival(
                    direction = tr.destinationAt(now) ?: "?",
                    minutes = ((at - now) / 1000).toInt().coerceAtLeast(0),
                    line = tr.line
                )
            }
        }
        val coords = com.example.metrotime.data.metro.PortoMetro.network?.coords?.get(id)
        return StationArrivals(
            station = com.example.metrotime.data.Station(id, name, coords?.first ?: 0.0, coords?.second ?: 0.0, "", id),
            arrivals = arrivals,
            fetchedAtMillis = now
        )
    }

    private val _metroTrains = MutableStateFlow<List<MetroTrain>>(emptyList())
    /** Os metros e as horas a que chegam às estações seguintes (da API do Metro, a cada 10 s). */
    val metroTrains: StateFlow<List<MetroTrain>> = _metroTrains

    private val _metroOffsetMs = MutableStateFlow(0L)
    /** Seletor de tempo: quanto à frente de agora o mapa mostra os metros (ms; 0 = agora), contínuo ao arrastar. */
    val metroOffsetMs: StateFlow<Long> = _metroOffsetMs
    fun setMetroOffset(ms: Long) { _metroOffsetMs.value = ms.coerceIn(0L, MetroMotion.MAX_OFFSET_MIN * 60_000L) }

    private val _selectedMetro = MutableStateFlow<String?>(null)
    /** O metro aberto ("linha|número"). */
    val selectedMetro: StateFlow<String?> = _selectedMetro

    /** A bordo: o metro em que vais, de onde (para a barra de progresso), onde sais e se avisa antes. */
    data class Onboard(
        val key: String, val line: String, val trainId: String,
        val fromStation: String, val exitStation: String, val alarm: Boolean
    )
    private val _onboard = MutableStateFlow<Onboard?>(null)
    val onboard: StateFlow<Onboard?> = _onboard
    private var alarmArriveMs = 0L
    private var alarmScheduledAt = 0L

    private val _followMetro = MutableStateFlow<String?>(null)
    /** O metro que a câmara segue (o "Seguir" do painel, ou o teu a bordo). */
    val followMetro: StateFlow<String?> = _followMetro
    private val _followPaused = MutableStateFlow(false)
    /** Mexeste no mapa: a câmara deixa de seguir até tocares em "Seguir o metro". */
    val followPaused: StateFlow<Boolean> = _followPaused
    fun followMetro(key: String?) {
        _followMetro.value = key
        _followPaused.value = false
    }
    fun setFollowPaused(paused: Boolean) { _followPaused.value = paused }

    private val _metroStatuses = MutableStateFlow<List<MetroLineStatus>>(emptyList())
    /** Estado de cada linha (perturbações), a cada minuto. */
    val metroStatuses: StateFlow<List<MetroLineStatus>> = _metroStatuses

    private val _myLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    /** A tua localização (do ponto azul do mapa), para a estação mais perto à noite. */
    val myLocation: StateFlow<Pair<Double, Double>?> = _myLocation
    fun setMyLocation(lat: Double, lon: Double) { _myLocation.value = lat to lon }

    fun metroTrain(key: String?): MetroTrain? =
        key?.let { k -> _metroTrains.value.firstOrNull { it.key == k } }

    fun selectMetro(key: String?) {
        // Fechar ou trocar de metro deixa de seguir o anterior (a bordo continua a seguir o teu)
        if (key != _selectedMetro.value && _followMetro.value != _onboard.value?.key) _followMetro.value = null
        _selectedMetro.value = key
        if (key != null) {
            _selectedStation.value = null
            selectCpTrain(null)
            closeRegional()
        }
    }

    /** Vais neste metro e sais em [exitStation]: o mapa segue-o e, com [alarm], avisa antes de chegares. */
    fun startOnboard(key: String, exitStation: String, alarm: Boolean) {
        val train = metroTrain(key) ?: return
        val track = metroTracks[train.line] ?: return
        val place = train.placeAt(System.currentTimeMillis(), track)
        // De onde parte a barra de progresso: a estação onde está, ou a anterior à seguinte
        val fromIdx = place?.atStation ?: place?.nextStation?.let { it - place.dir } ?: 0
        val from = track.stations[fromIdx.coerceIn(0, track.stations.lastIndex)]
        val board = Onboard(key, train.line, train.trainId, from, exitStation, alarm)
        _onboard.value = board
        _selectedMetro.value = null
        _metroOffsetMs.value = 0L
        followMetro(key)
        alarmArriveMs = 0L
        if (alarm) scheduleOnboardAlarm(board, force = true)
    }

    fun setOnboardAlarm(on: Boolean) {
        val board = _onboard.value ?: return
        _onboard.value = board.copy(alarm = on)
        if (on) scheduleOnboardAlarm(board.copy(alarm = true), force = true) else ExitAlarm.stop(getApplication())
    }

    fun stopOnboard() {
        val board = _onboard.value ?: return
        _onboard.value = null
        if (_followMetro.value == board.key) _followMetro.value = null
        if (board.alarm) ExitAlarm.stop(getApplication())
    }

    /** O aviso antes de sair (pelo relógio, como no planeador): reagendado só se a hora de chegada mudar a sério. */
    private fun scheduleOnboardAlarm(board: Onboard, force: Boolean = false) {
        val train = metroTrain(board.key) ?: return
        val now = System.currentTimeMillis()
        val exitIdx = metroTracks[board.line]?.indexOf(board.exitStation) ?: return
        val arrive = train.upcoming(now).firstOrNull { it.first == exitIdx }?.second ?: return
        val changed = kotlin.math.abs(arrive - alarmArriveMs) >= 45_000L
        // O aviso é 90 s antes: depois disso já não se mexe (o serviço já o deu ou está a dar)
        if (!force && (!changed || arrive - now < 120_000L || now - alarmScheduledAt < 60_000L)) return
        val name = MetroLines.nameOf(board.exitStation)
        ExitAlarm.startTimed(
            getApplication(), "Metro", name,
            listOf(ExitAlarm.Step(arrive - 90_000L, "Prepara-te para sair", "A seguir chegas a $name.")), arrive
        )
        alarmArriveMs = arrive
        alarmScheduledAt = now
    }

    /** As chegadas das quatro linhas, ao mesmo tempo; uma linha que falhe fica com os metros da leitura anterior. */
    private suspend fun fetchMetro(): List<MetroTrain> = coroutineScope {
        val now = System.currentTimeMillis()
        val read = MetroLines.NAMES.map { line ->
            async { line to runCatching { arrivalsSource.fetchLineArrivals(line).arrivals }.getOrNull() }
        }.awaitAll()
        val ok = read.mapNotNull { (line, list) -> list?.let { line to it } }.toMap()
        MetroMotion.build(ok, metroTracks, now) + _metroTrains.value.filter { it.line !in ok.keys }
    }

    data class SelectedStationState(val id: String, val name: String, val arrivals: StationArrivals?, val loading: Boolean)
    private val _selectedStation = MutableStateFlow<SelectedStationState?>(null)
    val selectedStation: StateFlow<SelectedStationState?> = _selectedStation

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var locationCallback: LocationCallback? = null

    data class FriendLocation(
        val uid: String,
        val lat: Double,
        val lon: Double,
        val timestamp: Long,
        val name: String,
        val initial: String,
        val photoUrl: String?,
        val photoBase64: String?
    )
    private val _friendLocations = MutableStateFlow<List<FriendLocation>>(emptyList())
    val friendLocations: StateFlow<List<FriendLocation>> = _friendLocations

    private var friendsListener: ValueEventListener? = null
    private val locationListeners = mutableMapOf<String, ValueEventListener>()

    // ── Bus search state ────────────────────────────────────────────────────────
    private val _busSearchQuery = MutableStateFlow("")
    val busSearchQuery: StateFlow<String> = _busSearchQuery

    private val _busSearchResults = MutableStateFlow<List<BusRoute>>(emptyList())
    val busSearchResults: StateFlow<List<BusRoute>> = _busSearchResults

    private val _selectedBusRoute = MutableStateFlow<BusRoute?>(null)
    val selectedBusRoute: StateFlow<BusRoute?> = _selectedBusRoute

    private val _liveBuses = MutableStateFlow<List<LiveBus>>(emptyList())
    val liveBuses: StateFlow<List<LiveBus>> = _liveBuses

    private val _busRouteStops = MutableStateFlow<List<BusStop>>(emptyList())
    val busRouteStops: StateFlow<List<BusStop>> = _busRouteStops

    private val _busRouteShape = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val busRouteShape: StateFlow<List<Pair<Double, Double>>> = _busRouteShape

    private val _isBusMode = MutableStateFlow(false)
    val isBusMode: StateFlow<Boolean> = _isBusMode

    private val _isBusSearchOpen = MutableStateFlow(false)
    val isBusSearchOpen: StateFlow<Boolean> = _isBusSearchOpen

    private val _isBusLoading = MutableStateFlow(false)
    val isBusLoading: StateFlow<Boolean> = _isBusLoading

    private val _selectedDirection = MutableStateFlow(0)
    val selectedDirection: StateFlow<Int> = _selectedDirection

    private var busPollingJob: Job? = null
    private var searchJob: Job? = null
    private var stopArrivalsJob: Job? = null

    // ── Selected individual live bus ─────────────────────────────────────────────
    data class SelectedLiveBusState(
        val bus: LiveBus,
        val schedule: List<BusScheduleStop> = emptyList(),
        val loading: Boolean = true,
        /** Atraso pelo horário da viagem (segundos; negativo = adiantado); null se não se souber. */
        val delaySeconds: Int? = null
    )

    private var liveBusJob: Job? = null

    private fun busDelay(bus: LiveBus, schedule: List<BusScheduleStop>): Int? =
        com.example.metrotime.data.bus.busScheduleDelay(
            schedule, bus.currentStatus == "STOPPED_AT",
            java.time.LocalTime.now(java.time.ZoneId.of("Europe/Lisbon")).toSecondOfDay()
        )

    private val _selectedLiveBus = MutableStateFlow<SelectedLiveBusState?>(null)
    val selectedLiveBus: StateFlow<SelectedLiveBusState?> = _selectedLiveBus

    // ── Bus stop selection state ─────────────────────────────────────────────────
    data class SelectedStopState(
        val stop: BusStop,
        val arrivals: List<BusMapRepository.StopArrivalInfo> = emptyList(),
        val loading: Boolean = true,
        /** Quando chegaram as chegadas (para o "atualizado há X s"). */
        val updatedAt: Long = 0L
    )

    private val _selectedBusStop = MutableStateFlow<SelectedStopState?>(null)
    val selectedBusStop: StateFlow<SelectedStopState?> = _selectedBusStop

    private val _allBusStops = MutableStateFlow<List<BusStop>>(emptyList())
    /** As paragens no mapa: todas, sempre (os filtros escolhem os veículos, não as paragens). */
    val allBusStops: StateFlow<List<BusStop>> = _allBusStops
    private var allBusStopsLoadStarted = false

    // Declarados antes do init: as propriedades são inicializadas por ordem, e o init já as usa
    private var friendsUid: String? = null
    private var cleared = false

    // ── Comboios CP no mapa ─────────────────────────────────────────────────────

    private val _mapFilters = MutableStateFlow(com.example.metrotime.prefs.Prefs.getMapFilters(application))
    val mapFilters: StateFlow<com.example.metrotime.prefs.Prefs.MapFilters> = _mapFilters

    private val _allCpTrains = MutableStateFlow<List<CpMapTrain>>(emptyList())
    /** As paragens de cada comboio (cp_routes), por número. */
    private val _cpRoutes = MutableStateFlow<Map<Int, com.example.metrotime.data.cp.CpRoute>>(emptyMap())
    /** Os comboios com posição calculada pela mesma conta do painel, por número (os outros ficam no GPS). */
    private val _cpLive = MutableStateFlow<Map<Int, com.example.metrotime.data.cp.CpLiveTrain>>(emptyMap())
    val cpLive: StateFlow<Map<Int, com.example.metrotime.data.cp.CpLiveTrain>> = _cpLive
    /** Percursos já calculados (traçado e paragens nele), por comboio, dia e paragens. */
    private val cpGeometryCache = HashMap<String, com.example.metrotime.data.cp.CpRouteGeometry?>()
    private val _cpUpdatedAt = MutableStateFlow(0L)
    val cpUpdatedAt: StateFlow<Long> = _cpUpdatedAt

    /** Comboios CP depois dos filtros. */
    val cpTrains: StateFlow<List<CpMapTrain>> = combine(_allCpTrains, _mapFilters) { trains, f ->
        if (!f.cp) emptyList()
        else trains.filter { t ->
            f.showsCpService(t.service) &&
                (!f.cpOnlyDelayed || t.tone == com.example.metrotime.data.cp.CpTone.LATE || t.tone == com.example.metrotime.data.cp.CpTone.VERY_LATE)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Tipos de serviço presentes agora (só esses aparecem nos filtros). */
    val cpServicesPresent: StateFlow<List<String>> = _allCpTrains.map { trains ->
        val present = trains.map { it.service }.toSet()
        // A Fertagus ("FT") anda com os comboios: aparece sempre como tipo nos filtros
        com.example.metrotime.data.cp.CpService.ORDER.filter { it in present || it == com.example.metrotime.data.cp.CpService.FT }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(com.example.metrotime.data.cp.CpService.FT))

    private val _selectedCpNumber = MutableStateFlow<Int?>(null)
    val selectedCpNumber: StateFlow<Int?> = _selectedCpNumber
    val selectedCpTrain: StateFlow<CpMapTrain?> = combine(_selectedCpNumber, _allCpTrains) { n, trains ->
        n?.let { number -> trains.find { it.number == number } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class CpTripState(
        val number: Int,
        val trip: com.example.metrotime.data.cp.CpTripDetail? = null,
        val runDate: java.time.LocalDate? = null,
        val loading: Boolean = true,
        val geometry: com.example.metrotime.data.cp.CpRouteGeometry? = null
    )
    private val _cpTrip = MutableStateFlow<CpTripState?>(null)
    val cpTrip: StateFlow<CpTripState?> = _cpTrip

    /** Pedidos à Activity para centrar o mapa (lat, lon, zoom). */
    // Canal (não SharedFlow): um pedido feito antes de o mapa estar pronto espera por ele
    private val _cameraRequests = kotlinx.coroutines.channels.Channel<Triple<Double, Double, Double>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val cameraRequests: kotlinx.coroutines.flow.Flow<Triple<Double, Double, Double>> = _cameraRequests.receiveAsFlow()

    private var pendingCpFocus: Int? = null
    private var cpTripJob: Job? = null
    private val screenVisible = MutableStateFlow(true)
    private val lastCpFix = mutableMapOf<Int, Pair<Double, Double>>()
    private val cpStationsByCode: Map<String, com.example.metrotime.data.cp.CpNetwork.Station> by lazy {
        runCatching { com.example.metrotime.data.cp.CpNetwork.stations(getApplication()).associateBy { it.code } }.getOrDefault(emptyMap())
    }
    private val lastCpBearing = mutableMapOf<Int, Double>()

    fun setScreenVisible(visible: Boolean) { screenVisible.value = visible }

    fun updateMapFilters(transform: (com.example.metrotime.prefs.Prefs.MapFilters) -> com.example.metrotime.prefs.Prefs.MapFilters) {
        val next = transform(_mapFilters.value)
        _mapFilters.value = next
        com.example.metrotime.prefs.Prefs.setMapFilters(getApplication(), next)
    }

    fun selectCpTrain(number: Int?) {
        _selectedCpNumber.value = number
        cpTripJob?.cancel()
        if (number == null) {
            _cpTrip.value = null
            return
        }
        closeRegional()
        // Um comboio CP aberto fecha as seleções do metro e das paragens
        _selectedMetro.value = null
        _selectedStation.value = null
        _selectedBusStop.value = null
        _cpTrip.value = CpTripState(number)
        val runDate = _allCpTrains.value.find { it.number == number }?.runDate
        cpTripJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val (trip, date) = com.example.metrotime.data.cp.CpRepository.getTripDetail(number, runDate)
                    // Traçado e paragens nele: aqui, fora da thread principal, para o painel e o mapa
                    val geometry = runCatching { cpRouteGeometry(getApplication(), number, trip.trainStops.orEmpty()) }.getOrNull()
                    _cpTrip.value = CpTripState(number, trip, date, loading = false, geometry = geometry)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _cpTrip.value = _cpTrip.value?.copy(loading = false)
                }
                delay(30_000)
            }
        }
    }

    /** Centra o mapa num ponto (paragem aberta a partir do separador Autocarros). */
    fun requestCamera(lat: Double, lon: Double, zoom: Double = 16.5) {
        _cameraRequests.trySend(Triple(lat, lon, zoom))
    }

    /** Abre o mapa já centrado num comboio CP (vindo do detalhe do comboio). */
    fun focusCpTrain(number: Int) {
        pendingCpFocus = number
        tryFocusPendingCp()
    }

    private fun tryFocusPendingCp() {
        val number = pendingCpFocus ?: return
        val train = _allCpTrains.value.find { it.number == number } ?: return
        pendingCpFocus = null
        // Garante que os filtros não o escondem
        val f = _mapFilters.value
        if (!f.cp || !f.showsCpService(train.service) || f.cpOnlyDelayed) {
            updateMapFilters {
                val shown = if (it.shownCpServices.isEmpty()) it.shownCpServices else it.shownCpServices + train.service
                it.copy(cp = true, shownCpServices = shown, cpOnlyDelayed = false)
            }
        }
        selectCpTrain(number)
        _cameraRequests.trySend(Triple(train.lat, train.lon, 11.0))
    }

    /**
     * Comboios CP em tempo real: com o mapa à vista, escuta o Firebase e atualiza logo que a Cloud Function escreve
     * (antes perguntava de 30 em 30 s). Com o ecrã escondido larga a ligação; ao voltar, recebe logo o mais recente.
     */
    private fun startCpPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            screenVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                launch {
                    com.example.metrotime.data.cp.CpRepository.liveRoutes()
                        .retry { e ->
                            if (e is kotlinx.coroutines.CancellationException) return@retry false
                            e.printStackTrace()
                            delay(5_000)
                            true
                        }
                        .collect { _cpRoutes.value = it }
                }
                com.example.metrotime.data.cp.CpRepository.liveVehicles()
                    .retry { e ->
                        if (e is kotlinx.coroutines.CancellationException) return@retry false
                        e.printStackTrace()
                        delay(5_000)
                        true
                    }
                    .collect { update ->
                        _allCpTrains.value = update.vehicles.mapNotNull { toMapTrain(it, update.dataAt) }
                        // A hora dos dados (a da Cloud Function), e não a da leitura
                        _cpUpdatedAt.value = update.dataAt
                        withContext(Dispatchers.Main) { tryFocusPendingCp() }
                    }
            }
        }
    }

    /** Monta os comboios com posição calculada sempre que mudam os comboios, os percursos ou o comboio aberto. */
    private fun startCpLive() {
        viewModelScope.launch(Dispatchers.Default) {
            combine(_allCpTrains, _cpRoutes, _cpTrip) { trains, routes, trip -> Triple(trains, routes, trip) }
                .collectLatest { (trains, routes, trip) ->
                    _cpLive.value = runCatching { buildCpLive(trains, routes, trip) }.getOrElse {
                        it.printStackTrace()
                        emptyMap()
                    }
                }
        }
    }

    private fun buildCpLive(
        trains: List<CpMapTrain>,
        routes: Map<Int, com.example.metrotime.data.cp.CpRoute>,
        trip: CpTripState?
    ): Map<Int, com.example.metrotime.data.cp.CpLiveTrain> {
        val app = getApplication<Application>()
        com.example.metrotime.data.cp.CpNetwork.ensureLoaded(app)
        val out = HashMap<Int, com.example.metrotime.data.cp.CpLiveTrain>()
        val used = HashSet<String>()
        for (t in trains) {
            // O comboio aberto: com o percurso da CP, o mesmo do painel
            if (trip != null && trip.number == t.number) {
                cpLiveOf(trip, t)?.let { out[t.number] = it }
                if (out.containsKey(t.number)) continue
            }
            val route = routes[t.number] ?: continue
            if (t.runDate != null && t.runDate != route.runDate.toString()) continue
            val key = "${t.number}|${route.runDate}|${route.stops.joinToString(",") { it.station?.code.orEmpty() }}"
            used += key
            val geometry = cpGeometryCache.getOrPut(key) {
                runCatching { com.example.metrotime.data.cp.CpLive.routeGeometry(t.number, route, ::cpStationPoint) }.getOrNull()
            } ?: continue
            val input = com.example.metrotime.data.cp.CpLiveInput(
                number = t.number,
                runDate = t.runDate,
                lastStationCode = t.lastStationCode,
                status = t.status,
                delaySeconds = t.delaySeconds,
                gps = t.gpsLat to t.gpsLon,
                gpsAtMs = t.gpsAt,
                passCode = t.passCode,
                passTime = t.passTime,
                passLeft = t.passLeft,
                dataAtMs = t.dataAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
            com.example.metrotime.data.cp.CpLive.fromRoute(input, route, geometry, ::cpStationPoint)?.let { out[t.number] = it }
        }
        cpGeometryCache.keys.retainAll(used)
        return out
    }

    private fun toMapTrain(v: com.example.metrotime.data.cp.CpVehicle, dataAt: Long): CpMapTrain? {
        val lat = v.latitude?.toDoubleOrNull() ?: return null
        val lon = v.longitude?.toDoubleOrNull() ?: return null
        if (lat == 0.0 || lon == 0.0) return null
        val here = lat to lon
        // Rumo: o da Cloud Function; senão, calculado a partir da posição anterior (se andou > 80 m)
        val previous = lastCpFix[v.trainNumber]
        val bearing = v.bearing ?: if (previous != null && com.example.metrotime.data.cp.CpTrack.metres(previous, here) > 80) {
            com.example.metrotime.data.cp.CpTrack.bearing(previous, here).also { lastCpBearing[v.trainNumber] = it }
        } else lastCpBearing[v.trainNumber]
        if (previous == null || com.example.metrotime.data.cp.CpTrack.metres(previous, here) > 80) lastCpFix[v.trainNumber] = here
        // O GPS chega atrasado: nunca atrás da última estação da CP (a mesma regra do comboio aberto, para não
        // saltar ao tocar nele)
        val stations = cpStationsByCode
        val last = v.lastStation?.let { stations[it] }?.let { it.lat to it.lon }
        val destination = v.destination?.code?.let { stations[it] }?.let { it.lat to it.lon }
        val track = runCatching { com.example.metrotime.data.cp.CpNetwork.trackForTrain(getApplication(), v.trainNumber) }.getOrNull()
        val place = com.example.metrotime.data.cp.CpMarker.place(
            here, v.status, last, lastIsDestination = v.lastStation != null && v.lastStation == v.destination?.code,
            destination = destination, track = track
        )
        // No carril: o ponto passa para cima do traçado do comboio (nunca na estrada ou no rio) e a seta segue-o
        val onTrack = track?.let { tr ->
            val hit = tr.locate(place.point)
            val p = tr.pointAt(hit.s)
            if (com.example.metrotime.data.cp.CpTrack.metres(p, place.point) <= 1_000.0) p to tr.bearingAt(hit.s) else null
        }
        val shown = onTrack?.first ?: place.point
        return CpMapTrain(
            number = v.trainNumber,
            lat = shown.first,
            lon = shown.second,
            bearing = if (place.atStation) null else onTrack?.second ?: bearing,
            service = com.example.metrotime.data.cp.CpService.shortName(v.service?.code),
            serviceName = v.service?.designation,
            delaySeconds = v.delay,
            status = v.status,
            origin = v.origin?.designation?.let { com.example.metrotime.data.cp.formatStationName(it) },
            destination = v.destination?.designation?.let { com.example.metrotime.data.cp.formatStationName(it) },
            lastStationCode = v.lastStation,
            runDate = v.runDate?.take(10),
            gpsLat = lat,
            gpsLon = lon,
            gpsAt = v.timestamp?.takeIf { it > 0 },
            dataAt = dataAt,
            passCode = v.passCode,
            passTime = v.passTime,
            passLeft = v.passLeft
        )
    }

    // ── Outros operadores em direto (GO Hub da TML e Eleven Systems) ─────────────
    // Declarados antes do init (que já arranca o ciclo)

    /** Os veículos de cada região (LISBOA, PORTO, NORTE, CENTRO), do servidor ou, com ele parado, das fontes. */
    private val _liveByArea = MutableStateFlow<Map<String, List<com.example.metrotime.data.regional.RegionalVehicle>>>(emptyMap())
    /** Quando chegou o último dado do servidor de cada região (relógio do telemóvel). */
    private val serverSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Os metros do Porto onde o horário os põe agora (recalculados a cada segundo com o mapa à vista). */
    private val _scheduledVehicles = MutableStateFlow<List<com.example.metrotime.data.regional.RegionalVehicle>>(emptyList())

    /** Todos os autocarros da Carris e da Carris Metropolitana em direto (pontos no mapa), lidos pela app. */
    private val _cityBuses = MutableStateFlow<List<LiveBus>>(emptyList())
    /** O mapa está sobre a área de Lisboa (só aí vale a pena ler os autocarros da Carris e da CM). */
    private val _mapNearLisbon = MutableStateFlow(true)

    /** Onde está o mapa (a cada paragem da câmara). */
    fun setMapCamera(lat: Double, lon: Double, zoom: Double) {
        val far = com.example.metrotime.data.cp.CpTrack.metres(lat to lon, 38.75 to -9.15)
        _mapNearLisbon.value = zoom >= 8.5 && far < 75_000.0
    }

    private val tramLine = Regex("\\d+E")

    /** Um autocarro da Carris ou da CM como veículo da camada (o mesmo ponto, a mesma bolinha, os mesmos filtros). */
    private fun cityVehicle(b: LiveBus): com.example.metrotime.data.regional.RegionalVehicle {
        val cm = b.operator == "carris_metropolitana"
        val base = if (cm) com.example.metrotime.data.regional.RegionalOperators.CM else com.example.metrotime.data.regional.RegionalOperators.CARRIS
        val op = if (!cm && tramLine.matches(b.routeShortName)) base.copy(kind = com.example.metrotime.data.regional.VehicleKind.TRAM) else base
        return com.example.metrotime.data.regional.RegionalVehicle(
            operator = op,
            vehicleId = b.vehicleId,
            lat = b.lat,
            lon = b.lon,
            line = b.routeShortName,
            color = com.example.metrotime.data.bus.BusColors.forRoute(b.routeShortName, b.operator),
            status = b.currentStatus.ifEmpty { null },
            destination = b.destination.ifEmpty { null }
        )
    }

    /** Todos os veículos das outras redes, sem filtros (para abrir um pela chave). */
    private val allRegional: StateFlow<List<com.example.metrotime.data.regional.RegionalVehicle>> =
        combine(_liveByArea, _scheduledVehicles, _cityBuses) { byArea, scheduled, city ->
            byArea.values.flatten() + scheduled + city.map(::cityVehicle)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** MobiCascais, TCB, MTS, barcos, STCP, Covilhã, Viseu, Guimarães… (a Fertagus vai com os comboios). */
    val regionalVehicles: StateFlow<List<com.example.metrotime.data.regional.RegionalVehicle>> =
        combine(allRegional, _mapFilters) { all, f ->
            all.filter { it.operator.kind != com.example.metrotime.data.regional.VehicleKind.TRAIN && f.showsRegional(it) }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Comboios da Fertagus: com o filtro "Comboios" (e o tipo "FT"), desenhados como os da CP. */
    val fertagusTrains: StateFlow<List<com.example.metrotime.data.regional.RegionalVehicle>> =
        combine(allRegional, _mapFilters) { all, f ->
            if (!f.cp || !f.showsCpService("FT") || "tml:7NTB1" in f.hiddenOperators) emptyList()
            else all.filter {
                it.operator.kind == com.example.metrotime.data.regional.VehicleKind.TRAIN &&
                    (!f.cpOnlyDelayed || (it.delaySeconds ?: 0) >= 120)
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    data class RegionalSelection(
        val vehicle: com.example.metrotime.data.regional.RegionalVehicle,
        val trip: com.example.metrotime.data.regional.RegionalTrip? = null,
        val loading: Boolean = true
    )
    private val _selectedRegional = MutableStateFlow<RegionalSelection?>(null)
    val selectedRegional: StateFlow<RegionalSelection?> = _selectedRegional
    private var regionalTripJob: Job? = null

    /** As regiões a ouvir com estes filtros: só as que têm alguma empresa à vista (Lisboa também pela Fertagus). */
    private fun activeAreas(f: com.example.metrotime.prefs.Prefs.MapFilters): Set<String> {
        val ops = com.example.metrotime.data.regional.RegionalOperators.ALL
        fun visibleIn(area: com.example.metrotime.data.regional.RegionalArea) = f.regional && ops.any {
            it.area == area && it.source != com.example.metrotime.data.regional.RegionalSource.NONE &&
                it.source != com.example.metrotime.data.regional.RegionalSource.SCHEDULE &&
                it.source != com.example.metrotime.data.regional.RegionalSource.CITY &&
                it.kind != com.example.metrotime.data.regional.VehicleKind.TRAIN && it.key !in f.hiddenOperators
        }
        return buildSet {
            if (visibleIn(com.example.metrotime.data.regional.RegionalArea.LISBOA) ||
                (f.cp && f.showsCpService("FT") && "tml:7NTB1" !in f.hiddenOperators)) add("LISBOA")
            if (visibleIn(com.example.metrotime.data.regional.RegionalArea.PORTO)) add("PORTO")
            if (visibleIn(com.example.metrotime.data.regional.RegionalArea.NORTE)) add("NORTE")
            if (visibleIn(com.example.metrotime.data.regional.RegionalArea.CENTRO)) add("CENTRO")
        }
    }

    /** Lê uma região diretamente das fontes (só quando o servidor está parado). */
    private suspend fun directArea(area: String): List<com.example.metrotime.data.regional.RegionalVehicle> =
        when (area) {
            "LISBOA" -> com.example.metrotime.data.regional.RegionalRepository.tml()
            "PORTO" -> com.example.metrotime.data.regional.RegionalRepository.stcp()
            else -> com.example.metrotime.data.regional.RegionalRepository.eleven().filter { it.operator.area.name == area }
        }

    /**
     * Só com o mapa à vista: ouve no servidor (regional_live/<região>, gravado de 10 em 10 s) as regiões com empresas à
     * vista. Se o servidor não disser nada há 2 min, lê essa região diretamente das fontes (como antes), de 20 em 20 s,
     * até ele voltar. Os horários novos que o servidor gera de madrugada descarregam-se uma vez ao abrir o mapa.
     */
    private fun startRegionalPolling() {
        com.example.metrotime.data.regional.RegionalRepository.init(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            combine(screenVisible, _mapFilters) { visible, f -> if (visible) activeAreas(f) else emptySet() }
                .distinctUntilChanged()
                .collectLatest { areas ->
                    // As regiões que deixaram de se ver saem logo do mapa
                    _liveByArea.update { current -> current.filterKeys { it in areas } }
                    if (areas.isEmpty()) return@collectLatest
                    coroutineScope {
                        for (area in areas) {
                            launch {
                                com.example.metrotime.data.regional.RegionalRepository.serverArea(area)
                                    .retry { e ->
                                        if (e is kotlinx.coroutines.CancellationException) return@retry false
                                        e.printStackTrace()
                                        delay(10_000)
                                        true
                                    }
                                    .collect { snap ->
                                        serverSeenAt[area] = System.currentTimeMillis()
                                        _liveByArea.update { it + (area to snap.vehicles) }
                                    }
                            }
                            launch {
                                // Reserva: servidor parado (ou sem rede para ele) há 2 min → as fontes diretamente
                                delay(15_000)
                                while (isActive) {
                                    val seen = serverSeenAt[area] ?: 0L
                                    if (System.currentTimeMillis() - seen > 120_000) {
                                        try {
                                            val direct = directArea(area)
                                            _liveByArea.update { it + (area to direct) }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            e.printStackTrace()
                                        }
                                        delay(20_000)
                                    } else delay(15_000)
                                }
                            }
                        }
                    }
                }
        }
        // Horários novos do servidor (uma vez por abertura do mapa)
        viewModelScope.launch(Dispatchers.IO) {
            if (com.example.metrotime.data.regional.RegionalRepository.syncPacks()) reloadPackStops()
        }
        // O veículo aberto acompanha a posição nova
        viewModelScope.launch {
            allRegional.collect { list ->
                val current = _selectedRegional.value ?: return@collect
                val fresh = list.firstOrNull { it.key == current.vehicle.key }
                if (fresh != null) _selectedRegional.value = current.copy(vehicle = fresh)
            }
        }
    }

    /** Abre um veículo da camada: fecha o resto e vai buscando a viagem (paragens e minutos) de 30 em 30 s. */
    fun selectRegional(key: String?) {
        if (key == null) {
            closeRegional()
            return
        }
        // Também os que os filtros escondem (aberto a partir das chegadas de uma paragem)
        val vehicle = allRegional.value.firstOrNull { it.key == key }
            ?: com.example.metrotime.data.regional.RegionalRepository.liveVehicle(key)
            ?: return
        // Carris e Carris Metropolitana: o painel delas (paragens e horas da viagem)
        if (vehicle.operator.source == com.example.metrotime.data.regional.RegionalSource.CITY) {
            val cm = vehicle.operator.key == com.example.metrotime.data.regional.RegionalOperators.CM.key
            val bus = _cityBuses.value.firstOrNull {
                it.vehicleId == vehicle.vehicleId && (it.operator == "carris_metropolitana") == cm
            } ?: return
            openLiveBus(bus)
            return
        }
        _selectedMetro.value = null
        _selectedStation.value = null
        selectCpTrain(null)
        clearSelectedBusStop()
        val cached = com.example.metrotime.data.regional.RegionalRepository.cachedTrip(vehicle)
        val hasTrip = vehicle.operator.source != com.example.metrotime.data.regional.RegionalSource.NONE
        _selectedRegional.value = RegionalSelection(vehicle, cached, loading = hasTrip && cached == null)
        regionalTripJob?.cancel()
        if (!hasTrip) return
        regionalTripJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val shown = _selectedRegional.value ?: break
                val trip = try {
                    com.example.metrotime.data.regional.RegionalRepository.trip(shown.vehicle)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
                _selectedRegional.value = _selectedRegional.value?.let { it.copy(trip = trip ?: it.trip, loading = false) }
                delay(30_000)
            }
        }
    }

    private fun closeRegional() {
        regionalTripJob?.cancel()
        regionalTripJob = null
        _selectedRegional.value = null
    }

    // ── Filtros das outras redes (painel "O que ver no mapa") ─────────────────

    /** Todos os veículos das outras redes, sem filtros (contagens e linhas com veículos no painel). */
    val regionalAll: StateFlow<List<com.example.metrotime.data.regional.RegionalVehicle>> get() = allRegional

    fun setOperatorShown(key: String, shown: Boolean) = updateMapFilters { f ->
        f.copy(
            regional = f.regional || shown,
            hiddenOperators = if (shown) f.hiddenOperators - key else f.hiddenOperators + key
        )
    }

    /**
     * Uma região num toque: mostra as empresas dela e esconde as outras; null = todas. O Metro de Lisboa vai com a
     * região de Lisboa; os comboios CP (todo o país) ficam como estão.
     */
    fun showOnlyArea(area: com.example.metrotime.data.regional.RegionalArea?) = updateMapFilters { f ->
        val hidden = if (area == null) emptySet()
        else com.example.metrotime.data.regional.RegionalOperators.ALL.filter { it.area != area }.mapTo(HashSet()) { it.key }
        f.copy(
            regional = true,
            hiddenOperators = hidden,
            metro = area == null || area == com.example.metrotime.data.regional.RegionalArea.LISBOA
        )
    }

    /** As linhas a ver de uma empresa (vazio = todas); escolher linhas também a põe à vista. */
    fun setOperatorLines(key: String, lines: Set<String>) = updateMapFilters { f ->
        f.copy(
            regional = true,
            hiddenOperators = f.hiddenOperators - key,
            shownLines = f.shownLines.filterNot { it.startsWith("$key|") }.toSet() + lines.map { "$key|$it" }
        )
    }

    /** "Repor" no painel: tudo à vista outra vez, como de origem. */
    fun resetRegionalFilters() = updateMapFilters { com.example.metrotime.prefs.Prefs.MapFilters() }

    /**
     * Carris e Carris Metropolitana: todos os autocarros a cada 20 s (~100 KB), só com o mapa à vista sobre a área de
     * Lisboa, sem uma linha pesquisada aberta e com elas ligadas nos filtros. Se uma fonte falhar, fica a lista anterior.
     */
    private fun startCityBuses() {
        viewModelScope.launch(Dispatchers.IO) {
            val carrisKey = com.example.metrotime.data.regional.RegionalOperators.CARRIS.key
            val cmKey = com.example.metrotime.data.regional.RegionalOperators.CM.key
            combine(screenVisible, _mapFilters, _mapNearLisbon, _isBusMode) { visible, f, near, busMode ->
                val on = visible && near && !busMode && f.regional
                (on && carrisKey !in f.hiddenOperators) to (on && cmKey !in f.hiddenOperators)
            }.distinctUntilChanged().collectLatest { (carris, cm) ->
                if (!carris && !cm) {
                    // Com o mapa à vista mas longe de Lisboa (ou desligadas): os pontos antigos saem
                    if (screenVisible.value) _cityBuses.value = emptyList()
                    return@collectLatest
                }
                while (isActive) {
                    val (carrisList, cmList) = coroutineScope {
                        val a = async {
                            if (!carris) emptyList()
                            else runCatching { BusMapRepository.getLiveBusesCarrisUrban(getApplication(), null) }.getOrNull()
                        }
                        val b = async {
                            if (!cm) emptyList() else runCatching { BusMapRepository.getLiveBusesForLines(null) }.getOrNull()
                        }
                        a.await() to b.await()
                    }
                    val old = _cityBuses.value
                    _cityBuses.value =
                        (carrisList?.takeIf { it.isNotEmpty() || !carris } ?: old.filter { it.operator != "carris_metropolitana" }) +
                            (cmList?.takeIf { it.isNotEmpty() || !cm } ?: old.filter { it.operator == "carris_metropolitana" })
                    delay(20_000)
                }
            }
        }
    }

    init {
        startPolling()
        startFriendsTracking()
        startCpPolling()
        startCpLive()
        startRegionalPolling()
        startCityBuses()
        // As paragens aparecem sempre, logo ao abrir o mapa (antes só depois de abrir a pesquisa)
        loadAllBusStops()
    }

    /**
     * Load all Carris urban bus stops for display on the map.
     */
    fun loadAllBusStops() {
        if (allBusStopsLoadStarted) return
        allBusStopsLoadStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.example.metrotime.data.bus.CarrisGtfsData.ensureLoaded(getApplication())
                val carrisStops = com.example.metrotime.data.bus.CarrisGtfsData.stops.map { entry ->
                    BusStop(
                        id = entry.id,
                        name = entry.name,
                        lat = entry.lat,
                        lon = entry.lon,
                        operator = "carris"
                    )
                }
                _allBusStops.value = carrisStops

                // Paragens das outras redes (MobiCascais, TCB, MTS, Fertagus, barcos, STCP, Metro do Porto)
                com.example.metrotime.data.regional.RegionalRepository.init(getApplication())
                val packStops = com.example.metrotime.data.regional.RegionalRepository.packStops().map { (op, s) ->
                    BusStop(id = s.id, name = s.name, lat = s.lat, lon = s.lon, operator = "pack:${op.pack}")
                }
                _allBusStops.value = carrisStops + packStops

                // Also fetch CM stops in the background
                val cmStops = BusMapRepository.getAllCmStops()
                if (cmStops.isNotEmpty()) {
                    _allBusStops.value = carrisStops + packStops + cmStops
                }

                // Paragens das redes do Norte e do Centro (Covilhã, Viseu, Guimarães, Vila Real…), guardadas uma semana
                val elevenStops = com.example.metrotime.data.regional.RegionalRepository.elevenStops().map { (op, s) ->
                    BusStop(id = s.id, name = s.name, lat = s.lat, lon = s.lon, operator = op.key)
                }
                if (elevenStops.isNotEmpty()) _allBusStops.update { it.filterNot { s -> s.operator.startsWith("eleven:") } + elevenStops }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Os horários do servidor mudaram: as paragens das outras redes são lidas de novo. */
    private fun reloadPackStops() {
        if (!allBusStopsLoadStarted) return
        val packStops = com.example.metrotime.data.regional.RegionalRepository.packStops().map { (op, s) ->
            BusStop(id = s.id, name = s.name, lat = s.lat, lon = s.lon, operator = "pack:${op.pack}")
        }
        _allBusStops.update { current -> current.filterNot { it.operator.startsWith("pack:") } + packStops }
    }

    /**
     * Select a bus stop and load its arrivals.
     */
    fun selectBusStop(stop: BusStop) {
        _selectedBusStop.value = SelectedStopState(stop = stop, loading = true)
        closeRegional()
        stopArrivalsJob?.cancel()
        // Chegadas atualizadas de 30 em 30 s enquanto a paragem está aberta
        stopArrivalsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val arrivals = BusMapRepository.getStopArrivals(
                        getApplication(), stop.id, stop.operator,
                        aheadSecs = BusMapRepository.STOP_SHEET_WINDOW_S, perLine = Int.MAX_VALUE
                    )
                    _selectedBusStop.value = SelectedStopState(
                        stop = stop, arrivals = arrivals, loading = false, updatedAt = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val previous = _selectedBusStop.value
                    _selectedBusStop.value = SelectedStopState(
                        stop = stop,
                        arrivals = previous?.arrivals.orEmpty(),
                        loading = false,
                        updatedAt = previous?.updatedAt ?: 0L
                    )
                }
                delay(30_000)
            }
        }
    }

    fun clearSelectedBusStop() {
        stopArrivalsJob?.cancel()
        _selectedBusStop.value = null
    }

    // ── Bus search functions ────────────────────────────────────────────────────

    fun toggleBusSearch() {
        _isBusSearchOpen.value = !_isBusSearchOpen.value
        if (!_isBusSearchOpen.value) {
            // Closing search but keep results if a route is selected
            if (_selectedBusRoute.value == null) {
                _busSearchQuery.value = ""
                _busSearchResults.value = emptyList()
            }
        }
    }

    fun openBusSearch() {
        loadAllBusStops()
        _isBusSearchOpen.value = true
    }

    fun searchBuses(query: String) {
        _busSearchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _busSearchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // Debounce
            try {
                val results = BusMapRepository.searchRoutes(getApplication(), query)
                _busSearchResults.value = results
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun selectBusRouteByShortName(shortName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isBusLoading.value = true
                val results = BusMapRepository.searchRoutes(getApplication(), shortName)
                val exactMatch = results.find { it.shortName.equals(shortName, ignoreCase = true) }
                if (exactMatch != null) {
                    selectBusRoute(exactMatch)
                } else {
                    _isBusLoading.value = false
                }
            } catch (e: Exception) {
                _isBusLoading.value = false
                e.printStackTrace()
            }
        }
    }

    // Internal storage for ALL buses (both directions) - filtered version exposed via liveBuses
    private val _allLiveBuses = MutableStateFlow<List<LiveBus>>(emptyList())

    fun selectBusRoute(route: BusRoute) {
        loadAllBusStops()
        com.example.metrotime.prefs.Prefs.addRecentBusLine(getApplication(), route.operator, route.shortName, route.longName, route.color)
        _selectedBusRoute.value = route
        _selectedDirection.value = 0
        _isBusMode.value = true
        _isBusSearchOpen.value = false
        _busSearchResults.value = emptyList()
        _isBusLoading.value = true

        // Dismiss any metro selections
        _selectedMetro.value = null
        _selectedStation.value = null
        closeRegional()

        // Fetch live buses immediately
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lineIds = setOf(route.shortName, route.routeId)
                val buses = if (route.operator == "carris_metropolitana") {
                    BusMapRepository.getLiveBusesForLines(lineIds)
                } else {
                    val routeIds = com.example.metrotime.data.bus.CarrisGtfsData.routes
                        .filter { it.value.shortName == route.shortName }
                        .keys.toSet()
                    BusMapRepository.getLiveBusesCarrisUrban(getApplication(), routeIds)
                }
                _allLiveBuses.value = buses
                _liveBuses.value = filterBusesByDirection(buses, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Load stops & shape for default direction 0
        loadRouteDetails(route, 0)

        // Start polling for live bus updates
        startBusPolling(route)
    }

    fun setDirection(direction: Int) {
        _selectedDirection.value = direction
        // Re-filter buses by new direction
        _liveBuses.value = filterBusesByDirection(_allLiveBuses.value, direction)
        val route = _selectedBusRoute.value ?: return
        loadRouteDetails(route, direction)
    }

    /**
     * Filter buses to only show those traveling in the selected direction.
     * Buses with unknown direction (-1) are included in both directions.
     */
    private fun filterBusesByDirection(buses: List<LiveBus>, direction: Int): List<LiveBus> {
        return buses.filter { bus ->
            bus.direction == direction || bus.direction == -1
        }
    }

    private fun loadRouteDetails(route: BusRoute, direction: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusLoading.value = true
            try {
                if (route.operator == "carris_metropolitana") {
                    // Find first pattern for this direction (split by '_' and check middle segment)
                    val patternId = route.patterns.find { pat ->
                        val parts = pat.split("_")
                        parts.size >= 2 && parts[1] == direction.toString()
                    } ?: route.patterns.firstOrNull() ?: "${route.routeId}_${direction}_1"

                    val stops = BusMapRepository.getCmPatternStops(patternId)
                    _busRouteStops.value = stops

                    val shape = BusMapRepository.getCmPatternShape(patternId)
                    _busRouteShape.value = shape
                        ?: BusMapRepository.buildShapeFromStops(stops)
                } else {
                    // ── Carris urban: use GTFS shapes + ordered stop sequences ──────
                    // Direction 0/1 maps to direction_id 0/1 WITHIN the primary variant
                    val gtfs = com.example.metrotime.data.bus.CarrisGtfsData
                    gtfs.ensureLoaded(getApplication())

                    // Always use the PRIMARY variant (_0 suffix)
                    val primaryVariantId = gtfs.routes
                        .filter { it.value.shortName == route.shortName }
                        .keys.sorted()
                        .firstOrNull()

                    if (primaryVariantId != null) {
                        // Key format: "route_id_direction" e.g. "204_0_0" or "204_0_1"
                        val key = "${primaryVariantId}_${direction}"

                        // 1. Get ordered stops
                        val orderedStopIds = gtfs.stopOrder[key]
                        val stopsById = gtfs.stops.associateBy { it.id }

                        val stops = if (orderedStopIds != null) {
                            orderedStopIds.mapNotNull { stopId ->
                                stopsById[stopId]?.let { entry ->
                                    com.example.metrotime.data.bus.BusStop(
                                        id = entry.id,
                                        name = entry.name,
                                        lat = entry.lat,
                                        lon = entry.lon,
                                        operator = "carris"
                                    )
                                }
                            }
                        } else {
                            BusMapRepository.getCarrisRouteStops(getApplication(), primaryVariantId)
                        }
                        _busRouteStops.value = stops

                        // 2. Get road-following shape
                        val shape = gtfs.shapes[key]
                        _busRouteShape.value = shape
                            ?: BusMapRepository.buildShapeFromStops(stops)
                    } else {
                        _busRouteStops.value = emptyList()
                        _busRouteShape.value = emptyList()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isBusLoading.value = false
            }
        }
    }

    fun clearBusSearch() {
        busPollingJob?.cancel()
        _selectedBusRoute.value = null
        _selectedDirection.value = 0
        _isBusMode.value = false
        _isBusSearchOpen.value = false
        _busSearchQuery.value = ""
        _busSearchResults.value = emptyList()
        _liveBuses.value = emptyList()
        _allLiveBuses.value = emptyList()
        _busRouteStops.value = emptyList()
        _busRouteShape.value = emptyList()
        _isBusLoading.value = false
        _selectedLiveBus.value = null
    }

    /**
     * Select a specific live bus and fetch its schedule.
     */
    fun selectLiveBus(vehicleId: String) {
        val bus = _liveBuses.value.find { it.vehicleId == vehicleId }
            ?: _allLiveBuses.value.find { it.vehicleId == vehicleId }
            ?: return
        openLiveBus(bus)
    }

    /** Abre o painel de um autocarro da Carris ou da CM (da linha pesquisada ou um dos pontos do mapa). */
    private fun openLiveBus(bus: LiveBus) {
        _selectedMetro.value = null
        _selectedStation.value = null
        selectCpTrain(null)
        closeRegional()
        clearSelectedBusStop()
        _selectedLiveBus.value = SelectedLiveBusState(bus = bus, loading = true)

        liveBusJob?.cancel()
        liveBusJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val schedule = if (bus.operator == "carris_metropolitana" && bus.patternId.isNotEmpty()) {
                    BusMapRepository.getCmBusSchedule(bus.patternId, bus.tripId, bus.currentStopId)
                } else {
                    // Carris urban: use lightweight GTFS schedule offsets (27KB asset)
                    BusMapRepository.getCarrisUrbanBusSchedule(
                        getApplication(), bus.routeId, bus.tripId, bus.currentStopId
                    )
                }
                _selectedLiveBus.value = SelectedLiveBusState(
                    bus = bus,
                    schedule = schedule,
                    loading = false,
                    delaySeconds = busDelay(bus, schedule)
                )
                // Com o painel aberto: a posição nova do autocarro (dos pontos ou da linha pesquisada) e o atraso
                while (isActive) {
                    delay(15_000)
                    val shown = _selectedLiveBus.value ?: break
                    if (shown.bus.vehicleId != bus.vehicleId) break
                    val fresh = (_cityBuses.value + _liveBuses.value + _allLiveBuses.value)
                        .firstOrNull { it.vehicleId == bus.vehicleId && it.operator == bus.operator } ?: shown.bus
                    val moved = com.example.metrotime.data.bus.reanchorSchedule(shown.schedule, fresh.currentStopId)
                    _selectedLiveBus.value = shown.copy(bus = fresh, schedule = moved, delaySeconds = busDelay(fresh, moved))
                }
            } catch (e: Exception) {
                _selectedLiveBus.value = SelectedLiveBusState(
                    bus = bus,
                    schedule = emptyList(),
                    loading = false
                )
                e.printStackTrace()
            }
        }
    }

    fun clearSelectedLiveBus() {
        liveBusJob?.cancel()
        _selectedLiveBus.value = null
    }

    /**
     * Locate a live bus by its vehicleId, resolve its route, and pre-select it on the map.
     */
    fun selectBusByVehicleId(vehicleId: String, operator: String? = null, line: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusLoading.value = true
            try {
                val liveBus = BusMapRepository.findLiveBusByVehicleId(getApplication(), vehicleId, operator, line)
                if (liveBus == null && line != null) {
                    // Já sem GPS (acabou a viagem, ou saiu do feed): mostra pelo menos a linha
                    selectBusRouteByShortName(line)
                }
                if (liveBus != null) {
                    val busRoute = if (liveBus.operator == "carris_metropolitana") {
                        val cmLines = BusMapRepository.searchRoutes(getApplication(), liveBus.routeShortName)
                        cmLines.firstOrNull { it.operator == "carris_metropolitana" && it.shortName == liveBus.routeShortName }
                            ?: BusRoute(
                                routeId = liveBus.routeId.ifEmpty { liveBus.routeShortName },
                                shortName = liveBus.routeShortName,
                                longName = liveBus.destination.ifEmpty { "Carris Metropolitana" },
                                operator = "carris_metropolitana"
                            )
                    } else {
                        val routeInfo = com.example.metrotime.data.bus.CarrisGtfsData.routes[BusMapRepository.normalizeRouteId(liveBus.routeId)]
                        BusRoute(
                            routeId = liveBus.routeId,
                            shortName = liveBus.routeShortName,
                            longName = routeInfo?.longName ?: liveBus.destination,
                            color = com.example.metrotime.data.bus.BusColors.carris(liveBus.routeShortName),
                            operator = "carris"
                        )
                    }

                    // 1. Select the bus route
                    _selectedBusRoute.value = busRoute
                    _selectedDirection.value = if (liveBus.direction != -1) liveBus.direction else 0
                    _isBusMode.value = true
                    _isBusSearchOpen.value = false

                    // Dismiss metro/train selections
                    _selectedMetro.value = null
                    _selectedStation.value = null

                    // 2. Fetch live buses
                    val lineIds = setOf(busRoute.shortName, busRoute.routeId)
                    val buses = if (busRoute.operator == "carris_metropolitana") {
                        BusMapRepository.getLiveBusesForLines(lineIds)
                    } else {
                        val routeIds = com.example.metrotime.data.bus.CarrisGtfsData.routes
                            .filter { it.value.shortName == busRoute.shortName }
                            .keys.toSet()
                        BusMapRepository.getLiveBusesCarrisUrban(getApplication(), routeIds)
                    }
                    _allLiveBuses.value = buses
                    _liveBuses.value = filterBusesByDirection(buses, _selectedDirection.value)

                    // 3. Load stops & shape details
                    loadRouteDetails(busRoute, _selectedDirection.value)

                    // 4. Start polling for live bus updates
                    startBusPolling(busRoute)

                    // 5. Select individual live bus (pelo id completo, com o prefixo do operador)
                    selectLiveBus(liveBus.vehicleId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isBusLoading.value = false
            }
        }
    }

    private fun startBusPolling(route: BusRoute) {
        busPollingJob?.cancel()
        busPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000) // Refresh every 10 seconds
                try {
                    val lineIds = setOf(route.shortName, route.routeId)
                    val buses = if (route.operator == "carris_metropolitana") {
                        BusMapRepository.getLiveBusesForLines(lineIds)
                    } else {
                        val routeIds = com.example.metrotime.data.bus.CarrisGtfsData.routes
                            .filter { it.value.shortName == route.shortName }
                            .keys.toSet()
                        BusMapRepository.getLiveBusesCarrisUrban(getApplication(), routeIds)
                    }
                    _allLiveBuses.value = buses
                    _liveBuses.value = filterBusesByDirection(buses, _selectedDirection.value)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Os metros: as horas das estações seguintes (o mapa calcula onde estão a cada imagem)
                    if (screenVisible.value || _metroTrains.value.isEmpty()) {
                        _metroTrains.value = fetchMetro().filterNot { com.example.metrotime.data.metro.PortoMetro.isPorto(it.line) } + portoTrains()
                        _onboard.value?.takeIf { it.alarm }?.let { scheduleOnboardAlarm(it) }
                    }

                    // Update selected station arrivals if open
                    _selectedStation.value?.let { currentStation ->
                        try {
                            val newArrivals = if (com.example.metrotime.data.metro.PortoMetro.isPorto(currentStation.id))
                                portoStationArrivals(currentStation.id, currentStation.name)
                            else arrivalsSource.fetchArrivals(currentStation.id)
                            _selectedStation.value = currentStation.copy(arrivals = newArrivals, loading = false)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                    
                    _isLoading.value = false
                } catch (e: Exception) {
                    _isLoading.value = false
                }
                delay(10_000) // Refresh every 10 seconds
            }
        }
        
        // Perturbações nas linhas: a cada minuto (o mapa pinta o troço interrompido)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (screenVisible.value) {
                    runCatching { RemoteArrivalsSource.fetchLineStatus() }.getOrNull()?.let {
                        _metroStatuses.value = it
                        com.example.metrotime.data.alerts.DisruptionLog.record(getApplication(), it)
                    }
                }
                delay(60_000)
            }
        }

        // Os tempos do painel da estação aberta descem entre leituras
        viewModelScope.launch {
            val tickerIntervalSeconds = 3
            while (isActive) {
                delay(tickerIntervalSeconds * 1_000L)
                run {
                    _selectedStation.value?.let { currentStation ->
                        val updatedArrivals = currentStation.arrivals?.let { obj ->
                            obj.copy(arrivals = obj.arrivals.map { arr ->
                                if (arr.minutes > 0) {
                                    arr.copy(minutes = (arr.minutes - tickerIntervalSeconds).coerceAtLeast(0))
                                } else arr
                            })
                        }
                        _selectedStation.value = currentStation.copy(arrivals = updatedArrivals)
                    }
                }
            }
        }
    }

    fun selectStation(id: String?, name: String? = null) {
        if (id == null) {
            _selectedStation.value = null
        } else {
            _selectedStation.value = SelectedStationState(id, name ?: id, null, true)
            _selectedMetro.value = null // dismiss train if station is clicked
            selectCpTrain(null)
            closeRegional()
            
            // fetch immediately
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val arrivals = if (com.example.metrotime.data.metro.PortoMetro.isPorto(id)) portoStationArrivals(id, name ?: id)
                    else arrivalsSource.fetchArrivals(id)
                    _selectedStation.value = _selectedStation.value?.copy(arrivals = arrivals, loading = false)
                } catch (e: Exception) {
                    _selectedStation.value = _selectedStation.value?.copy(loading = false)
                }
            }
        }
    }

    fun startLocationTracking() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        if (locationCallback != null) return // Already tracking

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
                    val map = mapOf(
                        "lat" to loc.latitude,
                        "lon" to loc.longitude,
                        "timestamp" to System.currentTimeMillis()
                    )
                    // updateChildren: setValue apagava o currentTrain/trainDestination escrito pelo serviço
                    db.getReference("locations").child(uid).updateChildren(map)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    fun stopLocationTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            // Sem "Partilhar em background", a posição só deve existir enquanto o mapa está aberto
            if (!com.example.metrotime.prefs.Prefs.isContinuousLocation(getApplication())) {
                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("locations").child(uid).removeValue()
                }
            }
        }
    }

    private fun startFriendsTracking() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        friendsUid = uid
        val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)

        friendsListener = db.getReference("friends").child(uid).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val acceptedFriends = mutableListOf<String>()
                for (child in snapshot.children) {
                    if (child.stringOrNull() == "accepted") {
                        child.key?.let { acceptedFriends.add(it) }
                    }
                }
                updateLocationListeners(acceptedFriends)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateLocationListeners(friendUids: List<String>) {
        if (cleared) return
        val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)

        // Remove old listeners
        val toRemove = locationListeners.keys - friendUids.toSet()
        for (uid in toRemove) {
            val listener = locationListeners.remove(uid)
            if (listener != null) db.getReference("locations").child(uid).removeEventListener(listener)
            _friendLocations.value = _friendLocations.value.filter { it.uid != uid }
        }

        // Add new listeners
        for (uid in friendUids) {
            if (!locationListeners.containsKey(uid)) {
                // Need a temp listener so we don't request again while waiting
                val placeholder = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {}
                    override fun onCancelled(error: DatabaseError) {}
                }
                locationListeners[uid] = placeholder

                viewModelScope.launch {
                    val profile = runCatching { UserDirectory.getProfile(uid, withPhoto = true) }.getOrNull()
                    // O amigo pode ter sido removido (ou o ViewModel destruído) enquanto o perfil carregava:
                    // antes isto dava NullPointerException e deixava listeners órfãos
                    if (cleared || locationListeners[uid] !== placeholder) return@launch

                    val name = profile?.name ?: "Amigo"
                    val photoUrl = profile?.photoUrl
                    val photoBase64 = profile?.photoBase64
                    val initial = name.take(1).uppercase()

                    val listener = object : ValueEventListener {
                        override fun onDataChange(locSnap: DataSnapshot) {
                            val lat = locSnap.child("lat").doubleOrNull()
                            val lon = locSnap.child("lon").doubleOrNull()
                            val ts = locSnap.child("timestamp").longOrNull() ?: 0L

                            if (lat != null && lon != null) {
                                val friendLoc = FriendLocation(uid, lat, lon, ts, name, initial, photoUrl, photoBase64)
                                val current = _friendLocations.value.toMutableList()
                                val index = current.indexOfFirst { it.uid == uid }
                                if (index != -1) {
                                    current[index] = friendLoc
                                } else {
                                    current.add(friendLoc)
                                }
                                _friendLocations.value = current
                            } else {
                                _friendLocations.value = _friendLocations.value.filter { it.uid != uid }
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    }
                    
                    // Replace temp listener
                    db.getReference("locations").child(uid).addValueEventListener(listener)
                    locationListeners[uid] = listener
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleared = true
        stopLocationTracking()
        val db = FirebaseDatabase.getInstance(FIREBASE_DB_URL)
        val uid = friendsUid
        friendsListener?.let { if (uid != null) db.getReference("friends").child(uid).removeEventListener(it) }
        for ((friendUid, listener) in locationListeners) {
            db.getReference("locations").child(friendUid).removeEventListener(listener)
        }
        locationListeners.clear()
    }
}
