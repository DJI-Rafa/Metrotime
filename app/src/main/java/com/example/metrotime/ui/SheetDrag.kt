package com.example.metrotime.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// ──────────────────────────────────────────────────────────────────────────────
// Painéis do mapa que se arrastam pela pega (como no Google Maps): para cima abrem mais, para baixo encolhem até um
// mínimo (o título e a próxima paragem à vista, [PEEK_FRACTION] do ecrã) e ficam lá. Nunca fecham por se puxar: só
// no ✕ ou a tocar no mapa. Os painéis sem alturas fixas (do tamanho do conteúdo) descem até mostrarem só esse mínimo.
// ──────────────────────────────────────────────────────────────────────────────

/** O mínimo de um painel encolhido: o título, o estado e a próxima paragem (como no painel dos comboios). */
internal const val PEEK_FRACTION = 0.22f

@Stable
internal class SheetDrag(
    /** Alturas possíveis (px), da mais pequena à maior; vazia = painel do tamanho do conteúdo. */
    val snaps: List<Float>,
    initial: Int,
    /** A altura do painel encolhido (px). */
    private val peekPx: Float,
    private val scope: CoroutineScope
) {
    /** Altura pedida (px); abaixo da mais pequena, o painel desce em vez de encolher. Sem alturas: o quanto já desceu (≤ 0). */
    var extent by mutableFloatStateOf(snaps.getOrElse(initial) { 0f })
        private set
    private var settling: Job? = null
    /** Um só estado de arrasto por painel (um novo a cada recomposição cortava o gesto a meio). */
    internal val dragState: DraggableState = DraggableState { dragBy(it) }

    val resizable: Boolean get() = snaps.isNotEmpty()

    /** A altura do conteúdo (px) nos painéis do tamanho dele (medida ao desenhar). */
    internal var contentPx by mutableFloatStateOf(0f)

    /** Quanto um painel do tamanho do conteúdo pode descer: até ficar só o mínimo à vista. */
    private val maxDown: Float get() = maxOf(0f, contentPx - peekPx)

    /** As alturas onde pode parar, com o mínimo (encolhido). */
    private val stops: List<Float>
        get() = if (resizable) (listOf(minOf(peekPx, snaps.first())) + snaps).distinct().sorted() else listOf(-maxDown, 0f).distinct()

    /** Altura do painel (px) nos que mudam de tamanho. */
    val heightPx: Float get() = extent
    /** Quanto desceu (px) um painel do tamanho do conteúdo (encolhido = até só se ver o mínimo). */
    val pulledDown: Float get() = if (resizable) 0f else maxOf(0f, -extent)

    internal fun dragBy(deltaY: Float) {
        settling?.cancel()
        extent = (extent - deltaY).coerceIn(stops.first(), stops.last())
    }

    /** Larga: vai para a altura mais perto na direção do gesto; nunca fecha (só o ✕ ou um toque no mapa). */
    @Suppress("UNUSED_PARAMETER")
    internal fun release(velocityY: Float, onClose: () -> Unit) {
        val all = stops
        val target = when {
            velocityY < -FLING -> all.firstOrNull { it > extent + 1f } ?: all.last()
            velocityY > FLING -> all.lastOrNull { it < extent - 1f } ?: all.first()
            else -> all.minBy { abs(it - extent) }
        }
        settling = scope.launch {
            animate(extent, target, initialVelocity = -velocityY, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)) { v, _ ->
                extent = v
            }
        }
    }

    private companion object {
        const val FLING = 1200f
    }
}

/**
 * Estado de um painel arrastável. [fractions]: as alturas como fração do ecrã (vazio = do tamanho do conteúdo);
 * [initial]: a altura com que abre.
 */
@Composable
internal fun rememberSheetDrag(fractions: List<Float> = emptyList(), initial: Int = 0, key: Any? = null): SheetDrag {
    val screen = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(key, screen) {
        with(density) {
            SheetDrag(fractions.map { (screen * it).dp.toPx() }, initial, peekPx = (screen * PEEK_FRACTION).dp.toPx(), scope = scope)
        }
    }
}

/** A altura (nos que mudam de tamanho) ou, nos do tamanho do conteúdo, quanto desceram (encolhidos). */
internal fun Modifier.sheetDrag(drag: SheetDrag, density: androidx.compose.ui.unit.Density): Modifier =
    this
        .then(
            if (drag.resizable) Modifier.height(with(density) { drag.heightPx.toDp() })
            else Modifier.onSizeChanged { drag.contentPx = it.height.toFloat() }
        )
        .graphicsLayer { translationY = drag.pulledDown }

/** Zona por onde se agarra o painel (a pega e o que está à volta dela): arrastar para cima ou para baixo. */
internal fun Modifier.sheetDragArea(drag: SheetDrag, onClose: () -> Unit): Modifier = this.then(
    Modifier.draggable(
        state = drag.dragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> drag.release(velocity, onClose) }
    )
)

/** A pega: uma barra pequena num retângulo largo, fácil de agarrar com o polegar. */
@Composable
internal fun SheetHandle(drag: SheetDrag, onClose: () -> Unit, color: Color = Color(0xFF4A4D53), height: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .sheetDragArea(drag, onClose),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 40.dp, height = 4.dp).background(color, RoundedCornerShape(2.dp)))
    }
}
