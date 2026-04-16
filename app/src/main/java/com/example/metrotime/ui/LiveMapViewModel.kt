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

class LiveMapViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = TrainPositionEngine(application)

    private val _trains = MutableStateFlow<List<TrainPosition>>(emptyList())
    val trains: StateFlow<List<TrainPosition>> = _trains

    private val _selectedTrain = MutableStateFlow<TrainPosition?>(null)
    val selectedTrain: StateFlow<TrainPosition?> = _selectedTrain

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
                    _isLoading.value = false
                } catch (e: Exception) {
                    _isLoading.value = false
                }
                delay(10_000) // Refresh every 10 seconds
            }
        }
    }

    fun selectTrain(train: TrainPosition?) {
        _selectedTrain.value = train
    }
}
