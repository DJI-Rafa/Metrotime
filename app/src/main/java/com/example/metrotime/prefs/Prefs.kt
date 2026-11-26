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

    /** Última versão da intro das novidades que já foi vista (0 = nunca). */
    fun getIntroSeen(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("intro_seen", 0)

    fun setIntroSeen(context: Context, version: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putInt("intro_seen", version) }
    }

    /** Tempos do metro da estação mais perto na notificação (e no ecrã de bloqueio); desligado = notificação discreta. */
    fun isLiveMetroNotification(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("live_metro_notification", false)

    fun setLiveMetroNotification(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("live_metro_notification", on) }
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
        val busStops: Boolean = true,
        /** Outros operadores em direto (MobiCascais, barcos, Covilhã, Viseu…). */
        val regional: Boolean = true,
        /** Operadores escondidos ("tml:HF16N", "eleven:cov", "stcp:STCP"…). */
        val hiddenOperators: Set<String> = emptySet(),
        /** Linhas escolhidas ("stcp:STCP|200"); vazio = todas as linhas dos operadores à vista. */
        val shownLines: Set<String> = emptySet()
    ) {
        /**
         * Um veículo das outras redes passa nos filtros: a camada ligada, o operador à vista e, se nele houver linhas
         * escolhidas, uma delas (os operadores sem linhas escolhidas mostram-se todos).
         */
        fun showsRegional(v: com.example.metrotime.data.regional.RegionalVehicle): Boolean {
            if (!regional || v.operator.key in hiddenOperators) return false
            val mine = linesOf(v.operator.key)
            return mine.isEmpty() || v.line in mine
        }

        /** As linhas escolhidas num operador (vazio = todas). */
        fun linesOf(operatorKey: String): Set<String> =
            shownLines.filter { it.startsWith("$operatorKey|") }.mapTo(HashSet()) { it.substringAfter("|") }
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
            busStops = p.getBoolean("map_bus_stops", true),
            regional = p.getBoolean("map_regional", true),
            hiddenOperators = p.getStringSet("map_hidden_ops", emptySet()).orEmpty().toSet(),
            shownLines = p.getStringSet("map_shown_lines", emptySet()).orEmpty().toSet()
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
            putBoolean("map_regional", f.regional)
            putStringSet("map_hidden_ops", f.hiddenOperators)
            putStringSet("map_shown_lines", f.shownLines)
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

    // ── Planeador, casa e último metro ──────────────────────────────────────────

    /** Casa (um sítio do planeador, Place.encode()); null se não estiver definida. */
    fun getHome(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("home_place", null)

    fun setHome(context: Context, encoded: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("home_place", encoded) }
    }

    /** Aviso à noite quando está a chegar a hora do último metro/comboio para casa (ligado por omissão). */
    fun isLastTripAlerts(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("last_trip_alerts", true)

    fun setLastTripAlerts(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("last_trip_alerts", on) }
    }

    /** Cartões do "Agora" na página inicial, pela ordem, com 1/0 = visível/escondido ("metro:1,favorites:0,…"). */
    fun getHomeNowCards(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("home_now_cards", null)

    fun setHomeNowCards(context: Context, value: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("home_now_cards", value) }
    }

    /** Definições do cartão "Comboios CP" do "Agora" (HomeNowConfig.CpCard.encode()). */
    fun getNowCpCard(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("now_cp_card", null)

    fun setNowCpCard(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("now_cp_card", value) }
    }

    /** "As minhas estações" do "Agora", pela ordem (Place.encode(), até 5). */
    fun getNowStations(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("now_stations", null)
            ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    fun setNowStations(context: Context, entries: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("now_stations", entries.joinToString("\n")) }
    }

    /** No cartão "As minhas estações": partidas e chegadas (true) ou só partidas. */
    fun isNowStationsBoth(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("now_stations_both", true)

    fun setNowStationsBoth(context: Context, both: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("now_stations_both", both) }
    }

    /** Transportes que o planeador pode usar (filtros); por omissão todos. */
    fun getPlanModes(context: Context): Set<com.example.metrotime.data.plan.LegMode> {
        val all = setOf(
            com.example.metrotime.data.plan.LegMode.METRO, com.example.metrotime.data.plan.LegMode.BUS,
            com.example.metrotime.data.plan.LegMode.TRAIN, com.example.metrotime.data.plan.LegMode.BOAT
        )
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("plan_modes", null) ?: return all
        // O barco chegou depois: quem já tinha filtros guardados fica com ele ligado (até o desligar)
        return all.filter { it.name in saved || (it == com.example.metrotime.data.plan.LegMode.BOAT && "BOAT_OFF" !in saved) }
            .toSet().ifEmpty { all }
    }

    fun setPlanModes(context: Context, modes: Set<com.example.metrotime.data.plan.LegMode>) {
        val names = modes.map { it.name }.toMutableSet()
        if (com.example.metrotime.data.plan.LegMode.BOAT !in modes) names += "BOAT_OFF"
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putStringSet("plan_modes", names) }
    }

    /** Últimas pesquisas do planeador, mais recente primeiro: "partida\tchegada" (Place.encode()). */
    fun getRecentPlans(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("plan_recent", null)
            ?.split("\n")?.filter { it.count { c -> c == '\t' } == 1 }.orEmpty()

    fun addRecentPlan(context: Context, entry: String) {
        val list = (listOf(entry) + getRecentPlans(context).filter { it != entry }).take(6)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString("plan_recent", list.joinToString("\n")) }
    }
}
