package com.datacall.ccp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF81C784),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF2E7D32),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun DataCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
