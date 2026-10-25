package com.yaya.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val YayaiColorScheme = lightColorScheme(
    primary = YayaColors.Primary,
    onPrimary = YayaColors.OnPrimary,
    primaryContainer = YayaColors.PrimaryContainer,
    onPrimaryContainer = YayaColors.OnPrimaryContainer,
    secondary = YayaColors.Warning,
    onSecondary = YayaColors.TextPrimary,
    background = YayaColors.Background,
    onBackground = YayaColors.TextPrimary,
    surface = YayaColors.Surface,
    onSurface = YayaColors.TextPrimary,
    surfaceVariant = YayaColors.SurfaceVariant,
    onSurfaceVariant = YayaColors.TextSecondary,
    outline = YayaColors.Outline,
    outlineVariant = YayaColors.OutlineVariant,
    error = YayaColors.Error,
    onError = YayaColors.OnPrimary,
)

@Composable
fun YayaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YayaiColorScheme,
        typography = YayaTypography,
        shapes = YayaShapes,
        content = content,
    )
}
