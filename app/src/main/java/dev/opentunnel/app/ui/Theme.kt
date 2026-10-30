package dev.opentunnel.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE8EEF9),
    surface = Color(0xFF121B2E),
    onSurface = Color(0xFFE8EEF9),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFFA9B7CF),
    outline = Color(0xFF2C3A55),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A5DB0),
    background = Color(0xFFF6F8FC),
    surface = Color.White,
)

@Composable
fun OpenTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
