package org.qinda.deck.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * A macro pad gets used in dim rooms next to a monitor, so the dark scheme is the
 * designed one and the light scheme is the courtesy.
 */

private val Ink = Color(0xFF0E1117)
private val Slate = Color(0xFF161B24)
private val SlateHi = Color(0xFF1E2532)
private val Edge = Color(0xFF2B3344)
private val Text = Color(0xFFE8ECF4)
private val Muted = Color(0xFF93A0B8)
private val Blue = Color(0xFF4F8DF5)
private val Amber = Color(0xFFF2A33C)
private val Red = Color(0xFFE4574C)

private val DarkScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF23395C),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Amber,
    onSecondary = Color(0xFF241800),
    tertiary = Color(0xFF12B981),
    background = Ink,
    onBackground = Text,
    surface = Slate,
    onSurface = Text,
    surfaceVariant = SlateHi,
    onSurfaceVariant = Muted,
    surfaceContainer = SlateHi,
    surfaceContainerHigh = Color(0xFF242C3B),
    outline = Edge,
    outlineVariant = Color(0xFF232A37),
    error = Red,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2D6BD4),
    secondary = Color(0xFFB0741F),
    tertiary = Color(0xFF0E8A61),
    background = Color(0xFFF6F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF4),
    error = Color(0xFFC0392B),
)

/** Tabular figures matter here: coordinates and durations sit in aligned columns. */
val MonoNumbers = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
)

private val DeckTypography = Typography()

@Composable
fun QindaDeckTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = DeckTypography,
        content = content,
    )
}

/** Parse a `#RRGGBB` from a profile or button, or fall back. */
fun parseHex(hex: String, fallback: Color): Color {
    val s = hex.removePrefix("#")
    if (s.length != 6) return fallback
    val v = s.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000 or v)
}

fun Color.toHex(): String =
    "#%02X%02X%02X".format(
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
    )
