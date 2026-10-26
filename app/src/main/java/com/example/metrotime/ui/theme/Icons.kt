package com.example.metrotime.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Inline icon vectors to avoid depending on material-icons-extended (~30MB).
 */

val TrainIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Train",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            curveTo(8f, 2f, 4f, 2.5f, 4f, 6f)
            lineTo(4f, 15.5f)
            curveTo(4f, 17.43f, 5.57f, 19f, 7.5f, 19f)
            lineTo(6f, 20.5f)
            lineTo(6f, 21f)
            lineTo(8.23f, 21f)
            lineTo(10.23f, 19f)
            lineTo(13.77f, 19f)
            lineTo(15.77f, 21f)
            lineTo(18f, 21f)
            lineTo(18f, 20.5f)
            lineTo(16.5f, 19f)
            curveTo(18.43f, 19f, 20f, 17.43f, 20f, 15.5f)
            lineTo(20f, 6f)
            curveTo(20f, 2.5f, 16f, 2f, 12f, 2f)
            close()
            moveTo(12f, 4f)
            curveTo(15.51f, 4f, 18f, 4.38f, 18f, 6f)
            lineTo(6f, 6f)
            curveTo(6f, 4.38f, 8.49f, 4f, 12f, 4f)
            close()
            // Left window
            moveTo(6f, 8f)
            lineTo(11.5f, 8f)
            lineTo(11.5f, 12f)
            lineTo(6f, 12f)
            lineTo(6f, 8f)
            close()
            // Right window
            moveTo(12.5f, 8f)
            lineTo(18f, 8f)
            lineTo(18f, 12f)
            lineTo(12.5f, 12f)
            lineTo(12.5f, 8f)
            close()
            moveTo(8.5f, 17f)
            curveTo(7.67f, 17f, 7f, 16.33f, 7f, 15.5f)
            curveTo(7f, 14.67f, 7.67f, 14f, 8.5f, 14f)
            curveTo(9.33f, 14f, 10f, 14.67f, 10f, 15.5f)
            curveTo(10f, 16.33f, 9.33f, 17f, 8.5f, 17f)
            close()
            moveTo(15.5f, 17f)
            curveTo(14.67f, 17f, 14f, 16.33f, 14f, 15.5f)
            curveTo(14f, 14.67f, 14.67f, 14f, 15.5f, 14f)
            curveTo(16.33f, 14f, 17f, 14.67f, 17f, 15.5f)
            curveTo(17f, 16.33f, 16.33f, 17f, 15.5f, 17f)
            close()
        }
    }.build()
}

/** Bandeira de traço (reportar um problema numa estação). */
val FlagIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Flag",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 22f)
            lineTo(5f, 3f)
            moveTo(5f, 4f)
            lineTo(19f, 4f)
            lineTo(16f, 8.5f)
            lineTo(19f, 13f)
            lineTo(5f, 13f)
        }
    }.build()
}

/** Relógio de traço (viagens recentes). */
val ClockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Clock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2f)
            arcTo(10f, 10f, 0f, true, true, 11.99f, 2f)
            close()
            moveTo(12f, 6f)
            lineTo(12f, 12f)
            lineTo(16f, 14f)
        }
    }.build()
}

/** Trocar (setas para cima e para baixo), como o "swap_vert" do Material. */
val SwapVertIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SwapVert",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 17.01f)
            lineTo(16f, 10f)
            lineTo(14f, 10f)
            lineTo(14f, 17.01f)
            lineTo(11f, 17.01f)
            lineTo(15f, 21f)
            lineTo(19f, 17.01f)
            close()
            moveTo(9f, 3f)
            lineTo(5f, 6.99f)
            lineTo(8f, 6.99f)
            lineTo(8f, 14f)
            lineTo(10f, 14f)
            lineTo(10f, 6.99f)
            lineTo(13f, 6.99f)
            close()
        }
    }.build()
}

val CircleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Circle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
        }
    }.build()
}

val MapIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Map",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(15f, 5.1f)
            lineTo(9f, 3f)
            lineTo(3.7f, 5.05f)
            curveTo(3.28f, 5.21f, 3f, 5.61f, 3f, 6.06f)
            lineTo(3f, 19.5f)
            curveTo(3f, 20.2f, 3.69f, 20.68f, 4.34f, 20.43f)
            lineTo(9f, 18.62f)
            lineTo(15f, 20.72f)
            lineTo(20.3f, 18.67f)
            curveTo(20.72f, 18.51f, 21f, 18.11f, 21f, 17.66f)
            lineTo(21f, 4.22f)
            curveTo(21f, 3.52f, 20.31f, 3.04f, 19.66f, 3.29f)
            lineTo(15f, 5.1f)
            close()
            moveTo(10f, 5.38f)
            lineTo(14f, 6.78f)
            lineTo(14f, 18.34f)
            lineTo(10f, 16.94f)
            lineTo(10f, 5.38f)
            close()
            moveTo(5f, 6.75f)
            lineTo(8f, 5.59f)
            lineTo(8f, 16.9f)
            lineTo(5f, 18.06f)
            lineTo(5f, 6.75f)
            close()
            moveTo(19f, 16.97f)
            lineTo(16f, 18.13f)
            lineTo(16f, 6.82f)
            lineTo(19f, 5.66f)
            lineTo(19f, 16.97f)
            close()
        }
    }.build()
}

val ChartIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Chart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 19f)
            lineTo(19f, 19f)
            curveTo(19.55f, 19f, 20f, 19.45f, 20f, 20f)
            curveTo(20f, 20.55f, 19.55f, 21f, 19f, 21f)
            lineTo(4f, 21f)
            curveTo(3.45f, 21f, 3f, 20.55f, 3f, 20f)
            lineTo(3f, 5f)
            curveTo(3f, 4.45f, 3.45f, 4f, 4f, 4f)
            curveTo(4.55f, 4f, 5f, 4.45f, 5f, 5f)
            lineTo(5f, 19f)
            close()
            moveTo(8f, 17f)
            lineTo(8f, 11f)
            lineTo(11f, 11f)
            lineTo(11f, 17f)
            lineTo(8f, 17f)
            close()
            moveTo(13f, 17f)
            lineTo(13f, 7f)
            lineTo(16f, 7f)
            lineTo(16f, 17f)
            lineTo(13f, 17f)
            close()
            moveTo(18f, 17f)
            lineTo(18f, 13f)
            lineTo(21f, 13f)
            lineTo(21f, 17f)
            lineTo(18f, 17f)
            close()
        }
    }.build()
}

val FavoriteIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Favorite",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 17.27f)
            lineTo(18.18f, 21f)
            lineTo(16.54f, 13.97f)
            lineTo(22f, 9.24f)
            lineTo(14.81f, 8.63f)
            lineTo(12f, 2f)
            lineTo(9.19f, 8.63f)
            lineTo(2f, 9.24f)
            lineTo(7.46f, 13.97f)
            lineTo(5.82f, 21f)
            lineTo(12f, 17.27f)
            close()
        }
    }.build()
}

val BusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Bus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(6.5f, 3f)
            lineTo(17.5f, 3f)
            curveTo(19.43f, 3f, 21f, 4.57f, 21f, 6.5f)
            lineTo(21f, 16f)
            curveTo(21f, 17.1f, 20.1f, 18f, 19f, 18f)
            lineTo(19f, 20f)
            curveTo(19f, 20.55f, 18.55f, 21f, 18f, 21f)
            lineTo(17f, 21f)
            curveTo(16.45f, 21f, 16f, 20.55f, 16f, 20f)
            lineTo(16f, 18f)
            lineTo(8f, 18f)
            lineTo(8f, 20f)
            curveTo(8f, 20.55f, 7.55f, 21f, 7f, 21f)
            lineTo(6f, 21f)
            curveTo(5.45f, 21f, 5f, 20.55f, 5f, 20f)
            lineTo(5f, 18f)
            curveTo(3.9f, 18f, 3f, 17.1f, 3f, 16f)
            lineTo(3f, 6.5f)
            curveTo(3f, 4.57f, 4.57f, 3f, 6.5f, 3f)
            close()
            moveTo(6.5f, 5f)
            curveTo(5.67f, 5f, 5f, 5.67f, 5f, 6.5f)
            lineTo(5f, 11f)
            lineTo(19f, 11f)
            lineTo(19f, 6.5f)
            curveTo(19f, 5.67f, 18.33f, 5f, 17.5f, 5f)
            lineTo(6.5f, 5f)
            close()
            moveTo(7.5f, 16f)
            curveTo(6.67f, 16f, 6f, 15.33f, 6f, 14.5f)
            curveTo(6f, 13.67f, 6.67f, 13f, 7.5f, 13f)
            curveTo(8.33f, 13f, 9f, 13.67f, 9f, 14.5f)
            curveTo(9f, 15.33f, 8.33f, 16f, 7.5f, 16f)
            close()
            moveTo(16.5f, 16f)
            curveTo(15.67f, 16f, 15f, 15.33f, 15f, 14.5f)
            curveTo(15f, 13.67f, 15.67f, 13f, 16.5f, 13f)
            curveTo(17.33f, 13f, 18f, 13.67f, 18f, 14.5f)
            curveTo(18f, 15.33f, 17.33f, 16f, 16.5f, 16f)
            close()
        }
    }.build()
}

val BusStopIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BusStop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 3f)
            lineTo(17f, 3f)
            curveTo(18.1f, 3f, 19f, 3.9f, 19f, 5f)
            lineTo(19f, 14f)
            curveTo(19f, 15.1f, 18.1f, 16f, 17f, 16f)
            lineTo(16f, 16f)
            lineTo(16f, 20f)
            curveTo(16f, 20.55f, 15.55f, 21f, 15f, 21f)
            curveTo(14.45f, 21f, 14f, 20.55f, 14f, 20f)
            lineTo(14f, 16f)
            lineTo(10f, 16f)
            lineTo(10f, 20f)
            curveTo(10f, 20.55f, 9.55f, 21f, 9f, 21f)
            curveTo(8.45f, 21f, 8f, 20.55f, 8f, 20f)
            lineTo(8f, 16f)
            lineTo(7f, 16f)
            curveTo(5.9f, 16f, 5f, 15.1f, 5f, 14f)
            lineTo(5f, 5f)
            curveTo(5f, 3.9f, 5.9f, 3f, 7f, 3f)
            close()
            moveTo(7f, 5f)
            lineTo(7f, 9f)
            lineTo(17f, 9f)
            lineTo(17f, 5f)
            lineTo(7f, 5f)
            close()
            moveTo(8f, 13f)
            curveTo(8.55f, 13f, 9f, 12.55f, 9f, 12f)
            curveTo(9f, 11.45f, 8.55f, 11f, 8f, 11f)
            curveTo(7.45f, 11f, 7f, 11.45f, 7f, 12f)
            curveTo(7f, 12.55f, 7.45f, 13f, 8f, 13f)
            close()
            moveTo(16f, 13f)
            curveTo(16.55f, 13f, 17f, 12.55f, 17f, 12f)
            curveTo(17f, 11.45f, 16.55f, 11f, 16f, 11f)
            curveTo(15.45f, 11f, 15f, 11.45f, 15f, 12f)
            curveTo(15f, 12.55f, 15.45f, 13f, 16f, 13f)
            close()
        }
    }.build()
}

val VisibilityIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Visibility",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
            moveTo(12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            close()
        }
    }.build()
}

val VisibilityOffIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "VisibilityOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f)
            lineTo(19.56f, 16.75f)
            curveTo(21.07f, 15.49f, 22.26f, 13.86f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            curveTo(10.6f, 4.5f, 9.26f, 4.75f, 8f, 5.2f)
            lineTo(10.17f, 7.36f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineTo(4.28f, 6.55f)
            lineTo(4.74f, 7.01f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f)
            lineTo(16.81f, 19.08f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            lineTo(2f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineTo(9.08f, 11.35f)
            curveTo(9.03f, 11.56f, 9f, 11.78f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(12.22f, 15f, 12.44f, 14.97f, 12.65f, 14.92f)
            lineTo(14.2f, 16.47f)
            curveTo(13.53f, 16.8f, 12.79f, 17f, 12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 11.21f, 7.2f, 10.47f, 7.53f, 9.8f)
            close()
            moveTo(11.84f, 9.02f)
            lineTo(14.99f, 12.17f)
            lineTo(15.01f, 12.01f)
            curveTo(15.01f, 10.35f, 13.67f, 9.01f, 12.01f, 9.01f)
            lineTo(11.84f, 9.02f)
            close()
        }
    }.build()
}

/** Tempo real (o sinal do Google Maps): um ponto e dois arcos, para os autocarros seguidos por GPS. */
val LiveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Live",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Ponto com centro em (5, 19) e arcos à volta dele
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 16.5f)
            arcTo(2.5f, 2.5f, 0f, true, true, 4.99f, 16.5f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 11f)
            arcTo(8f, 8f, 0f, false, true, 13f, 19f)
            moveTo(5f, 4.5f)
            arcTo(14.5f, 14.5f, 0f, false, true, 19.5f, 19f)
        }
    }.build()
}

/** Percurso (uma linha a subir com a seta), para a distância percorrida. */
val RouteIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Route", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(21f, 7f)
            moveTo(14f, 7f); lineTo(21f, 7f); lineTo(21f, 14f)
        }
    }.build()
}

/** Marcador de sítio, para as estações. */
val PinIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Pin", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 21f)
            curveTo(12f, 21f, 5f, 14.8f, 5f, 10f)
            arcTo(7f, 7f, 0f, false, true, 19f, 10f)
            curveTo(19f, 14.8f, 12f, 21f, 12f, 21f)
            close()
            moveTo(12f, 7.5f)
            arcTo(2.5f, 2.5f, 0f, true, true, 11.99f, 7.5f)
            close()
        }
    }.build()
}
