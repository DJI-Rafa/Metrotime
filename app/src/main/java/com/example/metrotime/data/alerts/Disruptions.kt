package com.example.metrotime.data.alerts

import com.example.metrotime.data.cp.CpNotice

// ──────────────────────────────────────────────────────────────────────────────
// Alertas de perturbação: regras sem dependências Android (testadas em app/src/test).
// ──────────────────────────────────────────────────────────────────────────────

/** Estado de uma linha do metro, como vem do estadoLinha/todos. [message] vazio quando está tudo normal. */
data class MetroLineStatus(val line: String, val ok: Boolean, val message: String)

object MetroLineStatusParser {
    /** Nome da linha, chave no JSON e sufixo dos campos tipo_msg_*. */
    private val LINES = listOf(
        Triple("Azul", "azul", "az"),
        Triple("Amarela", "amarela", "am"),
        Triple("Verde", "verde", "vd"),
        Triple("Vermelha", "vermelha", "vm")
    )

    val LINE_NAMES: List<String> = LINES.map { it.first }

    /**
     * O campo "resposta" do estadoLinha/todos: {"verde": " Ok", "verde_curta": "normal", ...}.
     * Fora do normal o texto longo traz a mensagem do Metro ("Circulação interrompida entre…").
     */
    fun parse(resposta: Map<String, String?>): List<MetroLineStatus> = LINES.mapNotNull { (name, key, _) ->
        val text = resposta[key]?.trim() ?: return@mapNotNull null
        val short = resposta["${key}_curta"]?.trim().orEmpty()
        val ok = text.equals("ok", ignoreCase = true) && (short.isEmpty() || short.equals("normal", ignoreCase = true))
        val message = when {
            ok -> ""
            !text.equals("ok", ignoreCase = true) && text.isNotBlank() -> text
            else -> "Circulação com perturbações (${short.lowercase()})."
        }
        MetroLineStatus(name, ok, message)
    }
}

/** O que já se avisou de cada linha. [okReadings] = leituras "Ok" seguidas desde a última perturbação. */
data class LineAlertState(val disrupted: Boolean = false, val message: String = "", val okReadings: Int = 0)

sealed class LineAlert {
    abstract val line: String
    data class Started(override val line: String, val message: String) : LineAlert()
    /** A mensagem mudou mas a perturbação continua: atualiza a notificação sem voltar a tocar. */
    data class Updated(override val line: String, val message: String) : LineAlert()
    data class Ended(override val line: String) : LineAlert()
}

object MetroDisruptions {
    /** Leituras "Ok" seguidas para dar a perturbação como terminada (a API às vezes oscila). */
    const val OK_READINGS_TO_END = 2

    /** Compara o estado lido agora com o que já se avisou; devolve o novo estado e os avisos a dar. */
    fun step(
        previous: Map<String, LineAlertState>,
        now: List<MetroLineStatus>
    ): Pair<Map<String, LineAlertState>, List<LineAlert>> {
        val next = previous.toMutableMap()
        val alerts = mutableListOf<LineAlert>()
        for (status in now) {
            val before = previous[status.line] ?: LineAlertState()
            next[status.line] = when {
                !status.ok -> {
                    when {
                        !before.disrupted -> alerts += LineAlert.Started(status.line, status.message)
                        before.message != status.message -> alerts += LineAlert.Updated(status.line, status.message)
                    }
                    LineAlertState(disrupted = true, message = status.message)
                }
                before.disrupted && before.okReadings + 1 >= OK_READINGS_TO_END -> {
                    alerts += LineAlert.Ended(status.line)
                    LineAlertState()
                }
                before.disrupted -> before.copy(okReadings = before.okReadings + 1)
                else -> LineAlertState()
            }
        }
        return next to alerts
    }
}

object CpNoticeAlerts {
    fun key(n: CpNotice): String = "${n.title}|${n.start}"

    /**
     * Avisos novos a mostrar, de entre os que dizem respeito às estações favoritas. [seen] = null na
     * primeira vez: aí só se guardam os que já existem, sem avisar (senão chegavam todos de uma vez).
     * Devolve os avisos a mostrar e o conjunto de vistos a guardar (só os que ainda existem).
     */
    fun pickNew(relevant: List<CpNotice>, seen: Set<String>?, max: Int = 3): Pair<List<CpNotice>, Set<String>> {
        val keys = relevant.map(::key).toSet()
        if (seen == null) return emptyList<CpNotice>() to keys
        val fresh = relevant.filter { key(it) !in seen }.distinctBy(::key)
        return fresh.take(max) to keys
    }
}
