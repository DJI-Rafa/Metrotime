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

    init {
        startPolling()
        startFriendsTracking()
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
                    val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                    val map = mapOf(
                        "lat" to loc.latitude,
                        "lon" to loc.longitude,
                        "timestamp" to System.currentTimeMillis()
                    )
                    db.getReference("locations").child(uid).setValue(map)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    fun stopLocationTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private fun startFriendsTracking() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        
        friendsListener = db.getReference("friends").child(uid).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val acceptedFriends = mutableListOf<String>()
                for (child in snapshot.children) {
                    if (child.getValue(String::class.java) == "accepted") {
                        child.key?.let { acceptedFriends.add(it) }
                    }
                }
                updateLocationListeners(acceptedFriends)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateLocationListeners(friendUids: List<String>) {
        val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        
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
                locationListeners[uid] = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {}
                    override fun onCancelled(error: DatabaseError) {}
                }
                
                db.getReference("users").child(uid).get().addOnSuccessListener { userSnap ->
                    val name = userSnap.child("name").getValue(String::class.java) ?: "Amigo"
                    val photoUrl = userSnap.child("photoUrl").getValue(String::class.java)
                    val photoBase64 = userSnap.child("photoBase64").getValue(String::class.java)
                    val initial = name.take(1).uppercase()
                    
                    val listener = object : ValueEventListener {
                        override fun onDataChange(locSnap: DataSnapshot) {
                            val lat = locSnap.child("lat").getValue(Double::class.java)
                            val lon = locSnap.child("lon").getValue(Double::class.java)
                            val ts = locSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                            
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
                    db.getReference("locations").child(uid).removeEventListener(locationListeners[uid]!!)
                    db.getReference("locations").child(uid).addValueEventListener(listener)
                    locationListeners[uid] = listener
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
        friendsListener?.let { db.getReference("friends").child(FirebaseAuth.getInstance().currentUser?.uid ?: "").removeEventListener(it) }
        for ((uid, listener) in locationListeners) {
            db.getReference("locations").child(uid).removeEventListener(listener)
        }
    }
}
