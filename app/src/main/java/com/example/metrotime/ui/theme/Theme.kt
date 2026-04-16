package com.example.metrotime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Red80, // A slightly lighter red for dark mode
    secondary = RedGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Red40, // The main brand red for light mode
    secondary = RedGrey40,
    tertiary = Pink40
)

@Composable
fun MetrotimeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // This will be created next
        content = content
    )
}
