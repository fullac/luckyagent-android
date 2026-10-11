package com.luckyagent.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CloverShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

private val LightColors = lightColorScheme(
    primary = CloverAccent,
    onPrimary = Color.White,
    primaryContainer = CloverAccentSoft,
    onPrimaryContainer = CloverLeafInk,
    secondary = CloverAccent,
    onSecondary = Color.White,
    secondaryContainer = CloverAccentSoft,
    onSecondaryContainer = CloverAccent,
    tertiary = CloverAccent,
    tertiaryContainer = CloverAccentSoft,
    onTertiaryContainer = CloverAccent,
    outlineVariant = CloverLine,
    background = CloverBg,
    onBackground = CloverText,
    surface = CloverSurface,
    onSurface = CloverText,
    surfaceVariant = CloverSurface2,
    onSurfaceVariant = CloverText2,
    outline = CloverLine,
    error = CloverError,
)

private val DarkColors = darkColorScheme(
    primary = CloverDarkAccent,
    onPrimary = CloverLeafInk,
    primaryContainer = CloverDarkAccentSoft,
    onPrimaryContainer = Color.White,
    secondary = CloverAccent,
    onSecondary = Color.White,
    secondaryContainer = CloverDarkAccentSoft,
    onSecondaryContainer = CloverDarkAccent,
    tertiary = CloverDarkAccent,
    tertiaryContainer = CloverDarkAccentSoft,
    onTertiaryContainer = CloverDarkAccent,
    outlineVariant = CloverDarkLine,
    background = CloverDarkBg,
    onBackground = CloverDarkText,
    surface = CloverDarkSurface,
    onSurface = CloverDarkText,
    surfaceVariant = CloverDarkSurface2,
    onSurfaceVariant = CloverDarkText2,
    outline = CloverDarkLine,
    error = CloverDarkError,
)

@Composable
fun CloverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    fontFamily: String = "sans",
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = cloverTypography(fontScale, cloverFontFamily(fontFamily)),
        shapes = CloverShapes,
        content = content,
    )
}
