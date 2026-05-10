package com.example.metrotime.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
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
