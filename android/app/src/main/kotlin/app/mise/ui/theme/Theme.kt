package app.mise.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.mise.data.settings.ThemeMode

// A warm, spice-inspired fallback palette for devices without dynamic color (Android < 12).
private val Light = lightColorScheme(
    primary = Color(0xFF9A4521), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCE), onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCE), onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF6A5E2F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E2A7), onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFFFFBF8), onBackground = Color(0xFF231917),
    surface = Color(0xFFFFFBF8), onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED6), onSurfaceVariant = Color(0xFF53433E),
    error = Color(0xFFBA1A1A), onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB598), onPrimary = Color(0xFF5B1A00),
    primaryContainer = Color(0xFF7A2E0B), onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFE7BEAE), onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035), onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFFD6C68D), onTertiary = Color(0xFF3A3005),
    tertiaryContainer = Color(0xFF51461A), onTertiaryContainer = Color(0xFFF3E2A7),
    background = Color(0xFF1A1110), onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF1A1110), onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433E), onSurfaceVariant = Color(0xFFD8C2BA),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * [textScale] (Settings → Text size) multiplies the *system* font scale, so both apply.
 */
@Composable
fun MiseTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, textScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = isDark(themeMode)
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
