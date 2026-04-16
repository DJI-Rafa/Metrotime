package com.example.metrotime.prefs

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val FILE = "metro_widget_prefs"
    private const val KEY_FIXED = "fixed_station_id"
    fun setFixedStation(context: Context, id: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_FIXED, id) }
    }
    fun getFixedStation(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_FIXED, null)
}
