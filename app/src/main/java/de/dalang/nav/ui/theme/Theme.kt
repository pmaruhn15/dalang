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
import de.dalang.nav.util.CrashLogger

// Minimalistische Farbpalette
private val Primary = Color(0xFF1976D2)
private val OnPrimary = Color.White
private val Surface = Color.White
private val OnSurface = Color(0xFF1A1A1A)
private val SurfaceDark = Color(0xFF121212)
private val OnSurfaceDark = Color(0xFFE0E0E0)
private val Background = Color(0xFFF5F5F5)
private val BackgroundDark = Color(0xFF1A1A1A)

/**
 * Theme-aware Farben für Map-Elemente (Route, Marker-Labels, etc.)
 * Diese werden außerhalb von Compose verwendet (Canvas/Bitmaps)
 */
object MapColors {
    // Route-Farbe: Weiß auf dunkler Karte, Schwarz auf heller Karte
    fun routeColor(isDark: Boolean): Int =
        if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK

    // Traffic-Farben (Google Maps Stil)
    val TRAFFIC_GREEN: Int = android.graphics.Color.parseColor("#4CAF50")   // Frei fließend
    val TRAFFIC_YELLOW: Int = android.graphics.Color.parseColor("#FFC107")  // Leicht verlangsamt
    val TRAFFIC_ORANGE: Int = android.graphics.Color.parseColor("#FF9800")  // Zähfließend
    val TRAFFIC_RED: Int = android.graphics.Color.parseColor("#F44336")     // Stau

    // Label-Hintergrund: Dunkel auf dunkler Karte, Hell auf heller Karte
    fun labelBackground(isDark: Boolean): Int =
        if (isDark) android.graphics.Color.parseColor("#1A1A1A") else android.graphics.Color.parseColor("#FFFFFF")

    // Label-Text: Weiß auf dunkler Karte, Schwarz auf heller Karte
    fun labelText(isDark: Boolean): Int =
        if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK

    // Label-Rand: Weiß auf dunkler Karte, Dunkelgrau auf heller Karte
    fun labelBorder(isDark: Boolean): Int =
        if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#424242")

    // Sekundärer Text (z.B. Umwegzeit): Hellgrau auf dunkler Karte, Dunkelgrau auf heller Karte
    fun labelSecondaryText(isDark: Boolean): Int =
        if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY
}

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
    darkThemeOverride: Boolean? = null,  // null = System entscheidet
    content: @Composable () -> Unit
) {
    val darkTheme = darkThemeOverride ?: isSystemInDarkTheme()
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            try {
                // Sicherer Cast mit Nullcheck
                val activity = view.context as? Activity
                if (activity != null) {
                    val window = activity.window
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Color.Transparent.toArgb()
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            } catch (e: Exception) {
                CrashLogger.logError("Theme", "Window configuration failed", e)
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
