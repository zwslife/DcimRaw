package com.dcimgallery.raw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dcimgallery.raw.ui.StudioColors

private val LightColors = lightColorScheme(
    primary = StudioColors.Accent,
    onPrimary = Color.White,
    secondary = StudioColors.Accent,
    background = StudioColors.Canvas,
    onBackground = StudioColors.Ink,
    surface = Color.White,
    onSurface = StudioColors.Ink,
    surfaceVariant = StudioColors.Tint,
    onSurfaceVariant = StudioColors.Muted,
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
