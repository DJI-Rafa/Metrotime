package com.example.metrotime.ui.caderneta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.metrotime.R
import com.example.metrotime.data.caderneta.BadgeState
import com.example.metrotime.data.caderneta.ShareText
import com.example.metrotime.data.caderneta.Tier
import java.time.LocalDate

/**
 * A imagem para os stories (1080 × 1920) de um distintivo ganho: num cartão escuro com um contorno fino, o distintivo
 * com uma luz por trás, a raridade, o nome e como se ganha; por baixo de uma linha, o símbolo e o nome da Metrotime e
 * a data. Cada raridade tem a sua cor de fundo e a sua luz.
 */
object BadgeStory {

    const val WIDTH = 1080
    const val HEIGHT = 1920

    private const val CARD_LEFT = 87f
    private const val CARD_RIGHT = 993f
    private const val CARD_RADIUS = 52f
    private const val PAD = 62f
    private const val BADGE = 494f
    private const val ICON = 71f

    /** Uma luz atrás do distintivo: deslocada do centro dele, com o seu raio e a sua cor. */
    private class Glow(val dx: Float, val dy: Float, val r: Float, val color: Int)

    private class Theme(
        val page: Int, val pageBottom: Int, val card: Int, val cardBottom: Int,
        val accent: Int, val accentGradient: IntArray?, val glows: List<Glow>, val floor: Int
    )

    private fun c(alpha: Float, rgb: Int): Int = ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private fun halo(rgb: Int, strong: Float) = listOf(Glow(0f, 0f, 340f, c(strong, rgb)), Glow(0f, 0f, 600f, c(strong * 0.28f, rgb)))

    private fun theme(tier: Tier): Theme = when (tier) {
        Tier.COMUM -> Theme(
            0xFF10131A.toInt(), 0xFF0B0D12.toInt(), 0xFF151922.toInt(), 0xFF181D29.toInt(),
            0xFFA9C2F5.toInt(), null, halo(0x8FB0F0, 0.32f), c(0.24f, 0x5D7BC0)
        )
        Tier.RARO -> Theme(
            0xFF0A1330.toInt(), 0xFF070D22.toInt(), 0xFF0F1B3D.toInt(), 0xFF13224A.toInt(),
            0xFFA9C1FF.toInt(), null, halo(0x7FA2FF, 0.40f), c(0.30f, 0x3F64D8)
        )
        Tier.LENDARIO -> Theme(
            0xFF0C1122.toInt(), 0xFF080B17.toInt(), 0xFF10172A.toInt(), 0xFF121B33.toInt(),
            0xFFF2C14E.toInt(), null, halo(0xF2C14E, 0.46f), c(0.26f, 0x2F4F9A)
        )
        Tier.IMPOSSIVEL -> Theme(
            0xFF07070C.toInt(), 0xFF050509.toInt(), 0xFF0D0D15.toInt(), 0xFF12121D.toInt(),
            0xFFA7E8FF.toInt(),
            intArrayOf(0xFFFF9AC8.toInt(), 0xFFFFE08A.toInt(), 0xFF9CFFC4.toInt(), 0xFF8FE9FF.toInt(), 0xFFB5A8FF.toInt()),
            listOf(
                Glow(-95f, -45f, 300f, c(0.30f, 0xFF7AB6)), Glow(95f, 45f, 300f, c(0.30f, 0x6FE3FF)),
                Glow(0f, 0f, 600f, c(0.10f, 0x9C8CFF))
            ),
            c(0.22f, 0x6F5CFF)
        )
        Tier.SECRETO -> Theme(
            0xFF120A24.toInt(), 0xFF0C0618.toInt(), 0xFF1A1033.toInt(), 0xFF1F143D.toInt(),
            0xFFCDBBFF.toInt(), null, halo(0xB9A3F0, 0.40f), c(0.28f, 0x6A4FC0)
        )
    }

    private fun font(weight: Int) = Typeface.create(Typeface.DEFAULT, weight, false)

    /**
     * Grão fino (pontos brancos e pretos quase transparentes, ao acaso). O Instagram volta a comprimir a imagem e, nos
     * degradês escuros e lisos, isso dá manchas aos quadrados e faixas; com o grão por cima, a compressão não as mostra.
     */
    private val grain: Bitmap by lazy {
        val n = 256
        val random = java.util.Random(7)
        val pixels = IntArray(n * n) {
            val v = random.nextGaussian()
            val alpha = (kotlin.math.abs(v) * 7).toInt().coerceAtMost(20)
            if (v >= 0) (alpha shl 24) or 0xFFFFFF else alpha shl 24
        }
        Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun drawGrain(canvas: Canvas) {
        val paint = Paint().apply { shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    /** Uma luz elíptica com centro em ([cx], [cy]) e raios [rx] × [ry], a desvanecer até às pontas. */
    private fun light(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val clear = color and 0x00FFFFFF
        val half = c(((color ushr 24) / 255f) * 0.4f, color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f, intArrayOf(color, half, clear), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    /**
     * Desenha a imagem do distintivo; [date]: quando foi ganho (ou hoje, se não se souber). Por baixo do nome vai como se
     * ganha ou, se houver, o que aconteceu ([BadgeState.story]).
     */
    fun render(context: Context, state: BadgeState, date: LocalDate): Bitmap {
        val t = theme(state.def.tier)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fundo: um pouco mais escuro em baixo, com uma luz no chão por baixo do cartão
        canvas.drawRect(0f, 0f, w, h, Paint(Paint.DITHER_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, h, t.page, t.pageBottom, Shader.TileMode.CLAMP) })
        light(canvas, w / 2, h * 0.93f, w * 0.6f, h * 0.17f, t.floor)

        // Os textos (medem-se primeiro, para o cartão ter a altura certa)
        val title = ShareText.tierTitle(state.def.tier)
        val tierPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(800); textSize = 34f; letterSpacing = 0.26f; color = t.accent; textAlign = Paint.Align.CENTER
        }
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(800); textSize = 76f; letterSpacing = -0.01f; color = 0xFFF2F3F5.toInt()
        }
        // Um pouco mais grossa e clara do que no desenho: letra fina cinzenta é a primeira a borrar com a compressão
        val howPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(500); textSize = 39f; color = 0xFFBAC0CB.toInt()
        }
        val nameWidth = (CARD_RIGHT - CARD_LEFT - 2 * PAD).toInt()
        val howWidth = 760
        val name = layout(state.def.name, namePaint, nameWidth, 1f)
        // Com uma história (o comboio do "Dia de greve"), conta-a em vez da regra
        val how = layout(state.story ?: state.def.how, howPaint, howWidth, 1.28f)

        val tierBox = 40f
        val cardHeight = 80f + BADGE + 100f + tierBox + 37f + name.height + 44f + how.height + 84f + 2f + 34f + ICON + 54f
        val top = ((h - cardHeight) / 2).coerceAtLeast(200f)
        val bottom = top + cardHeight
        val card = RectF(CARD_LEFT, top, CARD_RIGHT, bottom)

        // Sombra e cartão
        canvas.drawRoundRect(
            RectF(CARD_LEFT + 20f, top + 40f, CARD_RIGHT - 20f, bottom + 30f), CARD_RADIUS, CARD_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(0.55f, 0x000000); maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL) }
        )
        canvas.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, bottom, t.card, t.cardBottom, Shader.TileMode.CLAMP)
        })

        // A luz atrás do distintivo, só dentro do cartão
        val badgeLeft = w / 2 - BADGE / 2
        val badgeTop = top + 80f
        val cx = w / 2
        val cy = badgeTop + BADGE / 2
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(card, CARD_RADIUS, CARD_RADIUS, Path.Direction.CW) })
        t.glows.forEach { light(canvas, cx + it.dx, cy + it.dy, it.r, it.r, it.color) }
        canvas.restore()

        // Contorno fino: claro em cima, com a cor da luz do chão em baixo
        val edge = 2.5f
        canvas.drawRoundRect(
            RectF(card.left + edge / 2, card.top + edge / 2, card.right - edge / 2, card.bottom - edge / 2), CARD_RADIUS, CARD_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = edge
                shader = LinearGradient(
                    0f, top, 0f, bottom,
                    intArrayOf(c(0.16f, 0xFFFFFF), c(0.07f, 0xFFFFFF), c(0.9f, t.floor)), floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        // Grão no fundo e no cartão (antes do distintivo e dos textos, que ficam nítidos)
        drawGrain(canvas)

        // O distintivo, com sombra
        canvas.drawRoundRect(
            RectF(badgeLeft + 50f, badgeTop + 70f, badgeLeft + BADGE - 50f, badgeTop + BADGE + 30f), 70f, 70f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c(0.6f, 0x000000); maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL) }
        )
        canvas.save()
        canvas.translate(badgeLeft, badgeTop)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), Size(BADGE, BADGE)) {
            drawBadge(state.def.id, state.def.tier, state.label, locked = false, side = BADGE, holoShift = 0.15f)
        }
        canvas.restore()

        // Raridade, nome e como se ganha
        var y = badgeTop + BADGE + 100f
        t.accentGradient?.let { colors ->
            val half = tierPaint.measureText(title) / 2
            tierPaint.shader = LinearGradient(cx - half, 0f, cx + half, 0f, colors, null, Shader.TileMode.CLAMP)
        }
        val tierMetrics = tierPaint.fontMetrics
        canvas.drawText(title, cx, y + tierBox / 2 - (tierMetrics.ascent + tierMetrics.descent) / 2, tierPaint)
        y += tierBox + 37f
        canvas.save()
        canvas.translate((w - nameWidth) / 2, y)
        name.draw(canvas)
        canvas.restore()
        y += name.height + 44f
        canvas.save()
        canvas.translate((w - howWidth) / 2, y)
        how.draw(canvas)
        canvas.restore()
        y += how.height + 84f

        // Linha e rodapé: símbolo e nome da app, data
        val left = CARD_LEFT + PAD
        val right = CARD_RIGHT - PAD
        canvas.drawRect(left, y, right, y + 2f, Paint().apply { color = c(0.10f, 0xFFFFFF) })
        y += 2f + 34f
        drawSymbol(context, canvas, RectF(left, y, left + ICON, y + ICON))
        val middle = y + ICON / 2
        val appPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font(600); textSize = 38f; color = 0xFFF2F3F5.toInt() }
        val appMetrics = appPaint.fontMetrics
        canvas.drawText("Metrotime", left + ICON + 22f, middle - (appMetrics.ascent + appMetrics.descent) / 2, appPaint)
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(500); textSize = 34f; letterSpacing = 0.04f; color = 0xFFB4BAC5.toInt(); textAlign = Paint.Align.RIGHT
        }
        val dateMetrics = datePaint.fontMetrics
        canvas.drawText(ShareText.date(date), right, middle - (dateMetrics.ascent + dateMetrics.descent) / 2, datePaint)
        return bitmap
    }

    /** O símbolo da app (o M com o relógio, sem o nome por baixo) num quadrado vermelho de cantos redondos. */
    private fun drawSymbol(context: Context, canvas: Canvas, box: RectF) {
        val radius = box.width() * 0.23f
        canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE4161E.toInt() })
        val logo = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.metrotime_logo, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull() ?: return
        // No logótipo (1254 px) o M e o relógio vão de (330, 250) a (1060, 835); o nome está por baixo
        val k = logo.width / 1254f
        val src = Rect((330 * k).toInt(), (250 * k).toInt(), (1060 * k).toInt(), (835 * k).toInt())
        val inner = box.width() * 0.74f
        val dw = inner
        val dh = inner * src.height() / src.width()
        val dst = RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
        val matrix = Matrix().apply { setRectToRect(RectF(src), dst, Matrix.ScaleToFit.FILL) }
        canvas.save()
        canvas.clipRect(dst)
        canvas.drawBitmap(logo, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        logo.recycle()
    }
}
