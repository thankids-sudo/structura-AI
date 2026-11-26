package com.structura.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF1B2A41)
val Concrete = Color(0xFF6B7280)
val Rebar = Color(0xFFB4532A)
val PassGreen = Color(0xFF2E7D32)
val FailRed = Color(0xFFC62828)
val InfoBlue = Color(0xFF1565C0)

private val Light = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Rebar,
    onSecondary = Color.White,
    tertiary = Concrete,
    background = Color(0xFFF7F7F5),
    surface = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB4D8),
    onPrimary = Navy,
    secondary = Color(0xFFE39A78),
    tertiary = Color(0xFFB0B7C3),
)

/** Black-and-white "paper" scheme used throughout the app (engineering-drawing look). */
private val Mono = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8), onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF3A3A3A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED), onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF6B6B6B), onTertiary = Color.White,
    background = Color.White, onBackground = Color(0xFF111111),
    surface = Color.White, onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF444444),
    surfaceContainer = Color(0xFFF7F7F7), surfaceContainerHigh = Color(0xFFF2F2F2), surfaceContainerHighest = Color(0xFFEDEDED),
    surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainerLowest = Color.White,
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFD6D6D6),
    error = Color(0xFF111111), onError = Color.White, errorContainer = Color(0xFFE6E6E6), onErrorContainer = Color(0xFF111111),
    inverseSurface = Color(0xFF111111), inverseOnSurface = Color.White, inversePrimary = Color(0xFFDADADA),
)

@Composable
fun StructuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Mono, content = content)
}

@Suppress("unused") private val keepLight = Light
@Suppress("unused") private val keepDark = Dark
