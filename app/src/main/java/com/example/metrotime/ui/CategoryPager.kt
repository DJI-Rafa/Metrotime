package com.example.metrotime.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.R
import com.example.metrotime.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/** Os três ecrãs (nome usado no resto da app, separador, logótipo). */
private val CATEGORIES = listOf(
    Triple("Metro", "Metro", R.drawable.metro_logo),
    Triple("Comboio", "Comboios", R.drawable.cp_logo),
    Triple("Autocarro", "Autocarros", R.drawable.bus_symbol)
)

/**
 * Metro, Comboios e Autocarros lado a lado: passa-se de um para o outro a deslizar ou tocando no separador.
 * O sublinhado acompanha o dedo; [onPage] avisa qual está à vista (para o título e os avisos da CP).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CategoryPager(activity: ComponentActivity, initial: String, onPage: (String) -> Unit) {
    val start = CATEGORIES.indexOfFirst { it.first == initial }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start, pageCount = { CATEGORIES.size })
    val scope = rememberCoroutineScope()
    val currentOnPage by rememberUpdatedState(onPage)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { currentOnPage(CATEGORIES[it].first) }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val tabWidth = maxWidth / CATEGORIES.size
            val position = pager.currentPage + pager.currentPageOffsetFraction
            Row(Modifier.fillMaxWidth()) {
                CATEGORIES.forEachIndexed { i, (_, label, logo) ->
                    val selected = (position - i).absoluteValue < 0.5f
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painterResource(logo), contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.size(18.dp).alpha(if (selected) 1f else 0.5f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = if (selected) Color.White else Color(0xFF9E9E9E), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(DIVIDER))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset((position * tabWidth.toPx()).roundToInt(), 0) }
                    .width(tabWidth)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MetrotimeBlue)
            )
        }
        HorizontalPager(
            state = pager,
            key = { CATEGORIES[it].first },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Column(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> MetroTabContent(activity)
                    1 -> ComboioTabContent(activity)
                    else -> BusTabContent(activity)
                }
            }
        }
    }
}
