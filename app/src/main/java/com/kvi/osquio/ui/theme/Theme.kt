package com.kvi.osquio.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import com.kvi.osquio.R

enum class AppTheme { MIDNIGHT, TWILIGHT, DAWN, SPONKE }

// Pure black OLED theme — Dota crimson accents
private val MidnightColors = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF9E9E9E),
    primary = Color(0xFF752336),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3D0F1C),
    onPrimaryContainer = Color(0xFFFFB3BF),
    secondary = Color(0xFF9E4457),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2A0A12),
    onSecondaryContainer = Color(0xFFFFB3BF),
    outline = Color(0xFF2A1419),
    outlineVariant = Color(0xFF1E0D11),
)

// Dark indigo / deep purple theme
private val TwilightColors = darkColorScheme(
    background = Color(0xFF0D0D1A),
    surface = Color(0xFF13132B),
    surfaceVariant = Color(0xFF1C1C3A),
    onBackground = Color(0xFFE8E6FF),
    onSurface = Color(0xFFE8E6FF),
    onSurfaceVariant = Color(0xFF9B97CC),
    primary = Color(0xFF9F8FEF),
    onPrimary = Color(0xFF1A0060),
    outline = Color(0xFF2E2B52),
    outlineVariant = Color(0xFF232049),
)

// Warm light theme — Dota red accents
private val DawnColors = lightColorScheme(
    background = Color(0xFFF5F0EF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEE4E3),
    onBackground = Color(0xFF1F1412),
    onSurface = Color(0xFF1F1412),
    onSurfaceVariant = Color(0xFF6B4F4D),
    primary = Color(0xFFC0392B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF5C0A06),
    secondary = Color(0xFF9E3129),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF5C0A06),
    outline = Color(0xFFCDB3B1),
    outlineVariant = Color(0xFFE8D5D4),
)

// Childish bubblegum pink light theme — surface/surfaceVariant are semi-transparent so wallpaper shows through cards
private val SponkeColors = lightColorScheme(
    background = Color(0x00000000),     // transparent — wallpaper shows through Scaffold floor
    surface = Color(0xE6FFFFFF),        // 90% opaque white
    surfaceVariant = Color(0xE6FFE4EF), // 90% opaque pink tint
    onBackground = Color(0xFF4A1530),
    onSurface = Color(0xFF4A1530),
    onSurfaceVariant = Color(0xFFB06080),
    primary = Color(0xFFE91E8C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD6EA),
    onPrimaryContainer = Color(0xFF5C0030),
    secondary = Color(0xFFFF6BB5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD6EA),
    onSecondaryContainer = Color(0xFF5C0030),
    outline = Color(0xFFFFB3D4),
    outlineVariant = Color(0xFFFFD6EA),
)

object ThemeManager {
    var current by mutableStateOf(AppTheme.DAWN)
}

val LocalAppTheme = compositionLocalOf { AppTheme.MIDNIGHT }

@Composable
fun OsquioTheme(content: @Composable () -> Unit) {
    val theme = ThemeManager.current
    val colors = when (theme) {
        AppTheme.MIDNIGHT -> MidnightColors
        AppTheme.TWILIGHT -> TwilightColors
        AppTheme.DAWN -> DawnColors
        AppTheme.SPONKE -> SponkeColors
    }
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = colors) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFFF0F5))) {
                AsyncImage(
                    model = R.drawable.sponke_theme,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(if (theme == AppTheme.SPONKE) 0.4f else 0f),
                    contentScale = ContentScale.Crop,
                )
                content()
            }
        }
    }
}
