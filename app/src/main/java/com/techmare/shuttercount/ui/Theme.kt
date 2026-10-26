package com.techmare.shuttercount.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFF5B400)
private val AmberDark = Color(0xFF7A5900)

private val Light = lightColorScheme(
    primary = AmberDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF94),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6A5D3F),
    secondaryContainer = Color(0xFFF3E1BB),
    tertiary = Color(0xFF486648),
    tertiaryContainer = Color(0xFFCAECC7),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFFFF8F1),
    surface = Color(0xFFFFF8F1),
    surfaceVariant = Color(0xFFEDE1CF),
    surfaceContainer = Color(0xFFF7EDDF),
    surfaceContainerHigh = Color(0xFFF1E7D9),
)

private val Dark = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5B4300),
    onPrimaryContainer = Color(0xFFFFDF94),
    secondary = Color(0xFFD7C5A0),
    secondaryContainer = Color(0xFF51462A),
    tertiary = Color(0xFFAED0AC),
    tertiaryContainer = Color(0xFF314E32),
    background = Color(0xFF17130B),
    surface = Color(0xFF17130B),
    surfaceVariant = Color(0xFF4D4639),
    surfaceContainer = Color(0xFF241F17),
    surfaceContainerHigh = Color(0xFF2F2921),
)

@Composable
fun ShutterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
