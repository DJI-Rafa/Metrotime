package com.example.metrotime.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ArrivalsRepo {

    private val _currentArrivals = MutableStateFlow<StationArrivals?>(null)
    val currentArrivals = _currentArrivals.asStateFlow()

    fun newArrivals(data: StationArrivals) {
        _currentArrivals.value = data
    }

    fun tick() {
        _currentArrivals.value?.let { current ->
            val updatedArrivals = current.arrivals.map { 
                it.copy(minutes = it.minutes - 5) 
            }
            _currentArrivals.value = current.copy(arrivals = updatedArrivals)
        }
    }
}
