package com.example.metrotime.ui

/** Textos da página inicial, sem dependências Android (testados em app/src/test). */
object HomeText {

    /** "Bom dia" até ao meio-dia, "Boa tarde" até às 20h, depois "Boa noite"; com o primeiro nome, se houver. */
    fun greeting(hour: Int, firstName: String?): String {
        val base = when (hour) {
            in 5..11 -> "Bom dia"
            in 12..19 -> "Boa tarde"
            else -> "Boa noite"
        }
        return if (firstName.isNullOrBlank()) base else "$base, $firstName"
    }

    /** Primeiro nome de "joão rafael" → "João". O nome por omissão dos perfis ("Utilizador") não conta. */
    fun firstName(full: String?): String? =
        full?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("Utilizador", ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase() }

    /** Espera do metro a partir dos segundos da API: "a chegar", "1 min", "4 min". */
    fun metroWait(seconds: Int): String = if (seconds <= 30) "a chegar" else "${(seconds + 30) / 60} min"

    /** Os metros seguintes: "depois 6 min", "depois 6 e 10 min"; null se não houver. */
    fun laterWaits(seconds: List<Int>): String? {
        if (seconds.isEmpty()) return null
        val minutes = seconds.map { ((it + 30) / 60).coerceAtLeast(1) }
        val list = if (minutes.size == 1) "${minutes[0]}" else minutes.dropLast(1).joinToString(", ") + " e ${minutes.last()}"
        return "depois $list min"
    }

    /** Há quanto tempo: "há 5 min", "há 2 h", "há 3 dias". */
    fun ago(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(1)
        return when {
            minutes < 60 -> "há $minutes min"
            minutes < 24 * 60 -> "há ${minutes / 60} h"
            else -> "há ${minutes / (24 * 60)} dias".replace("há 1 dias", "há 1 dia")
        }
    }
}
