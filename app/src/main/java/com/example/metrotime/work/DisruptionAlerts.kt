package com.example.metrotime.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.example.metrotime.R
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.alerts.CpNoticeAlerts
import com.example.metrotime.data.alerts.LineAlert
import com.example.metrotime.data.alerts.LineAlertState
import com.example.metrotime.data.alerts.MetroDisruptions
import com.example.metrotime.data.alerts.MetroLineStatusParser
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.LineStationsActivity
import org.json.JSONObject

/**
 * Alertas de perturbação: "Linha Verde com problemas" (estado das linhas do Metro, de 2 em 2 min) e
 * avisos novos da CP para as linhas das estações CP favoritas (de 30 em 30 min). Chamado pelo ciclo do
 * WidgetUpdateService e, como recurso, pelo LocationKeepAliveWorker; os intervalos contam-se aqui.
 */
object DisruptionAlerts {

    private const val PREFS = "disruption_alerts"
    private const val CHANNEL = "disruptions"
    private const val NOTIF_METRO = 4300 // + índice da linha
    private const val NOTIF_CP = 4310    // + 0..2
    private const val METRO_EVERY_MS = 2 * 60_000L
    private const val CP_EVERY_MS = 30 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    suspend fun check(context: Context) {
        val now = System.currentTimeMillis()
        val p = prefs(context)
        if (now - p.getLong("metroCheckedAt", 0) >= METRO_EVERY_MS) {
            p.edit { putLong("metroCheckedAt", now) }
            runCatching { checkMetro(context) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        if (now - p.getLong("cpCheckedAt", 0) >= CP_EVERY_MS) {
            p.edit { putLong("cpCheckedAt", now) }
            runCatching { checkCpNotices(context) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    // ── Metro ──────────────────────────────────────────────────────────────

    private suspend fun checkMetro(context: Context) {
        val lines = Prefs.getMetroAlertLines(context)
        // Com o metro fechado o estado não interessa (e não se dá a perturbação como terminada)
        if (lines.isEmpty() || !RemoteArrivalsSource.isMetroOperating()) return
        val statuses = RemoteArrivalsSource.fetchLineStatus()
        if (statuses.isEmpty()) return
        com.example.metrotime.data.alerts.DisruptionLog.record(context, statuses)

        val p = prefs(context)
        val previous = readMetroState(p.getString("metroState", null))
        val (next, alerts) = MetroDisruptions.step(previous, statuses)
        p.edit { putString("metroState", writeMetroState(next)) }

        ensureChannel(context)
        alerts.filter { it.line in lines }.forEach { alert -> notifyMetro(context, alert) }
    }

    private fun notifyMetro(context: Context, alert: LineAlert) {
        val index = MetroLineStatusParser.LINE_NAMES.indexOf(alert.line).coerceAtLeast(0)
        val (title, text) = when (alert) {
            is LineAlert.Started -> "Linha ${alert.line} com problemas" to alert.message
            is LineAlert.Updated -> "Linha ${alert.line} com problemas" to alert.message
            is LineAlert.Ended -> "Linha ${alert.line} normalizada" to "A circulação voltou ao normal."
        }
        val open = PendingIntent.getActivity(
            context, NOTIF_METRO + index,
            LineStationsActivity.newIntent(context, alert.line).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager(context).notify(NOTIF_METRO + index, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_metro)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A mesma notificação por linha: uma atualização da mensagem não volta a tocar
            .setOnlyAlertOnce(alert is LineAlert.Updated)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
    }

    private fun readMetroState(raw: String?): Map<String, LineAlertState> {
        if (raw == null) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { line ->
                val s = o.getJSONObject(line)
                LineAlertState(s.optBoolean("disrupted"), s.optString("message"), s.optInt("okReadings"))
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeMetroState(state: Map<String, LineAlertState>): String = JSONObject().apply {
        state.forEach { (line, s) ->
            put(line, JSONObject().put("disrupted", s.disrupted).put("message", s.message).put("okReadings", s.okReadings))
        }
    }.toString()

    // ── CP ─────────────────────────────────────────────────────────────────

    private suspend fun checkCpNotices(context: Context) {
        if (!Prefs.isCpNoticeAlerts(context)) return
        val favourites = Prefs.getCpFavorites(context).map { it.substringBefore("|") to formatStationName(it.substringAfter("|")) }
        if (favourites.isEmpty()) return
        val notices = CpRepository.getNotices()
        // Linha de cada favorita (cache da CP), para apanhar os avisos que só falam da linha
        val places = favourites.map { (code, name) -> name to runCatching { CpRepository.getStationInfo(code)?.trainLine }.getOrNull() }
        val relevant = notices.filter { n -> places.any { (name, line) -> n.concerns(name, line) } }

        val p = prefs(context)
        val seen = if (p.getBoolean("cpInitialised", false)) p.getStringSet("cpSeen", emptySet()).orEmpty() else null
        val (fresh, keep) = CpNoticeAlerts.pickNew(relevant, seen)
        p.edit { putStringSet("cpSeen", keep); putBoolean("cpInitialised", true) }

        if (fresh.isEmpty()) return
        ensureChannel(context)
        fresh.forEachIndexed { i, n ->
            val where = places.firstOrNull { (name, line) -> n.concerns(name, line) }?.let { (name, line) -> line ?: name }
            val text = listOf(n.title, n.summary).filter { it.isNotBlank() }.joinToString("\n")
            val open = PendingIntent.getActivity(
                context, NOTIF_CP + i,
                Intent(Intent.ACTION_VIEW, Uri.parse(n.link.ifBlank { "https://www.cp.pt/pt/avisos" })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager(context).notify(NOTIF_CP + i, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_metro)
                .setContentTitle(if (where != null) "Aviso CP · $where" else "Aviso CP")
                .setContentText(n.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build())
        }
    }

    private fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, "Perturbações no metro e na CP", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Linhas do metro com problemas e avisos da CP para as tuas estações favoritas"
                enableVibration(true)
            }
        )
    }
}
