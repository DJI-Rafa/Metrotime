package com.example.metrotime.ui.intro

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.R

// ──────────────────────────────────────────────────────────────────────────────
// Peças da intro: um relógio por página (as animações são as do design, em ms desde que a página ficou à
// vista), as curvas e meia dúzia de componentes pequenos (logótipos, pílulas, o dedo).
// ──────────────────────────────────────────────────────────────────────────────

internal val INTRO_BG = Color(0xFF111215)
internal val PANEL = Color(0xFF1E1F22)
internal val PANEL_2 = Color(0xFF2B2D30)
internal val LINE = Color(0xFF2E3034)
internal val MUTED = Color(0xFFB7BBC3)
internal val YELLOW = Color(0xFFFFC20E)
internal val GOOD = Color(0xFF4CAF50)
internal val LATE = Color(0xFFFFC107)
internal val VERY_LATE = Color(0xFFFF7A7A)
internal val BLUE = Color(0xFF3B82F6)

/** Milissegundos desde que a página ficou à vista; 0 enquanto não está (as entradas recomeçam ao voltar). */
@Composable
internal fun rememberPageClock(active: Boolean): State<Long> {
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        t.longValue = 0L
        if (!active) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t.longValue = it - start }
    }
    return t
}

internal object Anim {
    private val ease = FastOutSlowInEasing
    /** As entradas "pop" do design (cubic-bezier(.2,.8,.3,1.5)): passam um pouco do tamanho e voltam. */
    val POP = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1.5f)

    /** 0 → 1 entre [delay] e [delay] + [dur], com aceleração suave. */
    fun enter(t: Long, delay: Int, dur: Int = 600): Float = ease.transform(((t - delay) / dur.toFloat()).coerceIn(0f, 1f))

    /** Posição (0..1) num ciclo de [period] ms que começa em [delay]. */
    fun loop(t: Long, period: Int, delay: Int = 0): Float = if (t < delay) 0f else ((t - delay) % period) / period.toFloat()

    /** Valor numa animação por fotogramas: pares (posição 0..1, valor), com transição suave entre eles. */
    fun kf(p: Float, vararg stops: Pair<Float, Float>): Float {
        if (p <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (a, va) = stops[i - 1]
            val (b, vb) = stops[i]
            if (p <= b) {
                val local = if (b > a) ((p - a) / (b - a)).coerceIn(0f, 1f) else 1f
                return va + (vb - va) * ease.transform(local)
            }
        }
        return stops.last().second
    }

    /** Liga/desliga (0 ou 1) por janelas do ciclo, com 6 % de transição. */
    fun window(p: Float, from: Float, to: Float, fade: Float = 0.04f): Float = when {
        p < from - fade || p > to + fade -> 0f
        p < from -> 1f - (from - p) / fade
        p > to -> 1f - (p - to) / fade
        else -> 1f
    }
}

/** Posição absoluta no palco (as páginas são desenhadas num palco de 390 × 404, como no design). */
internal fun Modifier.at(x: Number, y: Number): Modifier = offset(x.toFloat().dp, y.toFloat().dp)

/** Sobe e aparece ("rise"). */
internal fun Modifier.rise(t: Long, delay: Int = 0): Modifier = graphicsLayer {
    val e = Anim.enter(t, delay, 600)
    alpha = e
    translationY = (1 - e) * 18.dp.toPx()
}

/** Cresce com um pequeno salto ("pop"). */
internal fun Modifier.pop(t: Long, delay: Int = 0): Modifier = graphicsLayer {
    val x = ((t - delay) / 550f).coerceIn(0f, 1f)
    val s = Anim.POP.transform(x)
    scaleX = s
    scaleY = s
    alpha = (x * 2f).coerceAtMost(1f)
}

/** Flutua devagar para cima e para baixo. */
internal fun Modifier.float(t: Long): Modifier = graphicsLayer {
    translationY = -6.dp.toPx() * Anim.kf(Anim.loop(t, 4000), 0f to 0f, 0.5f to 1f, 1f to 0f)
}

@Composable
internal fun MetroLogo(w: Dp, h: Dp = w * 1.25f) =
    Image(painterResource(R.drawable.metro_logo), null, Modifier.size(w, h), contentScale = ContentScale.Fit)

@Composable
internal fun CpLogo(w: Dp, h: Dp = w * 0.66f) =
    Image(painterResource(R.drawable.cp_logo), null, Modifier.size(w, h), contentScale = ContentScale.Fit)

@Composable
internal fun BusLogo(size: Dp) =
    Image(painterResource(R.drawable.bus_symbol), null, Modifier.size(size), contentScale = ContentScale.Fit)

/** Quadrado escuro com um logótipo (o metro também em fundo escuro, como a CP e os autocarros). */
@Composable
internal fun LogoTile(size: Dp, glow: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(size)
            .shadow(16.dp, RoundedCornerShape(size * 0.28f), ambientColor = glow, spotColor = glow)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(PANEL)
            .border(1.dp, LINE, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Texto do palco (tamanho fixo: o palco é uma ilustração e escala inteiro). */
@Composable
internal fun T(
    text: String,
    size: TextUnit = 13.sp,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) = Text(text, modifier, color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.25f, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** Pílula com texto (número de linha, serviço da CP). */
@Composable
internal fun Badge(text: String, bg: Color, fg: Color = Color.White, size: TextUnit = 11.sp, radius: Dp = 5.dp) {
    Box(Modifier.clip(RoundedCornerShape(radius)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T(text, size, fg, FontWeight.Black)
    }
}

/** O dedo que mostra o gesto. */
@Composable
internal fun Finger(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .drawBehind { drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 + 8.dp.toPx()) }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.85f))
    )
}

/** Cartão escuro com borda, a base da maior parte das ilustrações. */
internal fun Modifier.card(radius: Dp = 18.dp, bg: Color = PANEL): Modifier =
    this.clip(RoundedCornerShape(radius)).background(bg).border(1.dp, LINE, RoundedCornerShape(radius))
