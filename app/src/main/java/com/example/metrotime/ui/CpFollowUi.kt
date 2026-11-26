package com.example.metrotime.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.cp.CpTrainStop
import com.example.metrotime.data.cp.formatStationName
import com.example.metrotime.ui.theme.CardBackground
import com.example.metrotime.ui.theme.CpGreen
import com.example.metrotime.work.CpFollow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Escolher onde vais sair e começar a seguir o comboio. [fromIndex] = primeira paragem que ainda
 * faz sentido escolher (a seguir à posição atual); [preselectCode] = estação sugerida.
 */
@Composable
fun CpFollowDialog(
    trainNumber: Int,
    runDate: String,
    service: String,
    destination: String?,
    stops: List<CpTrainStop>,
    fromIndex: Int,
    boardCode: String?,
    preselectCode: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val choices = stops.withIndex().drop(fromIndex.coerceIn(0, maxOf(0, stops.size - 1))).filter { it.value.station?.code != null }
    var selected by remember {
        mutableStateOf(choices.find { it.value.station?.code == preselectCode }?.value?.station?.code ?: choices.lastOrNull()?.value?.station?.code)
    }
    val alreadyFollowing = remember { CpFollow.isFollowing(context, trainNumber) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        title = { Text("Seguir comboio $trainNumber", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Avisamos quando o atraso mudar, quando souber a linha (ou ela mudar), se for suprimido e 3 minutos antes de chegares.",
                    color = Color.LightGray, fontSize = 13.sp
                )
                Text(
                    "Onde vais sair?",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(choices, key = { it.index }) { (_, stop) ->
                        val code = stop.station?.code
                        FollowStopRow(
                            name = formatStationName(stop.station?.designation ?: "?"),
                            scheduled = stop.arrival ?: stop.departure,
                            expected = expectedTime(stop),
                            delayMin = stop.delay?.takeIf { it > 0 },
                            selected = selected == code,
                            onClick = { selected = code }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val stop = choices.find { it.value.station?.code == selected }?.value
                    CpFollow.start(context, CpFollow.Followed(
                        trainNumber = trainNumber,
                        runDate = runDate,
                        service = service,
                        destination = destination,
                        boardCode = boardCode,
                        alightCode = stop?.station?.code,
                        alightName = stop?.station?.designation?.let { formatStationName(it) }
                    ))
                    Toast.makeText(context, "A seguir o comboio $trainNumber. Vês o estado nas notificações.", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CpGreen)
            ) {
                Text("Seguir", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (alreadyFollowing) {
                    TextButton(onClick = {
                        CpFollow.stop(context)
                        Toast.makeText(context, "Deixaste de seguir o comboio $trainNumber.", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) { Text("Deixar de seguir", color = Color(0xFFFF8A7D)) }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.Gray) }
            }
        }
    )
}

/**
 * Hora a que o comboio deve mesmo chegar, como na página do comboio: ETA (ou ETD) quando há atraso;
 * sem ETA, hora prevista + atraso. null = sem atraso (vale a hora prevista).
 */
private fun expectedTime(stop: CpTrainStop): String? {
    val delay = stop.delay?.takeIf { it > 0 } ?: return null
    val real = if (stop.arrival != null) stop.eta ?: stop.etd else stop.etd ?: stop.eta
    if (!real.isNullOrBlank()) return real.take(5)
    val scheduled = stop.arrival ?: stop.departure ?: return null
    return runCatching { LocalTime.parse(scheduled.take(5)).plusMinutes(delay.toLong()).format(HM) }.getOrNull()
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Uma paragem a escolher: cartão como os do resto da app, verde CP quando escolhida. */
@Composable
private fun FollowStopRow(
    name: String,
    scheduled: String?,
    expected: String?,
    delayMin: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) CpGreen.copy(alpha = 0.18f) else CardBackground)
            .border(1.dp, if (selected) CpGreen else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(2.dp, if (selected) CpGreen else Color.Gray, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(CpGreen))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (expected != null && expected != scheduled?.take(5)) {
            // Com atraso: prevista riscada e a hora real na cor do atraso
            scheduled?.let {
                Text(it.take(5), color = Color.Gray, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                expected,
                color = if ((delayMin ?: 0) <= 5) Color(0xFFFFC107) else Color(0xFFFF5252),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(scheduled?.take(5).orEmpty(), color = if (selected) Color.White else Color.Gray, fontSize = 13.sp)
        }
    }
}
