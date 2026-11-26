package com.example.metrotime.ui.caderneta

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.metrotime.data.caderneta.BadgeState
import com.example.metrotime.data.caderneta.Tier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A cor de destaque de cada raridade, para os raios e os azulejos a cair. */
private fun accent(tier: Tier): Color = when (tier) {
    Tier.COMUM -> Color(0xFF7FB2FF)
    Tier.RARO -> Color(0xFF9DB8FF)
    Tier.LENDARIO -> Color(0xFFF4D27A)
    Tier.IMPOSSIVEL -> Color(0xFF9FE7FF)
    Tier.SECRETO -> Color(0xFFC9B6FF)
}

/**
 * "Distintivo novo": o distintivo aparece em grande com uma mola, raios a rodar por trás, azulejos a cair e uma
 * vibração. Vários de uma vez passam um a um ("Seguinte"). "Partilhar" abre a imagem para os stories.
 */
@Composable
fun BadgeUnlockDialog(badges: List<BadgeState>, onShowAll: () -> Unit = {}, onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(false) }
    val state = badges.getOrNull(index) ?: return
    val haptic = LocalHapticFeedback.current
    val pop = remember(index) { Animatable(0.3f) }
    LaunchedEffect(index) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    val transition = rememberInfiniteTransition(label = "ganho")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "raios")
    val glow by transition.animateFloat(0.55f, 0.95f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "brilho")
    val fall by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "cair")
    val color = accent(state.def.tier)
    // Quantas pessoas da app o têm (contado no Firebase; sem rede, não aparece)
    val holders by produceState<String?>(null, state.def.id) {
        value = com.example.metrotime.data.caderneta.BadgeCloud.counts()?.text(state.def.id, mine = true)
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF20B0C0E))) {
            // Azulejos a cair
            Canvas(Modifier.fillMaxSize()) {
                val tiles = listOf(Color(0xFFF3EFE6), Color(0xFF1E4AA8), color, Color(0xFF141A30))
                for (i in 0 until 18) {
                    val x = ((i * 53) % 97) / 97f * size.width
                    val speed = 0.6f + (i % 5) * 0.12f
                    val y = (((fall * speed + i * 0.137f) % 1f) * (size.height + 80f)) - 40f
                    val s = (9 + (i * 7) % 7).dp.toPx()
                    rotate((fall * 540f + i * 40f) % 360f, Offset(x, y)) {
                        drawRoundRect(tiles[i % tiles.size], Offset(x - s / 2, y - s / 2), Size(s, s), CornerRadius(2.dp.toPx()))
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (badges.size > 1) "DISTINTIVO NOVO · ${index + 1} DE ${badges.size}" else "DISTINTIVO NOVO",
                        color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White) }
                }
                Spacer(Modifier.weight(0.6f))
                Box(Modifier.size(320.dp), contentAlignment = Alignment.Center) {
                    // Raios a rodar e o halo
                    Canvas(Modifier.fillMaxSize()) {
                        val c = center
                        val r = size.minDimension / 2
                        rotate(spin, c) {
                            for (k in 0 until 16) {
                                val a0 = (k * 22.5) * PI / 180
                                val a1 = (k * 22.5 + 7) * PI / 180
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(c.x, c.y)
                                    lineTo(c.x + r * cos(a0).toFloat(), c.y + r * sin(a0).toFloat())
                                    lineTo(c.x + r * cos(a1).toFloat(), c.y + r * sin(a1).toFloat())
                                    close()
                                }
                                drawPath(path, color.copy(alpha = 0.13f))
                            }
                        }
                        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * glow), color.copy(alpha = 0f)), c, r * 0.62f), r * 0.62f, c)
                    }
                    BadgeArt(
                        state.def.id, state.def.tier, state.label, locked = false,
                        modifier = Modifier.size(220.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -14f }
                    )
                }
                Spacer(Modifier.height(10.dp))
                TierPill(state.def.tier)
                Spacer(Modifier.height(10.dp))
                Text(state.def.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 34.sp)
                Spacer(Modifier.height(8.dp))
                Text(state.def.how, color = Color(0xFFC9CDD3), fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                holders?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val last = index == badges.lastIndex
                    Button(
                        onClick = { sharing = true },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF101113))
                    ) { Text("Partilhar", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF3A3D42), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.TextButton(
                            onClick = { if (last) { onShowAll(); onDone() } else index++ },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(if (last) "Ver distintivos" else "Seguinte", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
    if (sharing) BadgeShareDialog(state) { sharing = false }
}
