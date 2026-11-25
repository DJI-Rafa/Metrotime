package com.example.metrotime.ui.caderneta

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.metrotime.data.caderneta.Azulejos
import com.example.metrotime.data.caderneta.StampKind
import com.example.metrotime.data.caderneta.Tier

// ── Molduras das raridades ───────────────────────────────────────────────────

private class Palette(
    val bg: Color, val edge: Brush, val frame: Brush, val corner: Color, val medal: Color, val ring: Brush,
    val ink: Color, val ribbon: Brush, val ribbonText: Color, val glaze: Float
)

private fun solid(c: Long) = Brush.linearGradient(listOf(Color(c), Color(c)))

private val GOLD = Brush.linearGradient(
    0f to Color(0xFF9A7424), 0.3f to Color(0xFFF7DE8A), 0.55f to Color(0xFFC99A2E), 0.8f to Color(0xFFFFF0B8), 1f to Color(0xFFA97C22),
    start = Offset.Zero, end = Offset(120f, 120f)
)

private fun holo(shift: Float): Brush {
    val colors = listOf(0xFFFF7AB6, 0xFFFFD36E, 0xFF7CFFB2, 0xFF6FE3FF, 0xFF9C8CFF, 0xFFFF7AB6).map { Color(it) }
    // A moldura holográfica muda de cor: as cores deslizam ao longo da diagonal
    val d = shift * 120f
    return Brush.linearGradient(colors, start = Offset(-d, -d), end = Offset(240f - d, 240f - d), tileMode = androidx.compose.ui.graphics.TileMode.Repeated)
}

private fun palette(tier: Tier, locked: Boolean, holoShift: Float): Palette = if (locked) {
    Palette(Color(0xFF26282C), solid(0xFF33363B), solid(0xFF44474D), Color(0xFF44474D), Color(0xFF202226), solid(0xFF4A4D53), Color(0xFF6B7078), solid(0xFF3A3D42), Color(0xFF9DA2AA), 0.04f)
} else when (tier) {
    Tier.COMUM -> Palette(Color(0xFFF3EFE6), solid(0xFFD4CBB8), solid(0xFF2456B6), Color(0xFF2456B6), Color(0xFFFBF9F4), solid(0xFF2456B6), Color(0xFF1E4FAE), solid(0xFF1E4FAE), Color.White, 0.55f)
    Tier.RARO -> Palette(Color(0xFF1E4AA8), solid(0xFF143A88), solid(0xFFD6E1FF), Color(0xFFD6E1FF), Color(0xFF173D8E), solid(0xFFE6EDFF), Color.White, solid(0xFFF3EFE6), Color(0xFF173D8E), 0.16f)
    Tier.LENDARIO -> Palette(Color(0xFF141A30), GOLD, GOLD, Color(0xFFE9C25A), Color(0xFF1B2443), GOLD, Color(0xFFF4D27A), GOLD, Color(0xFF2A1E05), 0.1f)
    Tier.IMPOSSIVEL -> holo(holoShift).let { h -> Palette(Color(0xFF0D0D16), h, h, Color(0xFFA7E8FF), Color(0xFF15151F), h, Color(0xFFE4F6FF), h, Color(0xFF0D0D16), 0.08f) }
    Tier.SECRETO -> Palette(Color(0xFF22163A), solid(0xFF4B3477), solid(0xFFB9A3F0), Color(0xFFB9A3F0), Color(0xFF2C1D48), solid(0xFFC9B6FF), Color(0xFFEDE4FF), solid(0xFFB9A3F0), Color(0xFF22163A), 0.1f)
}

private val pathCache = HashMap<String, Path>()
private fun cached(d: String): Path = synchronized(pathCache) { pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() } }

private const val TILE = "M18,2 H102 A16,16 0 0 1 118,18 V102 A16,16 0 0 1 102,118 H18 A16,16 0 0 1 2,102 V18 A16,16 0 0 1 18,2 Z"
private const val CORNERS = "M10,26 A16,16 0 0 0 26,10 M10,19 A9,9 0 0 0 19,10 M110,26 A16,16 0 0 1 94,10 M110,19 A9,9 0 0 1 101,10 M10,94 A16,16 0 0 1 26,110 M10,101 A9,9 0 0 1 19,110 M110,94 A16,16 0 0 0 94,110 M110,101 A9,9 0 0 0 101,110"
private const val DIAMONDS = "M60,4.5 L63.5,10 L60,15.5 L56.5,10 Z M4.5,60 L10,56.5 L15.5,60 L10,63.5 Z M115.5,60 L110,56.5 L104.5,60 L110,63.5 Z"
private const val RIBBON = "M12,86 H108 L103,95 L108,104 H12 L17,95 Z"

// Uma por thread: a imagem para partilhar desenha-se fora do ecrã, ao mesmo tempo que a lista se desenha no ecrã
private val textPaints: ThreadLocal<Paint> = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }

/** Texto centrado em [x]; com [maxWidth], encolhe o que não cabe (a fita do "Dia de greve" leva o comboio e o dia). */
private fun DrawScope.text(x: Float, y: Float, s: String, size: Float, weight: Int, spacing: Float, color: Color, maxWidth: Float? = null) {
    val textPaint = textPaints.get()!!
    textPaint.textSize = size
    textPaint.color = color.toArgb()
    textPaint.typeface = Typeface.create(Typeface.DEFAULT, weight.coerceIn(100, 1000), false)
    textPaint.letterSpacing = if (size > 0) spacing / size else 0f
    if (maxWidth != null) {
        val width = textPaint.measureText(s)
        if (width > maxWidth) textPaint.textSize = size * maxWidth / width
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, textPaint)
}

/**
 * Um distintivo desenhado: o azulejo da raridade (cantos, medalhão, fita) com o desenho dele. [locked]: por ganhar
 * (cinzento; nos secretos, só "???"). Os lendários ganham um brilho a passar e os impossíveis uma moldura que muda de cor.
 */
@Composable
fun BadgeArt(
    id: String,
    tier: Tier,
    label: String,
    locked: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    description: String? = null
) {
    // Só os lendários e os impossíveis ganhos têm animação (os outros não gastam nada a desenhar-se outra vez)
    val moving = animate && !locked && (tier == Tier.LENDARIO || tier == Tier.IMPOSSIVEL)
    if (moving) {
        val transition = rememberInfiniteTransition(label = "distintivo")
        val shine by transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "brilho"
        )
        val holoShift by transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart), label = "holo"
        )
        BadgeCanvas(id, tier, label, locked, modifier, description, { shine }, { holoShift })
    } else {
        BadgeCanvas(id, tier, label, locked, modifier, description, null, { 0f })
    }
}

@Composable
private fun BadgeCanvas(
    id: String, tier: Tier, label: String, locked: Boolean, modifier: Modifier, description: String?,
    shineOf: (() -> Float)?, holoOf: () -> Float
) {
    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
        drawBadge(id, tier, label, locked, size.minDimension, holoOf(), shineOf?.invoke())
    }
}

/**
 * Desenha um distintivo num quadrado de [side] px a partir de (0, 0): o azulejo da raridade com o desenho dele. Serve
 * o ecrã e a imagem para partilhar. [holoShift]: onde vão as cores da moldura holográfica; [shine]: onde vai o brilho
 * a passar (0–1), ou nenhum.
 */
internal fun DrawScope.drawBadge(
    id: String, tier: Tier, label: String, locked: Boolean, side: Float, holoShift: Float = 0f, shine: Float? = null
) {
    val secretLocked = locked && tier == Tier.SECRETO
    val shownLabel = if (secretLocked) "???" else label
    run {
        val k = side / 120f
        scale(k, k, pivot = Offset.Zero) {
            val p = palette(if (secretLocked) Tier.SECRETO else tier, locked && !secretLocked, holoShift)
            val tile = cached(TILE)
            drawPath(tile, p.bg)
            drawPath(tile, p.edge, style = Stroke(3f))
            drawPath(tile, Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), start = Offset.Zero, end = Offset(51f, 60f)), alpha = p.glaze)
            drawRoundRect(p.frame, Offset(10f, 10f), Size(100f, 100f), CornerRadius(10f), style = Stroke(1.8f))
            drawPath(cached(CORNERS), p.corner, style = Stroke(1.8f))
            listOf(14.5f to 14.5f, 105.5f to 14.5f, 14.5f to 105.5f, 105.5f to 105.5f).forEach { (x, y) -> drawCircle(p.corner, 1.7f, Offset(x, y)) }
            drawPath(cached(DIAMONDS), p.corner)
            drawCircle(p.medal, 36f, Offset(60f, 60f))
            drawCircle(p.ring, 36f, Offset(60f, 60f), style = Stroke(2.4f))
            drawCircle(p.ring, 31.5f, Offset(60f, 60f), alpha = 0.75f, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 3f))))

            translate(36f, 36f) {
                val (parts, milestone) = if (secretLocked) listOf("secretLocked") to "" else BadgeIcons.parts(id)
                parts.forEach { part ->
                    BadgeIcons.PARTS[part].orEmpty().forEach { op -> drawOp(op, p, locked && !secretLocked, milestone, shownLabel) }
                }
            }

            if (shownLabel.isNotEmpty()) {
                drawPath(cached(RIBBON), p.ribbon)
                // A fita tem 96 de largura com as pontas em V: o texto fica nos 84 do meio
                text(60f, 98.1f, shownLabel, 8.4f, 800, 0.4f, p.ribbonText, maxWidth = 84f)
            }
            if (locked && !secretLocked) {
                drawCircle(Color(0xFF2B2D30), 12f, Offset(99f, 21f))
                drawCircle(Color(0xFF101113), 12f, Offset(99f, 21f), style = Stroke(3f))
                drawPath(cached("M95.6,19.5 V17 A3.4,3.4 0 0 1 102.4,17 V19.5"), Color(0xFFC9CDD3), style = Stroke(1.8f))
                drawRoundRect(Color(0xFFC9CDD3), Offset(93.6f, 19.5f), Size(10.8f, 8f), CornerRadius(1.6f))
            }
            // O brilho dos lendários e dos impossíveis ganhos
            if (shine != null) {
                val x = -120f + shine * 360f
                clipPath(tile) {
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0f), Color(0xFFFFF4D6).copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
                            start = Offset(x - 30f, 0f), end = Offset(x + 30f, 30f)
                        ),
                        Offset(0f, 0f), Size(120f, 120f)
                    )
                }
            }
        }
    }
}

private fun DrawScope.inkColor(ink: Ink, p: Palette, grey: Boolean): Color = when (ink) {
    Ink.Main -> p.ink
    Ink.Medal -> p.medal
    is Ink.Fixed -> if (grey) Color(0xFF5E636B) else ink.color
}

private fun DrawScope.drawOp(op: Op, p: Palette, grey: Boolean, milestone: String, label: String, alpha: Float = 1f) {
    fun stroke(w: Float, dash: FloatArray?, butt: Boolean = false) =
        Stroke(w, cap = if (butt) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
    when (op) {
        is PathOp -> {
            op.fill?.let { drawPath(op.path, inkColor(it, p, grey), alpha = op.alpha * alpha, style = Fill) }
            op.stroke?.let { drawPath(op.path, inkColor(it, p, grey), alpha = op.alpha * alpha, style = stroke(op.width, op.dash, op.butt)) }
        }
        is CircleOp -> {
            op.fill?.let { drawCircle(inkColor(it, p, grey), op.r, Offset(op.cx, op.cy), alpha = op.alpha * alpha) }
            op.stroke?.let { drawCircle(inkColor(it, p, grey), op.r, Offset(op.cx, op.cy), alpha = op.alpha * alpha, style = stroke(op.width, op.dash)) }
        }
        is RectOp -> {
            op.fill?.let { drawRoundRect(inkColor(it, p, grey), Offset(op.x, op.y), Size(op.w, op.h), CornerRadius(op.rx), alpha = op.alpha * alpha) }
            op.stroke?.let { drawRoundRect(inkColor(it, p, grey), Offset(op.x, op.y), Size(op.w, op.h), CornerRadius(op.rx), alpha = op.alpha * alpha, style = stroke(op.width, null)) }
        }
        is EllipseOp -> rotate(op.rotation, Offset(op.cx, op.cy)) {
            drawOval(p.ink, Offset(op.cx - op.rx, op.cy - op.ry), Size(op.rx * 2, op.ry * 2), alpha = op.alpha * alpha, style = stroke(op.width, op.dash))
        }
        is TextOp -> {
            val s = op.text.replace("{m}", milestone)
            val size = if (op.text == "{m}" && milestone.length > 2) 13.5f else op.size
            text(op.x, op.y, s, size, op.weight, op.spacing, p.ink.copy(alpha = alpha))
        }
        is GroupOp -> withTransform({
            translate(op.dx, op.dy)
            scale(op.scale, op.scale, pivot = Offset.Zero)
        }) { op.ops.forEach { drawOp(it, p, grey, milestone, label, alpha * op.alpha) } }
        RegionOp -> {
            val (x, y) = BadgeIcons.regionPoint(label)
            drawCircle(p.ink, 7.5f, Offset(x, y), alpha = 0.6f * alpha, style = Stroke(1.2f))
            drawCircle(p.ink, 4.6f, Offset(x, y), alpha = alpha)
        }
    }
}

// ── Azulejos das estações ────────────────────────────────────────────────────

private const val TILE_CORNERS = "M1,12 A11,11 0 0 0 12,1 M48,1 A11,11 0 0 0 59,12 M59,48 A11,11 0 0 0 48,59 M12,59 A11,11 0 0 0 1,48"
private const val TILE_CORNERS_DOUBLE = "M1,7 A6,6 0 0 0 7,1 M53,1 A6,6 0 0 0 59,7 M59,53 A6,6 0 0 0 53,59 M7,59 A6,6 0 0 0 1,53"
private val CREAM = Color(0xFFF3EFE6)

/**
 * O azulejo de uma estação: cheio (creme, com o padrão dela na cor da linha), de passagem (só o contorno) ou por
 * descobrir (escuro, com "?"). [others]: as cores das outras linhas numa correspondência.
 */
@Composable
fun AzulejoTile(
    pattern: Azulejos.Pattern,
    kind: StampKind?,
    color: Color,
    onDark: Color,
    modifier: Modifier = Modifier,
    others: List<Color> = emptyList(),
    description: String? = null
) {
    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
        val k = size.minDimension / 60f
        scale(k, k, pivot = Offset.Zero) {
            when (kind) {
                StampKind.FULL -> {
                    drawRoundRect(CREAM, Offset(1f, 1f), Size(58f, 58f), CornerRadius(6f))
                    drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(1f, 1f), Size(58f, 29f), CornerRadius(6f))
                    drawPattern(pattern, color, 2.3f, 1f)
                    others.forEachIndexed { i, c ->
                        val cx = 50f - i * 9f
                        drawCircle(c, 4.2f, Offset(cx, 9f))
                        drawCircle(CREAM, 4.2f, Offset(cx, 9f), style = Stroke(1.6f))
                    }
                }
                StampKind.PASSED -> {
                    drawRoundRect(Color(0xFF16171A), Offset(1.5f, 1.5f), Size(57f, 57f), CornerRadius(6f))
                    drawRoundRect(onDark, Offset(1.5f, 1.5f), Size(57f, 57f), CornerRadius(6f), style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                    drawPattern(pattern, onDark, 1.4f, 0.55f, corners = false)
                    others.forEachIndexed { i, c ->
                        val cx = 50f - i * 9f
                        drawCircle(c, 4.2f, Offset(cx, 9f))
                        drawCircle(Color(0xFF101113), 4.2f, Offset(cx, 9f), style = Stroke(1.6f))
                    }
                }
                null -> {
                    drawRoundRect(Color(0xFF1A1B1F), Offset(1f, 1f), Size(58f, 58f), CornerRadius(6f))
                    drawRoundRect(Color(0xFF2B2D30), Offset(1f, 1f), Size(58f, 58f), CornerRadius(6f), style = Stroke(1f))
                    text(30f, 38.5f, "?", 22f, 700, 0f, Color(0xFF3D4046))
                }
            }
        }
    }
}

private fun DrawScope.drawPattern(pattern: Azulejos.Pattern, color: Color, width: Float, alpha: Float, corners: Boolean = true) {
    val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (corners) {
        when (pattern.corners) {
            0 -> drawPath(cached(TILE_CORNERS), color, alpha = alpha, style = Stroke(1.6f))
            1 -> {
                drawPath(cached(TILE_CORNERS), color, alpha = alpha, style = Stroke(1.6f))
                drawPath(cached(TILE_CORNERS_DOUBLE), color, alpha = alpha, style = Stroke(1.2f))
            }
            2 -> drawPath(cached("M1,9 L9,1 L1,1 Z M59,9 L51,1 L59,1 Z M59,51 L51,59 L59,59 Z M1,51 L9,59 L1,59 Z"), color, alpha = alpha)
            else -> listOf(6f to 6f, 54f to 6f, 6f to 54f, 54f to 54f).forEach { (x, y) -> drawCircle(color, 2.2f, Offset(x, y), alpha = alpha) }
        }
        if (pattern.frame) drawRoundRect(color, Offset(4f, 4f), Size(52f, 52f), CornerRadius(3f), alpha = alpha, style = Stroke(0.9f))
    }
    rotate(pattern.rotation, Offset(30f, 30f)) {
        drawPath(cached(Azulejos.MOTIFS[pattern.motif]), color, alpha = alpha, style = line)
    }
    when (pattern.center) {
        1 -> drawCircle(color, 2.5f, Offset(30f, 30f), alpha = alpha)
        2 -> drawCircle(color, 4f, Offset(30f, 30f), alpha = alpha, style = Stroke(1.2f))
        else -> Unit
    }
}
