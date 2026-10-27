package fi.tunturi.rows.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 0 = follow system, 1 = force dark, 2 = force light. Stored in preferences. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

private val Dark = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF00293B),
    primaryContainer = Color(0xFF11455F),
    onPrimaryContainer = Color(0xFFCBEBFF),
    secondary = Color(0xFF8FE3C4),
    onSecondary = Color(0xFF00382A),
    background = Color(0xFF0A0F18),
    onBackground = Color(0xFFE8EEF8),
    surface = Color(0xFF121A27),
    onSurface = Color(0xFFE8EEF8),
    surfaceVariant = Color(0xFF1B2536),
    onSurfaceVariant = Color(0xFFA9B7CC),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B6E99),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6E7FF),
    onPrimaryContainer = Color(0xFF001E2B),
    secondary = Color(0xFF1F7A5F),
    onSecondary = Color.White,
    background = Color(0xFFF7FAFF),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE3EAF3),
    onSurfaceVariant = Color(0xFF46536A),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
)

@Composable
fun TunturiTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = AppShapes,
        typography = Typography(),
        content = content,
    )
}
