package com.dcimgallery.raw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF28745C),
    onPrimary = Color.White,
    secondary = Color(0xFF28745C),
    background = Color(0xFFF1F4EF),
    onBackground = Color(0xFF202923),
    surface = Color.White,
    onSurface = Color(0xFF202923),
    surfaceVariant = Color(0xFFE8F1EB),
    onSurfaceVariant = Color(0xFF68736B),
    surfaceTint = Color.Transparent,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
