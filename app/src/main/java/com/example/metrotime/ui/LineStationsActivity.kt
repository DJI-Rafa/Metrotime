package com.example.metrotime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.data.MetroLines
import com.example.metrotime.data.Station
import com.example.metrotime.data.StationsRepo
import com.example.metrotime.ui.theme.*

class LineStationsActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_LINE_NAME = "line_name"
        fun newIntent(context: Context, lineName: String): Intent {
            return Intent(context, LineStationsActivity::class.java).apply {
                putExtra(EXTRA_LINE_NAME, lineName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lineName = intent.getStringExtra(EXTRA_LINE_NAME) ?: run {
            finish()
            return
        }

        setContent {
            MetrotimeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    LineStationsScreen(this@LineStationsActivity, lineName)
                }
            }
        }
    }
}

/** Estações com ligação a barcos (as mesmas que mostram partidas de barcos no ecrã da estação). */
private val BOAT_STATIONS = setOf("cais_do_sodre", "terreiro_do_paco")
private val BOAT_COLOR = Color(0xFFF39200)

/**
 * Uma linha desenhada de cima para baixo, como o percurso dos comboios CP: estações por ordem, ligações
 * às outras linhas e aos barcos, e o troço cortado a tracejado laranja quando há problemas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineStationsScreen(activity: ComponentActivity, lineName: String) {
    val byApiId = remember { StationsRepo.loadStations(activity).associateBy { it.apiId } }
    val stations = remember(lineName) { MetroLines.stops(lineName).mapNotNull { byApiId[it] } }
    val lineColor = lineColorOf(lineName)
    val state = rememberMetroLines(activity)
    val problem = (state as? MetroNow.Lines)?.statuses?.firstOrNull { it.line == lineName && !it.ok }
    val cut = problem?.let { MetroLines.affectedRange(lineName, it.message) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Linha $lineName", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        listOfNotNull(MetroLines.terminals(lineName), "${stations.size} estações").joinToString(" · "),
                        color = TextSecondaryDark,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
        ) {
            item(key = "status") {
                LineStatusCard(state, problem?.message, hasCut = cut != null)
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(stations, key = { _, st -> st.id }, contentType = { _, _ -> "stop" }) { i, st ->
                LineStopRow(
                    station = st,
                    lineName = lineName,
                    lineColor = lineColor,
                    first = i == 0,
                    last = i == stations.lastIndex,
                    affected = cut != null && i in cut,
                    affectedAbove = cut != null && i in cut && i - 1 in cut,
                    affectedBelow = cut != null && i in cut && i + 1 in cut
                ) {
                    activity.startActivity(StationArrivalsActivity.newIntent(activity, st.id))
                }
            }
        }
    }
}

@Composable
private fun LineStatusCard(state: MetroNow, message: String?, hasCut: Boolean) {
    when {
        message != null -> Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E22)),
            border = BorderStroke(1.dp, ORANGE.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = ORANGE, modifier = Modifier.size(18.dp))
                    Text("Circulação com problemas", color = ORANGE, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Text(message, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                if (hasCut) Text("O troço afetado está a tracejado.", color = TextSecondaryDark, fontSize = 12.sp)
            }
        }
        state is MetroNow.Lines || state is MetroNow.Closed -> Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            val closed = state is MetroNow.Closed
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (closed) TextSecondaryDark else GREEN))
                Text(
                    if (closed) "Metro fechado · abre às 6:30" else "Circulação normal",
                    color = if (closed) TextSecondaryDark else GREEN,
                    fontSize = 13.sp
                )
            }
        }
        else -> Unit
    }
}

/** Uma estação: a linha e o ponto desenhados à esquerda (sem medir a linha duas vezes), nome e ligações. */
@Composable
private fun LineStopRow(
    station: Station,
    lineName: String,
    lineColor: Color,
    first: Boolean,
    last: Boolean,
    affected: Boolean,
    affectedAbove: Boolean,
    affectedBelow: Boolean,
    onClick: () -> Unit
) {
    val otherLines = MetroLines.linesOf(station.apiId).filter { it != lineName }
    val links = otherLines.map { it to lineColorOf(it) } +
        (if (station.id in BOAT_STATIONS) listOf("Barcos" to BOAT_COLOR) else emptyList())
    val important = first || last || links.isNotEmpty()
    val dotColor = when {
        affected -> ORANGE
        links.isNotEmpty() -> Color.White
        else -> lineColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .drawBehind {
                val x = 18.dp.toPx()
                val cy = size.height / 2
                val width = 4.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                if (!first) drawLine(
                    if (affectedAbove) ORANGE else lineColor, Offset(x, 0f), Offset(x, cy), width,
                    pathEffect = if (affectedAbove) dash else null
                )
                if (!last) drawLine(
                    if (affectedBelow) ORANGE else lineColor, Offset(x, cy), Offset(x, size.height), width,
                    pathEffect = if (affectedBelow) dash else null
                )
                val r = (if (important) 8.dp else 6.dp).toPx()
                drawCircle(DarkBackground, r, Offset(x, cy))
                drawCircle(dotColor, r, Offset(x, cy), style = Stroke(3.dp.toPx()))
            }
            .padding(start = 46.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            station.name,
            color = if (affected) TextSecondaryDark else Color.White,
            fontSize = 15.sp,
            fontWeight = if (important) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp)
        )
        links.forEach { (label, color) ->
            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(CardBackground)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(label, color = TextSecondaryDark, fontSize = 11.sp)
            }
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFF6B7078), modifier = Modifier.size(14.dp))
    }
}
