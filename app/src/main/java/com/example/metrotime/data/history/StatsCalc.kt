package com.example.metrotime.data.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Estatísticas das viagens (Trip) por período (7 dias, este mês, sempre), sem dependências Android
 * (testadas em app/src/test). O ecrã está em StatsActivity.
 */
object StatsCalc {

    enum class Period { WEEK, MONTH, ALL }

    /** Uma barra do gráfico: rótulo por baixo (vazio para não encher), viagens e se é hoje / este mês. */
    data class Bar(val label: String, val value: Int, val current: Boolean)

    /** Comparação com o período anterior; [trend] 1 = mais, -1 = menos, 0 = neutro. */
    data class Change(val text: String, val trend: Int)

    /**
     * Uma linha do metro ou um serviço da CP: a chave ("Azul", "IC", "CP" nos comboios antigos), o nome
     * para mostrar, as viagens, os km e os minutos a bordo.
     */
    data class LineCount(val key: String, val name: String, val count: Int, val km: Double = 0.0, val minutes: Int = 0) {
        val isTrain: Boolean get() = isTrainKey(key)
    }

    /** Uma estação com as linhas que lá passam (as do metro pelo nome; "CP" nos comboios). */
    data class StationCount(val name: String, val type: String, val lines: List<String>, val count: Int)

    data class Summary(
        val total: Int,
        val change: Change,
        val bars: List<Bar>,
        val km: Double,
        /** Minutos a bordo (só das viagens com fim conhecido). */
        val travelMinutes: Int,
        val distinctStations: Int,
        val lines: List<LineCount>,
        val stations: List<StationCount>,
        /** Viagens por hora do dia (0 a 23). */
        val hours: List<Int>,
        /** As últimas viagens do período, da mais recente para trás. */
        val recent: List<Trip> = emptyList()
    )

    /** Dias seguidos com viagens (até hoje ou ontem), o recorde e esta semana de segunda a domingo. */
    data class Streak(val current: Int, val longest: Int, val week: List<Boolean?>, val todayIndex: Int)

    val METRO_LINES = listOf("Azul", "Amarela", "Verde", "Vermelha")

    private val WEEKDAY = listOf("S", "T", "Q", "Q", "S", "S", "D")
    private val MONTH_SHORT = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    private val MONTH_LONG = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )

    private fun dateOf(t: Trip, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate()

    /** [legacyKm]: km dos comboios do histórico antigo, sem data (só entram em "sempre"). */
    fun summary(trips: List<Trip>, period: Period, today: LocalDate, zone: ZoneId, legacyKm: Double = 0.0): Summary {
        val sorted = trips.sortedBy { it.startMs }
        val (from, to) = range(period, today, sorted, zone)
        val inPeriod = sorted.filter { dateOf(it, zone) in from..to }
        val hours = MutableList(24) { 0 }
        inPeriod.forEach { hours[Instant.ofEpochMilli(it.startMs).atZone(zone).hour]++ }
        return Summary(
            total = inPeriod.size,
            change = change(period, sorted, today, zone, inPeriod.size),
            bars = bars(period, sorted, today, zone),
            km = inPeriod.sumOf { it.km } + if (period == Period.ALL) legacyKm else 0.0,
            travelMinutes = inPeriod.sumOf { it.minutes ?: 0 },
            distinctStations = inPeriod.flatMap { listOfNotNull(it.fromName, it.toName) }.distinct().size,
            lines = lineCounts(inPeriod),
            stations = stationCounts(inPeriod),
            hours = hours,
            recent = inPeriod.sortedByDescending { it.startMs }.take(5)
        )
    }

    private fun range(period: Period, today: LocalDate, sorted: List<Trip>, zone: ZoneId): Pair<LocalDate, LocalDate> = when (period) {
        Period.WEEK -> today.minusDays(6) to today
        Period.MONTH -> today.withDayOfMonth(1) to today
        Period.ALL -> (sorted.firstOrNull()?.let { dateOf(it, zone) } ?: today) to today
    }

    private fun count(sorted: List<Trip>, zone: ZoneId, from: LocalDate, to: LocalDate) =
        sorted.count { dateOf(it, zone) in from..to }

    /**
     * 7 dias: mais ou menos viagens que nos 7 dias antes. Este mês: em percentagem, contra os mesmos dias
     * do mês passado (comparar meio mês com um mês inteiro dava sempre "menos"). Sempre: desde quando.
     */
    fun change(period: Period, sorted: List<Trip>, today: LocalDate, zone: ZoneId, total: Int): Change = when (period) {
        Period.WEEK -> {
            val prev = count(sorted, zone, today.minusDays(13), today.minusDays(7))
            val diff = total - prev
            when {
                diff > 0 -> Change("+$diff que na semana anterior", 1)
                diff < 0 -> Change("$diff que na semana anterior", -1)
                else -> Change("igual à semana anterior", 0)
            }
        }
        Period.MONTH -> {
            val lastMonth = today.minusMonths(1)
            val start = lastMonth.withDayOfMonth(1)
            val end = lastMonth.withDayOfMonth(minOf(today.dayOfMonth, lastMonth.lengthOfMonth()))
            val prev = count(sorted, zone, start, end)
            val name = MONTH_LONG[lastMonth.monthValue - 1]
            when {
                prev == 0 && total == 0 -> Change("", 0)
                prev == 0 -> Change("sem viagens em $name", 0)
                else -> {
                    val pct = ((total - prev) * 100.0 / prev).roundToInt()
                    when {
                        pct > 0 -> Change("+$pct% que em $name", 1)
                        pct < 0 -> Change("$pct% que em $name", -1)
                        else -> Change("igual a $name", 0)
                    }
                }
            }
        }
        Period.ALL -> {
            val first = sorted.firstOrNull()?.let { dateOf(it, zone) }
            if (first == null) Change("", 0)
            else Change("desde ${MONTH_LONG[first.monthValue - 1]}" + if (first.year != today.year) " de ${first.year}" else "", 0)
        }
    }

    /** 7 dias: um por dia (S T Q…); este mês: um por dia (rótulos 1, 8, 15…); sempre: um por mês (até 12). */
    fun bars(period: Period, sorted: List<Trip>, today: LocalDate, zone: ZoneId): List<Bar> {
        val byDay = sorted.groupingBy { dateOf(it, zone) }.eachCount()
        return when (period) {
            Period.WEEK -> (6 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                Bar(WEEKDAY[d.dayOfWeek.value - 1], byDay[d] ?: 0, back == 0)
            }
            Period.MONTH -> (1..today.dayOfMonth).map { day ->
                val d = today.withDayOfMonth(day)
                Bar(if ((day - 1) % 7 == 0) "$day" else "", byDay[d] ?: 0, day == today.dayOfMonth)
            }
            Period.ALL -> {
                val first = sorted.firstOrNull()?.let { dateOf(it, zone).withDayOfMonth(1) } ?: today.withDayOfMonth(1)
                val thisMonth = today.withDayOfMonth(1)
                val months = (ChronoUnit.MONTHS.between(first, thisMonth).toInt()).coerceIn(0, 11)
                val byMonth = sorted.groupingBy { dateOf(it, zone).withDayOfMonth(1) }.eachCount()
                (months downTo 0).map { back ->
                    val m = thisMonth.minusMonths(back.toLong())
                    Bar(MONTH_SHORT[m.monthValue - 1], byMonth[m] ?: 0, back == 0)
                }
            }
        }
    }

    /** Serviços da CP (os comboios antigos, sem serviço guardado, ficam como "CP"). */
    val TRAIN_SERVICES = listOf("AP", "IC", "IR", "R", "U", "IN", "FT")

    fun isTrainKey(key: String) = key == "CP" || key in TRAIN_SERVICES

    /** Chave de um comboio: o serviço (Alfa, IC, Urbano…) ou "CP" se não se sabe. */
    fun trainKey(t: Trip): String = t.service?.takeIf { it in TRAIN_SERVICES } ?: t.lines.firstOrNull { it in TRAIN_SERVICES } ?: "CP"

    /**
     * Viagens por linha do metro e por serviço da CP: cada viagem conta uma vez em cada linha do metro por
     * onde passou; os comboios no serviço deles. Os km e os minutos vão para cada uma.
     */
    fun lineCounts(trips: List<Trip>, max: Int = 8): List<LineCount> {
        data class Acc(var count: Int = 0, var km: Double = 0.0, var minutes: Int = 0)
        val acc = LinkedHashMap<String, Acc>()
        for (t in trips) {
            val keys = if (t.mode == "cp") listOf(trainKey(t)) else t.lines.filter { it in METRO_LINES }.distinct()
            keys.forEach { k -> acc.getOrPut(k) { Acc() }.apply { count++; km += t.km; minutes += t.minutes ?: 0 } }
        }
        return acc.entries.sortedByDescending { it.value.count }.take(max)
            .map { (k, a) -> LineCount(k, lineName(k), a.count, a.km, a.minutes) }
    }

    /** Estações por onde entraste ou saíste (cada ponta da viagem conta), com as linhas que lá passam. */
    fun stationCounts(trips: List<Trip>, max: Int = 5): List<StationCount> {
        data class End(val id: String, val name: String, val trip: Trip)
        val ends = trips.flatMap { t ->
            listOfNotNull(End(t.fromId, t.fromName, t), t.toId?.let { id -> End(id, t.toName ?: id, t) })
        }
        return ends.groupBy { it.name to it.trip.mode }.map { (key, list) ->
            val (name, mode) = key
            val lines = if (mode == "cp") listOf("CP")
            else com.example.metrotime.data.MetroLines.linesOf(list.first().id).ifEmpty { list.first().trip.lines.filter { it in METRO_LINES } }
            StationCount(name, mode, lines, list.size)
        }.sortedByDescending { it.count }.take(max)
    }

    /** "Verde Vermelha" → [Verde, Vermelha]; só nomes de linhas do metro. */
    fun metroLines(lineName: String): List<String> =
        lineName.split(Regex("\\s+")).map { it.trim() }.filter { n -> METRO_LINES.any { it.equals(n, ignoreCase = true) } }
            .map { n -> METRO_LINES.first { it.equals(n, ignoreCase = true) } }
            .distinct()

    /** Nome para mostrar: "Linha Azul", "Alfa Pendular", "Urbanos", "Comboios CP"; o que vier de fora fica como está. */
    fun lineName(key: String): String = when (key) {
        "CP" -> "Comboios CP"
        "U" -> "Urbanos"
        in TRAIN_SERVICES -> com.example.metrotime.data.cp.CpService.longName(key)
        in METRO_LINES -> "Linha $key"
        else -> key
    }

    // ── Últimas viagens ─────────────────────────────────────────────────────

    /** "Alameda → Saldanha"; só a estação se não se sabe o destino. */
    fun tripTitle(t: Trip): String = if (t.toName != null) "${t.fromName} → ${t.toName}" else t.fromName

    /**
     * O detalhe de uma viagem: no metro as linhas, no comboio o serviço e o número ("IC 521"); depois o
     * tempo a bordo e os km, se se souberem.
     */
    fun tripDetail(t: Trip): String {
        val what = if (t.mode == "cp") {
            val key = trainKey(t)
            when {
                key == "CP" -> "Comboio" + (t.trainNumber?.let { " $it" } ?: "")
                t.trainNumber != null -> "$key ${t.trainNumber}"
                else -> com.example.metrotime.data.cp.CpService.longName(key)
            }
        } else {
            val lines = t.lines.filter { it in METRO_LINES }
            when {
                lines.isEmpty() -> "Metro"
                lines.size == 1 -> "Linha ${lines[0]}"
                t.toId != null -> "Linhas " + lines.joinToString(" → ")
                else -> "Linhas " + lines.joinToString(" e ")
            }
        }
        return listOfNotNull(
            what,
            t.minutes?.takeIf { it > 0 }?.let { duration(it) },
            t.km.takeIf { it >= 0.05 }?.let { distance(it) }
        ).joinToString(" · ")
    }

    /** "hoje, 21:14", "ontem, 08:02", "23 out, 18:30". */
    fun whenText(t: Trip, today: LocalDate, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(t.startMs).atZone(zone)
        val hm = "%02d:%02d".format(at.hour, at.minute)
        val day = at.toLocalDate()
        return when (day) {
            today -> "hoje, $hm"
            today.minusDays(1) -> "ontem, $hm"
            else -> "${day.dayOfMonth} ${MONTH_SHORT[day.monthValue - 1]}, $hm"
        }
    }

    /**
     * Dias seguidos com viagens até hoje (ou até ontem, se hoje ainda não houve), o recorde (o maior de
     * sempre, ou o guardado se for maior) e os dias desta semana, de segunda a domingo (null = ainda não chegou).
     */
    fun streak(trips: List<Trip>, today: LocalDate, zone: ZoneId, storedLongest: Int = 0): Streak {
        val days = trips.map { dateOf(it, zone) }.toSortedSet()
        var current = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) { current++; d = d.minusDays(1) }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (day in days) {
            run = if (prev != null && day == prev.plusDays(1)) run + 1 else 1
            longest = maxOf(longest, run)
            prev = day
        }
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val week = (0 until 7).map { i ->
            val day = monday.plusDays(i.toLong())
            if (day.isAfter(today)) null else day in days
        }
        return Streak(current, maxOf(longest, storedLongest, current), week, today.dayOfWeek.value - 1)
    }

    /**
     * As horas a que mais viajas: a de mais viagens e, se houver, outra a pelo menos 3 h de distância com
     * pelo menos metade das viagens dessa (a manhã e o fim da tarde).
     */
    fun peakHours(hours: List<Int>): List<Int> {
        val top = hours.indices.maxByOrNull { hours[it] } ?: return emptyList()
        if (hours[top] == 0) return emptyList()
        val second = hours.indices
            .filter { kotlin.math.abs(it - top) >= 3 && hours[it] * 2 >= hours[top] && hours[it] > 0 }
            .maxByOrNull { hours[it] }
        return listOfNotNull(top, second).sorted()
    }

    fun peakText(peaks: List<Int>): String = when (peaks.size) {
        0 -> ""
        1 -> "Mais vezes por volta das ${peaks[0]}h"
        else -> "Mais vezes por volta das ${peaks[0]}h e das ${peaks[1]}h"
    }

    /** "45 min", "6 h 40", "33 h". */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 || minutes >= 10 * 60 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
    }

    /** "0 km", "850 m", "4,2 km", "86 km" (sem casas decimais a partir de 10 km). */
    fun distance(km: Double): String = when {
        km < 0.05 -> "0 km"
        km < 1 -> "${(km * 1000).roundToInt()} m"
        km < 10 -> "%.1f km".format(km).replace('.', ',')
        else -> "${km.roundToInt()} km"
    }

}
