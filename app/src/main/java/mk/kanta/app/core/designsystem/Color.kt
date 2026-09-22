package mk.kanta.app.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Raw colour tokens from KANTA_SPEC.md §3.1.
 * These are the only place hex values are allowed to live.
 */

// --- Light ---
val BrandLight = Color(0xFF1F5A3A)
val AccentLight = Color(0xFFE0B41C)
val BackgroundLight = Color(0xFFF3F5F2)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceMutedLight = Color(0xFFEAEEE9)
val OnSurfaceLight = Color(0xFF141A16)
val OnSurfaceMutedLight = Color(0xFF5E6A62)
val OutlineLight = Color(0xFFD8DED7)

// --- Dark ---
val BrandDark = Color(0xFF7CC79A)
val AccentDark = Color(0xFFF0C94A)
val BackgroundDark = Color(0xFF0F1411)
val SurfaceDark = Color(0xFF171D19)
val SurfaceMutedDark = Color(0xFF1F2622)
val OnSurfaceDark = Color(0xFFE7ECE8)
val OnSurfaceMutedDark = Color(0xFF98A49C)
val OutlineDark = Color(0xFF2B342E)

/**
 * Marker colours (spec §3.1) — the only loud colours in the app.
 * Theme-independent: a full container is the same orange in light and dark.
 */
object MarkerColors {
    val BigOk = Color(0xFF2E7D4F)
    val SmallOk = Color(0xFFE0B41C)
    val Full = Color(0xFFE8772E)
    val Broken = Color(0xFFC0392B)
    val Destroyed = Color(0xFF8A918C)
    val Missing = Color(0xFF8A918C)
    val Suggestion = Color(0xFF1F5A3A)
}
