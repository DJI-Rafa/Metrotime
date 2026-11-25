package com.example.metrotime.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.cp.CpNetwork
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.data.history.TripHistoryManager
import com.example.metrotime.data.plan.LegMode
import com.example.metrotime.data.plan.Place
import com.example.metrotime.data.plan.PlanAlternative
import com.example.metrotime.data.plan.PlanLeg
import com.example.metrotime.data.plan.PlanOption
import com.example.metrotime.data.plan.TripPlanner
import com.example.metrotime.data.plan.JourneyPlanner
import com.example.metrotime.data.plan.OnlinePlanner
import com.example.metrotime.work.LeaveAlarm
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.metrotime.location.Locator
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.*
import com.example.metrotime.work.BusFollow
import com.example.metrotime.work.CpFollow
import com.example.metrotime.work.ExitAlarm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Planear viagem: de qualquer rua, localidade, estação, paragem ou da localização atual para qualquer outra, com
 * todos os transportes (JourneyPlanner): a mais rápida primeiro, ou, com "chegar às…", a hora a que tens de sair e o
 * aviso para saíres. Em cada opção, os atalhos para seguir o comboio, avisar quando o autocarro chegar, avisar
 * antes de sair e ver o caminho a pé até à paragem.
 */
class TripPlannerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"

        /** [from]/[to]: Place.encode(); sem partida usa-se a localização atual. */
        fun newIntent(context: Context, from: String? = null, to: String? = null): Intent =
            Intent(context, TripPlannerActivity::class.java).apply {
                from?.let { putExtra(EXTRA_FROM, it) }
                to?.let { putExtra(EXTRA_TO, it) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val from = Place.decode(intent.getStringExtra(EXTRA_FROM))
        val to = Place.decode(intent.getStringExtra(EXTRA_TO))
        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                    TripPlannerScreen(this, from, to)
                }
            }
        }
    }
}

// ── Peças partilhadas com a página inicial e o "Perto de mim" ────────────────

internal val HERE_PLACE = Place(Place.Kind.HERE, "", "A minha localização", 0.0, 0.0)

internal fun hasLocationPermission(context: Context) = ExitAlarm.hasLocationPermission(context)

/** Onde estás: uma leitura nova, senão a última do telemóvel, senão a última que a app guardou. */
internal suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    return attempt { Locator.getCurrentLocation(context) }
        ?: attempt {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                .lastLocation.await()?.let { it.latitude to it.longitude }
        }
        ?: TripHistoryManager.getLastUserLocation(context)
}

/** Ícone de um sítio: cores das linhas (metro), comboio (CP), sinal "P" (autocarro), marcador ou casa. */
@Composable
internal fun PlaceIcon(place: Place, size: Dp = 32.dp, isHome: Boolean = false) {
    Box(Modifier.width(size + 4.dp), contentAlignment = Alignment.Center) {
        when {
            isHome -> Icon(Icons.Filled.Home, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(size * 0.7f))
            place.kind == Place.Kind.HERE -> Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MetrotimeBlue, modifier = Modifier.size(size * 0.7f))
            place.kind == Place.Kind.METRO -> LineDots(MetroLines.linesOf(place.id).map { lineColorOf(it) }, size = 11.dp)
            place.kind == Place.Kind.CP -> Icon(TrainIcon, contentDescription = null, tint = CpGreen, modifier = Modifier.size(size * 0.65f))
            place.kind == Place.Kind.BUS -> BusStopSign(size * 0.8f)
            else -> Icon(PinIcon, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(size * 0.65f))
        }
    }
}

internal fun placeKindText(place: Place, context: android.content.Context? = null): String = when (place.kind) {
    Place.Kind.HERE -> "Onde estás agora"
    Place.Kind.METRO -> "Metro · " + MetroLines.linesOf(place.id).joinToString(" · ")
    Place.Kind.CP -> "Estação CP"
    // Carris Metropolitana: com o concelho, que distingue as muitas paragens com o mesmo nome
    Place.Kind.BUS -> if (place.id.startsWith(com.example.metrotime.data.plan.TransitBuilder.CM_PREFIX))
        "Carris Metropolitana" + (context?.let { TripPlanner.cmArea(it, place) }?.let { " · $it" } ?: "")
    else "Paragem da Carris"
    Place.Kind.POINT -> "Local guardado"
    Place.Kind.ADDRESS -> place.detail.ifEmpty { "Morada" }
}

@Composable
internal fun PlaceRow(place: Place, detail: String = placeKindText(place, LocalContext.current), isHome: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PlaceIcon(place, isHome = isHome)
        Column(Modifier.weight(1f)) {
            Text(if (isHome) "Casa" else place.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (isHome) place.name else detail, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Favoritas do metro e da CP como sítios do planeador. */
internal fun favoritePlaces(context: Context): List<Place> {
    val stations = StationsRepo.loadStations(context)
    val metro = Prefs.getMetroFavorites(context).mapNotNull { id -> stations.find { it.id == id } }
        .map { Place(Place.Kind.METRO, it.apiId, it.name, it.lat, it.lon) }
    val cpStations = runCatching { CpNetwork.stations(context) }.getOrDefault(emptyList())
    val cp = Prefs.getCpFavorites(context).mapNotNull { entry ->
        val code = entry.substringBefore("|")
        cpStations.find { it.code == code }?.let { Place(Place.Kind.CP, it.code, formatStationName(it.name), it.lat, it.lon) }
    }
    return (metro + cp).sortedBy { it.name }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private enum class PlanField { FROM, TO }

// ── Ecrã ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripPlannerScreen(activity: ComponentActivity, initialFrom: Place?, initialTo: Place?) {
    var from by remember { mutableStateOf(initialFrom ?: if (hasLocationPermission(activity)) HERE_PLACE else null) }
    var to by remember { mutableStateOf(initialTo) }
    var fromQuery by remember { mutableStateOf(from?.name.orEmpty()) }
    var toQuery by remember { mutableStateOf(to?.name.orEmpty()) }
    var activeField by remember { mutableStateOf<PlanField?>(null) }
    var departAt by remember { mutableStateOf<LocalDateTime?>(null) }
    // Com uma hora escolhida: true = é a hora a que se quer chegar ("chegar às…")
    var arriveBy by remember { mutableStateOf(false) }
    // Ruas, localidades e sítios da pesquisa de moradas (e para que texto foram procuradas)
    var addresses by remember { mutableStateOf<List<Place>>(emptyList()) }
    var addressesQuery by remember { mutableStateOf("") }
    // Onde estás (se já se souber): as moradas mais perto aparecem primeiro
    var nearHint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) { nearHint = withContext(Dispatchers.IO) { attempt { TripHistoryManager.getLastUserLocation(activity) } } }
    var results by remember { mutableStateOf<List<PlanAlternative>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Alternativa aberta e a partida escolhida em cada uma (pela "maneira", que não muda com as horas)
    var expanded by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(mapOf<String, Int>()) }
    var modes by remember { mutableStateOf(Prefs.getPlanModes(activity)) }
    var home by remember { mutableStateOf(Place.decode(Prefs.getHome(activity))) }
    var recents by remember { mutableStateOf(Prefs.getRecentPlans(activity)) }
    // As favoritas da CP precisam da rede da CP (um ficheiro de 150 KB): lidas fora do ecrã
    var favorites by remember { mutableStateOf<List<Place>>(emptyList()) }
    LaunchedEffect(Unit) { favorites = withContext(Dispatchers.IO) { favoritePlaces(activity) } }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searchedQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val toFocus = remember { FocusRequester() }
    var pendingHereField by remember { mutableStateOf<PlanField?>(null) }

    fun pick(field: PlanField, place: Place) {
        if (field == PlanField.FROM) { from = place; fromQuery = place.name } else { to = place; toQuery = place.name }
        if (field == PlanField.FROM && to == null) toFocus.requestFocus() else focusManager.clearFocus()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val field = pendingHereField
        pendingHereField = null
        if (granted.values.any { it } && field != null) pick(field, HERE_PLACE)
        else Toast.makeText(activity, "Sem acesso à localização: escolhe uma estação ou paragem.", Toast.LENGTH_LONG).show()
    }

    fun pickHere(field: PlanField) {
        if (hasLocationPermission(activity)) pick(field, HERE_PLACE)
        else {
            pendingHereField = field
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Planeia quando há partida e chegada; com "partir agora" volta a planear a cada 2 minutos
    LaunchedEffect(from, to, departAt, arriveBy, modes) {
        results = null
        error = null
        expanded = null
        selected = emptyMap()
        val a = from ?: return@LaunchedEffect
        val b = to ?: return@LaunchedEffect
        if (a == b) { error = "Escolhe sítios diferentes para a partida e a chegada."; return@LaunchedEffect }
        Prefs.addRecentPlan(activity, "${a.encode()}\t${b.encode()}")
        recents = Prefs.getRecentPlans(activity)
        while (isActive) {
            loading = true
            try {
                suspend fun resolve(p: Place): Place? =
                    if (p.kind != Place.Kind.HERE) p
                    else currentLocation(activity)?.let { (lat, lon) -> p.copy(lat = lat, lon = lon) }
                val ra = resolve(a)
                val rb = resolve(b)
                if (ra == null || rb == null) {
                    error = "Não foi possível saber onde estás. Escolhe uma estação ou paragem."
                } else {
                    results = JourneyPlanner.plan(activity, ra, rb, departAt, arriveBy && departAt != null, modes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                if (results == null) error = "Não foi possível planear a viagem (${e.javaClass.simpleName})."
            }
            loading = false
            if (departAt != null) break
            delay(120_000)
        }
    }

    // Sugestões do campo em edição
    val query = when (activeField) { PlanField.FROM -> fromQuery; PlanField.TO -> toQuery; null -> "" }
    val picked = when (activeField) { PlanField.FROM -> from; PlanField.TO -> to; null -> null }
    val showDefaults = activeField != null && (query.isBlank() || query == picked?.name)
    LaunchedEffect(activeField, query) {
        if (activeField == null || showDefaults) { suggestions = emptyList(); addresses = emptyList(); return@LaunchedEffect }
        delay(250) // espera que se acabe de escrever
        coroutineScope {
            // As estações e paragens aparecem logo; as ruas e localidades quando a pesquisa de moradas responder
            val online = async { OnlinePlanner.geocode(query, nearHint) }
            suggestions = TripPlanner.searchPlaces(activity, query)
            searchedQuery = query
            addresses = online.await()
            addressesQuery = query
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Planear viagem", color = Color.White, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        // ── De / Para ──
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.height(105.dp).padding(vertical = 21.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(10.dp).border(2.dp, MetrotimeBlue, CircleShape))
                    Box(Modifier.weight(1f).padding(vertical = 3.dp).width(2.dp).background(Color.White.copy(alpha = 0.15f)))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MetrotimeBlue))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    PlaceInput(
                        value = fromQuery,
                        onValueChange = { fromQuery = it; if (it != from?.name) from = null },
                        placeholder = "De onde?",
                        modifier = Modifier.onFocusChanged {
                            if (it.isFocused) activeField = PlanField.FROM else if (activeField == PlanField.FROM) activeField = null
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    PlaceInput(
                        value = toQuery,
                        onValueChange = { toQuery = it; if (it != to?.name) to = null },
                        placeholder = "Para onde?",
                        modifier = Modifier.focusRequester(toFocus).onFocusChanged {
                            if (it.isFocused) activeField = PlanField.TO else if (activeField == PlanField.TO) activeField = null
                        }
                    )
                }
                IconButton(onClick = {
                    val a = from; from = to; to = a
                    val q = fromQuery; fromQuery = toQuery; toQuery = q
                }) {
                    Icon(SwapVertIcon, contentDescription = "Trocar partida e chegada", tint = Color.Gray, modifier = Modifier.size(22.dp))
                }
            }
        }

        // ── Quando ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .padding(3.dp)
        ) {
            val now = CpTime.now()
            val at = departAt
            fun timeText(t: LocalDateTime) = (if (t.toLocalDate().isAfter(now.toLocalDate())) "amanhã " else "") + t.format(HM)
            fun pickTime(arrive: Boolean) {
                val base = departAt ?: now.plusMinutes(if (arrive) 60 else 15)
                TimePickerDialog(activity, { _, h, m ->
                    var chosen = LocalDateTime.of(now.toLocalDate(), LocalTime.of(h, m))
                    if (chosen.isBefore(now.minusMinutes(30))) chosen = chosen.plusDays(1)
                    departAt = chosen
                    arriveBy = arrive
                }, base.hour, base.minute, true).show()
            }
            WhenButton("Partir agora", at == null) { departAt = null; arriveBy = false }
            WhenButton(if (at != null && !arriveBy) "Partir ${timeText(at)}" else "Partir às…", at != null && !arriveBy) { pickTime(false) }
            WhenButton(if (at != null && arriveBy) "Chegar ${timeText(at)}" else "Chegar às…", at != null && arriveBy) { pickTime(true) }
        }

        // ── Transportes (filtros) ──
        ModeFilters(modes) { mode ->
            val next = if (mode in modes) modes - mode else modes + mode
            if (next.isEmpty()) {
                Toast.makeText(activity, "Escolhe pelo menos um transporte.", Toast.LENGTH_SHORT).show()
            } else {
                modes = next
                Prefs.setPlanModes(activity, next)
            }
        }

        val listPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        val field = activeField
        when {
            field != null && (showDefaults || suggestions.isNotEmpty() || addresses.isNotEmpty()) -> LazyColumn(
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showDefaults) {
                    item(key = "here") { PlaceRow(HERE_PLACE) { pickHere(field) } }
                    home?.let { h -> item(key = "home") { PlaceRow(h, isHome = true) { pick(field, h) } } }
                    if (favorites.isNotEmpty()) item(key = "fav_header") { CpSectionHeader("Favoritas") }
                    items(favorites, key = { "f_${it.kind}_${it.id}" }) { p -> PlaceRow(p) { pick(field, p) } }
                } else {
                    if (addresses.isNotEmpty()) {
                        item(key = "addr_header") { CpSectionHeader("Ruas e sítios") }
                        items(addresses, key = { "a_${it.id}_${it.name}_${it.detail}" }) { p -> PlaceRow(p) { pick(field, p) } }
                    }
                    if (suggestions.isNotEmpty()) {
                        if (addresses.isNotEmpty()) item(key = "stops_header") { CpSectionHeader("Estações e paragens") }
                        items(suggestions, key = { "s_${it.kind}_${it.id}" }) { p -> PlaceRow(p) { pick(field, p) } }
                    }
                    if (addressesQuery != query && query.trim().length >= 3) {
                        item(key = "addr_loading") {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = TextSecondaryDark, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("A procurar ruas e localidades…", color = TextSecondaryDark, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            field != null -> when {
                query.trim().length < 2 -> PlannerMessage("🔎", "Escreve pelo menos duas letras.")
                searchedQuery == query && (addressesQuery == query || query.trim().length < 3) ->
                    PlannerMessage("🔎", "Nenhuma rua, localidade, estação ou paragem com esse nome.")
                else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MetrotimeBlue, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            loading && results == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetrotimeBlue)
            }
            error != null -> PlannerMessage("⚠️", error.orEmpty())
            results != null -> {
                val list = results.orEmpty()
                LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dest = to
                    if (dest != null && dest.kind != Place.Kind.HERE && dest != home) {
                        item(key = "set_home") {
                            Text(
                                "Guardar ${dest.name} como casa",
                                color = MetrotimeBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        Prefs.setHome(activity, dest.encode())
                                        home = dest
                                        Toast.makeText(activity, "${dest.name} é agora a tua casa.", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    val arriving = arriveBy && departAt != null
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            PlannerMessage(
                                "🧭",
                                when {
                                    arriving -> "Não há maneira de chegar até às ${departAt?.format(HM)}. Experimenta uma hora mais tarde."
                                    modes.size < 4 -> "Sem ligações com estes transportes nas próximas horas. Experimenta ligar mais transportes nos filtros."
                                    else -> "Sem ligações nas próximas horas entre estes dois sítios."
                                }
                            )
                        }
                    } else if (arriving) {
                        // Chegar às…: a que deixa sair mais tarde, com o aviso para sair; depois as outras
                        val best = list.first()
                        item(key = "arrive_hero") { ArriveHero(activity, best, departAt ?: CpTime.now(), to?.name ?: "o destino") }
                        if (list.size > 1) item(key = "others_header") { CpSectionHeader("Outras maneiras de chegar a tempo") }
                        items(list.drop(1), key = { it.signature }) { alt ->
                            val index = (selected[alt.signature] ?: 0).coerceIn(0, alt.options.lastIndex)
                            AlternativeCard(
                                activity, alt, index,
                                fastest = false,
                                expanded = expanded == alt.signature,
                                onSelect = { i ->
                                    selected = selected + (alt.signature to i)
                                    expanded = alt.signature
                                },
                                onToggle = { expanded = if (expanded == alt.signature) null else alt.signature }
                            )
                        }
                    } else {
                        item(key = "header") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CpSectionHeader(if (list.size == 1) "1 maneira" else "${list.size} maneiras · a mais rápida primeiro")
                                if (loading) {
                                    Spacer(Modifier.width(8.dp))
                                    CircularProgressIndicator(color = TextSecondaryDark, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        items(list, key = { it.signature }) { alt ->
                            val index = (selected[alt.signature] ?: 0).coerceIn(0, alt.options.lastIndex)
                            AlternativeCard(
                                activity, alt, index,
                                fastest = alt === list.first(),
                                expanded = expanded == alt.signature,
                                onSelect = { i ->
                                    selected = selected + (alt.signature to i)
                                    expanded = alt.signature
                                },
                                onToggle = { expanded = if (expanded == alt.signature) null else alt.signature }
                            )
                        }
                    }
                    if (list.isNotEmpty()) {
                        item(key = "note") {
                            Text(
                                "Metro de Lisboa, Carris, Carris Metropolitana e CP calculados pela app (tempo real no próximo quando há); " +
                                    "Fertagus, barcos, MobiCascais, TCB, MTS, Porto e o resto do país pelo Transitous (horários abertos); " +
                                    "Covilhã, Viseu, Guimarães e outras redes pela Eleven.",
                                color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }
            }
            recents.isNotEmpty() -> LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "recent_header") { CpSectionHeader("Pesquisas recentes") }
                items(recents, key = { it }) { entry ->
                    val a = Place.decode(entry.substringBefore("\t"))
                    val b = Place.decode(entry.substringAfter("\t"))
                    if (a != null && b != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardBackground)
                                .clickable {
                                    from = a; fromQuery = a.name
                                    to = b; toQuery = b.name
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(ClockIcon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("→ ${b.name}", color = Color.LightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> PlannerMessage("🧭", "Escolhe de onde partes e para onde vais: uma rua, localidade, estação ou paragem.")
        }
    }
}

@Composable
private fun PlaceInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(MetrotimeBlue),
        modifier = modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = Color.Gray, fontSize = 15.sp)
                    inner()
                }
                if (focused && value.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Limpar",
                        tint = Color.Gray,
                        modifier = Modifier.padding(start = 8.dp).size(18.dp).clip(CircleShape).clickable { onValueChange("") }
                    )
                }
            }
        }
    )
}

@Composable
private fun RowScope.WhenButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MetrotimeBlue else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.White else Color.Gray, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PlannerMessage(emoji: String, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 36.sp)
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

// ── Opções ───────────────────────────────────────────────────────────────────

private fun durationText(minutes: Long) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Etiqueta pequena de um bocado da viagem: cor da linha, serviço da CP, número do autocarro ou "a pé". */
@Composable
private fun LegChip(leg: PlanLeg) {
    when (leg.mode) {
        LegMode.WALK -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(WalkIcon, contentDescription = "A pé", tint = TextSecondaryDark, modifier = Modifier.size(14.dp))
            Text("${leg.minutes}", color = TextSecondaryDark, fontSize = 12.sp)
        }
        LegMode.METRO -> if (leg.color.startsWith("#")) {
            // Metro do Porto (e outros): a cor da linha e a letra
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(busColor(leg.color)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(
                    "M " + leg.label.removePrefix("Metro").trim(),
                    color = busColor(com.example.metrotime.data.bus.BusColors.textOn(leg.color)),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
        } else Box(
            Modifier.clip(RoundedCornerShape(5.dp)).background(lineColorOf(leg.color)).padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                "M " + leg.color,
                color = if (leg.color == "Amarela") TextOnYellow else Color.White,
                fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
        }
        LegMode.BOAT -> Row(
            Modifier.clip(RoundedCornerShape(5.dp)).background(busColor(leg.color.ifEmpty { "#0E7490" })).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val fg = busColor(com.example.metrotime.data.bus.BusColors.textOn(leg.color.ifEmpty { "#0E7490" }))
            Icon(BoatIcon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Text("Barco", color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        LegMode.TRAIN -> CpServiceBadge(leg.label, getServiceColor(leg.color))
        LegMode.BUS -> BusLineBadge(leg.label, leg.color, height = 22.dp, fontSize = 12.sp)
    }
}

/** Filtros dos transportes: Metro, Autocarro, Comboio e Barco (pelo menos um ligado). */
@Composable
private fun ModeFilters(modes: Set<LegMode>, onToggle: (LegMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(LegMode.METRO to "Metro", LegMode.BUS to "Autocarro", LegMode.TRAIN to "Comboio", LegMode.BOAT to "Barco").forEach { (mode, text) ->
            val on = mode in modes
            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) MetrotimeBlue.copy(alpha = 0.18f) else Color.Transparent)
                    .border(1.dp, if (on) MetrotimeBlue else Color(0xFF4A4D53), RoundedCornerShape(18.dp))
                    .clickable { onToggle(mode) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (mode) {
                    LegMode.METRO -> androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.example.metrotime.R.drawable.metro_logo),
                        contentDescription = null, modifier = Modifier.size(16.dp)
                    )
                    LegMode.BUS -> Icon(BusIcon, contentDescription = null, tint = if (on) Color.White else TextSecondaryDark, modifier = Modifier.size(16.dp))
                    LegMode.BOAT -> Icon(BoatIcon, contentDescription = null, tint = if (on) Color.White else TextSecondaryDark, modifier = Modifier.size(16.dp))
                    else -> Icon(TrainIcon, contentDescription = null, tint = if (on) Color.White else TextSecondaryDark, modifier = Modifier.size(16.dp))
                }
                Text(text, color = if (on) Color.White else TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Uma maneira de fazer a viagem: as linhas, a partida escolhida (hora de saída → chegada) e, por baixo, as
 * próximas partidas das próximas 5 horas para escolher. Aberta, mostra cada bocado com os atalhos dos avisos.
 */
@Composable
private fun AlternativeCard(
    activity: ComponentActivity,
    alt: PlanAlternative,
    index: Int,
    fastest: Boolean,
    expanded: Boolean,
    onSelect: (Int) -> Unit,
    onToggle: () -> Unit
) {
    val option = alt.options[index]
    val firstTransit = option.transit.firstOrNull()
    // Onde se muda de veículo (no resumo): "muda em Cais do Sodré"
    val changes = option.transit.drop(1).map { it.fromName }.distinct()
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = if (fastest) BorderStroke(1.dp, MetrotimeBlue.copy(alpha = 0.6f)) else null
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.depart.format(HM), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("  →  ", color = Color.Gray, fontSize = 14.sp)
                Text(option.arrive.format(HM), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (option.arrive.toLocalDate().isAfter(option.depart.toLocalDate())) Text("  +1 dia", color = Color.Gray, fontSize = 12.sp)
                if (fastest) {
                    Spacer(Modifier.width(8.dp))
                    Text("mais rápida", color = MetrotimeBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text(durationText(option.minutes), color = Color.LightGray, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                option.legs.forEachIndexed { i, leg ->
                    if (i > 0) Text("›", color = Color.Gray, fontSize = 13.sp)
                    LegChip(leg)
                }
            }
            val summary = when {
                firstTransit == null -> "A pé todo o caminho"
                else -> "${firstTransit.label} às ${firstTransit.depart.format(HM)} em ${firstTransit.fromName}" +
                    if (changes.isEmpty()) "" else " · muda em " + changes.joinToString(", ")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (firstTransit?.realtime == true) Icon(LiveIcon, contentDescription = "Tempo real", tint = GREEN, modifier = Modifier.size(12.dp))
                Text(summary, color = TextSecondaryDark, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            // Próximas partidas desta maneira (a hora do primeiro veículo)
            if (alt.options.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    alt.options.forEachIndexed { i, o ->
                        val on = i == index
                        val t = (o.transit.firstOrNull() ?: o.legs.first()).depart
                        Box(
                            modifier = Modifier
                                .heightIn(min = 30.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (on) MetrotimeBlue else DarkBackground)
                                .clickable { onSelect(i) }
                                .padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(t.format(HM), color = if (on) Color.White else Color(0xFFDADDE2), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (alt.frequent) {
                        val gap = Duration.between(alt.options[0].depart, alt.options[1].depart).toMinutes().coerceAtLeast(1)
                        Text("· metro a cada ~$gap min", color = TextSecondaryDark, fontSize = 12.sp)
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(color = DIVIDER)
                option.legs.forEachIndexed { i, leg -> LegDetail(activity, option, i, leg) }
            }
        }
    }
}

/**
 * "Chegar às…": a maneira que deixa sair mais tarde e ainda chegar a tempo, a hora de sair em grande, a folga, cada
 * bocado (com o caminho a pé) e o botão para a app avisar 5 minutos antes e na hora de sair.
 */
@Composable
private fun ArriveHero(activity: ComponentActivity, alt: PlanAlternative, target: LocalDateTime, destination: String) {
    val option = alt.first
    val slack = Duration.between(option.arrive, target).toMinutes()
    var armed by remember(option.key) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, MetrotimeBlue.copy(alpha = 0.7f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Para chegares a $destination às ${target.format(HM)}", color = TextSecondaryDark, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Sai às", color = Color(0xFFC9CCD2), fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.width(10.dp))
                Text(option.depart.format(HM), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Chegas às ${option.arrive.format(HM)} · " + if (slack <= 0) "mesmo a tempo" else "$slack min de folga",
                color = Color(0xFF6FCF73), fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                option.legs.forEachIndexed { i, leg ->
                    if (i > 0) Text("›", color = Color.Gray, fontSize = 13.sp)
                    LegChip(leg)
                }
            }
            HorizontalDivider(color = DIVIDER)
            option.legs.forEachIndexed { i, leg -> LegDetail(activity, option, i, leg) }
            Button(
                onClick = {
                    if (LeaveAlarm.schedule(activity, option, destination)) {
                        armed = true
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
                        }
                        Toast.makeText(
                            activity,
                            "Avisamos às ${option.depart.minusMinutes(5).format(HM)} e às ${option.depart.format(HM)}.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(activity, "A hora de sair já passou.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (armed) Color(0xFF2E7D32) else MetrotimeBlue)
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (armed) "Aviso ligado" else "Avisar-me quando tiver de sair", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(
                if (armed) "Tocamos 5 min antes e na hora de sair" else "5 min antes e na hora de sair",
                color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LegDetail(activity: ComponentActivity, option: PlanOption, index: Int, leg: PlanLeg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(leg.depart.format(HM), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(42.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegChip(leg)
                Text(
                    when (leg.mode) {
                        LegMode.WALK -> "A pé até ${leg.toName}"
                        else -> "${leg.fromName} → ${leg.toName}"
                    },
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                listOf(leg.detail, durationText(leg.minutes)).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextSecondaryDark, fontSize = 12.sp
            )
            LegActions(activity, option, index, leg)
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    val icon = if (text.startsWith("Ver o caminho")) WalkIcon else Icons.Filled.Notifications
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MetrotimeBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MetrotimeBlue, modifier = Modifier.size(14.dp))
        Text(text, color = MetrotimeBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LegActions(activity: ComponentActivity, option: PlanOption, index: Int, leg: PlanLeg) {
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    when (leg.mode) {
        LegMode.METRO -> {
            // No primeiro troço de uma sequência de metro: o aviso conta as mudanças até sair
            if (option.legs.getOrNull(index - 1)?.mode != LegMode.METRO) {
                actions += "Entrei: avisa-me antes de sair" to { startMetroAlarm(activity, option, index) }
            }
        }
        LegMode.TRAIN -> {
            val number = leg.trainNumber
            if (number != null) {
                actions += "Seguir comboio" to {
                    CpFollow.start(
                        activity,
                        CpFollow.Followed(
                            trainNumber = number,
                            runDate = (leg.runDate ?: CpTime.today()).toString(),
                            service = leg.service ?: "?",
                            destination = leg.trainDestination,
                            boardCode = leg.fromCode,
                            alightCode = leg.toCode,
                            alightName = leg.toName
                        )
                    )
                    Toast.makeText(activity, "A seguir o ${leg.label}: avisa de atrasos, da linha e antes de chegares a ${leg.toName}.", Toast.LENGTH_LONG).show()
                }
                actions += "Ver comboio" to {
                    activity.startActivity(CpTripDetailActivity.newIntent(activity, number, leg.runDate?.toString(), leg.fromCode, leg.toCode))
                }
            }
        }
        LegMode.BUS -> {
            val board = leg.busBoardStop
            if (board != null && leg.busBoardLat != null && leg.busBoardLon != null) {
                actions += "Avisar quando chegar" to { startBusFollow(activity, leg) }
            }
            if (leg.busAlightLat != null && leg.busAlightLon != null) {
                actions += "Avisa-me antes de sair" to {
                    if (ExitAlarm.startGps(activity, leg.label, leg.toName, leg.busAlightLat, leg.busAlightLon)) {
                        Toast.makeText(activity, "Aviso quando estiveres perto de ${leg.toName}.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, "Para avisar antes de sair é preciso acesso à localização.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        LegMode.BOAT -> {
            if (leg.toLat != null && leg.toLon != null) {
                actions += "Avisa-me antes de sair" to {
                    if (ExitAlarm.startGps(activity, "Barco", leg.toName, leg.toLat, leg.toLon)) {
                        Toast.makeText(activity, "Aviso quando estiveres perto de ${leg.toName}.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, "Para avisar antes de sair é preciso acesso à localização.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        LegMode.WALK -> {
            val lat = leg.toLat
            val lon = leg.toLon
            if (lat != null && lon != null) {
                // O primeiro bocado vai de onde estás; os outros (mudanças) de onde se sai do veículo anterior
                val next = option.legs.getOrNull(index + 1)
                actions += "Ver o caminho a pé" to {
                    activity.startActivity(
                        WalkRouteActivity.newIntent(
                            activity, lat, lon, leg.toName,
                            fromLat = if (index > 0) leg.fromLat else null,
                            fromLon = if (index > 0) leg.fromLon else null,
                            note = next?.let { "${it.label} às ${it.depart.format(HM)}" }
                        )
                    )
                }
            }
        }
    }
    if (actions.isEmpty()) return
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        actions.forEach { (text, action) -> ActionChip(text, action) }
    }
}

/** Avisos pelo relógio a partir de agora: antes de cada mudança de linha e antes da estação de saída. */
private fun startMetroAlarm(activity: ComponentActivity, option: PlanOption, index: Int) {
    val run = option.legs.drop(index).takeWhile { it.mode == LegMode.METRO }
    if (run.isEmpty()) return
    var t = System.currentTimeMillis()
    val steps = mutableListOf<ExitAlarm.Step>()
    run.forEachIndexed { i, leg ->
        val arrive = t + Duration.between(leg.depart, leg.arrive).toMillis()
        val next = run.getOrNull(i + 1)
        if (next != null) {
            steps += ExitAlarm.Step(
                arrive - 60_000L, "Muda na próxima: ${leg.toName}",
                "Sai em ${leg.toName} e apanha a ${next.label} ${next.detail.substringBefore(" ·")}."
            )
            t = arrive + Duration.between(leg.arrive, next.depart).toMillis()
        } else {
            steps += ExitAlarm.Step(arrive - 90_000L, "Prepara-te para sair", "A seguir chegas a ${leg.toName}.")
            ExitAlarm.startTimed(activity, "Metro", leg.toName, steps, arrive)
            val minutes = ((arrive - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
            Toast.makeText(activity, "Aviso antes de ${leg.toName} (daqui a cerca de $minutes min).", Toast.LENGTH_LONG).show()
        }
    }
}

/** Segue o autocarro desta opção até à paragem de entrada (o destino exato vem das chegadas da paragem). */
private fun startBusFollow(activity: ComponentActivity, leg: PlanLeg) {
    val stop = leg.busBoardStop ?: return
    activity.lifecycleScope.launch {
        val arrivals = withContext(Dispatchers.IO) {
            attempt { BusMapRepository.getStopArrivals(activity, stop, leg.busOperator ?: "carris") }
        }.orEmpty()
        val match = leg.vehicleId?.let { v -> arrivals.firstOrNull { it.vehicleId == v } }
            ?: arrivals.filter { it.routeShortName == leg.label }.minByOrNull { kotlin.math.abs(Duration.between(CpTime.now(), leg.depart).toMinutes() - it.etaMinutes) }
        val minutes = match?.etaMinutes?.toLong() ?: Duration.between(CpTime.now(), leg.depart).toMinutes().coerceAtLeast(0)
        val started = BusFollow.start(
            activity,
            BusFollow.Followed(
                operator = leg.busOperator ?: "carris",
                stopId = stop,
                stopName = leg.fromName,
                stopLat = leg.busBoardLat ?: 0.0,
                stopLon = leg.busBoardLon ?: 0.0,
                line = leg.label,
                destination = match?.destination.orEmpty(),
                vehicleId = match?.vehicleId?.takeIf { match.realtime } ?: leg.vehicleId,
                scheduledTime = match?.scheduledTime,
                dueAt = System.currentTimeMillis() + minutes * 60_000L
            )
        )
        Toast.makeText(
            activity,
            if (started) "Aviso quando o ${leg.label} estiver a 3 min de ${leg.fromName}." else "Já tens ${BusFollow.MAX_FOLLOWS} avisos de autocarro ligados.",
            Toast.LENGTH_LONG
        ).show()
    }
}
