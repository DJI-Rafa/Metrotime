package com.example.metrotime.ui.caderneta

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

// Os desenhos dos 57 distintivos, iguais aos do Claude Design (caderneta, componente "Distintivo"): cada um é uma lista
// de formas num quadrado de 48 (o medalhão), com traço de 2,6 na cor da raridade, a não ser que digam outra coisa.

internal sealed class Ink {
    /** A cor da raridade (azul nos comuns, branco nos raros, dourado nos lendários…). */
    object Main : Ink()
    /** A cor do medalhão (para "furar" um desenho, como as manchas da girafa). */
    object Medal : Ink()
    class Fixed(val color: Color) : Ink()
}

internal sealed class Op
internal class PathOp(
    val d: String, val fill: Ink? = null, val stroke: Ink? = Ink.Main, val width: Float = 2.6f,
    val alpha: Float = 1f, val dash: FloatArray? = null, val butt: Boolean = false
) : Op() {
    val path: Path by lazy { PathParser().parsePathString(d).toPath() }
}
internal class CircleOp(
    val cx: Float, val cy: Float, val r: Float, val fill: Ink? = null, val stroke: Ink? = Ink.Main,
    val width: Float = 2.6f, val alpha: Float = 1f, val dash: FloatArray? = null
) : Op()
internal class RectOp(
    val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float, val fill: Ink? = null, val stroke: Ink? = Ink.Main,
    val width: Float = 2.6f, val alpha: Float = 1f
) : Op()
internal class EllipseOp(
    val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rotation: Float, val width: Float = 2.6f,
    val alpha: Float = 1f, val dash: FloatArray? = null
) : Op()
/** Texto centrado em [x]; "{m}" é o número do marco (10, 25, 100…). */
internal class TextOp(val x: Float, val y: Float, val text: String, val size: Float, val weight: Int = 900, val spacing: Float = 0f) : Op()
internal class GroupOp(val ops: List<Op>, val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val alpha: Float = 1f) : Op()
/** As manchas da região, no sítio da região escolhida. */
internal object RegionOp : Op()

private val AZUL = Color(0xFF0077C8)
private val VERDE = Color(0xFF009E60)
private val VERMELHA = Color(0xFFED1C24)
private val AMARELA = Color(0xFFFFD500)
private val AMARELA_ESCURA = Color(0xFFC9A400)
private val BRANCO = Color(0xFFFFFFFF)
private val CREME = Color(0xFFF3EFE6)
private val LAMPADA = Color(0xFFFFD54A)

private fun Color?.ink(): Ink = this?.let { Ink.Fixed(it) } ?: Ink.Main
private fun s(d: String, w: Float = 2.6f, a: Float = 1f, c: Color? = null, dash: FloatArray? = null, butt: Boolean = false) =
    PathOp(d, null, c.ink(), w, a, dash, butt)
private fun f(d: String, c: Color? = null, a: Float = 1f) = PathOp(d, c.ink(), null, alpha = a)
private fun cs(cx: Float, cy: Float, r: Float, w: Float = 2.6f, c: Color? = null, a: Float = 1f, dash: FloatArray? = null) =
    CircleOp(cx, cy, r, null, c.ink(), w, a, dash)
private fun cf(cx: Float, cy: Float, r: Float, c: Color? = null) = CircleOp(cx, cy, r, c.ink(), null)
private fun cm(cx: Float, cy: Float, r: Float) = CircleOp(cx, cy, r, Ink.Medal, null)
private fun rs(x: Float, y: Float, w: Float, h: Float, rx: Float, sw: Float = 2.6f) = RectOp(x, y, w, h, rx, null, Ink.Main, sw)
private fun rf(x: Float, y: Float, w: Float, h: Float, rx: Float, c: Color? = null) = RectOp(x, y, w, h, rx, c.ink(), null)
private fun t(x: Float, y: Float, text: String, size: Float, weight: Int = 900, spacing: Float = 0f) = TextOp(x, y, text, size, weight, spacing)
private fun g(vararg ops: Op, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f, a: Float = 1f) = GroupOp(ops.toList(), dx, dy, scale, a)

/** As quatro estações (a linha completa) num traço da cor da linha. */
private fun lineTrack(color: Color, dots: Boolean = true): List<Op> = buildList {
    add(s("M5,35 H43", w = 5f, c = color))
    listOf(8f, 18.7f, 29.3f, 40f).forEach { x -> add(cf(x, 35f, 3.8f, color)) }
    if (dots) listOf(8f, 18.7f, 29.3f, 40f).forEach { x -> add(cf(x, 35f, 1.4f, BRANCO)) }
}

/** As dez pétalas do girassol (Linha Amarela), à volta de (19,5; 12,5). */
private const val PETALS = "M18.43,9.27 Q17.55,6.19 19.50,3.90 Q21.45,6.19 20.57,9.27 Z M20.53,9.26 Q21.63,6.25 24.55,5.54 Q24.78,8.55 22.26,10.52 Z " +
    "M22.24,10.49 Q24.89,8.70 27.68,9.84 Q26.10,12.41 22.90,12.52 Z M22.90,12.48 Q26.10,12.59 27.68,15.16 Q24.89,16.30 22.24,14.51 Z " +
    "M22.26,14.48 Q24.78,16.45 24.55,19.46 Q21.63,18.75 20.53,15.74 Z M20.57,15.73 Q21.45,18.81 19.50,21.10 Q17.55,18.81 18.43,15.73 Z " +
    "M18.47,15.74 Q17.37,18.75 14.45,19.46 Q14.22,16.45 16.74,14.48 Z M16.76,14.51 Q14.11,16.30 11.32,15.16 Q12.90,12.59 16.10,12.48 Z " +
    "M16.10,12.52 Q12.90,12.41 11.32,9.84 Q14.11,8.70 16.76,10.49 Z M16.74,10.52 Q14.22,8.55 14.45,5.54 Q17.37,6.25 18.47,9.26 Z"

private const val CRESCENT ="M24.92,4.06 A10,10 0 1 0 35.47,17.23 A8.5,8.5 0 0 1 24.92,4.06 Z"
private const val OUTLINE = "M17.4,4.4 L18.8,3 L21.3,2 L23,4.5 L26.9,3.7 L31.2,3.4 L32.5,3.7 L34.8,6.7 L34.3,7.6 L30.9,10.9 L30,11.5 L30.7,15 L29.9,18.1 L29.7,21.6 L26.3,23 L27.1,25.3 L29.6,28.6 L28.5,29.7 L27.4,33.9 L29.7,36 L27.9,37.5 L26.2,40.9 L26.8,44 L23.5,45.6 L19.6,44.6 L16.6,45.4 L17.9,41.3 L17.3,37.5 L17.1,33 L15.1,33.6 L15.2,31.9 L13.2,30.5 L13.9,25.6 L16,23.6 L17.3,18.9 L18.1,14.8 L18.6,10.5 L17.5,5.9 Z"

internal object BadgeIcons {

    /** As peças (algumas partilhadas por vários distintivos). */
    val PARTS: Map<String, List<Op>> = mapOf(
        // Os símbolos das linhas do Metro: a gaivota (Azul), o girassol (Amarela), a caravela (Verde) e a bússola (Vermelha)
        "linhaAzul" to listOf(
            // Gaivota de asas abertas, com a cabeça para a direita, por cima das ondas
            f("M7,11.5 C11,7 16.5,7.2 21.5,12.5 C25.5,8.5 31.5,6 39.5,7.5 C34.5,8.6 30,11 26.8,14.6 C25.2,16.4 22.5,16.6 20.8,15 C17.5,11.6 12.5,10 7,11.5 Z", AZUL),
            f("M26.4,14.8 C28.4,14.2 30.6,14.6 32.4,15.9 C30.4,16.4 28.3,16.4 26.4,15.6 Z", AZUL),
            s("M13,23.5 q2.75,-3.2 5.5,0 t5.5,0 t5.5,0 t5.5,0", w = 2.2f, c = AZUL)
        ) + lineTrack(AZUL),
        "linhaAmarela" to listOf(
            // Girassol: o pé com uma folha e uma gavinha enrolada, as dez pétalas e o miolo
            s("M20.6,20.5 C22.6,23 23.8,25.6 24.2,28.6", w = 2f, c = AMARELA_ESCURA),
            f("M23.1,24.2 C24,19.8 28.6,17.2 34,17.8 C33,22 28.4,24.8 23.1,24.2 Z", AMARELA_ESCURA),
            s("M22.4,26.2 C18.8,27.6 15.6,26 15.9,23.4 C16.2,21.6 18.6,21.4 19,23.1", w = 1.6f, c = AMARELA_ESCURA),
            PathOp(PETALS, Ink.Fixed(AMARELA), Ink.Fixed(AMARELA_ESCURA), 0.9f),
            cf(19.5f, 12.5f, 3.6f, Color(0xFFA88700)),
            s("M5,35 H43", w = 6.6f, c = AMARELA_ESCURA), s("M5,35 H43", w = 4.2f, c = AMARELA)
        ) + listOf(8f, 18.7f, 29.3f, 40f).map { x -> CircleOp(x, 35f, 3.8f, Ink.Fixed(AMARELA), Ink.Fixed(Color(0xFFA88700)), 1.6f) },
        "linhaVerde" to listOf(
            // Caravela: dois mastros com velas latinas (a grande com a cruz), casco curvo com a popa alta
            s("M25,20.5 V3.6 M15.5,20.5 V8.6", w = 1.6f, c = VERDE),
            f("M25.6,3.8 C31.6,7.4 35.4,13 36.6,19.6 L25.6,19.6 Z", VERDE),
            f("M16.1,8.8 C19.9,11.2 22.6,15 23.4,19.6 L16.1,19.6 Z", VERDE, a = 0.8f),
            s("M30.2,10.4 V16.4 M27.2,13.4 H33.2", w = 1.8f, c = CREME),
            f("M7.2,14.6 C9.2,14.2 10.6,15.6 11,17.6 L11.4,20.4 L39.6,20.4 C38.6,25 33.6,27.8 24.4,27.8 C15.6,27.8 9.6,23.6 7.2,14.6 Z", VERDE),
            s("M14.5,24.2 H34", w = 1.2f, c = CREME, a = 0.85f)
        ) + lineTrack(VERDE),
        "linhaVermelha" to listOf(
            // Bússola: a rosa dos ventos dentro do mostrador, com a agulha a atravessá-lo
            cs(23f, 15f, 9.5f, w = 2.4f, c = VERMELHA),
            f("M23,7.4 L24.9,13.1 L30.6,15 L24.9,16.9 L23,22.6 L21.1,16.9 L15.4,15 L21.1,13.1 Z", VERMELHA),
            s("M13.5,24.5 L36.5,5.5", w = 1.8f, c = VERMELHA),
            f("M38.4,4 L36.6,9.2 L33.4,6.6 Z", VERMELHA),
            cf(23f, 15f, 1.5f, CREME)
        ) + lineTrack(VERMELHA),
        "metroFront" to listOf(
            rs(15f, 3f, 18f, 20f, 6f), rf(18f, 7f, 12f, 6.5f, 2f), cf(19.5f, 18.2f, 1.4f), cf(28.5f, 18.2f, 1.4f),
            s("M18.5,23 L16,26.5 M29.5,23 L32,26.5", w = 2f), t(24f, 45f, "{m}", 16f)
        ),
        "cpFront" to listOf(
            s("M20.5,4.5 L24,1.2 L27.5,4.5", w = 1.8f), s("M14,24 V11 C14,7.7 16.7,5 20,5 H28 C31.3,5 34,7.7 34,11 V24 Z"),
            f("M17,10 H31 V15.5 H17 Z"), cf(18.6f, 20f, 1.4f), cf(29.4f, 20f, 1.4f), t(24f, 45f, "{m}", 16f)
        ),
        "doisMundos" to listOf(
            rs(4f, 5f, 15f, 17f, 5f), rf(6.5f, 8.5f, 10f, 5f, 1.5f),
            s("M29,22 V11.5 C29,9 31,7 33.5,7 H40.5 C43,7 45,9 45,11.5 V22 Z"), rf(31.5f, 10.5f, 11f, 4.5f, 1f),
            s("M34.5,6.5 L37,4 L39.5,6.5", w = 1.6f),
            s("M9,31 C14,39.5 34,39.5 39,31"), s("M34.6,33.4 L39,31 L38.8,36"), s("M13.4,33.4 L9,31 L9.2,36")
        ),
        "noctivago" to listOf(
            f(CRESCENT),
            f("M9,5.8 L10.12,7.88 L12.2,9 L10.12,10.12 L9,12.2 L7.88,10.12 L5.8,9 L7.88,7.88 Z"),
            f("M13,16.8 L13.77,18.23 L15.2,19 L13.77,19.77 L13,21.2 L12.23,19.77 L10.8,19 L12.23,18.23 Z"),
            f("M41,4.6 L41.84,6.16 L43.4,7 L41.84,7.84 L41,9.4 L40.16,7.84 L38.6,7 L40.16,6.16 Z"),
            rf(8f, 33.5f, 5f, 3f, 0.8f), rf(16f, 33.5f, 5f, 3f, 0.8f), rf(24f, 33.5f, 5f, 3f, 0.8f),
            cf(11f, 42f, 1.5f), cf(33f, 42f, 1.5f),
            s("M5,31 H35 C40,31 43,34.5 43,38 V40 H5 Z", w = 2.2f), s("M2,44.5 H46", w = 1.8f)
        ),
        "madrugador" to listOf(
            f("M15,30 A9,9 0 0 1 33,30 Z"),
            s("M12.74,23.5 L9.28,21.5 M17.5,18.74 L15.5,15.28 M24,17 V13 M30.5,18.74 L32.5,15.28 M35.26,23.5 L38.72,21.5", w = 2.2f),
            s("M4,30 H44"), t(24f, 43.5f, "06:30", 9.5f, 800)
        ),
        "pontual" to listOf(
            cs(24f, 22f, 15f), s("M24,9.5 V12 M36.5,22 H34 M24,34.5 V32 M11.5,22 H14", w = 2f),
            s("M17,22.5 L22,27.5 L31.5,16.5", w = 3.2f), t(24f, 46f, "×10", 9.5f, 800)
        ),
        "relampago" to listOf(
            f("M28,2 L12,26 H22.5 L18,46 L36,19 H25.5 L31,2 Z"), s("M3,16 H10 M1.5,24 H8.5 M4,32 H11", w = 2.2f)
        ),
        "fimDaLinha" to listOf(
            s("M2,38 H33", w = 2.2f), s("M6,38 V41.5 M12,38 V41.5 M18,38 V41.5 M24,38 V41.5 M30,38 V41.5", w = 1.8f),
            s("M38,41 V20"), rf(33.5f, 22f, 9f, 7f, 1.5f), s("M38,41 H45 L38,31", w = 2f), s("M38,20 V6"),
            f("M38,6 L46,9 L38,12 Z"), s("M2,44.5 H46", w = 1.6f, a = 0.7f)
        ),
        "noCentral" to listOf(
            s("M24,17.5 V6 M29.63,20.75 L39.59,15 M29.63,27.25 L39.59,33 M24,30.5 V42 M18.37,27.25 L8.41,33 M18.37,20.75 L8.41,15"),
            cs(24f, 24f, 6.5f), cf(24f, 24f, 2.2f), cf(24f, 6f, 2.8f), cf(39.59f, 15f, 2.8f), cf(39.59f, 33f, 2.8f),
            cf(24f, 42f, 2.8f), cf(8.41f, 33f, 2.8f), cf(8.41f, 15f, 2.8f)
        ),
        "ringLines" to listOf(
            s("M26.6,9.23 A15,15 0 0 1 38.77,21.4", w = 4.2f, c = Color(0xFF6FB6FF)),
            s("M38.77,26.6 A15,15 0 0 1 26.6,38.77", w = 4.2f, c = AMARELA),
            s("M21.4,38.77 A15,15 0 0 1 9.23,26.6", w = 4.2f, c = Color(0xFF3AD68C)),
            s("M9.23,21.4 A15,15 0 0 1 21.4,9.23", w = 4.2f, c = Color(0xFFFF6B70))
        ),
        "voltaLisboa" to listOf(s("M17.17,7.69 L21.4,9.23 L17.95,12.12", c = Color(0xFFFF6B70)), t(24f, 28.7f, "4", 13f)),
        "metroNumDia" to listOf(s("M24,24 V15.5 M24,24 L30,27.5", w = 2.4f), cf(24f, 24f, 1.8f)),
        "linhaCp" to listOf(
            s("M11.9,42 H36.1 M14.2,34 H33.8 M16.2,27 H31.8 M17.8,21 H30.2 M19.1,16 H28.9 M20.2,12 H27.8", w = 2f, a = 0.8f),
            s("M13,46 L21,10 M35,46 L27,10", w = 2.4f), cf(24f, 6f, 3f)
        ),
        "mapOutline" to listOf(s(OUTLINE, w = 1.7f)),
        "regiao" to listOf(RegionOp),
        "colecaoComboios" to listOf(
            s("M7,21 V12 C7,8 10,5 13.5,5 C17,5 20,8 20,12 V21 Z", w = 2.2f), rs(28f, 8f, 15f, 13f, 1.5f, 2.2f),
            s("M33,8 L35.5,5 L38,8", w = 1.6f), rs(6f, 28f, 15f, 14f, 4f, 2.2f), rs(28f, 28f, 15f, 14f, 2f, 2.2f),
            f("M9.5,11.5 C10.5,9.3 16.5,9.3 17.5,11.5 V14 H9.5 Z"), rf(30f, 10.5f, 11f, 4f, 1f),
            rf(8.2f, 30.8f, 4.6f, 4f, 1f), rf(14.2f, 30.8f, 4.6f, 4f, 1f), rf(30f, 30.5f, 11f, 5f, 1f),
            cf(31f, 39f, 1f), cf(40f, 39f, 1f)
        ),
        "fimDoMundo" to listOf(
            s("M20,42 L22,16 H27 L29,42 Z"), s("M21.3,34 H27.7 M21.8,25 H27.2", w = 1.8f), rs(21f, 10.5f, 7f, 5.5f, 1f),
            f("M19.5,10.5 L24.5,5 L29.5,10.5 Z"), s("M17,12 L8,9 M17,14.5 L8,18 M32,12 L41,9 M32,14.5 L41,18", w = 1.8f),
            s("M4,44 H44", w = 1.8f)
        ),
        "pacienciaSanto" to listOf(
            s("M14,5 H34 M14,43 H34"),
            s("M16,5 C16,15 23,19.5 23,24 C23,28.5 16,33 16,43 M32,5 C32,15 25,19.5 25,24 C25,28.5 32,33 32,43", w = 2.2f),
            f("M18.8,11 H29.2 C28.4,14.5 25.8,17.5 24,19 C22.2,17.5 19.6,14.5 18.8,11 Z"), s("M24,20.5 V33", w = 1.2f),
            f("M17.5,42 C18.5,37 21.5,34.5 24,34 C26.5,34.5 29.5,37 30.5,42 Z")
        ),
        "sobrevivente" to listOf(
            s("M24,3 L40,9 V22 C40,33 33,41 24,45 C15,41 8,33 8,22 V9 Z"), rs(17f, 14f, 14f, 15f, 4f),
            rf(19.5f, 17f, 9f, 4.5f, 1.2f), cf(20.2f, 25.6f, 1.1f), cf(27.8f, 25.6f, 1.1f),
            s("M20,32.5 L18.5,35 M28,32.5 L29.5,35", w = 1.8f)
        ),
        "oPrimeiro" to listOf(t(21f, 37f, "1", 32f), t(33f, 20f, "º", 12f, 800), s("M29,42.5 H42 M38.5,39 L42,42.5 L38.5,46", w = 2.2f)),
        "ressacaAnoNovo" to listOf(
            s("M28,18 H35.5 M26.83,20.83 L32.13,26.13 M24,22 V29.5 M21.17,20.83 L15.87,26.13 M20,18 H12.5 M21.17,15.17 L15.87,9.87 M24,14 V6.5 M26.83,15.17 L32.13,9.87", w = 2.2f),
            cf(38.5f, 18f, 1.4f), cf(34.25f, 28.25f, 1.4f), cf(24f, 32.5f, 1.4f), cf(13.75f, 28.25f, 1.4f),
            cf(9.5f, 18f, 1.4f), cf(13.75f, 7.75f, 1.4f), cf(24f, 3.5f, 1.4f), cf(34.25f, 7.75f, 1.4f),
            t(24f, 45.5f, "00:00", 9.5f, 800)
        ),
        "parabensMetro" to listOf(
            s("M7,41 H41", w = 2.2f), rs(11f, 27f, 26f, 13f, 2f), s("M11,31 q3.25,3 6.5,0 t6.5,0 t6.5,0 t6.5,0", w = 2f),
            rs(16f, 19f, 16f, 8f, 2f), rf(22.8f, 10.5f, 2.4f, 8.5f, 0.6f), f("M24,3.5 C26.2,6.5 26.2,8.5 24,9.6 C21.8,8.5 21.8,6.5 24,3.5 Z")
        ),
        "porDoSolTejo" to listOf(
            f("M15,26 A9,9 0 0 1 33,26 Z"), s("M24,13.5 V9.5 M15.16,17.16 L12.33,14.33 M32.84,17.16 L35.67,14.33", w = 2.2f),
            s("M4,26 H44", w = 2.2f),
            s("M6,32 q3,-2.4 6,0 t6,0 t6,0 t6,0 t6,0 t6,0 M10,38 q3,-2.4 6,0 t6,0 t6,0 t6,0 t6,0 M16,44 q3,-2.4 6,0 t6,0 t6,0", w = 2f)
        ),
        "mesmaCarruagem" to listOf(
            rs(6f, 8f, 36f, 27f, 6f), cs(18f, 19f, 4f), cs(30f, 19f, 4f),
            s("M11,33.5 C11,27 25,27 25,33.5 M23,33.5 C23,27 37,27 37,33.5", w = 2.2f),
            f("M24,14.6 C21,12.4 20.6,10.2 22.3,9.6 C23.2,9.3 24,10 24,10.9 C24,10 24.8,9.3 25.7,9.6 C27.4,10.2 27,12.4 24,14.6 Z"),
            s("M4,41 H44", w = 2f), cf(12f, 44f, 1.6f), cf(36f, 44f, 1.6f)
        ),
        "adiantado" to listOf(
            cs(24f, 27f, 12.5f), s("M24,27 V20.5 M24,27 L29,29.5", w = 2.4f),
            s("M39.5,21.36 A16.5,16.5 0 0 0 10.49,17.53", w = 2.2f), s("M14.57,15.63 L10.49,17.53 L10.88,13.05", w = 2.2f)
        ),
        "gauge" to listOf(
            s("M7,34 A17,17 0 0 1 41,34"),
            s("M9.28,25.5 L12.74,27.5 M15.5,19.28 L17.5,22.74 M24,17 V21 M32.5,19.28 L30.5,22.74 M38.72,25.5 L35.26,27.5", w = 1.8f),
            s("M24,34 L31,24.5", w = 2.8f), cf(24f, 34f, 3f), s("M10,40 H38", w = 1.6f, a = 0.6f)
        ),
        "habitue" to listOf(
            s("M6,14 H42 V20 A4,4 0 0 0 42,28 V34 H6 V28 A4,4 0 0 0 6,20 Z"),
            cf(13f, 24f, 2.2f), cf(19f, 24f, 2.2f), cf(25f, 24f, 2.2f), cf(31f, 24f, 2.2f), cs(37f, 24f, 2.2f, 1.4f)
        ),
        "mole" to listOf(
            s("M3,46 V25 A21,21 0 0 1 45,25 V46", w = 1.8f, a = 0.45f), cs(24f, 31f, 11f),
            f("M20,34.5 A4,3 0 1 0 28,34.5 A4,3 0 1 0 20,34.5 Z"), s("M19.4,28.6 H22 M26,28.6 H28.6", w = 1.8f),
            s("M16,43 q2,-3 4,0 M28,43 q2,-3 4,0", w = 1.8f)
        ),
        "toupeira" to listOf(
            f("M12.6,26 C12.6,16.5 35.4,16.5 35.4,26 Z"), s("M10.5,26.5 H37.5", w = 2.4f),
            s("M26.6,19.5 L40,13.5 M26.8,21.5 L41,19.5", w = 1.3f, a = 0.85f, c = LAMPADA), cf(24f, 20.5f, 2.4f, LAMPADA)
        ),
        "reiSubsolo" to listOf(f("M13.5,22.5 L15.5,13 L20,18 L24,11 L28,18 L32.5,13 L34.5,22.5 Z")),
        "metroOuro" to listOf(
            f("M15,9.5 L17.5,3 L21.5,7 L24,2 L26.5,7 L30.5,3 L33,9.5 Z"), rs(16f, 12f, 16f, 14f, 5f), rf(18.5f, 15f, 11f, 5f, 1.5f),
            cf(19.8f, 22.6f, 1.1f), cf(28.2f, 22.6f, 1.1f), t(24f, 45f, "50", 15f)
        ),
        "cpPlatina" to listOf(
            f("M18,7 L21,3 H27 L30,7 L24,12 Z"), s("M15,30 V19 C15,16 17.5,13.5 20.5,13.5 H27.5 C30.5,13.5 33,16 33,19 V30 Z"),
            f("M17.5,17.5 H30.5 V22 H17.5 Z"), cf(19.5f, 26.8f, 1.2f), cf(28.5f, 26.8f, 1.2f), t(24f, 45.5f, "250", 12.5f)
        ),
        "minhoAlgarve" to listOf(s("M18.8,3 C30,14 14,30 26.8,44", w = 1.6f, dash = floatArrayOf(2f, 3f)), cf(18.8f, 3f, 2.6f), cf(26.8f, 44f, 2.6f)),
        "relampagoIc" to listOf(f("M24,2 L8,26 H18.5 L14,46 L32,19 H21.5 L27,2 Z"), t(39f, 40f, "200", 10.5f)),
        "maratonista" to listOf(
            cs(24f, 27f, 14f), s("M21.5,7.5 H26.5 M24,7.5 V13 M35.5,14.5 L38,12", w = 2.4f), t(24f, 31f, "300", 11f), t(24f, 38.5f, "km", 6.5f, 700)
        ),
        "douroAteAoFim" to listOf(
            cs(12f, 9f, 3.6f, 2f), s("M4,30 C10,22 16,19 24,18 C31,17 38,13 44,8", w = 2.2f),
            s("M8,30 C14,25.5 20,23.5 27,23 M12.5,30 C18,27.8 24,27 31,26.8 M17.5,30.5 C22.5,29.6 28,29.3 35,29.4", w = 1.5f, a = 0.85f),
            s("M4,36 C12,33 18,39 26,36 C33,33.5 38,37 44,35", w = 2.4f), rf(9f, 40f, 15f, 4.6f, 1.5f), s("M4,46.5 H44", w = 1.4f)
        ),
        "fiel" to listOf(
            rs(8f, 9f, 32f, 32f, 4f), s("M8,17 H40"), s("M16,5.5 V12 M32,5.5 V12"),
            f("M24,21 C28.5,25 30.5,28.5 29.5,32.5 C28.7,35.6 26.5,37.4 24,37.4 C20.8,37.4 18.3,35.2 18.3,31.9 C18.3,29 20.2,27 21.6,25.2 C21.6,28.2 22.8,29.6 24.3,29.9 C24,26.8 22.9,24.2 24,21 Z")
        ),
        "pacienciaMonge" to listOf(
            s("M14.5,8 A11,11 0 0 1 33.5,8", w = 1.4f, a = 0.7f), cf(24f, 10f, 4.5f), s("M15.5,31 C15.5,20.5 32.5,20.5 32.5,31"),
            s("M17,24.5 C12.5,29 14,33.5 21,33.8 M31,24.5 C35.5,29 34,33.5 27,33.8", w = 2.2f),
            s("M8,38 C14,32.5 34,32.5 40,38 C33,42.5 15,42.5 8,38 Z", w = 2.2f), cf(24f, 33.8f, 2f)
        ),
        "diaDeGreve" to listOf(
            g(
                rs(2f, 15f, 12f, 17f, 3f, 2f), rs(34f, 15f, 12f, 17f, 3f, 2f),
                s("M3.5,14 L12.5,33 M12.5,14 L3.5,33 M35.5,14 L44.5,33 M44.5,14 L35.5,33", w = 1.8f), a = 0.55f
            ),
            rs(16.5f, 9f, 15f, 23f, 4f), rf(18.8f, 12.5f, 10.4f, 6f, 1.4f), cf(20.5f, 27.3f, 1.3f), cf(27.5f, 27.3f, 1.3f),
            s("M2,38 H46", w = 1.8f)
        ),
        "cruzamento" to listOf(
            s("M4,12 H30 C35,12 38,15 38,18.5 V21 H4 Z", w = 2.2f), s("M44,30 H18 C13,30 10,33 10,36.5 V39 H44 Z", w = 2.2f),
            rf(7f, 14.3f, 4.6f, 3f, 0.8f), rf(14f, 14.3f, 4.6f, 3f, 0.8f), rf(21f, 14.3f, 4.6f, 3f, 0.8f),
            rf(36.4f, 32.3f, 4.6f, 3f, 0.8f), rf(29.4f, 32.3f, 4.6f, 3f, 0.8f), rf(22.4f, 32.3f, 4.6f, 3f, 0.8f),
            f("M43,23.9 L43.91,25.59 L45.6,26.5 L43.91,27.41 L43,29.1 L42.09,27.41 L40.4,26.5 L42.09,25.59 Z"),
            f("M5,25 L5.7,26.3 L7,27 L5.7,27.7 L5,29 L4.3,27.7 L3,27 L4.3,26.3 Z"),
            s("M2,24 H46 M2,42 H46", w = 1.4f)
        ),
        "dezMil" to listOf(
            rs(4f, 15f, 40f, 18f, 3f), s("M12,15 V33 M20,15 V33 M28,15 V33 M36,15 V33", w = 1.2f),
            t(8f, 28.6f, "1", 11f), t(16f, 28.6f, "0", 11f), t(24f, 28.6f, "0", 11f), t(32f, 28.6f, "0", 11f), t(40f, 28.6f, "0", 11f)
        ),
        "donoDaLinha" to listOf(
            f("M11,22 L14.5,9 L20.5,16 L24,6.5 L27.5,16 L33.5,9 L37,22 Z"), s("M11,25.5 H37"), s("M4,37 H44", w = 3f),
            cf(8f, 37f, 2.6f), cf(18.7f, 37f, 2.6f), cf(29.3f, 37f, 2.6f), cf(40f, 37f, 2.6f)
        ),
        "mapDots" to listOf(
            20f to 6.5f, 25f to 6f, 30f to 5.5f, 28f to 9f, 21f to 11f, 25.5f to 10.5f, 20.5f to 15.5f, 26f to 15f, 19.5f to 19.5f,
            24.5f to 20f, 27.5f to 20.5f, 18f to 24f, 23f to 24.5f, 16.5f to 28.5f, 22f to 28f, 26.5f to 29f, 18f to 32.5f,
            23f to 32.5f, 20f to 36f, 25f to 37f, 20.5f to 40.5f, 24f to 41f, 22.5f to 43.5f
        ).map { (x, y) -> cf(x, y, 1.25f) },
        "os506" to listOf(CircleOp(16.5f, 31f, 5.2f, Ink.Medal, Ink.Main, 1.8f), cf(16.5f, 31f, 1.9f)),
        "portugalNumDia" to listOf(
            s("M18.8,3 C42,10 42,37 26.8,44", w = 1.6f, dash = floatArrayOf(2f, 3f)), cf(37.2f, 23.5f, 3.4f),
            s("M37.2,17.6 V15.8 M43.1,23.5 H44.9 M37.2,29.4 V31.2", w = 1.6f), cf(18.8f, 3f, 2.2f), cf(26.8f, 44f, 2.2f)
        ),
        "pontualidadeSuica" to listOf(
            cs(24f, 4.5f, 2.6f, 1.8f), RectOp(21.5f, 7f, 5f, 3.8f, 1f, null, Ink.Main, 1.8f), cs(24f, 26f, 15f),
            s("M24,15.8 L24,12.8 M29.7,16.1 L30.6,14.6 M33.9,20.3 L35.4,19.4 M34.2,26 L37.2,26 M33.9,31.7 L35.4,32.6 M29.7,35.9 L30.6,37.4 M24,36.2 L24,39.2 M18.3,35.9 L17.4,37.4 M14.1,31.7 L12.6,32.6 M13.8,26 L10.8,26 M14.1,20.3 L12.6,19.4 M18.3,16.1 L17.4,14.6", w = 1.4f),
            s("M24,26 V17.2", w = 2.4f), s("M24,26 V20", w = 3.6f), cf(24f, 26f, 1.6f)
        ),
        "voltaAoMundo" to listOf(
            cs(24f, 24f, 13f), EllipseOp(24f, 24f, 6f, 13f, 0f, 1.4f), s("M11,24 H37", w = 1.4f), s("M13.3,17 H34.7 M13.3,31 H34.7", w = 1.2f),
            EllipseOp(24f, 24f, 21.5f, 6.5f, -18f, 4.5f, 0.6f, floatArrayOf(1.2f, 3f)), EllipseOp(24f, 24f, 21.5f, 6.5f, -18f, 1.6f)
        ),
        "milEUmaNoites" to listOf(
            g(f(CRESCENT), dx = -8f, dy = 2f, scale = 1.3f),
            f("M40,5.4 L40.91,7.09 L42.6,8 L40.91,8.91 L40,10.6 L39.09,8.91 L37.4,8 L39.09,7.09 Z"),
            f("M43,22 L43.7,23.3 L45,24 L43.7,24.7 L43,26 L42.3,24.7 L41,24 L42.3,23.3 Z"),
            f("M36,36.8 L36.77,38.23 L38.2,39 L36.77,39.77 L36,41.2 L35.23,39.77 L33.8,39 L35.23,38.23 Z"),
            cf(8f, 40f, 1f), cf(14f, 44f, 0.9f)
        ),
        "pioneiro" to listOf(
            f("M3,25 H20 L22.5,28 L20,31 H3 L6,28 Z"), f("M45,25 H28 L25.5,28 L28,31 H45 L42,28 Z"),
            cs(19.5f, 8.5f, 3.6f, 2.2f), cs(28.5f, 8.5f, 3.6f, 2.2f), s("M21.4,11.6 L28.6,24 M26.6,11.6 L19.4,24"), cf(24f, 16.1f, 1.2f),
            f("M24,35 L25.05,36.95 L27,38 L25.05,39.05 L24,41 L22.95,39.05 L21,38 L22.95,36.95 Z"), cf(15f, 40f, 1f), cf(33f, 40f, 1f)
        ),
        "todosABordo" to listOf(
            g(
                s("M4,32 V16.5 C4,13.5 6.2,11.5 9,11.5 H14 C16.8,11.5 19,13.5 19,16.5 V32 Z", w = 2.2f),
                s("M29,32 V16.5 C29,13.5 31.2,11.5 34,11.5 H39 C41.8,11.5 44,13.5 44,16.5 V32 Z", w = 2.2f),
                rf(6f, 15f, 11f, 3.6f, 1f), rf(31f, 15f, 11f, 3.6f, 1f), a = 0.65f
            ),
            s("M20.5,4 L24,1 L27.5,4", w = 1.8f),
            PathOp("M13,36 V11 C13,7.1 16.1,4 20,4 H28 C31.9,4 35,7.1 35,11 V36 Z", Ink.Medal, Ink.Main),
            f("M16,10 H32 V17 H16 Z"), cf(18f, 29.5f, 1.6f), cf(30f, 29.5f, 1.6f), s("M2,41 H46", w = 1.8f)
        ),
        "bicho" to listOf(
            f("M17,46 L20,22 C20.5,17 23,13.5 27,12.5 L34,10.5 C36.5,10 38,12 36.5,14 L31,17.5 L29,46 Z"),
            s("M25.5,12.6 L24.6,6.6 M29,11.6 L29.2,5.6", w = 2f), cf(24.5f, 6.1f, 1.6f), cf(29.2f, 5.1f, 1.6f),
            s("M24.5,13.8 L19.8,12.4", w = 2.2f),
            cm(31.5f, 13f, 1f), cm(23.5f, 24f, 2.1f), cm(22.4f, 31.5f, 2.3f), cm(26.3f, 37.5f, 2.1f), cm(25.6f, 29f, 1.4f), cm(21.8f, 40.5f, 1.6f)
        ),
        "capicua" to listOf(
            s("M24,7 V41", w = 1.3f, dash = floatArrayOf(2f, 2.5f)), t(24f, 30f, "16061", 13.5f, 900, 0.4f),
            s("M14,13 C18.5,8.5 29.5,8.5 34,13 M14,13 L14.4,9.3 M14,13 L17.7,13.5 M34,13 L33.6,9.3 M34,13 L30.3,13.5", w = 1.8f)
        ),
        "ateLua" to listOf(
            s("M8,46 C11.7,35 18,27.7 26.3,23", w = 6.5f, a = 0.55f, dash = floatArrayOf(1.4f, 3.2f), butt = true),
            s("M5,46 C9,34 16,26 25,21 M11,46 C14.5,36 20,29.5 27.5,25.2", w = 2f), cs(33.5f, 12.5f, 9.5f, 2.2f),
            cs(30.5f, 10f, 2.1f, 1.3f), cs(36.6f, 15.8f, 1.6f, 1.3f), cs(35f, 8.4f, 1.1f, 1.3f),
            f("M10,8 L10.7,9.3 L12,10 L10.7,10.7 L10,12 L9.3,10.7 L8,10 L9.3,9.3 Z"),
            f("M18,3.4 L18.56,4.44 L19.6,5 L18.56,5.56 L18,6.6 L17.44,5.56 L16.4,5 L17.44,4.44 Z")
        ),
        "secretLocked" to listOf(t(24f, 37f, "?", 34f))
    )

    /** Que peças tem cada distintivo e, nos marcos, o número. */
    val META: Map<String, Pair<List<String>, String>> = mapOf(
        "metroBronze" to (listOf("metroFront") to "10"),
        "metroPrata" to (listOf("metroFront") to "25"),
        "cpBronze" to (listOf("cpFront") to "10"),
        "cpPrata" to (listOf("cpFront") to "50"),
        "cpOuro" to (listOf("cpFront") to "100"),
        "voltaLisboa" to (listOf("ringLines", "voltaLisboa") to ""),
        "metroNumDia" to (listOf("ringLines", "metroNumDia") to ""),
        "regiao" to (listOf("mapOutline", "regiao") to ""),
        "minhoAlgarve" to (listOf("mapOutline", "minhoAlgarve") to ""),
        "portugalInteiro" to (listOf("mapOutline", "mapDots") to ""),
        "os506" to (listOf("mapOutline", "mapDots", "os506") to ""),
        "portugalNumDia" to (listOf("mapOutline", "portugalNumDia") to ""),
        "milKm" to (listOf("gauge") to ""),
        "toupeira" to (listOf("mole", "toupeira") to ""),
        "reiSubsolo" to (listOf("mole", "reiSubsolo") to "")
    )

    fun parts(id: String): Pair<List<String>, String> = META[id] ?: (listOf(id) to "")

    /** Onde fica a região no mapa do distintivo "Região", pelo nome da fita. */
    fun regionPoint(label: String): Pair<Float, Float> = when {
        label.contains("NORTE") -> 25f to 8.5f
        label.contains("CENTRO") -> 23.5f to 19f
        label.contains("ALENTEJO") -> 23f to 35f
        label.contains("ALGARVE") -> 21.5f to 43.6f
        else -> 16.5f to 31f
    }
}
