package com.example.metrotime.work

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.example.metrotime.R
import com.example.metrotime.data.cp.CpRepository
import com.example.metrotime.data.cp.CpService
import com.example.metrotime.data.cp.CpTime
import com.example.metrotime.data.cp.CpTimetableWindow
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.CpStationActivity
import com.example.metrotime.ui.CpWidgetProvider
import com.example.metrotime.ui.getServiceColor
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/**
 * Desenha o widget CP: as próximas partidas da estação escolhida (ou da primeira favorita), com a hora
 * real, o tipo de comboio na cor da CP, número e linha, e o estado (a horas, +4 min, Suprimido).
 */
object CpWidgetUpdater {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private const val RC_REFRESH = 7301
    private const val RC_OPEN = 7302
    private const val RC_CHOOSE = 7303
    private const val ROW_DP = 47f

    /** Uma partida pronta a desenhar. */
    private data class Departure(
        val time: String,
        val planned: String?,
        val destination: String,
        val service: String,
        val serviceColor: Int,
        val detail: String,
        val status: String,
        val statusColor: Int,
        val suppressed: Boolean
    )

    /** Últimas partidas que vieram bem: sem rede, mostram-se estas (até 10 min) em vez de esvaziar o widget. */
    private data class LastGood(val code: String, val departures: List<Departure>, val at: String, val millis: Long)

    @Volatile private var lastGood: LastGood? = null

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CpWidgetProvider::class.java)).isNotEmpty()

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CpWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val refresh = WidgetViews.broadcast(
            context, RC_REFRESH, Intent(context, CpWidgetProvider::class.java).setAction(CpWidgetProvider.ACTION_REFRESH)
        )
        val entry = Prefs.getCpWidgetStation(context)
        if (entry == null) {
            manager.updateAppWidget(ids, noStation(context, refresh))
            return
        }
        val code = entry.substringBefore("|")
        val rawName = entry.substringAfter("|", code)
        val name = formatStationName(rawName)
        val open = WidgetViews.activity(context, RC_OPEN, CpStationActivity.newIntent(context, code, rawName))

        val now = CpTime.now()
        val stops = withTimeoutOrNull(9_000) {
            runCatching { CpRepository.getTimetableWindow(code, now).stops }.getOrNull()
        }
        val departures: List<Departure>
        val at: String
        val offline: Boolean
        if (stops != null) {
            departures = stops.mapNotNull { timed ->
                val time = timed.dateTimeOf(timed.stop.departureTime) ?: return@mapNotNull null
                if (!CpTimetableWindow.isInWindow(time.toLocalDate(), time.toLocalTime(), timed.stop.delay, now)) return@mapNotNull null
                timed to time
            }.sortedBy { it.second }.take(4).map { (timed, time) ->
                val stop = timed.stop
                val suppressed = stop.supression != null || stop.delay == -1
                val (status, state) = WidgetRows.cpStatus(stop.delay, suppressed)
                val color = stateColor(state)
                val late = !suppressed && (stop.delay ?: 0) > 0
                val code4 = stop.trainService?.code
                Departure(
                    time = (if (late) time.plusMinutes(stop.delay!!.toLong()) else time).format(HM),
                    planned = if (late) time.format(HM) else null,
                    destination = formatStationName(stop.trainDestination?.designation ?: "?"),
                    service = CpService.shortName(code4),
                    serviceColor = getServiceColor(code4).toArgb(),
                    detail = listOfNotNull(
                        stop.trainNumber.toString(),
                        stop.platform?.takeIf { it.isNotBlank() && it != "0" }?.let { "Linha $it" }
                    ).joinToString(" · "),
                    status = status,
                    statusColor = color,
                    suppressed = suppressed
                )
            }
            at = now.format(HM)
            offline = false
            lastGood = LastGood(code, departures, at, System.currentTimeMillis())
        } else {
            val cached = lastGood?.takeIf { it.code == code && System.currentTimeMillis() - it.millis < 10 * 60_000 }
            if (cached == null) {
                manager.updateAppWidget(ids, offlineViews(context, name, refresh, open))
                return
            }
            departures = cached.departures
            at = cached.at
            offline = true
        }
        manager.updateAppWidget(ids, draw(context, name, departures, at, offline, refresh, open))
    }

    private fun stateColor(state: WidgetRows.CpState): Int = Color.parseColor(
        when (state) {
            WidgetRows.CpState.ON_TIME -> "#4CAF50"
            WidgetRows.CpState.SMALL_DELAY -> "#FFC107"
            WidgetRows.CpState.BIG_DELAY, WidgetRows.CpState.SUPPRESSED -> "#FF5252"
            WidgetRows.CpState.SCHEDULED -> "#8A8D93"
        }
    )

    private fun header(context: Context, name: String, ago: String, agoColor: Int = WidgetViews.SECONDARY) =
        WidgetViews.list(
            context, WidgetViews.Icon.Letters("CP", Color.parseColor("#7BC68A"), 11f, R.drawable.widget_icon_box_cp),
            name, ago, agoColor
        )

    private fun smallBadge(context: Context) =
        WidgetViews.pill(context, "CP", Color.parseColor("#66388243"), Color.parseColor("#9BD6A6"), 14f, 9f, radiusDp = 4f, paddingDp = 4f)

    private fun draw(
        context: Context,
        name: String,
        departures: List<Departure>,
        at: String,
        offline: Boolean,
        refresh: android.app.PendingIntent,
        open: android.app.PendingIntent
    ): RemoteViews {
        val ago = if (offline) "sem rede · $at" else "às $at"
        val agoColor = if (offline) WidgetViews.ORANGE else WidgetViews.SECONDARY

        fun large(maxRows: Int): RemoteViews {
            val views = header(context, name, ago, agoColor)
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            if (departures.isEmpty()) {
                WidgetViews.message(views, "Sem partidas", "Não há comboios nas próximas 5 horas.", R.drawable.ic_widget_moon)
                return views
            }
            departures.take(maxRows).forEachIndexed { i, d ->
                val row = RemoteViews(context.packageName, R.layout.widget_row_cp)
                row.setViewVisibility(R.id.divider, if (i == 0) View.GONE else View.VISIBLE)
                row.setTextViewText(R.id.time, if (d.suppressed) WidgetViews.struck(d.time) else d.time)
                row.setTextColor(
                    R.id.time,
                    when {
                        d.suppressed -> WidgetViews.MUTED
                        d.planned != null -> d.statusColor
                        else -> WidgetViews.WHITE
                    }
                )
                d.planned?.let {
                    row.setViewVisibility(R.id.planned, View.VISIBLE)
                    row.setTextViewText(R.id.planned, WidgetViews.struck(it))
                }
                row.setTextViewText(R.id.main, if (d.suppressed) WidgetViews.struck(d.destination) else d.destination)
                row.setImageViewBitmap(R.id.badge, WidgetViews.pill(context, d.service, d.serviceColor, Color.WHITE, 15f, 10f, radiusDp = 4f, paddingDp = 5f))
                row.setTextViewText(R.id.sub, d.detail)
                row.setTextViewText(R.id.value, d.status)
                row.setTextColor(R.id.value, d.statusColor)
                views.addView(R.id.rows, row)
            }
            return views
        }

        val small = WidgetViews.small(context, name, badge = smallBadge(context))
        small.setOnClickPendingIntent(R.id.widgetRoot, open)
        if (departures.isEmpty()) {
            WidgetViews.smallMessage(small, "Sem partidas nas próximas 5 horas")
        } else {
            departures.take(2).forEach { d ->
                small.addView(R.id.rows, WidgetViews.smallRow(context, "${d.time} ${d.destination}", d.status, d.statusColor).apply {
                    setTextViewTextSize(R.id.value, android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
        }
        return WidgetViews.sized(small, ROW_DP, ::large)
    }

    /** Sem estação escolhida: explica como escolher e abre o separador dos comboios. */
    private fun noStation(context: Context, refresh: android.app.PendingIntent): RemoteViews {
        val choose = WidgetViews.openTab(context, RC_CHOOSE, "Comboio")
        fun large(@Suppress("UNUSED_PARAMETER") rows: Int): RemoteViews {
            val views = header(context, "Comboios CP", "")
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            WidgetViews.message(
                views, "Escolhe uma estação", "Abre uma estação na app e toca em ⋮ › Mostrar no widget.",
                button = "Escolher estação", onButton = choose
            )
            return views
        }
        val small = WidgetViews.small(context, "Comboios CP", badge = smallBadge(context))
        small.setOnClickPendingIntent(R.id.widgetRoot, choose)
        WidgetViews.smallMessage(small, "Toca para escolher uma estação")
        return WidgetViews.sized(small, ROW_DP, ::large)
    }

    private fun offlineViews(context: Context, name: String, refresh: android.app.PendingIntent, open: android.app.PendingIntent): RemoteViews {
        fun large(@Suppress("UNUSED_PARAMETER") rows: Int): RemoteViews {
            val views = header(context, name, "")
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            WidgetViews.message(
                views, "Sem ligação", "Toca em atualizar para tentar outra vez.",
                R.drawable.widget_dot, WidgetViews.ORANGE
            )
            return views
        }
        val small = WidgetViews.small(context, name, badge = smallBadge(context))
        small.setOnClickPendingIntent(R.id.widgetRoot, refresh)
        WidgetViews.smallMessage(small, "Sem ligação · toca para tentar outra vez")
        return WidgetViews.sized(small, ROW_DP, ::large)
    }
}
