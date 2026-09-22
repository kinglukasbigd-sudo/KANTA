package mk.kanta.app.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Raw colour tokens from KANTA_SPEC.md §3.1.
 * This file is the only place hex values are allowed to live.
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
 *
 * Deliberately theme-independent: a full container must read as the same orange in light and
 * dark, because the map tiles underneath it barely change. The only theme-dependent part is
 * [fullBorder], the 2dp border that keeps orange (full) distinct from yellow (small can OK).
 */
object MarkerColors {
    /** Big container, no open reports. */
    val BigOk = Color(0xFF2E7D4F)

    /** Small street can, no open reports. */
    val SmallOk = Color(0xFFE0B41C)

    /** Full — any container type. */
    val Full = Color(0xFFE8772E)

    /** Damaged or burning. */
    val Broken = Color(0xFFC0392B)

    /** Destroyed — filled grey. */
    val Destroyed = Color(0xFF8A918C)

    /** Missing — same grey, but drawn hollow + dashed. */
    val Missing = Color(0xFF8A918C)

    /** "A container should be here" — brand green, hollow, with a plus. */
    val Suggestion = Color(0xFF1F5A3A)

    /** Spec §3.1: white border in light, background colour in dark. */
    fun fullBorder(darkTheme: Boolean): Color = if (darkTheme) BackgroundDark else Color.White

    /** Recycling material dots (spec §3.4), shown only at zoom >= 17. */
    val RecyclingGlass = Color(0xFF2E7D4F)
    val RecyclingPaper = Color(0xFF2F6FB0)
    val RecyclingPlastic = Color(0xFFE0B41C)
}
