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

    /**
     * Registar as minhas viagens (estatísticas): só no telemóvel, separado da partilha com os amigos.
     * Por omissão segue a partilha em background (quem a tinha ligada continua a ter estatísticas).
     */
    fun isTripTracking(context: Context): Boolean {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (p.contains("trip_tracking")) p.getBoolean("trip_tracking", false) else isContinuousLocation(context)
    }

    fun setTripTracking(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("trip_tracking", on) }
    }

    fun setMapSharingBlocked(context: Context, blocked: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("map_sharing_blocked", blocked) }
    }
    fun isMapSharingBlocked(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("map_sharing_blocked", false)

    fun setAdmin(context: Context, isAdmin: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("is_admin", isAdmin) }
    }
    fun isAdmin(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("is_admin", false)

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

    // ── Autocarros ──────────────────────────────────────────────────────────────

    /** Últimas linhas abertas no mapa, mais recente primeiro: "operador|número|nome|cor". */
    fun getRecentBusLines(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("bus_recent_lines", null)
            ?.split("\n")?.filter { it.count { c -> c == '|' } == 3 }.orEmpty()

    fun addRecentBusLine(context: Context, operator: String, shortName: String, longName: String, color: String) {
        val entry = listOf(operator, shortName, longName, color).joinToString("|") { it.replace("|", "/").replace("\n", " ") }
        val list = (listOf(entry) + getRecentBusLines(context).filter { it.split("|").getOrNull(1) != shortName }).take(5)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("bus_recent_lines", list.joinToString("\n")) }
    }

    /** Paragens de autocarro favoritas: "operador|id|nome|lat|lon". */
    fun getFavoriteBusStops(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("bus_fav_stops", emptySet()).orEmpty()

    fun isFavoriteBusStop(context: Context, operator: String, id: String): Boolean =
        getFavoriteBusStops(context).any { it.startsWith("$operator|$id|") }

    fun toggleFavoriteBusStop(context: Context, operator: String, id: String, name: String, lat: Double, lon: Double) {
        val current = getFavoriteBusStops(context).toMutableSet()
        val existing = current.firstOrNull { it.startsWith("$operator|$id|") }
        if (existing != null) current.remove(existing) else current.add("$operator|$id|${name.replace("|", "/")}|$lat|$lon")
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putStringSet("bus_fav_stops", current) }
    }

    /** Primeiro nome do utilizador, guardado para a saudação aparecer logo ao abrir a app. */
    fun getUserFirstName(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("user_first_name", null)

    fun setUserFirstName(context: Context, name: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            if (name == null) remove("user_first_name") else putString("user_first_name", name)
        }
    }

    // ── Alertas de perturbação ──────────────────────────────────────────────────

    /** Linhas do metro de que se recebem alertas (por omissão, todas). */
    fun getMetroAlertLines(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet("alert_metro_lines", null)?.toSet()
            ?: com.example.metrotime.data.alerts.MetroLineStatusParser.LINE_NAMES.toSet()

    fun setMetroAlertLines(context: Context, lines: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putStringSet("alert_metro_lines", lines) }
    }

    /** Avisos da CP (obras, supressões) das linhas das estações CP favoritas. */
    fun isCpNoticeAlerts(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("alert_cp_notices", true)

    fun setCpNoticeAlerts(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("alert_cp_notices", enabled) }
    }

    // ── Filtros do mapa ao vivo ─────────────────────────────────────────────────

    data class MapFilters(
        val metro: Boolean = true,
        val cp: Boolean = true,
        /** Tipos de serviço CP escolhidos para mostrar; vazio = todos. */
        val shownCpServices: Set<String> = emptySet(),
        val cpOnlyDelayed: Boolean = false,
        val friends: Boolean = true,
        val busStops: Boolean = true
    ) {
        fun showsCpService(service: String): Boolean = shownCpServices.isEmpty() || service in shownCpServices
    }

    fun getMapFilters(context: Context): MapFilters {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return MapFilters(
            metro = p.getBoolean("map_metro", true),
            cp = p.getBoolean("map_cp", true),
            shownCpServices = p.getStringSet("map_cp_shown", emptySet()).orEmpty().toSet(),
            cpOnlyDelayed = p.getBoolean("map_cp_delayed", false),
            friends = p.getBoolean("map_friends", true),
            busStops = p.getBoolean("map_bus_stops", true)
        )
    }

    fun setMapFilters(context: Context, f: MapFilters) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean("map_metro", f.metro)
            putBoolean("map_cp", f.cp)
            putStringSet("map_cp_shown", f.shownCpServices)
            remove("map_cp_hidden") // filtro antigo (tipos escondidos), substituído pelo de cima
            putBoolean("map_cp_delayed", f.cpOnlyDelayed)
            putBoolean("map_friends", f.friends)
            putBoolean("map_bus_stops", f.busStops)
        }
    }

    // ── Widget CP e pesquisas recentes ──────────────────────────────────────────

    /** Estação do widget CP ("código|nome"); por omissão, a primeira favorita. */
    fun getCpWidgetStation(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("cp_widget_station", null)
            ?: getCpFavorites(context).sorted().firstOrNull()

    fun setCpWidgetStation(context: Context, entry: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("cp_widget_station", entry) }
    }

    /** Paragem do widget de autocarros ("operador|id|nome|lat|lon"); sem escolha, a primeira favorita. */
    fun getBusWidgetStop(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("bus_widget_stop", null)
            ?: getFavoriteBusStops(context).sorted().firstOrNull()

    fun setBusWidgetStop(context: Context, entry: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("bus_widget_stop", entry) }
    }

    /** Últimas pesquisas A → B, mais recente primeiro: "códigoA|nomeA|códigoB|nomeB". */
    fun getRecentJourneys(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("cp_recent_journeys", null)
            ?.split("\n")?.filter { it.count { c -> c == '|' } == 3 }.orEmpty()

    fun addRecentJourney(context: Context, entry: String) {
        val list = (listOf(entry) + getRecentJourneys(context).filter { it != entry }).take(5)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("cp_recent_journeys", list.joinToString("\n")) }
    }
}
