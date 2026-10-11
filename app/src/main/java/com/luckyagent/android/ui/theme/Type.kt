package com.luckyagent.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val CloverBaseTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * Uses Android's bundled CJK families. `serif` resolves to Noto Serif CJK on
 * supported Android images, so the app gets a polished reading face without
 * downloading a font or shipping a third-party font file.
 */
fun cloverFontFamily(id: String): FontFamily = when (id.lowercase()) {
    "sans" -> FontFamily.SansSerif
    else -> FontFamily.Serif
}

private fun scaled(style: TextStyle, family: FontFamily, scale: Float): TextStyle = style.copy(
    fontFamily = family,
    fontSize = (style.fontSize.value * scale).sp,
    lineHeight = (style.lineHeight.value * scale).sp,
)

fun cloverTypography(fontScale: Float = 1f, family: FontFamily = FontFamily.Serif): Typography {
    val scale = fontScale.coerceIn(0.85f, 1.35f)
    return CloverBaseTypography.copy(
        displayLarge = scaled(CloverBaseTypography.displayLarge, family, scale),
        displayMedium = scaled(CloverBaseTypography.displayMedium, family, scale),
        displaySmall = scaled(CloverBaseTypography.displaySmall, family, scale),
        headlineLarge = scaled(CloverBaseTypography.headlineLarge, family, scale),
        headlineMedium = scaled(CloverBaseTypography.headlineMedium, family, scale),
        headlineSmall = scaled(CloverBaseTypography.headlineSmall, family, scale),
        titleLarge = scaled(CloverBaseTypography.titleLarge, family, scale),
        titleMedium = scaled(CloverBaseTypography.titleMedium, family, scale),
        titleSmall = scaled(CloverBaseTypography.titleSmall, family, scale),
        bodyLarge = scaled(CloverBaseTypography.bodyLarge, family, scale),
        bodyMedium = scaled(CloverBaseTypography.bodyMedium, family, scale),
        bodySmall = scaled(CloverBaseTypography.bodySmall, family, scale),
        labelLarge = scaled(CloverBaseTypography.labelLarge, family, scale),
        labelMedium = scaled(CloverBaseTypography.labelMedium, family, scale),
        labelSmall = scaled(CloverBaseTypography.labelSmall, family, scale),
    )
}
