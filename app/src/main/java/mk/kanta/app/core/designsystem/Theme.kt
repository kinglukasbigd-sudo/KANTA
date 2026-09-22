package mk.kanta.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Kanta theme. Spec §9.4: no default Material purple anywhere — every colour comes from the
 * tokens in [Color.kt]. Dynamic colour is deliberately NOT used: the brand green is the identity.
 */

private val LightColors = lightColorScheme(
    primary = BrandLight,
    onPrimary = SurfaceLight,
    secondary = AccentLight,
    onSecondary = OnSurfaceLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceMutedLight,
    onSurfaceVariant = OnSurfaceMutedLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = BackgroundDark,
    secondary = AccentDark,
    onSecondary = BackgroundDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = OnSurfaceMutedDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
)

@Composable
fun KantaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KantaTypography,
        shapes = KantaShapes,
        content = content,
    )
}
