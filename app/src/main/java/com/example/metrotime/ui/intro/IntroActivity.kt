package com.example.metrotime.ui.intro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metrotime.prefs.Prefs
import com.example.metrotime.ui.theme.MetrotimeTheme
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────────────
// Intro das novidades: aparece uma vez ao abrir a versão nova (e no menu › Novidades). Dezasseis páginas a
// deslizar, cada uma com a sua cor e uma ilustração animada (as do Claude Design), o texto e onde encontrar.
// ──────────────────────────────────────────────────────────────────────────────

class IntroActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Vista uma vez chega: mesmo que se saia a meio, não volta a aparecer sozinha
        Prefs.setIntroSeen(this, INTRO_VERSION)
        // Barras do sistema na cor do fundo da intro (o tema pinta-as de roxo)
        window.statusBarColor = android.graphics.Color.rgb(0x11, 0x12, 0x15)
        window.navigationBarColor = android.graphics.Color.rgb(0x11, 0x12, 0x15)
        setContent {
            MetrotimeTheme(darkTheme = true) {
                IntroScreen(onDone = { finish() })
            }
        }
    }

    companion object {
        /** Sobe quando houver novidades novas para mostrar. */
        const val INTRO_VERSION = 2

        fun shouldShow(context: Context): Boolean = Prefs.getIntroSeen(context) < INTRO_VERSION

        fun newIntent(context: Context): Intent = Intent(context, IntroActivity::class.java)
    }
}

internal class IntroPage(
    val accent: Color,
    val tag: String,
    val title: String,
    val body: String,
    val where: String,
    val art: @Composable (Long) -> Unit
)

private fun introPages(): List<IntroPage> = listOf(
    IntroPage(Color(0xFF3B82F6), "Novo Metrotime", "Uma app nova, feita de raiz",
        "Novo visual, nova página inicial e muitas coisas novas para andares de metro, comboio e autocarro. Vê tudo em 1 minuto.",
        "Desliza para o lado ou toca em Seguinte") { ArtWelcome(it) },
    IntroPage(Color(0xFF38BDF8), "Página inicial", "Tudo à mão logo ao abrir",
        "Pesquisa no topo, o que está a acontecer agora no meio e, por baixo, Explorar e os atalhos.",
        "Página inicial") { ArtHome(it) },
    IntroPage(Color(0xFF22C55E), "Metro · Comboios · Autocarros", "Desliza entre os transportes",
        "Metro, Comboios e Autocarros num só ecrã: desliza para o lado para mudar. Os autocarros têm símbolo novo.",
        "Explorar › Metro, Comboios ou Autocarros") { ArtCategories(it) },
    IntroPage(Color(0xFF3B82F6), "Planear viagem", "Todas as maneiras de lá chegar",
        "Escreve o destino e vê as viagens das próximas 5 horas: metro, autocarro, comboio ou tudo junto. Filtra o que queres usar e toca numa hora para a ver.",
        "Página inicial › Para onde vais?") { ArtPlanner(it) },
    IntroPage(Color(0xFFF59E0B), "Carris Metropolitana", "Toda a área metropolitana",
        "Procura qualquer paragem da Carris Metropolitana, de Sintra a Setúbal, e planeia viagens com os autocarros dela, junto com o metro, a Carris e a CP.",
        "Para onde vais? › escreve a paragem ou o concelho") { ArtCm(it) },
    IntroPage(Color(0xFF8B5CF6), "Agora", "A página inicial desliza",
        "Um cartão de cada vez: estado do metro, comboios atrasados, as tuas paragens e estações. Desliza para o lado para mudar.",
        "Página inicial › Agora") { ArtCarousel(it) },
    IntroPage(Color(0xFFA78BFA), "Personalizar", "Escolhe o que vês",
        "Em Editar ligas e desligas cartões e arrastas para mudar a ordem. No cartão dos comboios escolhes os serviços e se ordenas pelo atraso.",
        "Agora › Editar") { ArtEdit(it) },
    IntroPage(Color(0xFF60A5FA), "As minhas estações", "As tuas estações, sempre à vista",
        "Guarda até 5 estações de metro ou comboio e vê as partidas e chegadas de cada uma. Define a casa e vê o último metro e comboio para lá.",
        "Agora › As minhas estações · Para casa") { ArtStations(it) },
    IntroPage(Color(0xFFFFC20E), "Paragens", "Um sininho em cada autocarro",
        "Toca numa paragem no mapa: todos os autocarros das próximas 5 h, por ordem. Liga o sino nos que quiseres e avisamos 3 min antes, mesmo com o ecrã desligado.",
        "Mapa › toca numa paragem") { ArtBells(it) },
    IntroPage(Color(0xFF22D3EE), "Mapa", "Puxa o painel",
        "Agarra a pega: para cima vês mais lista, para baixo vês mais mapa. Puxa até ao fundo para fechar.",
        "Funciona em todos os painéis do mapa") { ArtSheet(it) },
    IntroPage(Color(0xFFFF8A3D), "Avisos", "Avisamos-te antes de sair",
        "Num autocarro, toca na paragem onde sais e avisamos a 500 m. À noite, longe de casa, lembramos o último metro ou comboio.",
        "Mapa › autocarro › paragem · Para casa") { ArtAlerts(it) },
    IntroPage(Color(0xFF4CAF50), "Comboios", "Pela hora a que parte mesmo",
        "Os quadros da CP ordenam-se pela partida real. Atrasado? Vês a hora nova a cores e a prevista riscada, igual em todo o lado.",
        "Estações, Perto de mim e página inicial") { ArtRealTime(it) },
    IntroPage(Color(0xFFF472B6), "Perto de mim", "O que está à tua volta",
        "Estações de metro e comboio e paragens de autocarro aqui perto, com as próximas partidas, sem procurar.",
        "Página inicial › Atalhos › Perto de mim") { ArtNearby(it) },
    IntroPage(Color(0xFF2DD4BF), "Widgets", "Widgets novos no ecrã inicial",
        "Põe no ecrã inicial as próximas partidas de uma estação da CP e os autocarros de uma paragem, sem abrir a app. O do metro continua lá.",
        "Estação CP ou paragem › Mostrar no widget") { ArtWidgets(it) },
    IntroPage(Color(0xFF94A3B8), "Conforto", "Letra grande e menos ruído",
        "Os ecrãs adaptam-se ao tamanho de letra do telemóvel, sem texto cortado. A notificação permanente é agora discreta e podes escondê-la.",
        "Menu › Definições › Notificação") { ArtComfort(it) },
    IntroPage(Color(0xFF3B82F6), "Pronto", "Está tudo pronto!",
        "Podes voltar a ver esta intro no menu, em Novidades. Boa viagem.",
        "Menu › Novidades") { ArtDone(it) }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IntroScreen(onDone: () -> Unit) {
    val pages = remember { introPages() }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val current = pager.currentPage
    val last = pages.lastIndex
    val accent by animateColorAsState(pages[current].accent, tween(450), label = "accent")
    val go: (Int) -> Unit = { i -> scope.launch { pager.animateScrollToPage(i.coerceIn(0, last)) } }

    BackHandler { if (current > 0) go(current - 1) else onDone() }

    BoxWithConstraints(Modifier.fillMaxSize().background(INTRO_BG)) {
        // O palco das ilustrações (390 × 404 no design) cresce ou encolhe com o ecrã, até metade da altura
        val scale = minOf(maxWidth / 390.dp, (maxHeight * 0.5f) / 404.dp).coerceAtMost(1.2f)
        Glow(accent)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pages.indices.forEach { k ->
                        val c by animateColorAsState(
                            when {
                                k < current -> Color.White
                                k == current -> pages[current].accent
                                else -> Color.White.copy(alpha = 0.18f)
                            },
                            tween(300), label = "segment"
                        )
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c))
                    }
                }
                Box(
                    Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = current < last) { go(last) }.padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(if (current < last) "Saltar" else "", color = MUTED, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(404.dp * scale).padding(top = 4.dp)) { index ->
                val t by rememberPageClock(pager.settledPage == index)
                Stage(scale) { pages[index].art(t) }
            }

            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(320, 120)) + slideInHorizontally(tween(380)) { dir * it / 8 }) togetherWith fadeOut(tween(140))
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = "text"
            ) { i ->
                PageText(pages[i])
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(56.dp).alpha(if (current > 0) 1f else 0f).clip(CircleShape).background(Color(0xFF1A1B1F)).border(1.dp, LINE, CircleShape)
                        .clickable(enabled = current > 0) { go(current - 1) },
                    contentAlignment = Alignment.Center
                ) {
                    if (current > 0) Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Anterior", tint = Color.White)
                }
                val dark = accent.luminance() > 0.45f
                Box(
                    Modifier.weight(1f).heightIn(min = 56.dp)
                        .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = accent, spotColor = accent)
                        .clip(RoundedCornerShape(28.dp)).background(accent)
                        .clickable { if (current == last) onDone() else go(current + 1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (current) { 0 -> "Ver novidades"; last -> "Começar"; else -> "Seguinte" },
                        color = if (dark) INTRO_BG else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** O palco: a ilustração desenhada a 390 × 404, escalada inteira (e sem crescer com a letra do sistema). */
@Composable
private fun Stage(scale: Float, content: @Composable BoxScope.() -> Unit) {
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, fontScale = 1f)) {
            Box(Modifier.size(390.dp, 404.dp), content = content)
        }
    }
}

/** Mancha de luz na cor da página, a mexer devagar atrás da ilustração. */
@Composable
private fun Glow(accent: Color) {
    val drift = rememberInfiniteTransition(label = "glow")
    val x by drift.animateFloat(-14f, 14f, infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    Box(
        Modifier.fillMaxWidth().height(460.dp).drawBehind {
            val c = Offset(size.width / 2 + x.dp.toPx(), size.height * 0.42f - x.dp.toPx() * 0.6f)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.32f), Color.Transparent), c, size.width * 0.62f), size.width * 0.62f, c)
        }
    )
}

@Composable
private fun PageText(page: IntroPage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(page.accent.copy(alpha = 0.22f)).padding(horizontal = 11.dp, vertical = 5.dp)) {
            Text(page.tag, color = lerpColor(page.accent, Color.White, 0.45f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(page.title, color = Color.White, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
        Text(page.body, color = Color(0xFFC9CCD2), fontSize = 15.sp, lineHeight = 22.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFF8E929A), modifier = Modifier.size(14.dp))
            Text(page.where, color = Color(0xFF8E929A), fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}
