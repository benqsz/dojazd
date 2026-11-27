package app.dojazd.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import app.dojazd.R
import app.dojazd.data.ThemeMode
import app.dojazd.data.ThemeSettings
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import android.graphics.Color as AndroidColor

class SeedColorChoice(
    val color: Color,
    @param:StringRes val nameRes: Int,
)

/**
 * Seed colours offered in settings: a few hues spread around the colour wheel so
 * the resulting themes are clearly different, plus black for a monochrome theme.
 * Red and pink are not among them: red is the colour of live data, whatever the theme.
 */
val SeedColorChoices =
    listOf(
        SeedColorChoice(Color(0xFF1565C0), R.string.color_blue),
        SeedColorChoice(Color(0xFF2E7D32), R.string.color_green),
        SeedColorChoice(Color(0xFFEF6C00), R.string.color_orange),
        SeedColorChoice(Color(0xFF7B1FA2), R.string.color_purple),
        SeedColorChoice(Color(0xFF00796B), R.string.color_teal),
        SeedColorChoice(Color(0xFFF9A825), R.string.color_yellow),
        SeedColorChoice(Color(0xFF5D4037), R.string.color_brown),
        SeedColorChoice(Color(0xFF455A64), R.string.color_slate),
        SeedColorChoice(Color.Black, R.string.color_black),
    )

/** Used when the user has not picked a colour and the system offers none (before Android 12). */
val DefaultSeedColor = SeedColorChoices.first().color

val supportsSystemColors: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Material 3 theme with a user-selectable mode, seed colour and optional high contrast.
 *
 * System colours at standard contrast use the platform palette directly. Any
 * other combination is generated from a seed colour, because the platform
 * palette cannot be asked for a higher contrast. High contrast is the app's own
 * setting, off by default; the system's contrast settings are not followed.
 *
 * Black is not a hue, so it selects a greyscale palette; in dark mode it also
 * uses a true black background.
 */
@Composable
fun DojazdTheme(
    settings: ThemeSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark =
        when (settings.mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    // The system bar icons follow the device's mode unless told which theme is on screen.
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { dark },
        )
    }
    val contrast = if (settings.highContrast) HIGH_CONTRAST else STANDARD_CONTRAST

    val colorScheme =
        if (settings.seedColor == null && supportsSystemColors && !settings.highContrast) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            val seed =
                when {
                    settings.seedColor != null -> Color(settings.seedColor)
                    supportsSystemColors -> dynamicLightColorScheme(context).primary
                    else -> DefaultSeedColor
                }
            val monochrome = seed.toArgb() == Color.Black.toArgb()
            rememberDynamicColorScheme(
                seedColor = seed,
                isDark = dark,
                isAmoled = monochrome,
                style = if (monochrome) PaletteStyle.Monochrome else PaletteStyle.TonalSpot,
                contrastLevel = contrast,
            )
        }

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialExpressiveTheme(colorScheme = colorScheme, content = content)
    }
}

/** True when the dark theme is on screen, whether by the user's choice or the system's. */
val LocalDarkTheme = staticCompositionLocalOf { false }

// The scrims enableEdgeToEdge() uses by default behind three-button navigation.
private val LIGHT_NAVIGATION_SCRIM = AndroidColor.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DARK_NAVIGATION_SCRIM = AndroidColor.argb(0x80, 0x1B, 0x1B, 0x1B)

private const val STANDARD_CONTRAST = 0.0
private const val HIGH_CONTRAST = 1.0
