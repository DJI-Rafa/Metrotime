package com.example.metrotime.ui.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ──────────────────────────────────────────────────────────────────────────────
// As ilustrações da intro, uma por página, no palco de 390 × 404 (como no Claude Design). [t]: ms desde que
// a página ficou à vista; as entradas e os ciclos seguem os tempos do design.
// ──────────────────────────────────────────────────────────────────────────────

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/** Desenha só a parte [progress] (0..1) de um caminho em unidades do palco (dp). */
private fun DrawScope.partial(p: Path, color: Color, width: Float, progress: Float, dashed: Boolean = false) {
    if (progress <= 0f) return
    scale(density, density, pivot = Offset.Zero) {
        val pm = PathMeasure()
        pm.setPath(p, false)
        val out = Path()
        pm.getSegment(0f, pm.length * progress, out, true)
        drawPath(
            out, color,
            style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2f, 11f)) else null)
        )
    }
}

/** Um telemóvel com a página inicial nova lá dentro (a mini página tem 300 × 560 e é encolhida para caber). */
@Composable
private fun Phone(
    width: Int, height: Int, scale: Float, modifier: Modifier = Modifier,
    mark: (Int) -> Modifier = { Modifier }, overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier
            .size(width.dp, height.dp)
            .shadow(30.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(PANEL)
            .border(6.dp, PANEL_2, RoundedCornerShape(32.dp))
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size(300.dp, 560.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
        ) { MiniHome(mark) }
        overlay()
    }
}

/** A página inicial nova, em miniatura. [mark]: modificador de cada zona (0 pesquisa, 1 Agora, 2 Explorar e atalhos). */
@Composable
private fun MiniHome(mark: (Int) -> Modifier = { Modifier }) {
    Column(Modifier.fillMaxSize().background(PANEL).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(top = 14.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Boa tarde", 11.sp, MUTED)
                T("Para onde vais?", 18.sp, weight = FontWeight.Bold)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6B7C8F), Color(0xFFC9A26B)))))
        }
        Column(Modifier.fillMaxWidth().then(mark(0)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(11.dp)).background(PANEL_2).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { T("🔍  Metro, comboio, autocarro…", 12.sp, MUTED) }
        Box(Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_2).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            T("🕘 A minha localização → Restelo", 11.sp)
        }
        }
        Column(Modifier.fillMaxWidth().then(mark(1)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Agora", 11.sp, MUTED, FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Metro" to false, "Comboios" to true, "Autocarros" to false).forEach { (name, on) ->
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) BLUE else Color.Transparent)
                        .border(1.dp, if (on) BLUE else Color(0xFF4A4D53), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                ) { T(name, 11.sp, weight = FontWeight.SemiBold) }
            }
        }
        Column(
            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)).background(PANEL_2).padding(11.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(PANEL), contentAlignment = Alignment.Center) { CpLogo(16.dp) }
                T("Comboios", 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(BLUE).padding(horizontal = 6.dp, vertical = 2.dp)) { T("Atraso", 9.sp) }
            }
            MiniTrain("U", Color(0xFF607D8B), "16546 · Castanheira", "perto de Alverca", "+53")
            MiniTrain("R", Color(0xFFF7941D), "6459 · Figueira da Foz", "perto de Pataias", "+50")
            MiniTrain("IC", Color(0xFF0055A4), "574 · Lisboa - Oriente", "perto de Entrecampos", "+39")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf(5, 14, 5).forEachIndexed { i, w ->
                Box(Modifier.padding(horizontal = 2.dp).size(w.dp, 5.dp).clip(CircleShape).background(if (i == 1) Color.White else Color(0xFF5A5D63)))
            }
        }
        }
        Column(Modifier.fillMaxWidth().then(mark(2)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Explorar", 11.sp, MUTED, FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MiniTile(Modifier.weight(1f), "Metro", "4 linhas") { MetroLogo(16.dp) }
            MiniTile(Modifier.weight(1f), "Comboios", "CP") { CpLogo(18.dp) }
            MiniTile(Modifier.weight(1f), "Autocarros", "Carris") { BusLogo(18.dp) }
        }
        T("Atalhos", 11.sp, MUTED, FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("📍 Perto de mim", "🧭 Planear viagem").forEach {
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(PANEL_2).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    T(it, 11.sp, weight = FontWeight.SemiBold)
                }
            }
        }
        }
    }
}

@Composable
private fun MiniTrain(svc: String, color: Color, title: String, near: String, delay: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(22.dp).clip(RoundedCornerShape(4.dp)).background(color), contentAlignment = Alignment.Center) { T(svc, 9.sp, weight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            T(title, 12.sp, weight = FontWeight.Medium)
            T(near, 10.sp, MUTED)
        }
        T(delay, 12.sp, VERY_LATE, FontWeight.Bold)
    }
}

@Composable
private fun MiniTile(modifier: Modifier, title: String, sub: String, icon: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(PANEL_2).padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(PANEL), contentAlignment = Alignment.Center) { icon() }
        T(title, 12.sp, weight = FontWeight.Bold)
        T(sub, 10.sp, MUTED)
    }
}

// ── 0 · Uma app nova ─────────────────────────────────────────────────────────

private val WELCOME_RED = path("M-20 330 C 70 320, 90 230, 160 220")
private val WELCOME_GREEN = path("M230 120 S 330 60, 410 70")
private val WELCOME_YELLOW = path("M240 300 C 290 330, 340 340, 410 330")

@Composable
internal fun ArtWelcome(t: Long) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.7f }) {
            partial(WELCOME_RED, Color(0xFFED1C24), 5f, Anim.enter(t, 0, 1800))
            partial(WELCOME_GREEN, GOOD, 5f, Anim.enter(t, 450, 1800))
            partial(WELCOME_YELLOW, YELLOW, 5f, Anim.enter(t, 750, 1800), dashed = true)
        }
        Phone(
            230, 372, 0.727f,
            Modifier.at(80, 26).graphicsLayer {
                val e = Anim.enter(t, 200, 1000)
                alpha = e
                translationY = (1 - e) * 90.dp.toPx()
                rotationZ = -7f * (1 - e)
                scaleX = 0.92f + 0.08f * e
                scaleY = scaleX
            }
        )
        LogoTile(58.dp, Color(0xFFED1C24), Modifier.at(22, 230).pop(t, 900)) { MetroLogo(30.dp) }
        LogoTile(58.dp, GOOD, Modifier.at(312, 70).pop(t, 1200)) { CpLogo(38.dp) }
        LogoTile(58.dp, YELLOW, Modifier.at(312, 270).pop(t, 1400)) { BusLogo(36.dp) }
        Box(Modifier.fillMaxWidth().at(0, 6).rise(t, 1700), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.float(t).shadow(10.dp, RoundedCornerShape(20.dp), spotColor = BLUE).clip(RoundedCornerShape(20.dp)).background(BLUE.copy(alpha = 0.9f)).padding(horizontal = 13.dp, vertical = 6.dp)) {
                T("Novo · versão 3.5", 12.sp, weight = FontWeight.Bold)
            }
        }
    }
}

// ── 1 · Página inicial ───────────────────────────────────────────────────────

/** Onde estão (no ecrã) o telemóvel e as três zonas da mini página inicial, para o holofote as seguir. */
private class SpotTargets {
    var phone: LayoutCoordinates? = null
    val parts = arrayOfNulls<LayoutCoordinates>(3)
}

@Composable
internal fun ArtHome(t: Long) {
    val p = Anim.loop(t, 9000, 800)
    val sky = Color(0xFF38BDF8)
    val targets = remember { SpotTargets() }
    Box(Modifier.fillMaxSize()) {
        Phone(210, 392, 0.666f, Modifier.at(30, 8).rise(t, 0), mark = { i -> Modifier.onGloballyPositioned { targets.parts[i] = it } }) {
            // Holofote: escurece tudo menos a zona explicada (medida, com a escala do telemóvel)
            Canvas(Modifier.fillMaxSize().onGloballyPositioned { targets.phone = it }) {
                val phone = targets.phone?.takeIf { it.isAttached } ?: return@Canvas
                val rects = targets.parts.map { c -> c?.takeIf { it.isAttached }?.let { phone.localBoundingBoxOf(it, clipBounds = false) } }
                if (rects.any { it == null }) return@Canvas
                val (r0, r1, r2) = rects.map { it!! }
                fun comp(f: (androidx.compose.ui.geometry.Rect) -> Float) =
                    Anim.kf(p, 0f to f(r0), 0.26f to f(r0), 0.33f to f(r1), 0.59f to f(r1), 0.66f to f(r2), 0.92f to f(r2), 1f to f(r0))
                val pad = 4.dp.toPx()
                val edge = 6.dp.toPx()
                val rect = RoundRect(
                    (comp { it.left } - pad).coerceAtLeast(edge), (comp { it.top } - pad).coerceAtLeast(edge),
                    (comp { it.right } + pad).coerceAtMost(size.width - edge), (comp { it.bottom } + pad).coerceAtMost(size.height - edge),
                    CornerRadius(12.dp.toPx())
                )
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                    addRoundRect(rect)
                }
                drawPath(shade, Color(0xFF0A0B0E).copy(alpha = 0.62f))
                drawPath(Path().apply { addRoundRect(rect) }, sky, style = Stroke(2.dp.toPx()))
            }
        }
        val labels = listOf(
            Triple(34, "1" to "Pesquisa", "Escreve para onde vais e as tuas viagens recentes"),
            Triple(120, "2" to "Agora", "Cartões a deslizar com o que importa neste momento"),
            Triple(268, "3" to "Explorar e atalhos", "Metro, comboios, autocarros, Perto de mim e o planeador")
        )
        val windows = listOf(0f to 0.28f, 0.33f to 0.61f, 0.66f to 0.94f)
        labels.forEachIndexed { i, (y, head, text) ->
            val a = if (t < 800) (if (i == 0) Anim.enter(t, 300) else 0f) else Anim.window(p, windows[i].first, windows[i].second)
            Column(Modifier.at(252, y).width(124.dp).graphicsLayer { alpha = a }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(head.first, 26.sp, sky, FontWeight.Bold)
                T(head.second, 15.sp, weight = FontWeight.Bold, maxLines = 2)
                T(text, 12.sp, MUTED, maxLines = 4)
            }
        }
    }
}

// ── 2 · Metro, Comboios e Autocarros a deslizar ──────────────────────────────

@Composable
private fun PanelRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PANEL_2).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
internal fun ArtCategories(t: Long) {
    val p = Anim.loop(t, 8000, 600)
    val green = Color(0xFF22C55E)
    val line = Anim.kf(p, 0f to 0f, 0.24f to 0f, 0.33f to 115f, 0.57f to 115f, 0.66f to 230f, 0.92f to 230f, 1f to 0f)
    val track = Anim.kf(p, 0f to 0f, 0.24f to 0f, 0.33f to -346f, 0.57f to -346f, 0.66f to -692f, 0.92f to -692f, 1f to 0f)
    val badge = Anim.window(p, 0.70f, 0.92f)
    Column(Modifier.at(22, 14).width(346.dp).card(20.dp).rise(t, 0)) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(Modifier.fillMaxSize()) {
                listOf("Metro", "Comboios", "Autocarros").forEachIndexed { i, name ->
                    Row(Modifier.width(115.dp).fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        when (i) { 0 -> MetroLogo(14.dp); 1 -> CpLogo(18.dp); else -> BusLogo(17.dp) }
                        Spacer(Modifier.width(6.dp))
                        T(name, 13.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomStart).offset(x = (20 + line).dp).size(75.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(green))
            Box(
                Modifier.at(300, 2).graphicsLayer { alpha = badge; translationY = (1 - badge) * 8.dp.toPx(); scaleX = 0.8f + 0.2f * badge; scaleY = scaleX }
                    .clip(RoundedCornerShape(8.dp)).background(YELLOW).padding(horizontal = 6.dp, vertical = 2.dp)
            ) { T("símbolo novo", 9.sp, INTRO_BG, FontWeight.ExtraBold) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3D42)))
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(0.dp))) {
            Row(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(x = track.dp)) {
                Column(Modifier.width(346.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Azul" to Color(0xFF0077C8), "Amarela" to Color(0xFFFFD500), "Verde" to Color(0xFF009E60), "Vermelha" to Color(0xFFED1C24)).forEach { (n, c) ->
                        PanelRow {
                            Box(Modifier.size(11.dp).clip(CircleShape).background(c))
                            T(n, 14.sp, modifier = Modifier.weight(1f))
                            T("Normal", 12.sp, GOOD)
                        }
                    }
                }
                Column(Modifier.width(346.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        listOf("17:42", "U", "Sintra", "Na hora"), listOf("17:50", "R", "Azambuja", "+4 min"),
                        listOf("18:01", "AP", "Porto", "+30 min"), listOf("18:12", "IC", "Faro", "Previsto")
                    ).forEach { (time, svc, dest, st) ->
                        PanelRow {
                            T(time, 14.sp, weight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                            Badge(svc, serviceColor(svc), size = 10.sp, radius = 4.dp)
                            T(dest, 14.sp, modifier = Modifier.weight(1f))
                            T(st, 12.sp, statusColor(st))
                        }
                    }
                }
                Column(Modifier.width(346.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BusRows.forEach { (n, dest, eta) ->
                        PanelRow {
                            Badge(n, lineColor(n), if (n == "79B") PANEL else Color.White, 12.sp)
                            T(dest, 14.sp, modifier = Modifier.weight(1f))
                            T(eta, 14.sp, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private val BusRows = listOf(Triple("728", "Portela", "agora"), Triple("732", "Caselas", "5 min"), Triple("79B", "Algés", "12 min"), Triple("714", "Sete Rios", "18 min"))

internal fun serviceColor(svc: String): Color = when (svc) {
    "AP" -> Color(0xFFCC0000)
    "IC" -> Color(0xFF0055A4)
    "IR" -> Color(0xFF008B45)
    "R" -> Color(0xFFF7941D)
    "FT" -> Color(0xFF007DC5)
    else -> Color(0xFF607D8B)
}

private fun lineColor(n: String): Color = when (n) {
    "79B" -> Color(0xFFFFD500)
    "728", "727" -> Color(0xFF8C8C99)
    else -> Color(0xFFEC008C)
}

private fun statusColor(s: String): Color = when {
    s == "Na hora" || s == "agora" -> GOOD
    s == "Previsto" -> MUTED
    s.removePrefix("+").substringBefore(" ").toIntOrNull()?.let { it > 5 } == true -> VERY_LATE
    else -> LATE
}

// ── 3 · Planear viagem ───────────────────────────────────────────────────────

@Composable
internal fun ArtPlanner(t: Long) {
    val typed = "Restelo".take(((t - 500) / 170).toInt().coerceIn(0, 7))
    val caret = (t / 450) % 2 == 0L
    val blink = Anim.loop(t, 3000, 2400)
    val chipOn = t >= 2400 && (blink in 0.08f..0.32f)
    val filter = Anim.loop(t, 4000, 2000)
    val trainOn = t < 2000 || !(filter in 0.40f..0.92f)
    Column(
        Modifier.at(22, 18).width(346.dp).shadow(30.dp, RoundedCornerShape(18.dp)).card(18.dp).rise(t, 0).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Field(BLUE, false) { T("A minha localização", 15.sp, MUTED) }
        Field(BLUE, true) {
            T(typed, 15.sp)
            Box(Modifier.padding(start = 2.dp).size(2.dp, 18.dp).background(if (caret) BLUE else Color.Transparent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip("Metro", true) { MetroLogo(11.dp) }
            FilterChip("Autocarro", true) { BusLogo(14.dp) }
            FilterChip("Comboio", trainOn) { CpLogo(16.dp) }
        }
        Column(Modifier.fillMaxWidth().rise(t, 1200).clip(RoundedCornerShape(14.dp)).background(PANEL_2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("🚶 4", 13.sp, MUTED); T("›", 13.sp, Color(0xFF5A5D63))
                Badge("728", Color(0xFF8C8C99), size = 13.sp); T("›", 13.sp, Color(0xFF5A5D63))
                T("🚶 2", 13.sp, MUTED)
                Spacer(Modifier.weight(1f))
                T("32 min", 17.sp, weight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("20:07", "20:26", "20:46", "+9").forEachIndexed { i, s ->
                    val on = i == 0 && chipOn
                    Box(Modifier.clip(RoundedCornerShape(13.dp)).background(if (on) BLUE else PANEL).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        T(s, 12.sp, if (on) Color.White else Color(0xFFDADDE2), FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().rise(t, 1700).clip(RoundedCornerShape(14.dp)).background(PANEL_2).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Badge("Azul", Color(0xFF0077C8), size = 13.sp); T("›", 13.sp, Color(0xFF5A5D63))
            Badge("714", Color(0xFFEC008C), size = 13.sp)
            T("· muda no Cais", 13.sp, MUTED)
            Spacer(Modifier.weight(1f))
            T("38 min", 17.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Field(accent: Color, focused: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_2)
            .then(if (focused) Modifier.border(1.dp, accent, RoundedCornerShape(12.dp)) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).then(
                if (focused) Modifier.clip(RoundedCornerShape(2.dp)).background(accent)
                else Modifier.border(2.dp, accent, CircleShape)
            )
        )
        Spacer(Modifier.width(10.dp))
        content()
    }
}

@Composable
private fun FilterChip(text: String, on: Boolean, icon: @Composable () -> Unit) {
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) BLUE.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (on) BLUE else Color(0xFF4A4D53), RoundedCornerShape(15.dp)).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon()
        T(text, 13.sp, weight = FontWeight.SemiBold)
    }
}

// ── Carris Metropolitana em toda a área ──────────────────────────────────────

private val CM_RED = Color(0xFFC61D23)
private val CM_BLUE = Color(0xFF3D85C6)

@Composable
internal fun ArtCm(t: Long) {
    val typed = "seixal".take(((t - 400) / 160).toInt().coerceIn(0, 6))
    val caret = (t / 450) % 2 == 0L
    Column(Modifier.at(22, 14).width(346.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(18.dp)).card(18.dp).rise(t, 0).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_2).border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("🔍", 13.sp)
                Spacer(Modifier.width(8.dp))
                T(typed, 15.sp)
                Box(Modifier.padding(start = 2.dp).size(2.dp, 18.dp).background(if (caret) Color(0xFFF59E0B) else Color.Transparent))
            }
            listOf(
                "Seixal (Fórum) Tribunal" to 1300,
                "Seixal (Av Albano N Pereira) Cent Saúde" to 1500,
                "Estação do Fogueteiro P2" to 1700
            ).forEach { (name, d) ->
                Row(Modifier.fillMaxWidth().rise(t, d).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF34363A)), contentAlignment = Alignment.Center) { T("P", 14.sp, weight = FontWeight.Black) }
                    Column(Modifier.weight(1f)) {
                        T(name, 14.sp, weight = FontWeight.Medium)
                        T("Carris Metropolitana · Seixal", 11.sp, MUTED)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().card(18.dp).rise(t, 2300).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Cacilhas → Seixal", 15.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                T("56 min", 17.sp, weight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("🚶 1", 13.sp, MUTED); T("›", 13.sp, Color(0xFF5A5D63))
                Badge("3514", CM_RED, size = 13.sp); T("›", 13.sp, Color(0xFF5A5D63))
                Badge("3116", CM_RED, size = 13.sp); T("›", 13.sp, Color(0xFF5A5D63))
                T("🚶 2", 13.sp, MUTED)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("🚶 1", 13.sp, MUTED); T("›", 13.sp, Color(0xFF5A5D63))
                Badge("Azul", Color(0xFF0077C8), size = 13.sp); T("›", 13.sp, Color(0xFF5A5D63))
                Badge("3716", CM_RED, size = 13.sp); T("›", 13.sp, Color(0xFF5A5D63))
                Badge("3113", CM_BLUE, size = 13.sp)
                Spacer(Modifier.weight(1f))
                T("de Lisboa", 11.sp, MUTED)
            }
        }
    }
}

// ── 4 · Agora a deslizar ─────────────────────────────────────────────────────

@Composable
internal fun ArtCarousel(t: Long) {
    val p = Anim.loop(t, 7500, 600)
    val violet = Color(0xFF8B5CF6)
    val track = Anim.kf(p, 0f to 0f, 0.24f to 0f, 0.33f to -262f, 0.57f to -262f, 0.66f to -524f, 0.90f to -524f, 1f to 0f)
    val hlX = Anim.kf(p, 0f to 0f, 0.24f to 0f, 0.33f to 80f, 0.57f to 80f, 0.66f to 184f, 0.90f to 184f, 1f to 0f)
    val hlW = Anim.kf(p, 0f to 72f, 0.24f to 72f, 0.33f to 96f, 0.57f to 96f, 0.66f to 104f, 0.90f to 104f, 1f to 72f)
    // O dedo: duas passagens da direita para a esquerda, a acompanhar os cartões
    val fx = Anim.kf(p, 0f to 70f, 0.21f to 70f, 0.31f to -70f, 0.34f to 70f, 0.54f to 70f, 0.64f to -70f, 0.67f to 70f, 1f to 70f)
    val fa = maxOf(Anim.window(p, 0.21f, 0.31f, 0.02f), Anim.window(p, 0.54f, 0.64f, 0.02f)) * 0.95f
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.at(24, 24)) {
            Box(Modifier.offset(x = hlX.dp).size(hlW.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(violet))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Metro" to 72, "Comboios" to 96, "Autocarros" to 104).forEach { (n, w) ->
                    Box(Modifier.size(w.dp, 32.dp), contentAlignment = Alignment.Center) { T(n, 13.sp, weight = FontWeight.SemiBold) }
                }
            }
        }
        Box(Modifier.at(0, 72).fillMaxWidth().height(300.dp)) {
            Row(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(x = (70 + track).dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NowCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetroLogo(20.dp); T("Metro", 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f)); T("● Normal", 11.sp, GOOD)
                    }
                    MetroRow(Color(0xFFED1C24), "Aeroporto", "2 min", GOOD)
                    MetroRow(Color(0xFFED1C24), "São Sebastião", "6 min", Color.White)
                    MetroRow(Color(0xFFFFD500), "Odivelas", "a chegar", GOOD)
                    MetroRow(Color(0xFFFFD500), "Rato", "4 min", Color.White)
                }
                NowCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CpLogo(24.dp); T("Comboios", 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f)); T("pelo atraso", 11.sp, MUTED)
                    }
                    listOf(Triple("U", "16546 · Castanheira", "+53"), Triple("R", "6459 · Figueira", "+50"), Triple("IR", "805 · Caldas", "+49"), Triple("IC", "574 · Oriente", "+39")).forEach { (s, n, d) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Badge(s, serviceColor(s), size = 10.sp, radius = 4.dp); T(n, 13.sp, modifier = Modifier.weight(1f)); T(d, 13.sp, VERY_LATE, FontWeight.Bold)
                        }
                    }
                }
                NowCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BusLogo(22.dp); T("Autocarros", 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f)); T("as tuas paragens", 11.sp, MUTED)
                    }
                    T("Restelo (Torres)", 13.sp, weight = FontWeight.SemiBold)
                    BusRows.take(3).forEach { (n, dest, eta) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Badge(n, lineColor(n), if (n == "79B") PANEL else Color.White, 11.sp); T(dest, 13.sp, modifier = Modifier.weight(1f))
                            T(eta, 13.sp, if (eta == "agora") GOOD else Color.White, FontWeight.Bold)
                        }
                    }
                }
                NowCard {}
            }
            Finger(Modifier.at(165 + fx, 150).graphicsLayer { alpha = fa })
        }
    }
}

@Composable
private fun NowCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.size(250.dp, 290.dp).card(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun MetroRow(c: Color, dest: String, eta: String, etaColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(c))
        T(dest, 13.sp, modifier = Modifier.weight(1f))
        T(eta, 13.sp, etaColor, FontWeight.Bold)
    }
}

// ── 5 · Personalizar o "Agora" ───────────────────────────────────────────────

@Composable
internal fun ArtEdit(t: Long) {
    val p = Anim.loop(t, 6000, 600)
    val liftY = Anim.kf(p, 0f to 0f, 0.20f to 0f, 0.36f to -56f, 0.90f to -56f, 1f to 0f)
    val lifted = Anim.kf(p, 0f to 0f, 0.14f to 0f, 0.20f to 1f, 0.40f to 1f, 0.46f to 0f, 1f to 0f)
    val sinkY = Anim.kf(p, 0f to 0f, 0.20f to 0f, 0.36f to 56f, 0.90f to 56f, 1f to 0f)
    val favOn = Anim.kf(p, 0f to 1f, 0.56f to 1f, 0.62f to 0f, 0.94f to 0f, 1f to 1f)
    Column(Modifier.at(22, 18).width(346.dp).card(20.dp).rise(t, 0).padding(14.dp)) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Personalizar \"Agora\"", 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            T("Feito", 13.sp, Color(0xFF60A5FA), FontWeight.SemiBold)
        }
        EditRow("Metro", 1f, Modifier.graphicsLayer { translationY = sinkY.dp.toPx() }) { MetroLogo(14.dp) }
        EditRow(
            "Comboios a circular", 1f,
            Modifier.zIndex(2f).graphicsLayer {
                translationY = liftY.dp.toPx()
                scaleX = 1f + 0.03f * lifted
                scaleY = scaleX
                shadowElevation = 14.dp.toPx() * lifted
                shape = RoundedCornerShape(12.dp)
                clip = false
            }
        ) { CpLogo(18.dp) }
        EditRow("Autocarros", 1f) { BusLogo(17.dp) }
        EditRow("As minhas estações", 1f) { T("🚉", 14.sp) }
        EditRow("Para casa", 1f) { T("🏠", 14.sp) }
        EditRow("Favoritas", favOn) { T("⭐", 14.sp) }
    }
}

@Composable
private fun EditRow(name: String, on: Float, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Row(
        modifier.padding(bottom = 8.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        T("⋮⋮", 16.sp, Color(0xFF6B6E74))
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(PANEL), contentAlignment = Alignment.Center) { icon() }
        T(name, 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switchy(on)
    }
}

/** Interruptor desenhado (0 = desligado, 1 = ligado, valores pelo meio a meio caminho). */
@Composable
private fun Switchy(on: Float) {
    Box(Modifier.size(40.dp, 22.dp).clip(RoundedCornerShape(11.dp)).background(lerpColor(Color(0xFF4A4D53), BLUE, on))) {
        Box(Modifier.offset(x = (2 + 18 * on).dp, y = 2.dp).size(18.dp).clip(CircleShape).background(Color.White))
    }
}

internal fun lerpColor(a: Color, b: Color, f: Float): Color = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))

// ── 6 · As minhas estações e Para casa ───────────────────────────────────────

@Composable
internal fun ArtStations(t: Long) {
    val p = Anim.loop(t, 9000, 600)
    val hlX = Anim.kf(p, 0f to 0f, 0.28f to 0f, 0.33f to 78f, 0.61f to 78f, 0.66f to 184f, 0.95f to 184f, 1f to 0f)
    val hlW = Anim.kf(p, 0f to 72f, 0.28f to 72f, 0.33f to 100f, 0.61f to 100f, 0.66f to 82f, 0.95f to 82f, 1f to 72f)
    val a1 = if (t < 600) 1f else maxOf(Anim.window(p, 0f, 0.30f, 0.03f), Anim.window(p, 0.98f, 1.1f, 0.03f))
    val a2 = Anim.window(p, 0.33f, 0.62f, 0.03f)
    val a3 = Anim.window(p, 0.66f, 0.95f, 0.03f)
    Column(Modifier.at(22, 14).width(346.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().card(20.dp).rise(t, 0).padding(14.dp)) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("🚉", 16.sp); T("As minhas estações", 16.sp, weight = FontWeight.Bold)
            }
            Box(Modifier.padding(bottom = 10.dp)) {
                Box(Modifier.offset(x = hlX.dp).size(hlW.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(BLUE))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Oriente" to 72, "Entrecampos" to 100, "Saldanha" to 82).forEach { (n, w) ->
                        Box(Modifier.size(w.dp, 28.dp), contentAlignment = Alignment.Center) { T(n, 12.sp, weight = FontWeight.SemiBold) }
                    }
                }
            }
            Row(Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PANEL_2).padding(3.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(BLUE).padding(vertical = 5.dp), contentAlignment = Alignment.Center) { T("Partidas", 12.sp, weight = FontWeight.SemiBold) }
                Box(Modifier.weight(1f).padding(vertical = 5.dp), contentAlignment = Alignment.Center) { T("Chegadas", 12.sp, MUTED, FontWeight.SemiBold) }
            }
            Box(Modifier.fillMaxWidth().height(118.dp)) {
                Board(a1, listOf(listOf("17:44", "U", "Azambuja", "agora"), listOf("17:50", "IC", "Porto", "+4 min"), listOf("17:58", "AP", "Braga", "Na hora")))
                Board(a2, listOf(listOf("17:46", "U", "Sintra", "2 min"), listOf("17:52", "FT", "Setúbal", "+3 min"), listOf("18:01", "AP", "Porto", "+30 min")))
                Column(Modifier.graphicsLayer { alpha = a3 }) {
                    listOf(Triple(Color(0xFFED1C24), "Aeroporto", "2 min"), Triple(Color(0xFFFFD500), "Odivelas", "4 min"), Triple(Color(0xFFFFD500), "Rato", "6 min")).forEach { (c, d, e) ->
                        StationLine { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c)); T(d, 14.sp, modifier = Modifier.weight(1f)); T(e, 14.sp, if (e == "2 min") GOOD else Color.White, FontWeight.Bold) }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().rise(t, 900).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A2414), PANEL))).border(1.dp, Color(0xFF3A3320), RoundedCornerShape(18.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            T("🏠", 26.sp, modifier = Modifier.float(t))
            Column(Modifier.weight(1f)) {
                T("Para casa", 15.sp, weight = FontWeight.Bold)
                T("Último metro 01:12 · último comboio 00:58", 12.sp, MUTED, maxLines = 2)
            }
            T("Ir agora", 14.sp, BLUE, FontWeight.Bold)
        }
    }
}

@Composable
private fun StationLine(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(39.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Board(alpha: Float, rows: List<List<String>>) {
    Column(Modifier.graphicsLayer { this.alpha = alpha }) {
        rows.forEach { (time, svc, dest, st) ->
            StationLine {
                T(time, 14.sp, weight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                Badge(svc, serviceColor(svc), size = 10.sp, radius = 4.dp)
                T(dest, 14.sp, modifier = Modifier.weight(1f))
                T(st, 12.sp, statusColor(st))
            }
        }
    }
}

// ── 7 · Um sininho em cada autocarro ─────────────────────────────────────────

private const val BELL = "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-6V11a6 6 0 0 0-5-5.9V4a1 1 0 0 0-2 0v1.1A6 6 0 0 0 6 11v5l-2 2v1h16v-1z"
private val BELL_PATH = path(BELL)

@Composable
private fun Bell(on: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            if (on > 0f) drawPath(BELL_PATH, BLUE.copy(alpha = on))
            drawPath(BELL_PATH, lerpColor(MUTED, BLUE, on), style = Stroke(1.8f))
        }
    }
}

@Composable
internal fun ArtBells(t: Long) {
    val p = Anim.loop(t, 5500, 800)
    val on = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.29f to 0f, 0.31f to 1f, 0.92f to 1f, 1f to 0f)
    val ring = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.28f to 0f, 0.44f to 1f, 1f to 1f)
    val ringA = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.28f to 0f, 0.30f to 0.7f, 0.44f to 0f, 1f to 0f)
    val fingerA = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.14f to 0f, 0.24f to 0.95f, 0.34f to 0.95f, 0.44f to 0f, 1f to 0f)
    val fingerXY = Anim.kf(p, 0f to 1f, 0.14f to 1f, 0.24f to 0f, 1f to 0f)
    val press = Anim.kf(p, 0f to 1f, 0.24f to 1f, 0.29f to 0.82f, 0.34f to 1f, 1f to 1f)
    val notif = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.52f to 0f, 0.60f to 1f, 0.88f to 1f, 0.96f to 0f, 1f to 0f)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.at(20, 86).width(350.dp).card(18.dp).rise(t, 0)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF34363A)), contentAlignment = Alignment.Center) { T("P", 15.sp, weight = FontWeight.Black) }
                T("Restelo (Torres)", 15.sp, weight = FontWeight.Bold)
            }
            StopRow("79B", "Algés", "previsto · 20:06", MUTED, "5", Color.White) { Bell(0f) }
            StopRow("728", "Portela", "● a horas", Color(0xFF6FCF73), "6", Color(0xFF6FCF73)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(BLUE.copy(alpha = 0.2f * on)), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 0.3f + 1.6f * ring; scaleY = scaleX; alpha = ringA }.border(2.dp, BLUE, CircleShape))
                    Bell(on, Modifier.graphicsLayer { scaleX = press; scaleY = press })
                    Finger(Modifier.graphicsLayer { alpha = fingerA; translationX = 50.dp.toPx() * fingerXY; translationY = 70.dp.toPx() * fingerXY; scaleX = press; scaleY = press })
                }
            }
            StopRow("732", "Hosp. Sta. Maria", "previsto · 20:11", MUTED, "10", Color.White) { Bell(0f) }
            StopRow("79B", "Algés", "● atrasado 33 min · ", Color(0xFFFF5252), "15", Color(0xFFFF5252), struck = "19:43", last = true) { Bell(0f) }
        }
        Row(
            Modifier.at(20, 10).width(350.dp).graphicsLayer { translationY = -140.dp.toPx() * (1 - notif); alpha = if (notif > 0f) 1f else 0f }
                .shadow(16.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xFF303238)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFED1C24)), contentAlignment = Alignment.Center) { T("M", 15.sp, weight = FontWeight.Black) }
            Column {
                T("O 728 está a chegar", 14.sp, weight = FontWeight.Bold)
                T("Chega a Restelo dentro de 3 min (a horas).", 13.sp, Color(0xFFDADDE2))
            }
        }
    }
}

@Composable
private fun StopRow(
    line: String, dest: String, status: String, statusColor: Color, eta: String, etaColor: Color,
    struck: String? = null, last: Boolean = false, bell: @Composable () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LINE))
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(line, lineColor(line), if (line == "79B") PANEL else Color.White, 13.sp)
                T(dest, 15.sp)
            }
            Row {
                T(status, 12.sp, statusColor)
                if (struck != null) androidx.compose.material3.Text(struck, color = Color(0xFF8E929A), fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
            }
        }
        Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T(eta, 20.sp, etaColor, FontWeight.Bold)
            T("min", 11.sp, MUTED)
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { bell() }
    }
}

// ── 8 · Puxa o painel ────────────────────────────────────────────────────────

private val MAP_ROUTE = path("M-10 90 C 80 120, 160 60, 360 100")
private val MAP_ROAD_1 = path("M120 -10 L 170 410")
private val MAP_ROAD_2 = path("M-10 230 C 90 200, 220 260, 360 210")

@Composable
internal fun ArtSheet(t: Long) {
    val p = Anim.loop(t, 7000, 600)
    val h = Anim.kf(p, 0f to 0.46f, 0.12f to 0.46f, 0.28f to 0.86f, 0.42f to 0.86f, 0.58f to 0.24f, 0.72f to 0.24f, 0.88f to 0.46f, 1f to 0.46f)
    val busAt = Anim.loop(t, 9000)
    Box(Modifier.at(22, 0).size(346.dp, 404.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1B1D21)).border(1.dp, LINE, RoundedCornerShape(24.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            scale(density, density, pivot = Offset.Zero) {
                drawPath(MAP_ROUTE, Color(0xFF2A2D33), style = Stroke(14f))
                drawPath(MAP_ROAD_1, Color(0xFF25282E), style = Stroke(10f))
                drawPath(MAP_ROAD_2, Color(0xFF25282E), style = Stroke(9f))
                drawPath(MAP_ROUTE, YELLOW, style = Stroke(3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 9f))))
            }
        }
        val pm = PathMeasure().apply { setPath(MAP_ROUTE, false) }
        val pos = pm.getPosition(pm.length * busAt)
        Box(
            Modifier.at(pos.x - 15, pos.y - 15).size(30.dp).clip(RoundedCornerShape(9.dp)).background(PANEL).border(2.dp, YELLOW, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) { BusLogo(18.dp) }
        Box(Modifier.at(160, 150).size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF34363A)).border(2.dp, Color.White, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            T("P", 14.sp, weight = FontWeight.Black)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(h)
                .shadow(16.dp, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0xFF24262A)).padding(horizontal = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF6B6E74)))
                Finger(Modifier.offset(y = (-3).dp))
            }
            T("Restelo (Torres)", 15.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            repeat(7) {
                Box(Modifier.padding(bottom = 10.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E3034)))
            }
        }
    }
}

// ── 9 · Avisos de viagem ─────────────────────────────────────────────────────

@Composable
internal fun ArtAlerts(t: Long) {
    val p = Anim.loop(t, 6000, 500)
    val ride = if (t < 500) 0f else Anim.kf(p, 0f to 0f, 0.62f to 1f, 1f to 1f)
    val orange = Color(0xFFFF8A3D)
    val buzz = if (t < 500) 0f else Anim.kf(p, 0f to 0f, 0.61f to 0f, 0.63f to -4f, 0.65f to 4f, 0.67f to -3f, 0.69f to 3f, 0.71f to 0f, 1f to 0f)
    val notif = if (t < 500) 0f else Anim.kf(p, 0f to 0f, 0.60f to 0f, 0.66f to 1f, 0.94f to 1f, 1f to 0f)
    val dist = when {
        t < 500 || p < 0.22f -> "900 m"
        p < 0.44f -> "700 m"
        else -> "500 m"
    }
    Column(Modifier.at(22, 18).width(346.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().height(190.dp).graphicsLayer { rotationZ = buzz }.card(18.dp).padding(16.dp)) {
            Column {
                T("A caminho de", 12.sp, MUTED, FontWeight.Bold)
                T("Mosteiro dos Jerónimos", 17.sp, weight = FontWeight.Bold)
            }
            Box(Modifier.at(0, 82).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(LINE))
            Box(Modifier.at(0, 82).fillMaxWidth(0.04f + 0.54f * ride).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(listOf(orange, YELLOW))))
            Box(
                Modifier.at(314 * (0.04f + 0.54f * ride), 64).size(40.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_2).border(2.dp, YELLOW, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { BusLogo(24.dp) }
            Box(Modifier.align(Alignment.TopEnd).offset(y = 68.dp).size(34.dp).clip(CircleShape).background(orange), contentAlignment = Alignment.Center) { T("📍", 16.sp) }
            Row(Modifier.at(0, 118), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T(dist, 26.sp, if (dist == "500 m") orange else Color.White, FontWeight.Black)
                T("até saíres", 13.sp, MUTED, modifier = Modifier.padding(bottom = 4.dp))
            }
            Column(
                Modifier.fillMaxWidth().graphicsLayer { alpha = notif; translationY = (1 - notif) * -12.dp.toPx() }
                    .shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xFF34363A)).padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                T("Prepara-te para sair", 14.sp, weight = FontWeight.Bold)
                T("Mosteiro dos Jerónimos a 500 m.", 13.sp, Color(0xFFDADDE2))
            }
        }
        Row(
            Modifier.fillMaxWidth().rise(t, 900).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF1B2340), PANEL)))
                .border(1.dp, Color(0xFF2E3550), RoundedCornerShape(18.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            T("🌙", 32.sp, modifier = Modifier.float(t))
            Column {
                T("Último metro para casa", 15.sp, weight = FontWeight.Bold)
                T("00:58 em Saldanha · sai daqui a 25 min", 13.sp, MUTED)
            }
        }
    }
}

// ── 10 · Comboios pela hora real ─────────────────────────────────────────────

@Composable
internal fun ArtRealTime(t: Long) {
    val p = Anim.loop(t, 6000, 800)
    val move = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.24f to 0f, 0.40f to 1f, 0.86f to 1f, 0.96f to 0f, 1f to 0f)
    val newTime = if (t < 800) 0f else Anim.kf(p, 0f to 0f, 0.16f to 0f, 0.22f to 1f, 0.90f to 1f, 0.96f to 0f, 1f to 0f)
    Column(Modifier.at(22, 22).width(346.dp).card(18.dp).rise(t, 0).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 18.dp)) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CpLogo(26.dp); T("Entrecampos · Partidas", 16.sp, weight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(204.dp)) {
            BoardRow(Modifier.graphicsLayer { translationY = 136.dp.toPx() * move }) {
                Box(Modifier.width(52.dp).height(40.dp)) {
                    T("17:31", 17.sp, weight = FontWeight.Bold, modifier = Modifier.offset(y = 8.dp).graphicsLayer { alpha = 1 - newTime })
                    Column(Modifier.graphicsLayer { alpha = newTime }) {
                        T("18:01", 17.sp, VERY_LATE, FontWeight.Bold)
                        androidx.compose.material3.Text("17:31", color = Color(0xFF8E929A), fontSize = 12.sp, lineHeight = 14.sp, textDecoration = TextDecoration.LineThrough)
                    }
                }
                Badge("AP", serviceColor("AP"), size = 11.sp, radius = 4.dp)
                T("Porto - Campanhã", 15.sp, modifier = Modifier.weight(1f))
                T("+30 min", 12.sp, VERY_LATE, FontWeight.Bold, Modifier.graphicsLayer { alpha = newTime })
            }
            BoardRow(Modifier.offset(y = 68.dp).graphicsLayer { translationY = -68.dp.toPx() * move }) {
                T("17:42", 17.sp, weight = FontWeight.Bold, modifier = Modifier.width(52.dp)); Badge("U", serviceColor("U"), size = 11.sp, radius = 4.dp)
                T("Sintra", 15.sp, modifier = Modifier.weight(1f)); T("Na hora", 12.sp, GOOD, FontWeight.Bold)
            }
            BoardRow(Modifier.offset(y = 136.dp).graphicsLayer { translationY = -68.dp.toPx() * move }) {
                T("17:50", 17.sp, weight = FontWeight.Bold, modifier = Modifier.width(52.dp)); Badge("R", serviceColor("R"), size = 11.sp, radius = 4.dp)
                T("Azambuja", 15.sp, modifier = Modifier.weight(1f)); T("+4 min", 12.sp, LATE, FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BoardRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content
    )
}

// ── 11 · Perto de mim ────────────────────────────────────────────────────────

@Composable
internal fun ArtNearby(t: Long) {
    val pink = Color(0xFFF472B6)
    val sweep = Anim.loop(t, 3500) * 360f
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.at(45, 30).size(300.dp)) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFF2A2D33), r, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFF2A2D33), r / 2, style = Stroke(1.dp.toPx()))
            // O varrimento do radar
            drawArc(pink.copy(alpha = 0.10f), sweep - 90f, 90f, true)
            drawArc(pink.copy(alpha = 0.14f), sweep - 45f, 45f, true)
            drawArc(pink.copy(alpha = 0.18f), sweep - 15f, 15f, true)
            for (k in 0..2) {
                val q = Anim.loop(t + k * 900L, 2700)
                drawCircle(pink.copy(alpha = 0.75f * (1 - q)), r * (0.15f + 0.85f * q), style = Stroke(2.dp.toPx()))
            }
        }
        Box(Modifier.at(183, 168).size(24.dp).clip(CircleShape).background(Color.White).padding(4.dp).clip(CircleShape).background(BLUE))
        Pin(Modifier.at(76, 92).pop(t, 450), "Saldanha · 250 m") { MetroLogo(24.dp) }
        Pin(Modifier.at(214, 76).pop(t, 900), "Entrecampos · 600 m") { CpLogo(26.dp) }
        Pin(Modifier.at(186, 236).pop(t, 1400), "Av. República · 120 m") { BusLogo(26.dp) }
    }
}

@Composable
private fun Pin(modifier: Modifier, label: String, icon: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PANEL).border(1.dp, LINE, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { icon() }
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(PANEL).padding(horizontal = 7.dp, vertical = 2.dp)) { T(label, 11.sp, weight = FontWeight.Bold) }
    }
}

// ── 12 · Widgets novos ───────────────────────────────────────────────────────

@Composable
internal fun ArtWidgets(t: Long) {
    Box(
        Modifier.at(22, 0).size(346.dp, 404.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF23324F), Color(0xFF3B2A4F), Color(0xFF1B1D2A))))
    ) {
        T("20:02", 34.sp, Color.White.copy(alpha = 0.9f), FontWeight.Light, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        WidgetCard(Modifier.at(14, 66).pop(t, 300), { CpLogo(20.dp) }, "Entrecampos", "há 20 s ⟳") {
            listOf(listOf("17:46", "U", "Sintra", "Na hora"), listOf("17:52", "FT", "Setúbal", "+3 min"), listOf("18:01", "AP", "Porto", "+30 min")).forEach { (time, s, d, st) ->
                WidgetLine {
                    T(time, 12.sp, weight = FontWeight.Bold, modifier = Modifier.width(36.dp)); Badge(s, serviceColor(s), size = 9.sp, radius = 3.dp)
                    T(d, 12.sp, modifier = Modifier.weight(1f)); T(st, 11.sp, statusColor(st))
                }
            }
        }
        WidgetCard(Modifier.at(14, 196).pop(t, 900), { BusLogo(18.dp) }, "Restelo (Torres)", "há 10 s ⟳") {
            BusRows.take(3).forEach { (n, d, e) ->
                WidgetLine {
                    Badge(n, lineColor(n), if (n == "79B") PANEL else Color.White, 10.sp); T(d, 12.sp, modifier = Modifier.weight(1f))
                    T(e, 12.sp, if (e == "agora") Color(0xFF6FCF73) else Color.White, FontWeight.Bold)
                }
            }
        }
        Column(Modifier.at(14, 318).width(150.dp).pop(t, 1400).clip(RoundedCornerShape(18.dp)).background(Color(0xE016171B)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { MetroLogo(12.dp); T("Saldanha", 12.sp, weight = FontWeight.Bold) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) { T("Aeroporto", 12.sp, modifier = Modifier.weight(1f)); T("2 min", 12.sp, GOOD, FontWeight.Bold) }
        }
        Row(Modifier.at(184, 318).pop(t, 1700), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFFED1C24), Color(0xFFB3121A)))), contentAlignment = Alignment.Center) {
                T("M", 18.sp, weight = FontWeight.Black)
            }
            repeat(2) { Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.18f))) }
        }
    }
}

@Composable
private fun WidgetCard(modifier: Modifier, icon: @Composable () -> Unit, title: String, updated: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier.width(318.dp).shadow(14.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Color(0xE016171B)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon(); T(title, 13.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f)); T(updated, 10.sp, MUTED)
        }
        rows()
    }
}

@Composable
private fun WidgetLine(content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

// ── 13 · Letra grande e menos ruído ──────────────────────────────────────────

@Composable
internal fun ArtComfort(t: Long) {
    val p = Anim.loop(t, 6000, 600)
    val g = if (t < 600) 0f else Anim.kf(p, 0f to 0f, 0.15f to 0f, 0.45f to 1f, 0.85f to 1f, 1f to 0f)
    val off = if (t < 600) 0f else Anim.kf(p, 0f to 0f, 0.44f to 0f, 0.50f to 1f, 0.94f to 1f, 1f to 0f)
    val gone = if (t < 600) 0f else Anim.kf(p, 0f to 0f, 0.50f to 0f, 0.60f to 1f, 0.90f to 1f, 1f to 0f)
    Column(Modifier.at(22, 18).width(346.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().card(20.dp).rise(t, 0).padding(16.dp)) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                T("Aa", 14.sp, weight = FontWeight.Bold)
                BoxWithConstraints(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3D42)))
                    Box(Modifier.offset(x = maxWidth * (0.2f + 0.6f * g) - 9.dp).size(18.dp).clip(CircleShape).background(BLUE))
                }
                T("Aa", 20.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL_2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("U", "16546 · Castanheira do Ribatejo", "perto de Alverca") to "+53", Triple("IC", "574 · Lisboa - Oriente", "perto de Entrecampos") to "+39").forEach { (row, d) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Badge(row.first, serviceColor(row.first), size = 10.sp, radius = 4.dp)
                        Column(Modifier.weight(1f)) {
                            T(row.second, (14 + 5 * g).sp, weight = FontWeight.Medium)
                            T(row.third, (12 + 4 * g).sp, MUTED)
                        }
                        T(d, 14.sp, VERY_LATE, FontWeight.Bold)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().card(20.dp).rise(t, 750).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = 1 - gone; translationX = 60.dp.toPx() * gone }
                    .clip(RoundedCornerShape(14.dp)).background(PANEL_2).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFED1C24)), contentAlignment = Alignment.Center) { T("M", 12.sp, weight = FontWeight.Black) }
                Column {
                    T("Metrotime em segundo plano", 13.sp, weight = FontWeight.Bold)
                    T("Widgets e avisos de viagem", 11.sp, MUTED)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Mostrar a notificação", 13.sp, modifier = Modifier.weight(1f))
                Switchy(1 - off)
            }
        }
    }
}

// ── 14 · Tudo pronto ─────────────────────────────────────────────────────────

private val CONFETTI = listOf(Color(0xFFED1C24), Color(0xFFFFD500), Color(0xFF009E60), Color(0xFF0077C8), YELLOW, GOOD, BLUE, Color(0xFFF472B6))
private val CHECK = path("M36 62 L53 78 L86 44")

@Composable
internal fun ArtDone(t: Long) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 22) {
                val left = ((i * 37) % 360 + 10).dp.toPx()
                val dur = (2600 + (i * 7 % 10) * 100)
                val delay = (i * 13 % 25) * 100
                val q = Anim.loop(t, dur, delay)
                if (t < delay) continue
                val y = -20.dp.toPx() + 440.dp.toPx() * q
                rotate(560f * q, Offset(left + 4.5.dp.toPx(), y + 7.dp.toPx())) {
                    drawRoundRect(CONFETTI[i % CONFETTI.size].copy(alpha = 1 - q), Offset(left, y), Size(9.dp.toPx(), 14.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
        }
        Canvas(Modifier.at(135, 30).size(120.dp).pop(t, 0)) {
            drawCircle(BLUE, 54.dp.toPx())
            partial(CHECK, Color.White, 10f, Anim.enter(t, 500, 700))
        }
        Column(Modifier.at(32, 176).width(326.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple("Uma app nova, com página inicial nova", BLUE, 450),
                Triple("Planeador com metro, comboio e os autocarros de toda a área", Color(0xFF8B5CF6), 750),
                Triple("Sininhos em cada autocarro e widgets novos", YELLOW, 1050),
                Triple("Notificação discreta (esconde-a nas Definições)", GOOD, 1400)
            ).forEach { (text, c, d) ->
                Row(
                    Modifier.fillMaxWidth().rise(t, d).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    T("✓", 14.sp, c, FontWeight.Bold)
                    T(text, 14.sp, maxLines = 2)
                }
            }
        }
    }
}
