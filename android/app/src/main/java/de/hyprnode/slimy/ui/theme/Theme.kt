package de.hyprnode.slimy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Slimy on Android: Material 3 around a pixel-art slime, the counterpart of
 *  the iPhone app's native look. Slime green is the one accent: deep in light
 *  mode, bright in dark mode (both pass 4.5:1 with their text colour). */
object SlimyColors {
    val GreenLight = Color(0xFF1E7F37)
    val GreenDark = Color(0xFF4CC764)
    val OnGreenDark = Color(0xFF0B2912)
    val Hypr = Color(0xFF9B7BFF)
}

private val Light = lightColorScheme(
    primary = SlimyColors.GreenLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F0DA),
    onPrimaryContainer = Color(0xFF0B2912),
    secondary = Color(0xFF52634F),
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFF2F2F7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9E9EF),
    surfaceContainerHighest = Color(0xFFE2E2E8),
)

private val Dark = darkColorScheme(
    primary = SlimyColors.GreenDark,
    onPrimary = SlimyColors.OnGreenDark,
    primaryContainer = Color(0xFF1C4A27),
    onPrimaryContainer = Color(0xFFCDEFD3),
    secondary = Color(0xFFB9CCB4),
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
)

@Composable
fun SlimyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
