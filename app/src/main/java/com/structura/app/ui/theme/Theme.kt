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

@Composable
fun StructuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
