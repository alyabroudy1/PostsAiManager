package com.postsaimanager.core.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PamBlue,
    onPrimary = Color.White,
    primaryContainer = PamBlueContainer,
    onPrimaryContainer = PamBlueDark,
    secondary = PamTeal,
    onSecondary = Color.White,
    secondaryContainer = PamTealContainer,
    onSecondaryContainer = PamTealDarkContainer,
    tertiary = PamAmber,
    onTertiary = Color.White,
    tertiaryContainer = PamAmberContainer,
    onTertiaryContainer = PamAmberDarkContainer,
    error = PamRed,
    onError = Color.White,
    errorContainer = PamRedContainer,
    onErrorContainer = PamRedDarkContainer,
    background = PamGray50,
    onBackground = PamGray900,
    surface = Color.White,
    onSurface = PamGray900,
    surfaceVariant = PamGray100,
    onSurfaceVariant = PamGray600,
    outline = PamGray300,
    outlineVariant = PamGray200,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = PamGray50,
    surfaceContainer = PamGray100,
    surfaceContainerHigh = PamGray200,
    surfaceContainerHighest = PamGray300,
)

private val DarkColorScheme = darkColorScheme(
    primary = PamBlueLight,
    onPrimary = PamBlueDark,
    primaryContainer = PamBlueDarkContainer,
    onPrimaryContainer = PamBlueContainer,
    secondary = PamTealLight,
    onSecondary = PamTealDarkContainer,
    secondaryContainer = PamTealDarkContainer,
    onSecondaryContainer = PamTealLight,
    tertiary = PamAmber,
    onTertiary = PamAmberDarkContainer,
    tertiaryContainer = PamAmberDarkContainer,
    onTertiaryContainer = PamAmber,
    error = PamRedLight,
    onError = PamRedDarkContainer,
    errorContainer = PamRedDarkContainer,
    onErrorContainer = PamRedLight,
    background = PamSurfaceDark,
    onBackground = PamGray100,
    surface = PamSurfaceDark,
    onSurface = PamGray100,
    surfaceVariant = PamSurfaceContainerDark,
    onSurfaceVariant = PamGray400,
    outline = PamGray600,
    outlineVariant = PamGray700,
    surfaceContainerLowest = PamGray950,
    surfaceContainerLow = PamSurfaceDark,
    surfaceContainer = PamSurfaceContainerDark,
    surfaceContainerHigh = PamSurfaceContainerHighDark,
    surfaceContainerHighest = PamGray700,
)

/**
 * Posts AI Manager theme.
 *
 * @param darkTheme Whether to use dark theme
 * @param dynamicColor Whether to use Material You dynamic color (Android 12+)
 */
@Composable
fun PamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Off by default for consistent brand identity
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Update system bar colors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PamTypography,
        content = content,
    )
}
