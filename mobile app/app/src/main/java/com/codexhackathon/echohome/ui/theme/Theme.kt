package com.codexhackathon.echohome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EchoLightColors = lightColorScheme(
    primary = Color(0xFF168A57),
    onPrimary = Color.White,
    secondary = Color(0xFF2F6FB0),
    onSecondary = Color.White,
    tertiary = Color(0xFFD99A28),
    background = Color(0xFFF4F6F7),
    onBackground = Color(0xFF17211E),
    surface = Color.White,
    onSurface = Color(0xFF17211E),
    surfaceVariant = Color(0xFFE8EEEC),
    onSurfaceVariant = Color(0xFF65716F),
    error = Color(0xFFC94B4B)
)

@Composable
fun EchoHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EchoLightColors,
        typography = Typography(),
        content = content
    )
}
