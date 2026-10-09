package com.application.sensorforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    outlineVariant = Outline,
    error = SensorForgeColors().failSolid,
    errorContainer = SensorForgeColors().fail.container,
)

/** Soft light theme only; no dynamic colour, no dark mode (design.md §9). */
@Composable
fun SensorforgeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSfColors provides SensorForgeColors()) {
        MaterialTheme(colorScheme = LightColors, typography = Typography, content = content)
    }
}

object SfTheme {
    val colors: SensorForgeColors
        @Composable @ReadOnlyComposable get() = LocalSfColors.current
}
