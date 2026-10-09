package com.snowkey.viday.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8150F7),
    secondary = Color(0xFF222533),
    tertiary = Color(0xFF2A3544),
    background = Color(0xFF111218),
    surface = Color(0xFF1C1F2B),
    onPrimary = Color.White,
    onSecondary = Color(0xFF3B81F4),
    onTertiary = Color.Black,
    onBackground = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFFCFCFCF),
    onSurface = Color(0xFFBFBFBF),
    error = Color(0xFFEC4343),
    onError = Color.Black,
    primaryContainer = Color(0xFF292929),
    secondaryContainer = Color(0xFF212121),
    surfaceDim = Color.Black,
    scrim = Color(0xFF25AF5D)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6F3CD4),
    secondary = Color(0xFFEDF4FD),
    tertiary = Color(0xFFEDF4FD),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFDFDFD),
    onPrimary = Color.White,
    onSecondary = Color(0xFF3B81F4),
    onTertiary = Color.Black,
    onBackground = Color(0xFF1E293B),
    onSurface = Color(0xFF63738A),
    error = Color(0xFFEC4343),
    onError = Color.White,
    primaryContainer = Color(0xFFE2E8F0),
    secondaryContainer = Color(0xFFCBD5E1),
    surfaceDim = Color.Black,
    scrim = Color(0xFF25AF5D)
)

@Composable
fun VidayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VidayTypography,
        content = content
    )
}
