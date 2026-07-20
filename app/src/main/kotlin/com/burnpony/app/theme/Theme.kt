//
// Theme.kt
// Ember design tokens: single source of truth, identical hex across
// iOS (BurnPonyTheme.swift), the web viewer CSS variables, and here.
// Dark theme only.
//

package com.burnpony.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object BurnPonyTheme {
    val background = Color(0xFF050408)
    val panel = Color(0xFF14121A)
    val line = Color(0xFF262331)
    val ink = Color(0xFFFFF0DD)
    val dim = Color(0xFFAFA294)
    val ember = Color(0xFFF67529)
    val emberDeep = Color(0xFFEB2F33)
    val danger = Color(0xFFEB2F33)
    val buttonInk = Color(0xFF1A0E08)
    val fieldBackground = Color(0xFF0B0A10)

    // Primary buttons: top-to-bottom ember-to-emberDeep gradient, text buttonInk.
    val emberGradient = Brush.verticalGradient(colors = listOf(ember, emberDeep))
}

private val EmberColorScheme = darkColorScheme(
    primary = BurnPonyTheme.ember,
    onPrimary = BurnPonyTheme.buttonInk,
    secondary = BurnPonyTheme.emberDeep,
    onSecondary = BurnPonyTheme.buttonInk,
    background = BurnPonyTheme.background,
    onBackground = BurnPonyTheme.ink,
    surface = BurnPonyTheme.panel,
    onSurface = BurnPonyTheme.ink,
    surfaceVariant = BurnPonyTheme.fieldBackground,
    onSurfaceVariant = BurnPonyTheme.dim,
    outline = BurnPonyTheme.line,
    error = BurnPonyTheme.danger,
    onError = BurnPonyTheme.ink,
    surfaceContainer = BurnPonyTheme.panel,
    surfaceContainerHigh = BurnPonyTheme.panel,
    surfaceContainerHighest = BurnPonyTheme.panel,
    surfaceContainerLow = BurnPonyTheme.background,
    surfaceContainerLowest = BurnPonyTheme.background,
)

@Composable
fun BurnPonyMaterialTheme(content: @Composable () -> Unit) {
    // Dark-only regardless of system setting, matching iOS
    // (preferredColorScheme(.dark)): the ember scheme is always applied.
    MaterialTheme(
        colorScheme = EmberColorScheme,
        content = content,
    )
}
