package com.localtranscribe.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF35486F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF0B1B3B),
    secondary = Color(0xFF4E6472),
    secondaryContainer = Color(0xFFD2E7F5),
    onSecondaryContainer = Color(0xFF0A1F2B),
    tertiary = Color(0xFF8A4F2B),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE3E6EE),
    onSurfaceVariant = Color(0xFF454A55),
    surfaceContainer = Color(0xFFF0F2F7),
    surfaceContainerHigh = Color(0xFFE9ECF3),
    outline = Color(0xFF767B86),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C6F2),
    onPrimary = Color(0xFF1B2C4E),
    primaryContainer = Color(0xFF34456B),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFB6CBD8),
    secondaryContainer = Color(0xFF364B57),
    onSecondaryContainer = Color(0xFFD2E7F5),
    tertiary = Color(0xFFFFB68F),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE3E4E8),
    surface = Color(0xFF17191E),
    onSurface = Color(0xFFE3E4E8),
    surfaceVariant = Color(0xFF33363E),
    onSurfaceVariant = Color(0xFFC4C7D0),
    surfaceContainer = Color(0xFF1D1F24),
    surfaceContainerHigh = Color(0xFF26282F),
    outline = Color(0xFF8E919B),
    error = Color(0xFFFFB4AB),
)

@Composable
fun LocalTranscribeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
