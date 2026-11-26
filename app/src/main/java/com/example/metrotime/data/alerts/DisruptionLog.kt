package com.example.metrotime.data.alerts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quando é que cada linha do metro esteve com perturbação, pelas leituras do estado das linhas que a app já faz
 * (página inicial, mapa e avisos). Serve para o distintivo "Sobrevivente": uma viagem de metro numa linha durante
 * uma perturbação. Guarda os últimos 90 dias.
 */
object DisruptionLog {

    data class Window(val line: String, val startMs: Long, val endMs: Long)

    private const val PREFS = "disruption_log"
    private const val KEY = "windows"
    private const val KEEP_MS = 90L * 24 * 60 * 60 * 1000
    /** Duas leituras com perturbação até este tempo uma da outra são a mesma perturbação. */
    private const val JOIN_MS = 20L * 60 * 1000

    @Synchronized
    fun record(context: Context, statuses: List<MetroLineStatus>, nowMs: Long = System.currentTimeMillis()) {
        if (statuses.isEmpty()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = step(read(p.getString(KEY, null)), statuses, nowMs)
        p.edit().putString(KEY, write(next)).apply()
    }

    fun windows(context: Context): List<Window> =
        read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** Junta a leitura de agora: uma linha com problemas estende a janela dela (ou abre uma nova). */
    fun step(windows: List<Window>, statuses: List<MetroLineStatus>, nowMs: Long): List<Window> {
        val out = windows.filter { nowMs - it.endMs < KEEP_MS }.toMutableList()
        for (s in statuses) {
            if (s.ok) continue
            val i = out.indexOfLast { it.line == s.line && nowMs - it.endMs <= JOIN_MS }
            if (i >= 0) out[i] = out[i].copy(endMs = nowMs) else out += Window(s.line, nowMs, nowMs)
        }
        return out
    }

    private fun read(json: String?): List<Window> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Window(o.getString("l"), o.getLong("s"), o.getLong("e")) } }
    }.getOrDefault(emptyList())

    private fun write(windows: List<Window>): String = JSONArray().apply {
        windows.forEach { put(JSONObject().put("l", it.line).put("s", it.startMs).put("e", it.endMs)) }
    }.toString()
}
