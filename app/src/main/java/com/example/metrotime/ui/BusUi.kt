package com.example.metrotime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.bus.BusColors
import com.example.metrotime.data.bus.BusMapRepository
import com.example.metrotime.data.bus.BusTiming
import com.example.metrotime.ui.theme.LiveIcon
import com.example.metrotime.ui.theme.TextSecondaryDark

/** "#2FB61E" → Color; cinzento das circulares se a cor não for válida. */
internal fun busColor(hex: String?): Color =
    Color(android.graphics.Color.parseColor(BusColors.normalize(hex) ?: BusColors.FALLBACK))

/** Texto centrado ao meio da caixa (sem a margem de cima da fonte, que o puxava para baixo). */
private fun centeredText(size: TextUnit) = TextStyle(
    fontSize = size,
    lineHeight = size,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
)

/** Número da linha numa pílula com a cor oficial, como no site da Carris (número a branco). */
@Composable
internal fun BusLineBadge(number: String, colorHex: String?, height: Dp = 28.dp, fontSize: TextUnit = 15.sp) {
    val hex = BusColors.normalize(colorHex) ?: BusColors.FALLBACK
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = height * 1.7f)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(busColor(hex))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            number,
            color = busColor(BusColors.textOn(hex)),
            fontWeight = FontWeight.ExtraBold,
            style = centeredText(fontSize)
        )
    }
}

/** Sinal de paragem: um "P" a negrito num quadrado arredondado (a mesma ideia do sinal no mapa). */
@Composable
internal fun BusStopSign(size: Dp = 36.dp) {
    // Tamanho do "P" preso ao quadrado (não cresce com a letra do sistema e sai da caixa)
    val letter = with(LocalDensity.current) { (size * 0.5f).toSp() }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color(0xFF34363A)),
        contentAlignment = Alignment.Center
    ) {
        Text("P", color = Color.White, fontWeight = FontWeight.ExtraBold, style = centeredText(letter))
    }
}

/** Cor do estado: verde em tempo real (a horas ou adiantado), laranja ou vermelho com atraso, cinzento se previsto. */
internal fun busStatusColor(kind: BusTiming.Kind): Color = when (kind) {
    BusTiming.Kind.ON_TIME, BusTiming.Kind.EARLY, BusTiming.Kind.LIVE -> Color(0xFF6FCF73)
    BusTiming.Kind.LATE -> Color(0xFFFFB74D)
    BusTiming.Kind.VERY_LATE -> Color(0xFFFF5252)
    BusTiming.Kind.SCHEDULED -> TextSecondaryDark
}

/**
 * O tempo de um autocarro, como no Google Maps: em tempo real os minutos na cor do estado com o sinal
 * de GPS; previsto, pelo horário a branco.
 */
@Composable
internal fun BusEta(a: BusMapRepository.StopArrivalInfo, fontSize: TextUnit = 20.sp, showIcon: Boolean = false) {
    val status = a.status
    val color = if (status.realtime) busStatusColor(status.kind) else Color.White
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (showIcon && status.realtime) {
            Icon(LiveIcon, contentDescription = "Tempo real", tint = color, modifier = Modifier.size(with(LocalDensity.current) { (fontSize * 0.7f).toDp() }))
        }
        Text(a.etaText, color = color, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Por baixo do tempo: o sinal de GPS e "a horas" / "atrasado 4 min" / "adiantado 2 min"; ou "previsto". */
@Composable
internal fun BusStatusLine(a: BusMapRepository.StopArrivalInfo) {
    val status = a.status
    val color = busStatusColor(status.kind)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status.realtime) Icon(LiveIcon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        Text(status.text, color = color, fontSize = 12.sp, fontWeight = if (status.realtime) FontWeight.Medium else FontWeight.Normal)
    }
}
