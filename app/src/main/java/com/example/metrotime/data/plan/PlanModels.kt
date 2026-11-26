package com.example.metrotime.data.plan

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Um sítio de partida ou de chegada: a localização atual, uma estação de metro ou da CP, uma paragem, ou uma morada
 * (rua, localidade ou sítio da pesquisa de moradas, com [detail] a dizer onde fica: "Rua · Santa Maria Maior, Lisboa").
 */
data class Place(val kind: Kind, val id: String, val name: String, val lat: Double, val lon: Double, val detail: String = "") {
    /** HERE = a localização no momento da pesquisa; POINT = um ponto fixo (a casa guardada pela localização); ADDRESS = morada. */
    enum class Kind { HERE, METRO, CP, BUS, POINT, ADDRESS }

    /** Para guardar (casa, pesquisas recentes): "TIPO|id|nome|lat|lon|detalhe". */
    fun encode(): String = listOf(kind.name, id, clean(name), lat, lon, clean(detail)).joinToString("|")

    companion object {
        private fun clean(s: String) = s.replace("|", "/").replace("\n", " ").replace("\t", " ")

        fun decode(s: String?): Place? {
            val p = s?.split("|")?.takeIf { it.size == 5 || it.size == 6 } ?: return null
            val kind = runCatching { Kind.valueOf(p[0]) }.getOrNull() ?: return null
            val lat = p[3].toDoubleOrNull() ?: return null
            val lon = p[4].toDoubleOrNull() ?: return null
            return Place(kind, p[1], p[2], lat, lon, p.getOrNull(5).orEmpty())
        }
    }
}

/** Andar a pé (como no resto da app: cerca de 80 m por minuto em linha reta). */
object Walk {
    fun minutes(metres: Double): Int = if (metres < 40) 0 else kotlin.math.ceil(metres / 80).toInt()
}

enum class LegMode { WALK, METRO, TRAIN, BUS, BOAT }

/** Uma indicação do caminho a pé ("Vira à esquerda para a Rua Augusta", 120 m). */
data class WalkStep(val text: String, val metres: Double, val turn: Turn) {
    enum class Turn { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }
}

/** Um bocado da viagem, com o que é preciso para os atalhos (seguir comboio, avisar antes de sair…). */
data class PlanLeg(
    val mode: LegMode,
    val depart: LocalDateTime,
    val arrive: LocalDateTime,
    val fromName: String,
    val toName: String,
    /** "Linha Azul", "IC 523", "728", "A pé". */
    val label: String,
    /** "sentido Santa Apolónia · 5 estações", "linha 3", "tempo real"… */
    val detail: String = "",
    /** Nome da linha do metro, serviço da CP ("IC") ou cor do autocarro ("#FFCC00"). */
    val color: String = "",
    val realtime: Boolean = false,
    /** Metro: estações do troço (ids da API). */
    val metroStations: List<String> = emptyList(),
    val metroTowards: String? = null,
    /** Comboio. */
    val trainNumber: Int? = null,
    val runDate: LocalDate? = null,
    val service: String? = null,
    val trainDestination: String? = null,
    val fromCode: String? = null,
    val toCode: String? = null,
    /** Autocarro: paragem onde se entra, onde se sai (com coordenadas) e o veículo, se tiver GPS. */
    val busOperator: String? = null,
    val busBoardStop: String? = null,
    val busBoardLat: Double? = null,
    val busBoardLon: Double? = null,
    val busAlightStop: String? = null,
    val busAlightLat: Double? = null,
    val busAlightLon: Double? = null,
    val busDestination: String? = null,
    val vehicleId: String? = null,
    /** A empresa ("Fertagus", "MobiCascais", "Metro do Porto"), quando a viagem vem do planeador online. */
    val agency: String? = null,
    /** Onde começa e acaba (para o caminho a pé e o aviso antes de sair). */
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val toLat: Double? = null,
    val toLon: Double? = null,
    /** O caminho a pé pelas ruas (lat, lon) e as indicações, quando se sabem. */
    val path: List<Pair<Double, Double>> = emptyList(),
    val walkSteps: List<WalkStep> = emptyList(),
    /** Metros a pé (pelas ruas, ou em linha reta se não se souber o caminho). */
    val metres: Double? = null
) {
    val minutes: Long get() = Duration.between(depart, arrive).toMinutes()
}

data class PlanOption(val legs: List<PlanLeg>) {
    val depart: LocalDateTime get() = legs.first().depart
    val arrive: LocalDateTime get() = legs.last().arrive
    val minutes: Long get() = Duration.between(depart, arrive).toMinutes()
    val transit: List<PlanLeg> get() = legs.filter { it.mode != LegMode.WALK }
    val walkMinutes: Long get() = legs.filter { it.mode == LegMode.WALK }.sumOf { it.minutes }

    /** Para não repetir a mesma viagem encontrada por dois caminhos. */
    val key: String get() = transit.joinToString(">") { "${it.mode}:${it.label}:${it.depart}" }.ifEmpty { "walk" }

    /** A mesma viagem mesmo quando as horas mudam com o tempo real (linhas e comboios, sem horas). */
    val stableKey: String get() = transit.joinToString(">") { "${it.mode}:${it.label}:${it.fromName}" }.ifEmpty { "walk" }

    /**
     * A "maneira" de fazer a viagem, para juntar as partidas da mesma alternativa: as linhas por ordem (os comboios
     * pelas estações, porque cada partida é um comboio com outro número).
     */
    val signature: String get() = transit.joinToString(">") {
        if (it.mode == LegMode.TRAIN) "TRAIN:${it.fromName}:${it.toName}" else "${it.mode}:${it.label}"
    }.ifEmpty { "walk" }
}

/** Uma maneira de fazer a viagem e as próximas partidas dela (da mais cedo para a mais tarde). */
data class PlanAlternative(
    val options: List<PlanOption>,
    /** Só metro: passa a toda a hora, mostram-se poucas partidas. */
    val frequent: Boolean = false
) {
    val first: PlanOption get() = options.first()
    val signature: String get() = first.signature
}
