package com.example.metrotime.work

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrainTrackingDebugger {
    val debugLogs = MutableStateFlow<List<String>>(emptyList())

    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLogs = debugLogs.value.toMutableList()
        newLogs.add(0, "[$time] $msg")
        if (newLogs.size > 50) newLogs.removeLast()
        debugLogs.value = newLogs
    }

    fun clear() {
        debugLogs.value = emptyList()
    }
}
