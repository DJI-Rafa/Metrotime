package com.example.metrotime.work

import android.content.Context

/**
 * Thin facade kept for backward compatibility.
 * All real work now happens inside [WidgetUpdateService].
 */
object WidgetTickService {
    fun start(context: Context) {
        WidgetUpdateService.requestFetch(context)
    }

    fun stop(context: Context) {
        // Stopping is handled by WidgetUpdateService's own lifecycle
        try {
            val intent = android.content.Intent(context, WidgetUpdateService::class.java)
            context.stopService(intent)
        } catch (_: Exception) { }
    }
}
