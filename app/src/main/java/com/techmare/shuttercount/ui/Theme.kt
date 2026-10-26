package com.techmare.shuttercount.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.techmare.shuttercount.R

/**
 * Camera-body palette: aluminium paper, graphite instrument panel,
 * and the shop's signal yellow as the single accent.
 */
@Immutable
data class AppColors(
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
    val panel: Color,
    val panelText: Color,
    val panelMuted: Color,
    val panelTick: Color,
    val signal: Color,
    val ok: Color,
    val mid: Color,
    val high: Color,
)

private val LightColors = AppColors(
    paper = Color(0xFFE9EBEE),
    ink = Color(0xFF1C1F23),
    muted = Color(0xFF5E646C),
    rule = Color(0xFFD3D7DC),
    panel = Color(0xFF2A2E33),
    panelText = Color(0xFFF2F3F4),
    panelMuted = Color(0xFFA9AFB6),
    panelTick = Color(0xFF5A6067),
    signal = Color(0xFFFFC21A),
    ok = Color(0xFF3FA35E),
    mid = Color(0xFFE5932A),
    high = Color(0xFFE0533A),
)

private val DarkColors = LightColors.copy(
    paper = Color(0xFF1E2125),
    ink = Color(0xFFE8EAED),
    muted = Color(0xFF9CA2A9),
    rule = Color(0xFF353A40),
    panel = Color(0xFF2C3137),
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

object Fonts {
    val condensed = FontFamily(
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )
    val text = FontFamily(
        Font(R.font.barlow_semicondensed_regular, FontWeight.Normal),
        Font(R.font.barlow_semicondensed_medium, FontWeight.Medium),
    )
}

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Fonts.condensed, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Fonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Fonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Fonts.condensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Fonts.text, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Fonts.text, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Fonts.text, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Fonts.text, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Fonts.text, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
)

@Composable
fun ShutterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(
        primary = c.signal, onPrimary = Color(0xFF1C1F23),
        background = c.paper, surface = c.paper, onBackground = c.ink, onSurface = c.ink,
        surfaceVariant = c.rule, onSurfaceVariant = c.muted, outline = Color(0xFF6B7178),
        surfaceContainer = Color(0xFF25292E), surfaceContainerHigh = Color(0xFF2C3137),
        error = c.high,
    ) else lightColorScheme(
        primary = c.ink, onPrimary = Color.White,
        background = c.paper, surface = c.paper, onBackground = c.ink, onSurface = c.ink,
        surfaceVariant = c.rule, onSurfaceVariant = c.muted, outline = Color(0xFF9AA0A7),
        surfaceContainer = Color(0xFFF6F7F8), surfaceContainerHigh = Color(0xFFFFFFFF),
        error = c.high,
    )
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
