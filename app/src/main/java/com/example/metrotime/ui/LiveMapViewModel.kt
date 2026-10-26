package com.example.metrotime.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.metrotime.data.TrainPosition
import com.example.metrotime.data.TrainPositionEngine
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    private val engine = TrainPositionEngine(application)
    private val arrivalsSource = RemoteArrivalsSource(application)

    private val _trains = MutableStateFlow<List<TrainPosition>>(emptyList())
    val trains: StateFlow<List<TrainPosition>> = _trains

    private val _selectedTrain = MutableStateFlow<TrainPosition?>(null)
    val selectedTrain: StateFlow<TrainPosition?> = _selectedTrain

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
        val loading: Boolean = true
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
    val allBusStops: StateFlow<List<BusStop>> = _allBusStops
    private var allBusStopsLoadStarted = false

    // Declarados antes do init: as propriedades são inicializadas por ordem, e o init já as usa
    private var friendsUid: String? = null
    private var cleared = false

    // ── Comboios CP no mapa ─────────────────────────────────────────────────────

    private val _mapFilters = MutableStateFlow(com.example.metrotime.prefs.Prefs.getMapFilters(application))
    val mapFilters: StateFlow<com.example.metrotime.prefs.Prefs.MapFilters> = _mapFilters

    private val _allCpTrains = MutableStateFlow<List<CpMapTrain>>(emptyList())
    private val _cpUpdatedAt = MutableStateFlow(0L)
    val cpUpdatedAt: StateFlow<Long> = _cpUpdatedAt

    /** Comboios CP depois dos filtros. */
    val cpTrains: StateFlow<List<CpMapTrain>> = combine(_allCpTrains, _mapFilters) { trains, f ->
        if (!f.cp) emptyList()
        else trains.filter { t ->
            f.showsCpService(t.service) &&
                (!f.cpOnlyDelayed || t.tone == com.example.metrotime.data.cp.CpTone.LATE || t.tone == com.example.metrotime.data.cp.CpTone.VERY_LATE)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Tipos de serviço presentes agora (só esses aparecem nos filtros). */
    val cpServicesPresent: StateFlow<List<String>> = _allCpTrains.map { trains ->
        val present = trains.map { it.service }.toSet()
        com.example.metrotime.data.cp.CpService.ORDER.filter { it in present }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        val geometry: CpRouteGeometry? = null
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
        // Um comboio CP aberto fecha as seleções do metro e das paragens
        _selectedTrain.value = null
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

    private fun startCpPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (screenVisible.value) {
                    try {
                        val vehicles = com.example.metrotime.data.cp.CpRepository.getActiveVehicles()
                        _allCpTrains.value = vehicles.mapNotNull(::toMapTrain)
                        _cpUpdatedAt.value = System.currentTimeMillis()
                        withContext(Dispatchers.Main) { tryFocusPendingCp() }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        e.printStackTrace()
                    }
                }
                delay(30_000)
            }
        }
    }

    private fun toMapTrain(v: com.example.metrotime.data.cp.CpVehicle): CpMapTrain? {
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
        return CpMapTrain(
            number = v.trainNumber,
            lat = lat,
            lon = lon,
            bearing = bearing,
            service = com.example.metrotime.data.cp.CpService.shortName(v.service?.code),
            serviceName = v.service?.designation,
            delaySeconds = v.delay,
            status = v.status,
            origin = v.origin?.designation?.let { com.example.metrotime.data.cp.formatStationName(it) },
            destination = v.destination?.designation?.let { com.example.metrotime.data.cp.formatStationName(it) },
            lastStationCode = v.lastStation,
            runDate = v.runDate?.take(10)
        )
    }

    init {
        startPolling()
        startFriendsTracking()
        startCpPolling()
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

                // Also fetch CM stops in the background
                val cmStops = BusMapRepository.getAllCmStops()
                if (cmStops.isNotEmpty()) {
                    _allBusStops.value = carrisStops + cmStops
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Select a bus stop and load its arrivals.
     */
    fun selectBusStop(stop: BusStop) {
        _selectedBusStop.value = SelectedStopState(stop = stop, loading = true)
        stopArrivalsJob?.cancel()
        // Chegadas atualizadas de 30 em 30 s enquanto a paragem está aberta
        stopArrivalsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val arrivals = BusMapRepository.getStopArrivals(
                        getApplication(), stop.id, stop.operator
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
        _selectedTrain.value = null
        _selectedStation.value = null

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

        _selectedLiveBus.value = SelectedLiveBusState(bus = bus, loading = true)

        viewModelScope.launch(Dispatchers.IO) {
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
                    loading = false
                )
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
                    _selectedTrain.value = null
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
                    val positions = engine.fetchAllTrainPositions()
                    _trains.value = positions
                    
                    // Update selectedTrain if it is currently selected
                    _selectedTrain.value?.let { currentSelected ->
                        val updated = positions.find { it.id == currentSelected.id }
                        _selectedTrain.value = updated
                    }

                    // Update selected station arrivals if open
                    _selectedStation.value?.let { currentStation ->
                        try {
                            val newArrivals = arrivalsSource.fetchArrivals(currentStation.id)
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
        
        // Keep interpolation aligned with map marker updates without rebuilding every marker each second.
        viewModelScope.launch {
            val tickerIntervalSeconds = 3
            while (isActive) {
                delay(tickerIntervalSeconds * 1_000L)
                if (_trains.value.isNotEmpty()) {
                    val updatedTrains = _trains.value.map { train ->
                        if (train.secondsToNextStation > 0) {
                            train.updatePositionForSeconds(
                                (train.secondsToNextStation - tickerIntervalSeconds).coerceAtLeast(0)
                            )
                        } else {
                            train
                        }
                    }
                    _trains.value = updatedTrains
                    
                    _selectedTrain.value?.let { currentSelected ->
                        val updated = updatedTrains.find { it.id == currentSelected.id }
                        _selectedTrain.value = updated
                    }

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

    fun selectTrain(train: TrainPosition?) {
        _selectedTrain.value = train
        if (train != null) {
            _selectedStation.value = null // dismiss station if train is clicked
            selectCpTrain(null)
        }
    }

    fun selectStation(id: String?, name: String? = null) {
        if (id == null) {
            _selectedStation.value = null
        } else {
            _selectedStation.value = SelectedStationState(id, name ?: id, null, true)
            _selectedTrain.value = null // dismiss train if station is clicked
            selectCpTrain(null)
            
            // fetch immediately
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val arrivals = arrivalsSource.fetchArrivals(id)
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
