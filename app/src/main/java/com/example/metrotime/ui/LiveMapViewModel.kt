package com.example.metrotime.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.metrotime.data.TrainPosition
import com.example.metrotime.data.TrainPositionEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationArrivals

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

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
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
        
        // Local ticker: decrease seconds by 1 every second for smoother UI
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_trains.value.isNotEmpty()) {
                    val updatedTrains = _trains.value.map { train ->
                        if (train.secondsToNextStation > 0) {
                            train.copy(secondsToNextStation = train.secondsToNextStation - 1)
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
                                if (arr.minutes > 0) arr.copy(minutes = arr.minutes - 1) else arr
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
        if (train != null) _selectedStation.value = null // dismiss station if train is clicked
    }

    fun selectStation(id: String?, name: String? = null) {
        if (id == null) {
            _selectedStation.value = null
        } else {
            _selectedStation.value = SelectedStationState(id, name ?: id, null, true)
            _selectedTrain.value = null // dismiss train if station is clicked
            
            // fetch immediately
            viewModelScope.launch {
                try {
                    val arrivals = arrivalsSource.fetchArrivals(id)
                    _selectedStation.value = _selectedStation.value?.copy(arrivals = arrivals, loading = false)
                } catch (e: Exception) {
                    _selectedStation.value = _selectedStation.value?.copy(loading = false)
                }
            }
        }
    }
}
