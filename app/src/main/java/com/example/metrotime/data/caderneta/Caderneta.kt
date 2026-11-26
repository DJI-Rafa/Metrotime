package com.example.metrotime.data.caderneta

import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.history.Trip
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

// ─────────────────────────────────────────────────────────────────────────────
// Caderneta de estações: carimbos tirados das viagens e os 57 distintivos.
// Sem Android: tudo o que vem de fora (rede, estações da CP, perturbações) entra por parâmetro.
// ─────────────────────────────────────────────────────────────────────────────

enum class StampKind { PASSED, FULL }

/**
 * O carimbo de uma estação: cheio se entraste ou saíste lá, de passagem se só passaste.
 * [firstMs]: a primeira vez que lá estiveste; [firstFullMs]: a primeira vez que lá entraste ou saíste.
 */
data class Stamp(
    val key: String,
    val kind: StampKind,
    val firstMs: Long,
    val firstFullMs: Long?,
    val full: Int,
    val passed: Int
)

/** Os carimbos todos e, por dia, as estações carimbadas nesse dia. */
class StampBook(val stamps: Map<String, Stamp>, val byDay: Map<LocalDate, Set<String>>) {
    fun has(key: String): Boolean = stamps.containsKey(key)
    fun isFull(key: String): Boolean = stamps[key]?.kind == StampKind.FULL
    fun count(keys: Collection<String>, fullOnly: Boolean = false): Int = keys.count { if (fullOnly) isFull(it) else has(it) }
}

object Stamps {
    /** Chaves das estações na caderneta: "m:AM" (metro, id da API) e "c:94-31039" (CP, código). */
    fun metro(id: String) = "m:$id"
    fun cp(code: String) = "c:$code"

    /**
     * Os carimbos de todas as viagens: cheio na origem e no destino, de passagem nas estações pelo meio.
     * [metroPath]: as estações do metro de A a B, com as pontas; [cpPassed]: as estações da CP por onde a viagem passou,
     * sem as pontas.
     */
    fun collect(
        trips: List<Trip>,
        zone: ZoneId,
        metroPath: (String, String) -> List<String>?,
        cpPassed: (Trip) -> List<String>
    ): StampBook {
        class Acc(var kind: StampKind, var firstMs: Long, var firstFullMs: Long?, var full: Int, var passed: Int)
        val acc = LinkedHashMap<String, Acc>()
        val byDay = HashMap<LocalDate, MutableSet<String>>()
        fun add(key: String, kind: StampKind, at: Long, day: LocalDate) {
            val a = acc[key]
            if (a == null) {
                acc[key] = Acc(kind, at, if (kind == StampKind.FULL) at else null, if (kind == StampKind.FULL) 1 else 0, if (kind == StampKind.PASSED) 1 else 0)
            } else {
                a.firstMs = minOf(a.firstMs, at)
                if (kind == StampKind.FULL) {
                    a.kind = StampKind.FULL
                    a.full++
                    a.firstFullMs = a.firstFullMs?.let { minOf(it, at) } ?: at
                } else {
                    a.passed++
                }
            }
            byDay.getOrPut(day) { HashSet() } += key
        }
        for (t in trips.sortedBy { it.startMs }) {
            val day = Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate()
            val end = t.endMs ?: t.startMs
            val keyOf: (String) -> String = if (t.mode == "metro") ::metro else ::cp
            add(keyOf(t.fromId), StampKind.FULL, t.startMs, day)
            t.toId?.let { add(keyOf(it), StampKind.FULL, end, day) }
            val passed = if (t.mode == "metro") {
                val to = t.toId
                if (to == null) emptyList() else metroPath(t.fromId, to)?.let { if (it.size > 2) it.subList(1, it.size - 1) else emptyList() }.orEmpty()
            } else {
                cpPassed(t)
            }
            passed.forEach { add(keyOf(it), StampKind.PASSED, (t.startMs + end) / 2, day) }
        }
        val stamps = acc.mapValues { (key, a) -> Stamp(key, a.kind, a.firstMs, a.firstFullMs, a.full, a.passed) }
        return StampBook(stamps, byDay)
    }
}

// ── Distintivos ──────────────────────────────────────────────────────────────

enum class Tier(val title: String, val plural: String) {
    COMUM("Comum", "Comuns"), RARO("Raro", "Raros"), LENDARIO("Lendário", "Lendários"),
    IMPOSSIVEL("Impossível", "Impossíveis"), SECRETO("Secreto", "Secretos")
}

/** Um distintivo: [label] é o texto da fita por omissão; [soon]: ainda não se consegue ganhar nesta versão. */
data class BadgeDef(val id: String, val tier: Tier, val name: String, val how: String, val label: String = "", val soon: Boolean = false)

/**
 * O estado de um distintivo para ti: ganho ou não, o texto da fita (a linha, a região…) e quanto falta. [story]: o que
 * aconteceu quando o ganhaste, para o detalhe e a imagem de partilhar (no "Dia de greve": o comboio em que ias).
 * [why]: para o debug, porque ainda não está ganho (o que falta, o que mais perto chegou) ou de onde vem um guardado.
 */
data class BadgeState(
    val def: BadgeDef,
    val earned: Boolean,
    val label: String = def.label,
    val progress: Float? = null,
    val progressText: String? = null,
    val story: String? = null,
    val why: String? = null
)

object BadgeCatalog {
    val ALL: List<BadgeDef> = listOf(
        // Comuns
        BadgeDef("linhaAzul", Tier.COMUM, "Linha Azul completa", "Carimbo, cheio ou de passagem, nas 18 estações da Linha Azul."),
        BadgeDef("linhaAmarela", Tier.COMUM, "Linha Amarela completa", "Carimbo, cheio ou de passagem, nas 13 estações da Linha Amarela."),
        BadgeDef("linhaVerde", Tier.COMUM, "Linha Verde completa", "Carimbo, cheio ou de passagem, nas 13 estações da Linha Verde."),
        BadgeDef("linhaVermelha", Tier.COMUM, "Linha Vermelha completa", "Carimbo, cheio ou de passagem, nas 12 estações da Linha Vermelha."),
        BadgeDef("metroBronze", Tier.COMUM, "Metro bronze", "10 estações do metro com carimbo cheio."),
        BadgeDef("cpBronze", Tier.COMUM, "CP bronze", "10 estações da CP com carimbo cheio."),
        BadgeDef("doisMundos", Tier.COMUM, "Dois mundos", "Metro e comboio na mesma viagem, trocando numa estação onde os dois se encontram (Oriente, Cais do Sodré, Santa Apolónia, Entrecampos, Sete Rios…)."),
        BadgeDef("noctivago", Tier.COMUM, "Noctívago", "Apanhar o último metro da noite (depois da meia-noite e meia) ou um comboio depois da meia-noite."),
        BadgeDef("madrugador", Tier.COMUM, "Madrugador", "Começar uma viagem antes das 6:30."),
        BadgeDef("pontual", Tier.COMUM, "Pontual", "10 viagens de comboio que chegaram a horas."),
        BadgeDef("relampago", Tier.COMUM, "Relâmpago", "Ir num Alfa Pendular acima de 200 km/h durante 20 segundos seguidos."),
        // Raros
        BadgeDef("metroPrata", Tier.RARO, "Metro prata", "25 estações do metro com carimbo cheio."),
        BadgeDef("fimDaLinha", Tier.RARO, "Fim da linha", "Carimbo cheio nos 8 terminais do metro."),
        BadgeDef("noCentral", Tier.RARO, "Nó central", "Carimbo cheio nas 6 estações de correspondência do metro."),
        BadgeDef("voltaLisboa", Tier.RARO, "Volta a Lisboa", "Andar nas 4 linhas do metro no mesmo dia."),
        BadgeDef("linhaCp", Tier.RARO, "Linha completa da CP", "Carimbo, cheio ou de passagem, em todas as estações de uma linha da CP. Há um por cada linha."),
        BadgeDef("regiao", Tier.RARO, "Região", "Carimbo em todas as estações de uma região: Norte, Centro, Lisboa, Alentejo ou Algarve."),
        BadgeDef("cpPrata", Tier.RARO, "CP prata", "50 estações da CP com carimbo cheio."),
        BadgeDef("colecaoComboios", Tier.RARO, "Coleção de comboios", "Viajar em Alfa Pendular, Intercidades, Regional e Urbano."),
        BadgeDef("fimDoMundo", Tier.RARO, "Fim do mundo", "Chegar a um terminal remoto: Valença, Pocinho, Lagos ou Vila Real de Santo António."),
        BadgeDef("pacienciaSanto", Tier.RARO, "Paciência de Santo", "Ir num comboio que chegou com mais de 60 minutos de atraso.", label = "+60 MIN"),
        BadgeDef("sobrevivente", Tier.RARO, "Sobrevivente", "Viajar numa linha do metro enquanto ela tinha uma perturbação."),
        BadgeDef("oPrimeiro", Tier.RARO, "O Primeiro", "Apanhar o primeiro metro do dia (antes das 6:45)."),
        BadgeDef("ressacaAnoNovo", Tier.RARO, "Ressaca de Ano Novo", "Estar num metro ou comboio à meia-noite de 31 de dezembro."),
        BadgeDef("parabensMetro", Tier.RARO, "Parabéns, Metro", "Andar de metro a 29 de dezembro, o dia em que o Metro abriu, em 1959.", label = "1959"),
        BadgeDef("porDoSolTejo", Tier.RARO, "Pôr do sol no Tejo", "Ir na Linha de Cascais à hora do pôr do sol."),
        BadgeDef("mesmaCarruagem", Tier.RARO, "Mesma carruagem", "Ir no mesmo comboio que um amigo.", soon = true),
        BadgeDef("adiantado", Tier.RARO, "Adiantado", "Chegar num comboio da CP antes da hora marcada."),
        BadgeDef("milKm", Tier.RARO, "Mil quilómetros", "1.000 km de comboio, contados pelo carril.", label = "1.000 KM"),
        BadgeDef("habitue", Tier.RARO, "Habitué da Linha", "1.000 km na mesma linha da CP. Há um por cada linha."),
        BadgeDef("toupeira", Tier.RARO, "Toupeira", "1.000 km de metro."),
        // Lendários
        BadgeDef("metroOuro", Tier.LENDARIO, "Metro ouro", "As 50 estações do metro com carimbo cheio."),
        BadgeDef("cpOuro", Tier.LENDARIO, "CP ouro", "100 estações da CP com carimbo cheio."),
        BadgeDef("cpPlatina", Tier.LENDARIO, "CP platina", "250 estações da CP com carimbo cheio."),
        BadgeDef("minhoAlgarve", Tier.LENDARIO, "Do Minho ao Algarve", "Carimbo cheio na estação da CP mais a norte e na mais a sul de Portugal."),
        BadgeDef("relampagoIc", Tier.LENDARIO, "Relâmpago Intercidades", "Ir num Intercidades a 200 km/h ou mais durante 20 segundos seguidos.", label = "INTERCIDADES"),
        BadgeDef("maratonista", Tier.LENDARIO, "Maratonista", "300 km de comboio no mesmo dia.", label = "NUM DIA"),
        BadgeDef("metroNumDia", Tier.LENDARIO, "Metro num dia", "Passar pelas 50 estações do metro no mesmo dia.", label = "24 HORAS"),
        BadgeDef("douroAteAoFim", Tier.LENDARIO, "Douro até ao fim", "Chegar ao Pocinho, o fim da Linha do Douro.", label = "POCINHO"),
        BadgeDef("fiel", Tier.LENDARIO, "Fiel", "365 dias seguidos com pelo menos uma viagem.", label = "365 DIAS"),
        BadgeDef("pacienciaMonge", Tier.LENDARIO, "Paciência de Monge", "Ir num comboio que chegou com mais de 3 horas de atraso.", label = "+3 HORAS"),
        BadgeDef("diaDeGreve", Tier.LENDARIO, "Dia de greve", "Viajar num dos poucos comboios que circularam num dia de greve na CP: com aviso de greve da CP e mais comboios suprimidos do que a circular."),
        BadgeDef("cruzamento", Tier.LENDARIO, "Cruzamento", "Passar por um amigo que vai noutro comboio, em sentido contrário.", soon = true),
        BadgeDef("dezMil", Tier.LENDARIO, "Dez mil", "10.000 km de comboio.", label = "QUILÓMETROS"),
        BadgeDef("donoDaLinha", Tier.LENDARIO, "Dono da Linha", "10.000 km na mesma linha da CP. Há um por cada linha."),
        BadgeDef("reiSubsolo", Tier.LENDARIO, "Rei do Subsolo", "10.000 km de metro."),
        // Impossíveis
        BadgeDef("portugalInteiro", Tier.IMPOSSIVEL, "Portugal inteiro", "Carimbo em todas as estações da CP.", label = "456 ESTAÇÕES"),
        BadgeDef("os506", Tier.IMPOSSIVEL, "Os 506", "Todas as estações do metro e da CP: a caderneta completa.", label = "506 ESTAÇÕES"),
        BadgeDef("portugalNumDia", Tier.IMPOSSIVEL, "Portugal num dia", "Valença e Vila Real de Santo António no mesmo dia.", label = "NUM SÓ DIA"),
        BadgeDef("pontualidadeSuica", Tier.IMPOSSIVEL, "Pontualidade suíça", "50 viagens de CP seguidas sem um único minuto de atraso.", label = "50 A HORAS"),
        BadgeDef("voltaAoMundo", Tier.IMPOSSIVEL, "Volta ao Mundo", "40.075 km de comboio, a volta inteira à Terra, só nas linhas de Portugal.", label = "40.075 KM"),
        BadgeDef("milEUmaNoites", Tier.IMPOSSIVEL, "Mil e uma noites", "1001 viagens.", label = "1001 VIAGENS"),
        BadgeDef("pioneiro", Tier.IMPOSSIVEL, "Pioneiro", "Estar numa estação ou linha nova no dia em que abre. Fica à espera da Linha Circular e de Alcântara.", label = "DIA 1", soon = true),
        BadgeDef("todosABordo", Tier.IMPOSSIVEL, "Todos a bordo", "Andar em todos os comboios da CP: cada número de comboio do horário, pelo menos uma vez.", label = "CADA COMBOIO"),
        // Secretos
        BadgeDef("bicho", Tier.SECRETO, "Bicho", "Sair na estação Jardim Zoológico."),
        BadgeDef("capicua", Tier.SECRETO, "Capicua", "Ir num comboio com número capicua (por exemplo, 16061)."),
        BadgeDef("ateLua", Tier.SECRETO, "Até à Lua", "384.400 km de viagens: a distância até à Lua.", label = "384.400 KM")
    )

    val byId: Map<String, BadgeDef> = ALL.associateBy { it.id }

    /** As pistas dos secretos, enquanto ninguém sabe o que são. */
    val CLUES: Map<String, String> = mapOf(
        "bicho" to "Há uma estação com nome de animal.",
        "capicua" to "Há números que se leem nos dois sentidos.",
        "ateLua" to "É muito, muito longe."
    )
}

/** Uma estação da CP com as linhas e a região (da própria CP; vazias se não se souber). */
data class CpStationInfo(val code: String, val name: String, val lat: Double, val lon: Double, val lines: List<String>, val region: String?)

/** Uma perturbação de uma linha do metro, de [startMs] a [endMs]. */
data class Disruption(val line: String, val startMs: Long, val endMs: Long)

/** Tudo o que as regras precisam de saber. */
data class BadgeInput(
    val trips: List<Trip>,
    val book: StampBook,
    /** "Azul" → ids das estações, pela ordem da linha. */
    val metroLines: Map<String, List<String>>,
    val metroCoords: Map<String, Pair<Double, Double>>,
    val cpStations: List<CpStationInfo>,
    /** A linha da CP por onde a viagem foi (para os km por linha). */
    val cpLineOf: (Trip) -> String?,
    val longestStreak: Int,
    /** Km de comboio antigos sem viagem (histórico de antes das viagens). */
    val extraCpKm: Double,
    /** Quantos comboios diferentes tem o horário (para "Todos a bordo"). */
    val timetableTrains: Int,
    val disruptions: List<Disruption>,
    val zone: ZoneId,
    /** Id do metro → nome, para dizer que estações faltam ([BadgeState.why]). */
    val metroNames: Map<String, String> = emptyMap()
)

object Badges {

    private const val VALENCA = "94-7005"
    private const val POCINHO = "94-12005"
    private const val LAGOS = "94-90464"
    private const val VRSA = "94-73569"
    private val LISBON = 38.71 to -9.14

    fun evaluate(input: BadgeInput): List<BadgeState> {
        val r = Rules(input)
        return BadgeCatalog.ALL.map { def -> r.state(def) }
    }

    private class Rules(val i: BadgeInput) {
        val book = i.book
        val trips = i.trips.sortedBy { it.startMs }
        val metroTrips = trips.filter { it.mode == "metro" }
        val cpTrips = trips.filter { it.mode == "cp" }
        val metroAll: Set<String> = i.metroLines.values.flatten().toSet()
        val metroKeys = metroAll.map(Stamps::metro)
        val cpKeys = i.cpStations.map { Stamps.cp(it.code) }
        val metroFull = book.count(metroKeys, fullOnly = true)
        val cpFull = book.count(cpKeys, fullOnly = true)
        val metroKm = metroTrips.sumOf { it.km }
        val cpKm = cpTrips.sumOf { it.km } + i.extraCpKm

        fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(i.zone)
        fun timeOf(ms: Long): LocalTime = local(ms).toLocalTime()

        fun count(n: Int, goal: Int, unit: String = "") =
            Triple(n >= goal, (n.toFloat() / goal).coerceIn(0f, 1f), "${fmt(n.toDouble())} de ${fmt(goal.toDouble())}$unit")

        fun km(n: Double, goal: Double) =
            Triple(n >= goal, (n / goal).toFloat().coerceIn(0f, 1f), "${fmt(n)} de ${fmt(goal)} km")

        /** O estado e, se não está ganho, porquê ([BadgeState.why]). */
        fun state(def: BadgeDef): BadgeState {
            val s = rule(def)
            if (s.earned) return s
            val reason = if (def.soon) "Ainda não se ganha nesta versão." else runCatching { why(def.id) }.getOrNull()
            return s.copy(why = reason)
        }

        fun rule(def: BadgeDef): BadgeState {
            if (def.soon) return BadgeState(def, earned = false, progressText = "Ainda não conta: chega numa próxima versão.")
            val (earned, progress, text) = when (def.id) {
                "linhaAzul" -> line("Azul")
                "linhaAmarela" -> line("Amarela")
                "linhaVerde" -> line("Verde")
                "linhaVermelha" -> line("Vermelha")
                "metroBronze" -> count(metroFull, 10)
                "metroPrata" -> count(metroFull, 25)
                "metroOuro" -> count(metroFull, metroAll.size)
                "cpBronze" -> count(cpFull, 10)
                "cpPrata" -> count(cpFull, 50)
                "cpOuro" -> count(cpFull, 100)
                "cpPlatina" -> count(cpFull, 250)
                "doisMundos" -> flag(doisMundos())
                "noctivago" -> flag(trips.any { t ->
                    val h = timeOf(t.startMs)
                    if (t.mode == "metro") h >= LocalTime.of(0, 30) && h < LocalTime.of(5, 0) else h < LocalTime.of(5, 0)
                })
                "madrugador" -> flag(trips.any { val h = timeOf(it.startMs); h >= LocalTime.of(4, 30) && h < LocalTime.of(6, 30) })
                "pontual" -> count(cpTrips.count { it.delayMin != null && it.delayMin < 2 }, 10, " viagens")
                "relampago" -> flag(cpTrips.any { it.service == "AP" && (it.topKmh ?: 0.0) >= 200.0 })
                "relampagoIc" -> flag(cpTrips.any { it.service == "IC" && (it.topKmh ?: 0.0) >= 200.0 })
                "fimDaLinha" -> {
                    val ends = i.metroLines.values.flatMap { listOfNotNull(it.firstOrNull(), it.lastOrNull()) }.toSet()
                    count(ends.count { book.isFull(Stamps.metro(it)) }, ends.size)
                }
                "noCentral" -> {
                    val hubs = metroAll.filter { id -> i.metroLines.values.count { id in it } > 1 }
                    count(hubs.count { book.isFull(Stamps.metro(it)) }, hubs.size)
                }
                "voltaLisboa" -> flag(metroTrips.groupBy { local(it.startMs).toLocalDate() }.values.any { day ->
                    day.flatMap { ridden(it) }.toSet().containsAll(i.metroLines.keys)
                })
                "linhaCp" -> return groups(def, i.cpStations.flatMap { s -> s.lines.map { it to s.code } })
                "regiao" -> return groups(def, i.cpStations.mapNotNull { s -> s.region?.let { it to s.code } })
                "colecaoComboios" -> {
                    val used = cpTrips.mapNotNull { it.service }.toSet()
                    val kinds = listOf("AP" in used, "IC" in used, "R" in used || "IR" in used, "U" in used)
                    count(kinds.count { it }, 4, " tipos")
                }
                "fimDoMundo" -> flag(listOf(VALENCA, POCINHO, LAGOS, VRSA).any { book.isFull(Stamps.cp(it)) })
                "pacienciaSanto" -> flag(cpTrips.any { (it.delayMin ?: 0) >= 60 })
                "pacienciaMonge" -> flag(cpTrips.any { (it.delayMin ?: 0) >= 180 })
                "sobrevivente" -> flag(metroTrips.any { t ->
                    val end = t.endMs ?: (t.startMs + 30 * 60_000L)
                    val lines = ridden(t)
                    i.disruptions.any { d -> d.line in lines && t.startMs <= d.endMs + 10 * 60_000L && end >= d.startMs }
                })
                "oPrimeiro" -> flag(metroTrips.any { val h = timeOf(it.startMs); h >= LocalTime.of(6, 25) && h < LocalTime.of(6, 45) })
                "ressacaAnoNovo" -> flag(trips.any { t ->
                    val start = local(t.startMs)
                    val midnight = start.toLocalDate().let { d ->
                        if (d.monthValue == 12 && d.dayOfMonth == 31) d.plusDays(1) else if (d.monthValue == 1 && d.dayOfMonth == 1) d else null
                    }?.atStartOfDay(i.zone)?.toInstant()?.toEpochMilli()
                    midnight != null && t.startMs <= midnight + 5 * 60_000L && (t.endMs ?: t.startMs) >= midnight - 5 * 60_000L
                })
                "parabensMetro" -> flag(metroTrips.any { val d = local(it.startMs); d.monthValue == 12 && d.dayOfMonth == 29 })
                "porDoSolTejo" -> flag(cpTrips.any { t ->
                    val onCascais = t.fromId.startsWith("94-69") || t.toId?.startsWith("94-69") == true
                    val sunset = sunsetUtcMs(local(t.startMs).toLocalDate(), LISBON.first, LISBON.second)
                    onCascais && sunset != null && t.startMs <= sunset + 10 * 60_000L && (t.endMs ?: t.startMs) >= sunset - 10 * 60_000L
                })
                "adiantado" -> flag(cpTrips.any { (it.delayMin ?: 0) <= -1 })
                "milKm" -> km(cpKm, 1_000.0)
                "dezMil" -> km(cpKm, 10_000.0)
                "voltaAoMundo" -> km(cpKm, 40_075.0)
                "toupeira" -> km(metroKm, 1_000.0)
                "reiSubsolo" -> km(metroKm, 10_000.0)
                "ateLua" -> km(metroKm + cpKm, 384_400.0)
                "habitue" -> return kmByLine(def, 1_000.0)
                "donoDaLinha" -> return kmByLine(def, 10_000.0)
                "minhoAlgarve" -> {
                    // Só as de Portugal: a mais a norte da lista da CP é Vigo-Guixar, em Espanha
                    val pt = i.cpStations.filter { Regions.inPortugal(it.code) }
                    val north = pt.maxByOrNull { it.lat }
                    val south = pt.minByOrNull { it.lat }
                    val got = listOfNotNull(north, south).count { book.isFull(Stamps.cp(it.code)) }
                    count(got, 2)
                }
                "maratonista" -> {
                    val best = cpTrips.groupBy { local(it.startMs).toLocalDate() }.values.maxOfOrNull { day -> day.sumOf { it.km } } ?: 0.0
                    km(best, 300.0)
                }
                "metroNumDia" -> {
                    val best = book.byDay.values.maxOfOrNull { day -> metroKeys.count { it in day } } ?: 0
                    count(best, metroKeys.size)
                }
                "douroAteAoFim" -> flag(book.isFull(Stamps.cp(POCINHO)))
                "fiel" -> count(i.longestStreak, 365, " dias")
                "diaDeGreve" -> {
                    // A fita diz o comboio e o dia da greve (e é por ela que se sabe que foi dado pela regra de agora, [trusted])
                    val first = cpTrips.firstOrNull { it.strikeDay } ?: return BadgeState(def, false)
                    val day = local(first.endMs ?: first.startMs).toLocalDate()
                    return BadgeState(def, true, strikeLabel(first.service, first.trainNumber, day), story = strikeStory(first, day))
                }
                "portugalInteiro" -> count(book.count(cpKeys), cpKeys.size)
                "os506" -> count(book.count(cpKeys) + book.count(metroKeys), cpKeys.size + metroKeys.size)
                "portugalNumDia" -> flag(book.byDay.values.any { Stamps.cp(VALENCA) in it && Stamps.cp(VRSA) in it })
                "pontualidadeSuica" -> {
                    var run = 0
                    var best = 0
                    for (t in cpTrips) {
                        val d = t.delayMin ?: continue
                        run = if (d <= 0) run + 1 else 0
                        best = maxOf(best, run)
                    }
                    count(best, 50, " seguidas")
                }
                "milEUmaNoites" -> count(trips.size, 1001, " viagens")
                "todosABordo" -> count(cpTrips.mapNotNull { it.trainNumber }.toSet().size, i.timetableTrains.coerceAtLeast(1), " comboios")
                "bicho" -> flag(book.isFull(Stamps.metro("JZ")))
                "capicua" -> flag(cpTrips.any { t -> t.trainNumber?.let { it.length >= 3 && it == it.reversed() } == true })
                else -> flag(false)
            }
            return BadgeState(def, earned, def.label, progress, text)
        }

        fun flag(ok: Boolean) = Triple(ok, null as Float?, null as String?)

        /**
         * As linhas do metro por onde a viagem foi de certeza. Com destino, as do percurso. Sem destino (Iniciar viagem
         * que não acabou, visitas antigas), a viagem guarda as linhas todas da estação: numa correspondência (Alameda:
         * Verde e Vermelha) não se sabe qual se apanhou, por isso só conta a estação com uma linha só.
         */
        fun ridden(t: Trip): List<String> =
            if (t.toId != null && t.toId != t.fromId) t.lines
            else i.metroLines.filterValues { t.fromId in it }.keys.singleOrNull()?.let(::listOf).orEmpty()

        fun line(name: String): Triple<Boolean, Float?, String?> {
            val ids = i.metroLines[name].orEmpty()
            return count(ids.count { book.has(Stamps.metro(it)) }, ids.size.coerceAtLeast(1), " estações")
        }

        /** Linhas ou regiões da CP: ganho se uma estiver completa; a fita diz qual (ou a que está mais perto). */
        fun groups(def: BadgeDef, pairs: List<Pair<String, String>>): BadgeState {
            val byGroup = pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
            if (byGroup.isEmpty()) return BadgeState(def, false, def.label, null, "Ainda sem a lista das estações da CP.")
            val scored = byGroup.map { (g, codes) -> Triple(g, codes.count { book.has(Stamps.cp(it)) }, codes.size) }
            val best = scored.maxWithOrNull(compareBy<Triple<String, Int, Int>>({ it.second.toFloat() / it.third }, { it.second }))!!
            val done = scored.filter { it.second == it.third }
            val label = shortLabel(best.first)
            val text = if (done.isNotEmpty()) "${done.size} completa${if (done.size > 1) "s" else ""}: ${done.joinToString { it.first }}"
            else "${best.first}: ${best.second} de ${best.third}"
            return BadgeState(def, done.isNotEmpty(), label, best.second.toFloat() / best.third, text)
        }

        fun kmByLine(def: BadgeDef, goal: Double): BadgeState {
            val byLine = cpTrips.mapNotNull { t -> i.cpLineOf(t)?.let { it to t.km } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val best = byLine.maxByOrNull { it.value }
                ?: return BadgeState(def, false, def.label, 0f, "0 de ${fmt(goal)} km")
            return BadgeState(def, best.value >= goal, shortLabel(best.key), (best.value / goal).toFloat().coerceIn(0f, 1f), "${best.key}: ${fmt(best.value)} de ${fmt(goal)} km")
        }

        fun doisMundos(): Boolean {
            for (k in 1 until trips.size) {
                val a = trips[k - 1]
                val b = trips[k]
                if (a.mode == b.mode) continue
                val gap = b.startMs - (a.endMs ?: a.startMs)
                if (gap < -5 * 60_000L || gap > 45 * 60_000L) continue
                val p = pointOf(a, a.toId ?: a.fromId) ?: continue
                val q = pointOf(b, b.fromId) ?: continue
                if (metres(p, q) <= 800.0) return true
            }
            return false
        }

        fun pointOf(t: Trip, id: String): Pair<Double, Double>? =
            if (t.mode == "metro") i.metroCoords[id] else i.cpStations.firstOrNull { it.code == id }?.let { it.lat to it.lon }

        // ── Porque ainda não está ganho (para o debug) ──

        val cpByCode by lazy { i.cpStations.associateBy { it.code } }
        fun metroName(id: String) = i.metroNames[id] ?: id
        fun cpName(code: String) = cpByCode[code]?.name ?: code
        fun hm(ms: Long): String = timeOf(ms).let { "%d:%02d".format(it.hour, it.minute) }
        fun day(ms: Long): String = local(ms).toLocalDate().let { "${it.dayOfMonth}/${it.monthValue}/${it.year}" }

        /** Uma lista de nomes, as primeiras [max] e quantas mais. */
        fun names(list: List<String>, max: Int = 8): String =
            list.take(max).joinToString(", ") + if (list.size > max) " e mais ${list.size - max}" else ""

        fun stampText(key: String) = when {
            book.isFull(key) -> "cheio"
            book.has(key) -> "só de passagem"
            else -> "sem carimbo"
        }

        /** Segundos desde as 4:00 (o "dia" das viagens: a noite fica no fim). */
        fun fromFour(t: Trip) = (timeOf(t.startMs).toSecondOfDay() - 4 * 3600 + 86_400) % 86_400

        /** A viagem que começou mais cedo no dia, entre [list]. */
        fun earliest(list: List<Trip>): Trip? = list.minByOrNull(::fromFour)

        /** A viagem que começou mais tarde na noite, entre [list]. */
        fun latestAtNight(list: List<Trip>): Trip? = list.maxByOrNull(::fromFour)

        fun fastest(service: String, name: String, goal: Int): String {
            val rides = cpTrips.filter { it.service == service }
            if (rides.isEmpty()) return "Ainda não tens viagens de $name registadas."
            val top = rides.mapNotNull { it.topKmh }.maxOrNull()
                ?: return "Tens ${rides.size} viagem(ns) de $name, mas sem velocidade medida (leituras de GPS com mais de 60 m de erro, ou poucas)."
            return "O teu $name mais rápido: ${top.toInt()} km/h mantidos 20 s seguidos (precisa de $goal)."
        }

        fun latest(goalMin: Int): String {
            val delays = cpTrips.mapNotNull { it.delayMin }
            if (delays.isEmpty()) return "Nenhuma viagem de comboio com atraso conhecido (o atraso lê-se à chegada à estação onde sais)."
            return "O maior atraso que apanhaste: ${delays.max()} min (precisa de mais de $goalMin)."
        }

        fun missingStations(def: String, ids: List<String>, full: Boolean): String {
            val missing = ids.filter { if (full) !book.isFull(Stamps.metro(it)) else !book.has(Stamps.metro(it)) }
            val kind = if (full) "sem carimbo cheio" else "sem carimbo"
            return if (missing.isEmpty()) "Não falta nenhuma ($def)." else "Faltam ${missing.size} $kind: ${names(missing.map(::metroName))}."
        }

        fun cpCount(): String {
            if (i.cpStations.isEmpty()) return "Sem a lista das estações da CP (abre o separador Comboios com rede)."
            val passed = cpKeys.count { book.has(it) && !book.isFull(it) }
            return "Só contam carimbos cheios (entrar ou sair na estação). Tens $cpFull cheios e $passed só de passagem."
        }

        /** Linhas ou regiões da CP: a que está mais perto e as estações que lhe faltam. */
        fun groupWhy(pairs: List<Pair<String, String>>, what: String): String {
            if (pairs.isEmpty()) return "Sem a lista das estações da CP com as $what."
            val byGroup = pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
            val best = byGroup.maxWithOrNull(compareBy({ g -> g.value.count { book.has(Stamps.cp(it)) }.toFloat() / g.value.size }, { g -> g.value.size }))!!
            val missing = best.value.filter { !book.has(Stamps.cp(it)) }
            return "Mais perto: ${best.key}, faltam ${missing.size}: ${names(missing.map(::cpName))}."
        }

        fun why(id: String): String? = when (id) {
            "linhaAzul", "linhaAmarela", "linhaVerde", "linhaVermelha" -> {
                val line = id.removePrefix("linha")
                missingStations("Linha $line", i.metroLines[line].orEmpty(), full = false)
            }
            "metroBronze", "metroPrata" -> {
                val passed = metroKeys.count { book.has(it) && !book.isFull(it) }
                "Só contam carimbos cheios (entrar ou sair na estação). Tens $metroFull cheios e $passed só de passagem."
            }
            "metroOuro" -> missingStations("metro", metroAll.toList(), full = true)
            "cpBronze", "cpPrata", "cpOuro", "cpPlatina" -> cpCount()
            "doisMundos" ->
                if (metroTrips.isEmpty() || cpTrips.isEmpty()) "Precisas de viagens dos dois: tens ${metroTrips.size} de metro e ${cpTrips.size} de comboio."
                else "Nenhuma troca entre metro e comboio: a viagem seguinte tem de começar até 45 min depois e a menos de 800 m de onde acabou a anterior."
            "noctivago" -> {
                val m = latestAtNight(metroTrips)
                val c = latestAtNight(cpTrips)
                "Precisa de um metro a começar entre 0:30 e 5:00, ou um comboio entre a meia-noite e as 5:00. " +
                    "A tua viagem que começou mais tarde na noite: " +
                    listOfNotNull(m?.let { "metro às ${hm(it.startMs)}" }, c?.let { "comboio às ${hm(it.startMs)}" }).ifEmpty { listOf("nenhuma") }.joinToString(", ") + "."
            }
            "madrugador" -> earliest(trips)?.let { "Precisa de começar uma viagem entre 4:30 e 6:30. A tua mais cedo no dia: ${hm(it.startMs)} (${day(it.startMs)})." }
                ?: "Ainda não tens viagens."
            "pontual" -> {
                val known = cpTrips.count { it.delayMin != null }
                "A horas (menos de 2 min de atraso): ${cpTrips.count { it.delayMin != null && it.delayMin < 2 }}. Viagens de comboio sem atraso conhecido: ${cpTrips.size - known}."
            }
            "relampago" -> fastest("AP", "Alfa Pendular", 200)
            "relampagoIc" -> fastest("IC", "Intercidades", 200)
            "fimDaLinha" -> missingStations("terminais", i.metroLines.values.flatMap { listOfNotNull(it.firstOrNull(), it.lastOrNull()) }.distinct(), full = true)
            "noCentral" -> missingStations("correspondências", metroAll.filter { s -> i.metroLines.values.count { s in it } > 1 }, full = true)
            "voltaLisboa" -> {
                val best = metroTrips.groupBy { local(it.startMs).toLocalDate() }
                    .mapValues { (_, day) -> day.flatMap { ridden(it) }.toSet() }
                    .maxByOrNull { it.value.size }
                if (best == null) "Ainda não tens viagens de metro."
                else "O dia com mais linhas: ${best.key.dayOfMonth}/${best.key.monthValue}, com ${best.value.sorted().joinToString(", ").ifEmpty { "nenhuma certa" }}; " +
                    "faltou ${(i.metroLines.keys - best.value).joinToString(", ")}. Uma viagem só com origem numa correspondência não diz a linha."
            }
            "linhaCp" -> groupWhy(i.cpStations.flatMap { s -> s.lines.map { it to s.code } }, "linhas")
            "regiao" -> groupWhy(i.cpStations.mapNotNull { s -> s.region?.let { it to s.code } }, "regiões")
            "colecaoComboios" -> {
                val used = cpTrips.mapNotNull { it.service }.toSet()
                val missing = listOfNotNull(
                    "Alfa Pendular".takeIf { "AP" !in used }, "Intercidades".takeIf { "IC" !in used },
                    "Regional ou InterRegional".takeIf { "R" !in used && "IR" !in used }, "Urbano".takeIf { "U" !in used }
                )
                "Falta${if (missing.size > 1) "m" else ""}: ${missing.joinToString(", ")}." +
                    if (cpTrips.any { it.service == null }) " ${cpTrips.count { it.service == null }} viagem(ns) de comboio sem serviço conhecido." else ""
            }
            "fimDoMundo" -> "Precisa de carimbo cheio (sair lá). " +
                listOf(VALENCA, POCINHO, LAGOS, VRSA).joinToString("; ") { "${cpName(it)}: ${stampText(Stamps.cp(it))}" } + "."
            "pacienciaSanto" -> latest(60)
            "pacienciaMonge" -> latest(180)
            "sobrevivente" ->
                if (i.disruptions.isEmpty()) "Não há perturbações do metro registadas nos últimos 90 dias (só se registam com os alertas das linhas ligados)."
                else "Houve ${i.disruptions.size} perturbação(ões) (${i.disruptions.map { it.line }.distinct().joinToString(", ")}), mas nenhuma viagem tua nessa linha durante elas."
            "oPrimeiro" -> earliest(metroTrips)?.let { "Precisa de um metro a começar entre 6:25 e 6:45. O teu metro mais cedo no dia: ${hm(it.startMs)}." }
                ?: "Ainda não tens viagens de metro."
            "ressacaAnoNovo" -> "Só à meia-noite de 31 de dezembro (5 min antes ou depois), num metro ou comboio."
            "parabensMetro" -> "Só a 29 de dezembro, com uma viagem de metro."
            "porDoSolTejo" -> {
                val cascais = cpTrips.count { it.fromId.startsWith("94-69") || it.toId?.startsWith("94-69") == true }
                if (cascais == 0) "Ainda não tens viagens de comboio de ou para a Linha de Cascais."
                else "Tens $cascais viagem(ns) na Linha de Cascais, nenhuma à hora do pôr do sol (10 min antes ou depois)."
            }
            "adiantado" -> {
                val delays = cpTrips.mapNotNull { it.delayMin }
                if (delays.isEmpty()) "Nenhuma viagem de comboio com atraso conhecido."
                else "Nenhuma chegada adiantada: a melhor foi ${delays.min().let { if (it <= 0) "a horas" else "+$it min" }} (${delays.size} com atraso conhecido)."
            }
            "milKm", "dezMil", "voltaAoMundo" -> "Comboio: ${fmt(cpTrips.sumOf { it.km })} km das viagens + ${fmt(i.extraCpKm)} km do histórico antigo."
            "habitue", "donoDaLinha" -> {
                val unknown = cpTrips.count { i.cpLineOf(it) == null }
                if (unknown == 0) null else "$unknown viagem(ns) de comboio sem linha conhecida: contam para os km, mas não para uma linha."
            }
            "minhoAlgarve" -> {
                val pt = i.cpStations.filter { Regions.inPortugal(it.code) }
                val north = pt.maxByOrNull { it.lat }
                val south = pt.minByOrNull { it.lat }
                if (north == null || south == null) "Sem a lista das estações da CP."
                else "Precisa de carimbo cheio nas duas. ${north.name}: ${stampText(Stamps.cp(north.code))}; ${south.name}: ${stampText(Stamps.cp(south.code))}."
            }
            "maratonista" -> cpTrips.groupBy { local(it.startMs).toLocalDate() }.maxByOrNull { (_, d) -> d.sumOf { it.km } }
                ?.let { (d, list) -> "O teu dia com mais comboio: ${d.dayOfMonth}/${d.monthValue}, ${fmt(list.sumOf { it.km })} km." }
                ?: "Ainda não tens viagens de comboio."
            "metroNumDia" -> book.byDay.maxByOrNull { (_, keys) -> metroKeys.count { it in keys } }
                ?.let { (d, keys) -> "O teu dia com mais estações do metro: ${d.dayOfMonth}/${d.monthValue}, ${metroKeys.count { it in keys }}." }
                ?: "Ainda não tens viagens de metro."
            "douroAteAoFim" -> "Pocinho: ${stampText(Stamps.cp(POCINHO))} (precisa de cheio: sair lá)."
            "diaDeGreve" -> "Nenhuma viagem de comboio num dia de greve (${cpTrips.size} viagem(ns) de comboio). Vê-se no fim de cada viagem: " +
                "aviso de greve da CP para esse dia, 100 ou mais suprimidos e mais suprimidos do que a circular. Viagens de antes da regra nova não contam."
            "portugalNumDia" -> {
                val v = book.byDay.count { Stamps.cp(VALENCA) in it.value }
                val r = book.byDay.count { Stamps.cp(VRSA) in it.value }
                "Valença em $v dia(s), Vila Real de Santo António em $r dia(s), nunca no mesmo."
            }
            "bicho" -> "Jardim Zoológico: ${stampText(Stamps.metro("JZ"))} (precisa de cheio: sair lá)."
            "capicua" -> "${cpTrips.mapNotNull { it.trainNumber }.toSet().size} comboio(s) diferentes, nenhum com número capicua de 3 ou mais algarismos."
            else -> null
        }
    }

    /** "Linha de Sintra" → "SINTRA"; "Linha do Norte" → "NORTE"; regiões em maiúsculas. */
    fun shortLabel(name: String): String =
        name.substringAfterLast(" – ")
            .replace(Regex("^(Linha|Ramal)\\s+(de|do|da|dos|das)\\s+", RegexOption.IGNORE_CASE), "")
            .uppercase().take(16)

    fun fmt(v: Double): String {
        val n = Math.round(v)
        return n.toString().reversed().chunked(3).joinToString(".").reversed()
    }

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = cos(Math.toRadians((a.first + b.first) / 2))
        val dy = (a.first - b.first) * 111_320.0
        val dx = (a.second - b.second) * 111_320.0 * k
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Hora do pôr do sol (ms, UTC) num dia e num sítio (algoritmo do almanaque; erro de 1 ou 2 minutos). */
    fun sunsetUtcMs(date: LocalDate, lat: Double, lon: Double): Long? {
        fun rad(d: Double) = Math.toRadians(d)
        fun deg(r: Double) = Math.toDegrees(r)
        fun norm(v: Double, m: Double) = ((v % m) + m) % m
        val n = date.dayOfYear
        val lngHour = lon / 15.0
        val t = n + ((18.0 - lngHour) / 24.0)
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH < -1 || cosH > 1) return null
        val h = deg(acos(cosH)) / 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        val ut = norm(localT - lngHour, 24.0)
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + (ut * 3_600_000).toLong()
    }

    private val MONTHS = listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")
    private val MONTH_NAMES = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )
    private val STRIKE_LABEL = MONTHS.joinToString("|").let { m -> Regex("(([A-Z]{1,2} )?\\d{1,6} · \\d{1,2} ($m)|\\d{1,2} ($m) \\d{4})") }

    /** O serviço do comboio, se for um dos da CP (AP, IC, IR, R, U, IN, FT). */
    private fun serviceOf(service: String?): String? = service?.trim()?.takeIf { it in CpService.ORDER }

    /**
     * A fita do "Dia de greve": o comboio e o dia ("IC 521 · 24 NOV"). Sem número do comboio, só o dia ("24 NOV 2026").
     */
    fun strikeLabel(service: String?, number: String?, day: LocalDate): String {
        val date = "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
        val n = number?.trim()?.takeIf { it.length in 1..6 && it.all(Char::isDigit) } ?: return "$date ${day.year}"
        return listOfNotNull(serviceOf(service), n).joinToString(" ") + " · " + date
    }

    /**
     * O que conta o "Dia de greve" no detalhe e na imagem de partilhar: "Intercidades 521, de Lisboa - Oriente para
     * Coimbra-B, a 24 de novembro de 2026: um dos poucos comboios que circularam num dia de greve na CP."
     */
    fun strikeStory(t: Trip, day: LocalDate): String {
        val service = serviceOf(t.service)?.let(CpService::longName)
        val number = t.trainNumber?.trim()?.takeIf { it.isNotEmpty() }
        val train = when {
            service != null && number != null -> "$service $number"
            service != null -> service
            number != null -> "Comboio $number"
            else -> "Comboio"
        }
        val route = if (t.toName != null) "de ${t.fromName} para ${t.toName}" else "de ${t.fromName}"
        return "$train, $route, a ${day.dayOfMonth} de ${MONTH_NAMES[day.monthValue - 1]} de ${day.year}: " +
            "um dos poucos comboios que circularam num dia de greve na CP."
    }

    /**
     * Um distintivo guardado (ganho antes, aqui ou no Firebase) vale? Não valem os dados por uma regra errada:
     *  - a "Região" chegou a ser dada por uma zona da CP (ex.: COIMBRA) em vez de uma das 5 regiões;
     *  - o "Dia de greve" chegou a ser dado sem greve (bastavam mais suprimidos do que a circular, o que acontece de
     *    noite); o da regra de agora tem o comboio e o dia na fita.
     */
    fun trusted(id: String, label: String?): Boolean = when (id) {
        "regiao" -> Regions.isRegionLabel(label)
        "diaDeGreve" -> label != null && STRIKE_LABEL.matches(label)
        else -> true
    }

    /**
     * Os distintivos guardados ([kept]: id → texto da fita) ficam ganhos mesmo que as regras de agora não os deem: as
     * regras dependem de dados que podem faltar por um tempo (a lista das linhas da CP, as perturbações dos últimos 90
     * dias) e um distintivo ganho não se perde por isso. Só não vale um guardado com uma regra errada ([trusted]).
     */
    fun withKept(states: List<BadgeState>, kept: Map<String, String>): List<BadgeState> = states.map { s ->
        val label = kept[s.def.id]
        when {
            s.earned || s.def.soon || label == null -> s
            !trusted(s.def.id, label) -> s.copy(
                why = listOfNotNull(s.why, "Estava guardado${if (label.isNotEmpty()) " (\"$label\")" else ""}, dado por uma regra antiga que estava errada: já não vale.")
                    .joinToString(" ")
            )
            else -> s.copy(
                earned = true, label = label.ifEmpty { s.def.label }, progress = null, progressText = null,
                why = "Ganho antes e guardado (aqui ou no Firebase): as viagens deste telemóvel, sozinhas, já não o dão."
            )
        }
    }

    /** Os distintivos ganhos que ainda não estavam em [known] (os que já se festejaram). */
    fun newlyEarned(states: List<BadgeState>, known: Set<String>): List<BadgeState> =
        states.filter { it.earned && it.def.id !in known }.sortedByDescending { it.def.tier.ordinal.coerceAtMost(Tier.IMPOSSIVEL.ordinal) }
}
