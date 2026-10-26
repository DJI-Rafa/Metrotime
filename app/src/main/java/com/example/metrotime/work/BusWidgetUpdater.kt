package com.example.metrotime.work

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.example.metrotime.R
import com.example.metrotime.data.bus.BusColors
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.BusWidgetProvider
import com.example.metrotime.ui.LiveMapActivity
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Desenha o widget da paragem: a paragem escolhida no mapa ("Mostrar no widget") ou a primeira
 * favorita, com o próximo autocarro de cada linha e destino, números nas cores oficiais.
 */
object BusWidgetUpdater {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private const val RC_REFRESH = 7501
    private const val RC_OPEN = 7502
    private const val RC_CHOOSE = 7503
    private const val RC_BUS = 7600
    private const val ROW_DP = 48f

    private data class Stop(val operator: String, val id: String, val name: String, val lat: Double, val lon: Double, val entry: String)

    /** Últimas chegadas que vieram bem: sem rede, mostram-se estas (até 5 min) em vez de esvaziar o widget. */
    private data class LastGood(val stopId: String, val arrivals: List<BusMapRepository.StopArrivalInfo>, val at: String, val millis: Long)

    @Volatile private var lastGood: LastGood? = null

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, BusWidgetProvider::class.java)).isNotEmpty()

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, BusWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val refresh = WidgetViews.broadcast(
            context, RC_REFRESH, Intent(context, BusWidgetProvider::class.java).setAction(BusWidgetProvider.ACTION_REFRESH)
        )
        val stop = Prefs.getBusWidgetStop(context)?.let { entry ->
            entry.split("|").takeIf { it.size == 5 }?.let { (operator, id, name, lat, lon) ->
                Stop(operator, id, name, lat.toDoubleOrNull() ?: return@let null, lon.toDoubleOrNull() ?: return@let null, entry)
            }
        }
        if (stop == null) {
            manager.updateAppWidget(ids, noStop(context, refresh))
            return
        }
        val open = WidgetViews.activity(
            context, RC_OPEN, Intent(context, LiveMapActivity::class.java).putExtra(LiveMapActivity.EXTRA_BUS_STOP, stop.entry)
        )
        val fetched = withTimeoutOrNull(12_000) {
            runCatching { BusMapRepository.getStopArrivals(context, stop.id, stop.operator) }.getOrNull()
        }
        val now = LocalTime.now(ZoneId.of("Europe/Lisbon")).format(HM)
        val (arrivals, at, offline) = when {
            fetched != null -> {
                lastGood = LastGood(stop.id, fetched, now, System.currentTimeMillis())
                Triple(fetched, now, false)
            }
            else -> {
                val cached = lastGood?.takeIf { it.stopId == stop.id && System.currentTimeMillis() - it.millis < 5 * 60_000 }
                    ?: run {
                        manager.updateAppWidget(ids, offlineViews(context, stop.name, refresh, open))
                        return
                    }
                Triple(cached.arrivals, cached.at, true)
            }
        }
        val next = WidgetRows.nextPerLine(arrivals, { it.routeShortName }, { it.destination }, { it.etaMinutes })
        manager.updateAppWidget(ids, draw(context, stop.name, next, at, offline, refresh, open))
    }

    private fun header(context: Context, name: String, ago: String, agoColor: Int = WidgetViews.SECONDARY) =
        WidgetViews.list(context, WidgetViews.Icon.Letters("P", Color.WHITE, 16f, R.drawable.widget_icon_box_stop), name, ago, agoColor)

    private fun smallSign(context: Context) =
        WidgetViews.pill(context, "P", Color.parseColor("#34363A"), Color.WHITE, 16f, 10f, radiusDp = 4f, minWidthDp = 16f, paddingDp = 3f)

    private fun linePill(context: Context, a: BusMapRepository.StopArrivalInfo, heightDp: Float, textSp: Float) =
        BusColors.normalize(a.color).let { hex ->
            val color = hex ?: BusColors.FALLBACK
            WidgetViews.pill(
                context, a.routeShortName, Color.parseColor(color), Color.parseColor(BusColors.textOn(color)),
                heightDp, textSp, minWidthDp = heightDp * 1.7f
            )
        }

    private fun draw(
        context: Context,
        name: String,
        arrivals: List<BusMapRepository.StopArrivalInfo>,
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
            if (arrivals.isEmpty()) {
                WidgetViews.message(
                    views, "Nenhum autocarro a caminho", "Toca em atualizar para ver outra vez.",
                    R.drawable.widget_dot, WidgetViews.SECONDARY
                )
                return views
            }
            arrivals.take(maxRows).forEachIndexed { i, a ->
                // Tempo real: minutos na cor do estado, com o sinal; previsto: a branco
                val status = a.status
                views.addView(
                    R.id.rows,
                    WidgetViews.row(
                        context, first = i == 0, main = a.destination, value = a.etaText,
                        valueColor = if (status.realtime) WidgetViews.busStatusColor(status.kind) else WidgetViews.WHITE,
                        pill = linePill(context, a, 26f, 14f),
                        sub = status.text, subColor = WidgetViews.busStatusColor(status.kind), live = status.realtime,
                        // Tocar num autocarro com GPS abre o mapa centrado nele; um previsto abre a paragem
                        onClick = a.vehicleId?.takeIf { status.realtime }?.let { vehicleId ->
                            WidgetViews.activity(context, RC_BUS + i, LiveMapActivity.busIntent(context, vehicleId, a.operator, a.routeShortName))
                        } ?: open
                    )
                )
            }
            return views
        }

        val small = WidgetViews.small(context, name, badge = smallSign(context))
        small.setOnClickPendingIntent(R.id.widgetRoot, open)
        if (arrivals.isEmpty()) {
            WidgetViews.smallMessage(small, "Nenhum autocarro a caminho")
        } else {
            arrivals.take(2).forEach { a ->
                small.addView(
                    R.id.rows,
                    WidgetViews.smallRow(
                        context, a.destination, a.etaText,
                        if (a.status.realtime) WidgetViews.busStatusColor(a.status.kind) else WidgetViews.WHITE,
                        pill = linePill(context, a, 18f, 10f)
                    )
                )
            }
        }
        return WidgetViews.sized(small, ROW_DP, ::large)
    }

    /** Sem paragem: explica como escolher e abre o separador dos autocarros. */
    private fun noStop(context: Context, refresh: android.app.PendingIntent): RemoteViews {
        val choose = WidgetViews.openTab(context, RC_CHOOSE, "Autocarro")
        fun large(@Suppress("UNUSED_PARAMETER") rows: Int): RemoteViews {
            val views = header(context, "Paragem", "")
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            WidgetViews.message(
                views, "Escolhe uma paragem",
                "Marca uma paragem com ★ ou abre-a no mapa e toca em Mostrar no widget.",
                button = "Abrir autocarros", onButton = choose
            )
            return views
        }
        val small = WidgetViews.small(context, "Paragem", badge = smallSign(context))
        small.setOnClickPendingIntent(R.id.widgetRoot, choose)
        WidgetViews.smallMessage(small, "Toca para escolher uma paragem")
        return WidgetViews.sized(small, ROW_DP, ::large)
    }

    private fun offlineViews(context: Context, name: String, refresh: android.app.PendingIntent, open: android.app.PendingIntent): RemoteViews {
        fun large(@Suppress("UNUSED_PARAMETER") rows: Int): RemoteViews {
            val views = header(context, name, "")
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            WidgetViews.message(views, "Sem ligação", "Toca em atualizar para tentar outra vez.", R.drawable.widget_dot, WidgetViews.ORANGE)
            return views
        }
        val small = WidgetViews.small(context, name, badge = smallSign(context))
        small.setOnClickPendingIntent(R.id.widgetRoot, refresh)
        WidgetViews.smallMessage(small, "Sem ligação · toca para tentar outra vez")
        return WidgetViews.sized(small, ROW_DP, ::large)
    }
}
