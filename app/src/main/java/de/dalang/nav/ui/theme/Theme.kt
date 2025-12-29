package de.dalang.nav.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Minimalistische Farbpalette
private val Primary = Color(0xFF1976D2)
private val OnPrimary = Color.White
private val Surface = Color.White
private val OnSurface = Color(0xFF1A1A1A)
private val SurfaceDark = Color(0xFF121212)
private val OnSurfaceDark = Color(0xFFE0E0E0)
private val Background = Color(0xFFF5F5F5)
private val BackgroundDark = Color(0xFF1A1A1A)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    surface = Surface,
    onSurface = OnSurface,
    background = Background,
    onBackground = OnSurface,
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF666666)
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFAAAAAA)
)

@Composable
fun DaLangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

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
        content = content
    )
}
