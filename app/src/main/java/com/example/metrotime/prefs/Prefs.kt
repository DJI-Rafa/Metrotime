package com.example.metrotime.prefs

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val FILE = "metro_widget_prefs"
    private const val KEY_FIXED = "fixed_station_id"
    private const val KEY_LOC_MODE = "loc_mode_continuous"

    fun setFixedStation(context: Context, id: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_FIXED, id) }
    }
    fun getFixedStation(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_FIXED, null)
        
    fun setContinuousLocation(context: Context, continuous: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_LOC_MODE, continuous) }
    }
    fun isContinuousLocation(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LOC_MODE, true)

    private const val KEY_METRO_FAVS = "metro_favorites"
    private const val KEY_CP_FAVS = "cp_favorites"

    fun getMetroFavorites(context: Context): Set<String> {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY_METRO_FAVS, emptySet()) ?: emptySet()
    }

    fun toggleMetroFavorite(context: Context, id: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_METRO_FAVS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.contains(id)) current.remove(id) else current.add(id)
        prefs.edit { putStringSet(KEY_METRO_FAVS, current) }
    }

    fun getCpFavorites(context: Context): Set<String> {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY_CP_FAVS, emptySet()) ?: emptySet()
    }

    fun toggleCpFavorite(context: Context, code: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_CP_FAVS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.contains(code)) current.remove(code) else current.add(code)
        prefs.edit { putStringSet(KEY_CP_FAVS, current) }
    }
}
