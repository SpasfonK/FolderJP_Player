package com.n7folder.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val N7ColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Night,
    secondary = NeonMagenta,
    onSecondary = Night,
    tertiary = NeonLime,
    onTertiary = Night,
    background = Night,
    onBackground = TextPrimary,
    surface = NightSurface,
    onSurface = TextPrimary,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    onError = Night
)

/** Thème sombre néon/cyan, appliqué en permanence (indépendant du mode clair/sombre du système). */
@Composable
fun N7Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = N7ColorScheme, content = content)
}
