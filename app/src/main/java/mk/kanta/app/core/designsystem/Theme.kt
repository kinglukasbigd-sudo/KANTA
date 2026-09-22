package mk.kanta.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import mk.kanta.app.core.data.prefs.ThemeMode

/**
 * Kanta theme.
 *
 * Spec §9.4: no default Material purple anywhere. Dynamic colour is deliberately NOT used —
 * the brand green is the identity, and on the map it has to stay predictable.
 *
 * Every M3 role is assigned explicitly below. Material's defaults for any role left unset are
 * purple, so roles we do not use (tertiary, inverse*) are pointed at Kanta tokens rather than
 * left to default.
 */

private val LightColors = lightColorScheme(
    primary = BrandLight,
    onPrimary = Color.White,
    primaryContainer = SurfaceMutedLight,
    onPrimaryContainer = BrandLight,
    inversePrimary = BrandDark,

    secondary = AccentLight,
    onSecondary = OnSurfaceLight,
    secondaryContainer = SurfaceMutedLight,
    onSecondaryContainer = OnSurfaceLight,

    // Not used by the design; pointed at brand tokens so nothing can fall back to purple.
    tertiary = BrandLight,
    onTertiary = Color.White,
    tertiaryContainer = SurfaceMutedLight,
    onTertiaryContainer = BrandLight,

    background = BackgroundLight,
    onBackground = OnSurfaceLight,

    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceMutedLight,
    onSurfaceVariant = OnSurfaceMutedLight,
    surfaceTint = BrandLight,
    inverseSurface = OnSurfaceLight,
    inverseOnSurface = BackgroundLight,

    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = BackgroundLight,
    surfaceContainer = SurfaceMutedLight,
    surfaceContainerHigh = SurfaceMutedLight,
    surfaceContainerHighest = SurfaceMutedLight,
    surfaceBright = SurfaceLight,
    surfaceDim = SurfaceMutedLight,

    error = MarkerColors.Broken,
    onError = Color.White,
    errorContainer = SurfaceMutedLight,
    onErrorContainer = MarkerColors.Broken,

    outline = OutlineLight,
    outlineVariant = OutlineLight,
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = BackgroundDark,
    primaryContainer = SurfaceMutedDark,
    onPrimaryContainer = BrandDark,
    inversePrimary = BrandLight,

    secondary = AccentDark,
    onSecondary = BackgroundDark,
    secondaryContainer = SurfaceMutedDark,
    onSecondaryContainer = OnSurfaceDark,

    tertiary = BrandDark,
    onTertiary = BackgroundDark,
    tertiaryContainer = SurfaceMutedDark,
    onTertiaryContainer = BrandDark,

    background = BackgroundDark,
    onBackground = OnSurfaceDark,

    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = OnSurfaceMutedDark,
    surfaceTint = BrandDark,
    inverseSurface = OnSurfaceDark,
    inverseOnSurface = BackgroundDark,

    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = BackgroundDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceMutedDark,
    surfaceContainerHighest = SurfaceMutedDark,
    surfaceBright = SurfaceMutedDark,
    surfaceDim = BackgroundDark,

    error = MarkerColors.Broken,
    onError = Color.White,
    errorContainer = SurfaceMutedDark,
    onErrorContainer = MarkerColors.Broken,

    outline = OutlineDark,
    outlineVariant = OutlineDark,
    scrim = Color.Black,
)

/**
 * Tokens that have no honest Material 3 equivalent, so components read them from here instead
 * of bending an M3 role out of shape.
 */
data class KantaColors(
    val brand: Color,
    val accent: Color,
    val surfaceMuted: Color,
    val onSurfaceMuted: Color,
    val outline: Color,
    val isDark: Boolean,
)

private val LightKantaColors = KantaColors(
    brand = BrandLight,
    accent = AccentLight,
    surfaceMuted = SurfaceMutedLight,
    onSurfaceMuted = OnSurfaceMutedLight,
    outline = OutlineLight,
    isDark = false,
)

private val DarkKantaColors = KantaColors(
    brand = BrandDark,
    accent = AccentDark,
    surfaceMuted = SurfaceMutedDark,
    onSurfaceMuted = OnSurfaceMutedDark,
    outline = OutlineDark,
    isDark = true,
)

private val LocalKantaColors = staticCompositionLocalOf { LightKantaColors }

/** Kanta-specific tokens: `KantaTheme.colors.brand`. */
object KantaTheme {
    val colors: KantaColors
        @Composable @ReadOnlyComposable get() = LocalKantaColors.current
}

/** Resolves the stored override against the system setting. */
@Composable
fun ThemeMode.resolveIsDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun KantaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    KantaTheme(darkTheme = themeMode.resolveIsDark(), content = content)
}

/**
 * Direct dark/light entry point — used by previews and the design gallery.
 * [darkTheme] has no default on purpose: with one, `KantaTheme { }` would be ambiguous against
 * the [ThemeMode] overload above.
 */
@Composable
fun KantaTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKantaColors provides if (darkTheme) DarkKantaColors else LightKantaColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = KantaTypography,
            shapes = KantaShapes,
            content = content,
        )
    }
}
