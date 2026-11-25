package com.example.metrotime.data.cp

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────────────────────────────────────
// Utilitários da CP sem dependências Android (testáveis em JVM pura).
//
// Unidades de atraso usadas pelas várias APIs:
//   • horários da estação (stationStops[].delay)  → MINUTOS (null = sem dados; -1 = suprimido)
//   • detalhe do comboio (trainStops[].delay)     → MINUTOS
//   • veículos em circulação (vehicles[].delay)   → SEGUNDOS
// ──────────────────────────────────────────────────────────────────────────────

object CpTime {
    /** Os horários da CP estão sempre na hora de Lisboa, independentemente do fuso do telemóvel. */
    val LISBON: ZoneId = ZoneId.of("Europe/Lisbon")
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun now(): LocalDateTime = LocalDateTime.now(LISBON)
    fun today(): LocalDate = LocalDate.now(LISBON)

    fun formatHm(t: LocalTime): String = t.format(HM)

    /** "HH:mm" ou "HH:mm:ss" → LocalTime. Devolve null para valores inválidos. */
    fun parseHm(s: String?): LocalTime? {
        if (s.isNullOrBlank()) return null
        val parts = s.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    fun parseDate(s: String?): LocalDate? =
        s?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }
}

/** Pedido ao endpoint de horários de uma estação. */
data class CpTimetableQuery(val date: LocalDate, val start: String)

/** Janela mostrada no ecrã da estação: de 5 min atrás até 5 h à frente, atravessando a meia-noite. */
object CpTimetableWindow {
    const val PAST_MINUTES = 5L
    const val AHEAD_HOURS = 5L

    /** Que dias (e a partir de que hora) é preciso pedir à API para cobrir a janela. */
    fun queriesFor(now: LocalDateTime, aheadHours: Long = AHEAD_HOURS): List<CpTimetableQuery> {
        val start = now.minusMinutes(PAST_MINUTES)
        val end = now.plusHours(aheadHours)
        val today = now.toLocalDate()
        val firstStart = if (start.toLocalDate() == today) start.toLocalTime() else LocalTime.MIDNIGHT
        val queries = mutableListOf(CpTimetableQuery(today, CpTime.formatHm(firstStart)))
        if (end.toLocalDate().isAfter(today)) {
            queries += CpTimetableQuery(today.plusDays(1), "00:00")
        }
        return queries
    }

    /**
     * Data real de uma paragem devolvida num pedido feito para [queryDate] a partir de [queryStart].
     * Se a hora for muito anterior ao início do pedido (ex.: 00:30 num pedido feito às 21:00),
     * trata-se de um comboio depois da meia-noite, logo do dia seguinte.
     */
    fun resolveDate(queryDate: LocalDate, queryStart: LocalTime, time: LocalTime): LocalDate =
        if (queryStart.hour >= 12 && time.isBefore(queryStart.minusHours(12))) queryDate.plusDays(1) else queryDate

    /**
     * Mantém a paragem se a hora efetiva (prevista + atraso) não passou há mais de 5 min
     * e a hora prevista é antes do fim da janela.
     */
    fun isInWindow(date: LocalDate, scheduled: LocalTime, delayMinutes: Int?, now: LocalDateTime): Boolean {
        val sched = LocalDateTime.of(date, scheduled)
        val effective = if (delayMinutes != null && delayMinutes > 0) sched.plusMinutes(delayMinutes.toLong()) else sched
        return !effective.isBefore(now.minusMinutes(PAST_MINUTES)) && sched.isBefore(now.plusHours(AHEAD_HOURS))
    }
}

/** Estado de pontualidade mostrado ao utilizador. */
enum class CpDelayState { SCHEDULED, ON_TIME, DELAYED, SUPPRESSED }

object CpDelay {
    /**
     * Para atrasos em MINUTOS (horários e detalhe).
     * null ou -1 = sem dados em tempo real → "Previsto"; 0 ou negativo = a horas; > 0 = atrasado.
     */
    fun stateFromMinutes(delayMinutes: Int?, suppressed: Boolean): CpDelayState = when {
        suppressed -> CpDelayState.SUPPRESSED
        delayMinutes == null || delayMinutes == -1 -> CpDelayState.SCHEDULED
        delayMinutes <= 0 -> CpDelayState.ON_TIME
        else -> CpDelayState.DELAYED
    }

    /** Veículos: segundos → minutos arredondados (59 s conta como 1 min, não como "a horas"). */
    fun secondsToMinutes(seconds: Int?): Int? = seconds?.let { Math.round(it / 60.0).toInt() }
}

/** Filtro de comboios "fantasma" (presos na cache das APIs) — mesma regra que a Cloud Function. */
object CpVehicleFilter {
    private val ENDED = setOf("COMPLETED", "CANCELLED", "UNKNOWN")
    private val RUNNING = setOf("IN_TRANSIT", "AT_STATION", "NEAR_NEXT", "AT_ORIGIN")

    fun isActive(status: String?, runDate: String?, delaySeconds: Int?, hasGps: Boolean, today: LocalDate): Boolean {
        if (status in ENDED) return false
        val date = CpTime.parseDate(runDate)
        if (date != null) {
            if (date.isAfter(today) || date.isBefore(today.minusDays(1))) return false
            // Comboio de ontem só conta se ainda estiver efetivamente a circular (passou a meia-noite)
            if (date.isBefore(today) && status !in RUNNING) return false
        }
        val delay = delaySeconds ?: 0
        if (delay > 6 * 3600) return false
        if (status == "NOT_STARTED" && (delay > 3600 || !hasGps)) return false
        if (status == "NULL" && !hasGps) return false
        return true
    }
}

object CpText {
    private val MARKS = Regex("\\p{Mn}+")
    private val WORD = Regex("\\p{L}+")
    private val TAGS = Regex("<[^>]*>")

    /** Minúsculas e sem acentos, para pesquisar "Valença" e encontrar "Valenca" (e vice-versa). */
    fun normalize(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().trim()

    fun matches(text: String?, query: String): Boolean =
        text != null && normalize(text).contains(normalize(query))

    /** A API devolve os nomes sem acentos ("Valenca", "Porto Campanha"); repõe os mais comuns. */
    private val ACCENTS = mapOf(
        "Sao" to "São", "Joao" to "João", "Apolonia" to "Apolónia", "Campanha" to "Campanhã",
        "Valenca" to "Valença", "Setubal" to "Setúbal", "Santarem" to "Santarém", "Evora" to "Évora",
        "Covilha" to "Covilhã", "Famalicao" to "Famalicão", "Guimaraes" to "Guimarães",
        "Portimao" to "Portimão", "Olhao" to "Olhão", "Loule" to "Loulé", "Regua" to "Régua",
        "Pinhao" to "Pinhão", "Agueda" to "Águeda", "Sodre" to "Sodré", "Belem" to "Belém",
        "Alges" to "Algés", "Paco" to "Paço", "Cacem" to "Cacém", "Melecas" to "Meleças",
        "Sacavem" to "Sacavém", "Povoa" to "Póvoa", "Braco" to "Braço", "Abraao" to "Abraão",
        "Alcantara" to "Alcântara", "Antonio" to "António", "Conceicao" to "Conceição",
        "Estacao" to "Estação", "Braganca" to "Bragança", "Agua" to "Água",
        "Fatima" to "Fátima", "Obidos" to "Óbidos", "Alcacer" to "Alcácer", "Grandola" to "Grândola"
    )

    fun restoreAccents(name: String): String =
        WORD.replace(name) { m -> ACCENTS[m.value] ?: m.value }

    /** Texto simples a partir do HTML das mensagens da CP. */
    fun stripHtml(html: String): String =
        TAGS.replace(html.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
            .lines().joinToString("\n") { it.trim() }.trim()

    private val SERVICE_LABELS = mapOf(
        "ATM" to "Multibanco", "TICKET_OFFICE" to "Bilheteira", "TICKET_MACHINE" to "Máquina de bilhetes",
        "LUGGAGE_LOCKER" to "Cacifos", "METRO" to "Metro", "BUS" to "Autocarro", "TAXI" to "Táxi",
        "PARKING" to "Estacionamento", "BICYCLE_PARKING" to "Bicicletas", "INFO_OFFICE" to "Informações",
        "WAITTING_ROOM" to "Sala de espera", "WAITING_ROOM" to "Sala de espera", "WC" to "WC",
        "DISABILITY_WC" to "WC acessível", "INFO_SCREEN" to "Painéis", "AUDIO_INFO" to "Info sonora",
        "RENT_A_CAR" to "Rent-a-car", "SHOPPING" to "Lojas"
    )

    fun serviceLabel(code: String): String =
        SERVICE_LABELS[code] ?: code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Uma partida ou chegada tal como aparece no quadro da estação (CpStationActivity): a hora grande é a estimada
 * pela CP (ETD/ETA) quando o comboio vai atrasado, com a prevista riscada; o atraso e a contagem contam-se pela
 * hora efetiva (prevista + atraso). Os outros quadros (Perto de mim, As minhas estações, favoritas) usam isto
 * para nunca dizerem uma hora diferente da do quadro da estação.
 */
data class CpBoardEntry(
    val timed: CpTimedStop,
    /** Hora prevista, com a data certa. */
    val scheduled: LocalDateTime,
    /** Hora a mostrar em grande. */
    val shown: String,
    /** Hora prevista riscada (só quando vai atrasado). */
    val struck: String?,
    /** Quando parte (ou chega) mesmo, pela regra do percurso do comboio ([CpTripClock.momentOf]). Ordena os quadros. */
    val effective: LocalDateTime,
    /** Minutos de atraso desta partida (ou chegada): esperada − prevista; null = sem dados em tempo real. */
    val delayMinutes: Int?,
    val suppressed: Boolean,
    /** Minutos até à hora efetiva; 0 ou menos = "agora". */
    val minutesLeft: Long
) {
    val stop: CpStationStop get() = timed.stop
    val late: Boolean get() = !suppressed && (delayMinutes ?: 0) > 0

    /** "agora", "17 min"; null se suprimido ou a mais de uma hora. */
    val countdown: String? get() = when {
        suppressed -> null
        minutesLeft <= 0 -> "agora"
        minutesLeft < 60 -> "$minutesLeft min"
        else -> null
    }

    /** "Suprimido", "+4 min", "Previsto" ou "Na hora". */
    val status: String get() = when {
        suppressed -> "Suprimido"
        late -> "+$delayMinutes min"
        delayMinutes == null -> "Previsto"
        else -> "Na hora"
    }
}

object CpBoard {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Chegada e partida esperadas de um comboio numa estação, com a regra do percurso do comboio
     * ([CpTripClock.knownDelay] e [CpTripClock.momentOf]): o quadro da estação e o ecrã do comboio dão a mesma hora.
     */
    fun expected(stop: CpStationStop, scheduledArrival: LocalDateTime?, scheduledDeparture: LocalDateTime?): CpTripClock.StopMoment {
        val eta = scheduledArrival?.let { s -> stop.eta?.takeIf { CpTime.parseHm(it) != null }?.let { effectiveOf(s, it, null) } }
        val etd = scheduledDeparture?.let { s -> stop.etd?.takeIf { CpTime.parseHm(it) != null }?.let { effectiveOf(s, it, null) } }
        val known = CpTripClock.knownDelay(scheduledArrival, scheduledDeparture, eta, etd, stop.delay)
        return CpTripClock.momentOf(scheduledArrival, scheduledDeparture, eta, etd, known ?: 0)
    }

    /** A hora [time] ("17:50") no dia de [at] (o da véspera se for muito depois, o do dia seguinte se muito antes). */
    fun sameDay(at: LocalDateTime, time: String?): LocalDateTime? {
        val t = CpTime.parseHm(time) ?: return null
        var d = LocalDateTime.of(at.toLocalDate(), t)
        if (d.isAfter(at.plusHours(12))) d = d.minusDays(1)
        if (d.isBefore(at.minusHours(12))) d = d.plusDays(1)
        return d
    }

    /** A linha de [t] no quadro de partidas ([departures]) ou de chegadas; null se o comboio não parte/chega aqui. */
    fun entry(t: CpTimedStop, departures: Boolean, now: LocalDateTime): CpBoardEntry? {
        val stop = t.stop
        val scheduledArrival = t.dateTimeOf(stop.arrivalTime)
        val scheduledDeparture = t.dateTimeOf(stop.departureTime)
        val scheduled = (if (departures) scheduledDeparture else scheduledArrival) ?: return null
        val suppressed = stop.supression != null || stop.delay == -1
        val hasData = !suppressed && (stop.eta != null || stop.etd != null || (stop.delay ?: -1) >= 0)
        val m = expected(stop, scheduledArrival, scheduledDeparture)
        val effective = (if (departures) m.departure ?: m.arrival else m.arrival ?: m.departure) ?: scheduled
        // Adiantado conta como a horas (e -1 é o código da CP para suprimido)
        val delay = if (hasData) java.time.Duration.between(scheduled, effective).toMinutes().toInt().coerceAtLeast(0) else null
        val late = !suppressed && (delay ?: 0) >= 1
        return CpBoardEntry(
            timed = t,
            scheduled = scheduled,
            shown = (if (late) effective else scheduled).format(HM),
            struck = if (late) scheduled.format(HM) else null,
            effective = if (suppressed) scheduled else effective,
            delayMinutes = delay,
            suppressed = suppressed,
            minutesLeft = java.time.Duration.between(now, if (suppressed) scheduled else effective).toMinutes()
        )
    }

    /**
     * Hora efetiva: a estimada pela CP ("18:01", no dia certo: uma estimada muito antes da prevista é do dia
     * seguinte), senão a prevista mais o atraso.
     */
    fun effectiveOf(scheduled: LocalDateTime, estimated: String?, delayMinutes: Int?): LocalDateTime {
        val est = CpTime.parseHm(estimated)
        if (est != null) {
            var at = LocalDateTime.of(scheduled.toLocalDate(), est)
            if (at.isBefore(scheduled.minusHours(12))) at = at.plusDays(1)
            if (at.isAfter(scheduled.plusHours(12))) at = at.minusDays(1)
            return at
        }
        return scheduled.plusMinutes((delayMinutes ?: 0).coerceAtLeast(0).toLong())
    }

    /** Hora esperada da partida ([departures]) ou chegada de [t], prevista para [at] (a mesma do quadro). */
    fun effectiveOf(t: CpTimedStop, at: LocalDateTime, departures: Boolean): LocalDateTime =
        entry(t, departures, at)?.effective ?: at

    /**
     * O quadro como no ecrã da estação: só os que ainda não partiram há mais de 5 min, por ordem de partida
     * (ou chegada) efetiva: um comboio atrasado passa para depois dos que saem antes dele.
     */
    fun board(stops: List<CpTimedStop>, departures: Boolean, now: LocalDateTime): List<CpBoardEntry> =
        stops.mapNotNull { entry(it, departures, now) }
            .filter { CpTimetableWindow.isInWindow(it.scheduled.toLocalDate(), it.scheduled.toLocalTime(), it.delayMinutes, now) }
            .sortedWith(compareBy<CpBoardEntry>({ it.effective }, { it.scheduled }))
}
