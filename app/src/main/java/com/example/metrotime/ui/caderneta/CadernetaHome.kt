package com.example.metrotime.ui.caderneta

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.metrotime.data.caderneta.BadgeCloud
import com.example.metrotime.data.caderneta.BadgeState
import com.example.metrotime.data.caderneta.Caderneta
import com.example.metrotime.data.caderneta.CadernetaRepository
import com.example.metrotime.data.caderneta.Stamps
import com.example.metrotime.ui.theme.CardBackground

/**
 * O cartão da caderneta na página inicial: quantas estações e distintivos tens, com o último distintivo ganho. Ao
 * voltar à app depois de uma viagem que deu um distintivo novo, mostra o "Distintivo novo".
 */
@Composable
fun CadernetaHomeCard(activity: ComponentActivity) {
    var data by remember { mutableStateOf<Caderneta?>(null) }
    var unlocked by remember { mutableStateOf<List<BadgeState>>(emptyList()) }
    LaunchedEffect(Unit) {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val c = runCatching { CadernetaRepository.load(activity) }.getOrNull() ?: return@repeatOnLifecycle
            data = c
            unlocked = CadernetaRepository.unseen(activity, c)
            // Os distintivos sobem para o Firebase (e voltam de lá, se este telemóvel não os tiver)
            if (BadgeCloud.sync(activity, c)) data = runCatching { CadernetaRepository.load(activity) }.getOrNull() ?: c
        }
    }
    val c = data
    // Só as estações da caderneta (os carimbos de estações que já não existem não contam)
    val stations = c?.let { it.book.count(it.metroIds.map(Stamps::metro) + it.cpStations.map { s -> Stamps.cp(s.code) }) } ?: 0
    val total = c?.let { it.metroIds.size + it.cpStations.size } ?: 506
    val best = c?.badges?.filter { it.earned }?.maxByOrNull { it.def.tier.ordinal }
    Card(
        onClick = { activity.startActivity(CadernetaActivity.newIntent(activity)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (best != null) {
                BadgeArt(best.def.id, best.def.tier, best.label, locked = false, modifier = Modifier.size(48.dp), animate = false)
            } else {
                BadgeArt("metroBronze", com.example.metrotime.data.caderneta.Tier.COMUM, "", locked = true, modifier = Modifier.size(48.dp), animate = false)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Caderneta", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "$stations de $total estações · ${c?.earnedCount ?: 0} de ${c?.badges?.size ?: 57} distintivos",
                    color = Color(0xFFB7BBC3), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFB7BBC3))
        }
    }
    if (unlocked.isNotEmpty()) {
        BadgeUnlockDialog(unlocked, onShowAll = { activity.startActivity(CadernetaActivity.newIntent(activity, tab = 2)) }) {
            CadernetaRepository.markSeen(activity, unlocked.map { it.def.id })
            unlocked = emptyList()
        }
    }
}
