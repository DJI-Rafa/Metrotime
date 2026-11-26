package com.example.metrotime.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.LruCache
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.metrotime.R
import com.example.metrotime.ui.SettingsActivity

/**
 * Peças comuns dos widgets do Metro, CP e paragem (layout widget_list, widget_small e linhas):
 * cabeçalho, linhas, mensagens, tamanhos e as pílulas coloridas. As pílulas são bitmaps porque o
 * RemoteViews não pinta um fundo arredondado de uma cor qualquer.
 */
object WidgetViews {

    const val WHITE = Color.WHITE
    val SECONDARY = Color.parseColor("#B7BBC3")
    val GREEN = Color.parseColor("#6FCF73")
    val ORANGE = Color.parseColor("#FFB74D")
    val MUTED = Color.parseColor("#8A8D93")
    val RED = Color.parseColor("#FF5252")

    /** Cor do estado de um autocarro, a mesma da app (busStatusColor). */
    fun busStatusColor(kind: com.example.metrotime.data.bus.BusTiming.Kind): Int = when (kind) {
        com.example.metrotime.data.bus.BusTiming.Kind.ON_TIME,
        com.example.metrotime.data.bus.BusTiming.Kind.EARLY,
        com.example.metrotime.data.bus.BusTiming.Kind.LIVE -> GREEN
        com.example.metrotime.data.bus.BusTiming.Kind.LATE -> ORANGE
        com.example.metrotime.data.bus.BusTiming.Kind.VERY_LATE -> RED
        com.example.metrotime.data.bus.BusTiming.Kind.SCHEDULED -> SECONDARY
    }

    /** Cores das linhas do metro, as mesmas da app. */
    fun metroLineColor(line: String): Int = Color.parseColor(
        when {
            line.contains("Amarela", ignoreCase = true) -> "#FFD500"
            line.contains("Verde", ignoreCase = true) -> "#009E60"
            line.contains("Vermelha", ignoreCase = true) -> "#ED1C24"
            else -> "#0077C8"
        }
    )

    /** Símbolo do cabeçalho: cores das linhas, uma imagem ou uma letra numa caixa. */
    sealed interface Icon {
        data class Dots(val colors: List<Int>) : Icon
        data class Image(val res: Int) : Icon
        data class Letters(val text: String, val color: Int, val sizeSp: Float, val box: Int) : Icon
    }

    /**
     * Tamanhos: o pequeno (2 × 1) e o normal com 2, 3 ou 4 linhas conforme a altura que o ecrã
     * inicial dá ao widget (o sistema escolhe o maior que cabe). [rowDp] é a altura de uma linha
     * (as da CP têm a hora prevista por baixo); o cabeçalho e as margens ocupam 64dp.
     */
    fun sized(small: RemoteViews, rowDp: Float, large: (rows: Int) -> RemoteViews): RemoteViews = RemoteViews(
        mapOf(SizeF(110f, 40f) to small) + (2..4).associate { n -> SizeF(200f, 64f + n * rowDp) to large(n) }
    )

    // ── Layout normal (widget_list) ─────────────────────────────────────────

    /**
     * Cabeçalho do layout normal, com as linhas vazias à espera de addView. O ecrã inicial reaproveita
     * a vista anterior e só aplica as ações novas, por isso tudo o que muda é reposto aqui.
     */
    fun list(context: Context, icon: Icon, title: String, ago: CharSequence, agoColor: Int = SECONDARY): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        views.setTextViewText(R.id.title, title)
        views.setTextViewText(R.id.ago, ago)
        views.setTextColor(R.id.ago, agoColor)
        views.setViewVisibility(R.id.iconDots, if (icon is Icon.Dots) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.iconImage, if (icon is Icon.Image) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.iconText, if (icon is Icon.Letters) View.VISIBLE else View.GONE)
        views.setInt(R.id.iconBox, "setBackgroundResource", (icon as? Icon.Letters)?.box ?: R.drawable.widget_icon_box)
        when (icon) {
            is Icon.Dots -> dots(views, icon.colors)
            is Icon.Image -> views.setImageViewResource(R.id.iconImage, icon.res)
            is Icon.Letters -> {
                views.setTextViewText(R.id.iconText, icon.text)
                views.setTextColor(R.id.iconText, icon.color)
                views.setTextViewTextSize(R.id.iconText, TypedValue.COMPLEX_UNIT_SP, icon.sizeSp)
            }
        }
        views.removeAllViews(R.id.rows)
        views.setViewVisibility(R.id.rows, View.VISIBLE)
        views.setViewVisibility(R.id.message, View.GONE)
        views.setViewVisibility(R.id.messageIcon, View.GONE)
        views.setViewVisibility(R.id.messageButton, View.GONE)
        return views
    }

    private fun dots(views: RemoteViews, colors: List<Int>) {
        listOf(R.id.iconDot1, R.id.iconDot2).forEachIndexed { i, id ->
            val color = colors.getOrNull(i)
            views.setViewVisibility(id, if (color != null) View.VISIBLE else View.GONE)
            if (color != null) views.setInt(id, "setColorFilter", color)
        }
    }

    /** Linha do Metro ou da paragem (widget_row). [lead] é a faixa da linha ou a pílula com o número. */
    fun row(
        context: Context,
        first: Boolean,
        main: CharSequence,
        value: CharSequence,
        valueColor: Int = WHITE,
        barColor: Int? = null,
        pill: Bitmap? = null,
        top: String? = null,
        sub: String? = null,
        subColor: Int? = null,
        live: Boolean = false,
        onClick: PendingIntent? = null
    ): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        row.setViewVisibility(R.id.divider, if (first) View.GONE else View.VISIBLE)
        when {
            pill != null -> row.setImageViewBitmap(R.id.lead, pill)
            barColor != null -> {
                row.setImageViewResource(R.id.lead, R.drawable.widget_bar)
                row.setInt(R.id.lead, "setColorFilter", barColor)
                row.setViewLayoutWidth(R.id.lead, 4f, TypedValue.COMPLEX_UNIT_DIP)
                row.setViewLayoutHeight(R.id.lead, 28f, TypedValue.COMPLEX_UNIT_DIP)
            }
            else -> row.setViewVisibility(R.id.lead, View.GONE)
        }
        row.setTextViewText(R.id.main, main)
        row.setTextViewText(R.id.value, value)
        row.setTextColor(R.id.value, valueColor)
        top?.let { row.setViewVisibility(R.id.top, View.VISIBLE); row.setTextViewText(R.id.top, it) }
        sub?.let { row.setViewVisibility(R.id.sub, View.VISIBLE); row.setTextViewText(R.id.sub, it) }
        subColor?.let { row.setTextColor(R.id.sub, it) }
        onClick?.let { row.setOnClickPendingIntent(R.id.rowRoot, it) }
        // Sinal de tempo real na cor do tempo (autocarros com GPS)
        if (live) {
            row.setViewVisibility(R.id.live, View.VISIBLE)
            row.setInt(R.id.live, "setColorFilter", valueColor)
        }
        return row
    }

    /** Em vez das linhas: um aviso (metro fechado, sem ligação, nada escolhido), com botão opcional. */
    fun message(
        views: RemoteViews,
        title: String,
        text: String,
        icon: Int? = null,
        iconColor: Int? = null,
        button: String? = null,
        onButton: PendingIntent? = null
    ) {
        views.setViewVisibility(R.id.rows, View.GONE)
        views.setViewVisibility(R.id.message, View.VISIBLE)
        views.setTextViewText(R.id.messageTitle, title)
        views.setTextViewText(R.id.messageText, text)
        if (icon != null) {
            views.setViewVisibility(R.id.messageIcon, View.VISIBLE)
            views.setImageViewResource(R.id.messageIcon, icon)
            // 0 (transparente) tira a cor que tenha ficado de um aviso anterior
            views.setInt(R.id.messageIcon, "setColorFilter", iconColor ?: 0)
        }
        if (button != null && onButton != null) {
            views.setViewVisibility(R.id.messageButton, View.VISIBLE)
            views.setTextViewText(R.id.messageButton, button)
            views.setOnClickPendingIntent(R.id.messageButton, onButton)
        }
    }

    // ── Tamanho pequeno (widget_small) ──────────────────────────────────────

    fun small(context: Context, title: String, dots: List<Int> = emptyList(), badge: Bitmap? = null): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_small)
        views.setTextViewText(R.id.title, title)
        dots(views, dots)
        views.setViewVisibility(R.id.iconImage, if (badge != null) View.VISIBLE else View.GONE)
        badge?.let { views.setImageViewBitmap(R.id.iconImage, it) }
        views.removeAllViews(R.id.rows)
        views.setViewVisibility(R.id.rows, View.VISIBLE)
        views.setViewVisibility(R.id.messageText, View.GONE)
        return views
    }

    fun smallRow(context: Context, main: CharSequence, value: CharSequence, valueColor: Int = WHITE, pill: Bitmap? = null): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_row_small)
        pill?.let { row.setViewVisibility(R.id.lead, View.VISIBLE); row.setImageViewBitmap(R.id.lead, it) }
        row.setTextViewText(R.id.main, main)
        row.setTextViewText(R.id.value, value)
        row.setTextColor(R.id.value, valueColor)
        return row
    }

    fun smallMessage(views: RemoteViews, text: String) {
        views.setViewVisibility(R.id.rows, View.GONE)
        views.setViewVisibility(R.id.messageText, View.VISIBLE)
        views.setTextViewText(R.id.messageText, text)
    }

    // ── Pílulas, texto riscado e intents ────────────────────────────────────

    // A mesma pílula é o mesmo bitmap em todos os tamanhos: o RemoteViews só a envia uma vez
    private val pills = LruCache<String, Bitmap>(48)

    /** Texto numa pílula colorida (número da linha, tipo de comboio), centrado pela altura real das letras. */
    fun pill(
        context: Context,
        text: String,
        background: Int,
        textColor: Int,
        heightDp: Float,
        textSp: Float,
        radiusDp: Float = heightDp / 2,
        minWidthDp: Float = 0f,
        paddingDp: Float = 8f
    ): Bitmap {
        val key = listOf(text, background, textColor, heightDp, textSp, radiusDp, minWidthDp, paddingDp).joinToString("|")
        pills.get(key)?.let { return it }
        val d = context.resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = textSp * d
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val height = (heightDp * d).toInt()
        val width = maxOf((minWidthDp * d).toInt(), (paint.measureText(text) + 2 * paddingDp * d).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusDp * d, radiusDp * d,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, width / 2f, height / 2f - bounds.exactCenterY(), paint)
        pills.put(key, bitmap)
        return bitmap
    }

    fun struck(text: String): CharSequence =
        SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Abre a app num separador ("Metro", "Comboio", "Autocarro"). */
    fun openTab(context: Context, requestCode: Int, category: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_OPEN_CATEGORY, category)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
