package com.example.metrotime.work

import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.example.metrotime.R
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.RemoteArrivalsSource
import com.example.metrotime.data.StationArrivals
import com.example.metrotime.ui.HomeText
import com.example.metrotime.ui.LockWidgetProvider
import com.example.metrotime.ui.MetroWidgetProvider
import com.example.metrotime.ui.StationArrivalsActivity

/**
 * Desenho dos widgets do metro: o do ecrã inicial (um sentido por linha, faixa da cor da linha,
 * próximo metro em minutos e os seguintes por baixo) e o Metro Live do ecrã de bloqueio.
 */
object MetroWidgetViews {

    private const val RC_REFRESH = 0
    private const val RC_OPEN = 7401
    private const val RC_LOCK_REFRESH = 1
    private const val ROW_DP = 46f

    fun home(context: Context, data: StationArrivals?): RemoteViews {
        val refresh = WidgetViews.broadcast(
            context, RC_REFRESH,
            Intent(context, MetroWidgetProvider::class.java).setAction(MetroWidgetProvider.ACTION_CLEAR_AND_REFRESH)
        )
        val open = if (data != null) {
            WidgetViews.activity(context, RC_OPEN, StationArrivalsActivity.newIntent(context, data.station.id))
        } else {
            WidgetViews.openTab(context, RC_OPEN, "Metro")
        }
        val elapsed = data?.let { ((System.currentTimeMillis() - it.fetchedAtMillis) / 1000).toInt() } ?: 0
        val rows = data?.let { WidgetRows.metro(it.station.apiId, it.arrivals, elapsed) }.orEmpty()
        val closed = rows.isEmpty() && !RemoteArrivalsSource.isMetroOperating()
        val dots = data?.let { lineColors(it) }.orEmpty()
        val prefix = if (data?.isEstimated == true) "~" else ""

        fun large(maxRows: Int): RemoteViews {
            val views = if (data == null) {
                WidgetViews.list(context, WidgetViews.Icon.Image(R.drawable.metro_logo), "Metro de Lisboa", "")
            } else {
                WidgetViews.list(
                    context, WidgetViews.Icon.Dots(dots), data.station.name,
                    if (data.isEstimated) "estimado" else WidgetRows.ago(elapsed),
                    if (data.isEstimated) WidgetViews.ORANGE else WidgetViews.SECONDARY
                )
            }
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            when {
                data == null -> WidgetViews.message(
                    views, "A procurar a estação mais perto", "Toca em atualizar para começar.",
                    R.drawable.widget_dot, WidgetViews.SECONDARY
                )
                closed -> WidgetViews.message(views, "O metro está fechado", "Abre às 6:30", R.drawable.ic_widget_moon)
                rows.isEmpty() -> WidgetViews.message(
                    views, "Sem metros previstos", "Toca em atualizar para tentar outra vez.",
                    R.drawable.widget_dot, WidgetViews.ORANGE
                )
                else -> rows.take(maxRows).forEachIndexed { i, r ->
                    val wait = HomeText.metroWait(r.seconds.first())
                    views.addView(
                        R.id.rows,
                        WidgetViews.row(
                            context, first = i == 0, main = r.destination,
                            value = if (wait == "a chegar") wait else prefix + wait,
                            valueColor = if (wait == "a chegar") WidgetViews.GREEN else WidgetViews.WHITE,
                            barColor = WidgetViews.metroLineColor(r.line),
                            sub = HomeText.laterWaits(r.seconds.drop(1))
                        )
                    )
                }
            }
            return views
        }

        val small = WidgetViews.small(context, data?.station?.name ?: "Metro de Lisboa", dots)
        small.setOnClickPendingIntent(R.id.widgetRoot, if (data == null) refresh else open)
        when {
            data == null -> WidgetViews.smallMessage(small, "Toca para ver a estação mais perto")
            closed -> WidgetViews.smallMessage(small, "Fechado · abre às 6:30")
            rows.isEmpty() -> WidgetViews.smallMessage(small, "Sem metros previstos")
            else -> WidgetRows.metroSmall(rows).forEach { r ->
                val wait = HomeText.metroWait(r.seconds.first())
                small.addView(
                    R.id.rows,
                    WidgetViews.smallRow(
                        context, r.destination, if (wait == "a chegar") wait else prefix + wait,
                        if (wait == "a chegar") WidgetViews.GREEN else WidgetViews.WHITE
                    )
                )
            }
        }
        return WidgetViews.sized(small, ROW_DP, ::large)
    }

    /**
     * Metro Live: vidro escuro com os metros mais próximos (até três, conforme a altura que o ecrã
     * dá: cabeçalho e margens 49dp, cada linha 32dp); tocar atualiza.
     */
    fun lock(context: Context, data: StationArrivals?): RemoteViews =
        RemoteViews((1..3).associate { n -> SizeF(150f, 49f + 32f * n) to lock(context, data, n) })

    private fun lock(context: Context, data: StationArrivals?, maxRows: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lock)
        views.setOnClickPendingIntent(
            R.id.widgetLockRoot,
            WidgetViews.broadcast(
                context, RC_LOCK_REFRESH,
                Intent(context, LockWidgetProvider::class.java).setAction(LockWidgetProvider.ACTION_REFRESH)
            )
        )
        // O ecrã reaproveita a vista anterior: repor o que pode ter ficado visível
        views.removeAllViews(R.id.rows)
        views.setViewVisibility(R.id.messageText, View.GONE)
        val dots = data?.let { lineColors(it) }.orEmpty()
        listOf(R.id.iconDot1, R.id.iconDot2).forEachIndexed { i, id ->
            val color = dots.getOrNull(i)
            views.setViewVisibility(id, if (color != null) View.VISIBLE else View.GONE)
            color?.let { views.setInt(id, "setColorFilter", it) }
        }
        if (data == null) {
            views.setTextViewText(R.id.txtLockStation, "Metro de Lisboa")
            views.setTextViewText(R.id.txtLockUpdated, "")
            views.setViewVisibility(R.id.messageText, View.VISIBLE)
            views.setTextViewText(R.id.messageText, "A procurar a estação mais perto…")
            return views
        }
        val elapsed = ((System.currentTimeMillis() - data.fetchedAtMillis) / 1000).toInt()
        views.setTextViewText(R.id.txtLockStation, data.station.name)
        views.setTextViewText(R.id.txtLockUpdated, if (data.isEstimated) "estimado" else WidgetRows.ago(elapsed))
        val rows = WidgetRows.lock(WidgetRows.metro(data.station.apiId, data.arrivals, elapsed)).take(maxRows)
        if (rows.isEmpty()) {
            views.setViewVisibility(R.id.messageText, View.VISIBLE)
            views.setTextViewText(
                R.id.messageText,
                if (RemoteArrivalsSource.isMetroOperating()) "Sem metros previstos" else "O metro está fechado · abre às 6:30"
            )
            return views
        }
        val prefix = if (data.isEstimated) "~" else ""
        rows.forEach { r ->
            val wait = HomeText.metroWait(r.seconds.first())
            val row = RemoteViews(context.packageName, R.layout.widget_row_lock)
            row.setInt(R.id.lead, "setColorFilter", WidgetViews.metroLineColor(r.line))
            row.setTextViewText(R.id.main, r.destination)
            row.setTextViewText(R.id.value, if (wait == "a chegar") wait else prefix + wait)
            row.setTextColor(R.id.value, if (wait == "a chegar") WidgetViews.GREEN else WidgetViews.WHITE)
            views.addView(R.id.rows, row)
        }
        return views
    }

    /** Cores das linhas que passam na estação (Alameda: verde e vermelha). */
    private fun lineColors(data: StationArrivals): List<Int> =
        MetroLines.linesOf(data.station.apiId).ifEmpty { data.station.line.split(" ").filter { it.isNotBlank() } }
            .map { WidgetViews.metroLineColor(it) }
}
