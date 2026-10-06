package mk.kanta.app.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus

/**
 * Raw colour tokens (KANTA_SPEC.md §3.1).
 * This file is the only place hex values are allowed to live.
 *
 * The palette mixes Kanta's green with a warm editorial base: ivory paper and carbon ink with a
 * green undertone, oat hairlines, tiger gold and ember for the loud moments, and highlighter mint
 * as the dark theme's green. Every text pairing below is at least WCAG AA (4.5:1).
 */

// --- Light: ivory paper, carbon ink, forest green ---
val BrandLight = Color(0xFF1F5A3A)            // forest green — primary actions (8.1:1 with white)
val BrandContainerLight = Color(0xFFE2FFCC)   // highlighter mint — selected surfaces (forest on it 7.5:1)
val AccentLight = Color(0xFFFAAE33)           // tiger gold
val BackgroundLight = Color(0xFFFAF9F5)       // ivory
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceMutedLight = Color(0xFFF0EEE6)     // ivory, one step down
val OnSurfaceLight = Color(0xFF161B13)        // carbon ink
val OnSurfaceMutedLight = Color(0xFF655A4D)   // driftwood (5.8:1 on SurfaceMuted)
val OutlineLight = Color(0xFFE3DACC)          // oat — hairlines
val OutlineStrongLight = Color(0xFFB0AEA5)    // stone — button and tile borders
val ErrorLight = Color(0xFFC21F52)            // chili (5.5:1 on ivory)

// --- Dark: carbon ink, forest charcoal, highlighter mint ---
val BrandDark = Color(0xFFE2FFCC)             // highlighter mint — primary actions (ink on it 16:1)
val BrandContainerDark = Color(0xFF26331F)    // green-tinted charcoal — selected surfaces
val AccentDark = Color(0xFFFAAE33)
val BackgroundDark = Color(0xFF161B13)        // carbon ink
val SurfaceDark = Color(0xFF1E241B)
val SurfaceMutedDark = Color(0xFF2D3329)      // forest charcoal
val OnSurfaceDark = Color(0xFFF0EEE6)         // ivory
val OnSurfaceMutedDark = Color(0xFFA3AD9E)    // sage, lifted (5.6:1 on SurfaceMuted)
val OutlineDark = Color(0xFF343B30)
val OutlineStrongDark = Color(0xFF4F5849)
val ErrorDark = Color(0xFFF0628F)             // chili, lifted (5.7:1 on carbon)

/**
 * Marker colours (spec §3.1) — the only loud colours in the app.
 *
 * Deliberately theme-independent: a full container must read as the same ember in light and
 * dark, because the map tiles underneath it barely change. What does depend on the theme is the
 * ring around every marker ([ring]), which is what keeps a marker legible on either tile set.
 */
object MarkerColors {
    /** Big container, no open reports — Kanta green. */
    val BigOk = Color(0xFF2E7D4F)

    /** Small street can, no open reports — tiger gold. */
    val SmallOk = Color(0xFFFAAE33)

    /**
     * A bin nobody has given the size of yet (§4.6 "Bin size") — forest charcoal, so the
     * universal marker reads as "still to be checked" and the checked ones stand out in colour.
     */
    val UnknownOk = Color(0xFF2D3329)

    /** Full — any container type. Ember. */
    val Full = Color(0xFFDC5000)

    /** Damaged or burning. Chili red. */
    val Broken = Color(0xFFD1255C)

    /** Destroyed — filled stone grey. */
    val Destroyed = Color(0xFF87867F)

    /** Missing — same grey, but drawn hollow + dashed. */
    val Missing = Color(0xFF87867F)

    /** "A container should be here" — brand green, hollow, with a plus. */
    val Suggestion = Color(0xFF1F5A3A)

    /**
     * The one mapping from status to colour, shared by the map markers and every in-app dot.
     * OK is the only status whose colour depends on the size: green for big, gold for small,
     * charcoal while nobody has said. Every problem reads the same whatever the size.
     */
    fun status(status: ContainerStatus, kind: ContainerKind): Color = when (status) {
        ContainerStatus.OK -> when (kind) {
            ContainerKind.BIG -> BigOk
            ContainerKind.SMALL -> SmallOk
            ContainerKind.UNKNOWN -> UnknownOk
        }
        ContainerStatus.FULL -> Full
        ContainerStatus.BROKEN -> Broken
        ContainerStatus.DESTROYED -> Destroyed
        ContainerStatus.MISSING -> Missing
    }

    /** The glyph drawn on a marker of this status and size. */
    fun glyph(status: ContainerStatus, kind: ContainerKind): Color =
        if (status == ContainerStatus.OK && kind == ContainerKind.UNKNOWN) {
            GlyphUnknown
        } else {
            glyphOn(this.status(status, kind))
        }

    /** The bin glyph on light fills (gold, grey) and on dark ones (green, ember, chili). */
    val GlyphInk = Color(0xFF161B13)
    val GlyphLight = Color(0xFFFFFFFF)

    /** The universal marker's glyph: highlighter mint on charcoal (12:1). */
    val GlyphUnknown = Color(0xFFE2FFCC)

    /**
     * The ring around every marker: white on light tiles, ivory on dark ones. A light ring on
     * dark tiles is what keeps the charcoal universal marker visible at night.
     */
    fun ring(darkTheme: Boolean): Color = if (darkTheme) OnSurfaceDark else Color.White

    /** A soft ink hairline outside the ring, so gold and grey hold up on pale tiles. */
    val Shadow = Color(0x38161B13)

    /** Kept for callers that predate [ring]. */
    fun fullBorder(darkTheme: Boolean): Color = ring(darkTheme)

    /** Recycling material dots (spec §3.4), shown only at zoom >= 17. */
    val RecyclingGlass = Color(0xFF2E7D4F)
    val RecyclingPaper = Color(0xFF2F6FB0)
    val RecyclingPlastic = Color(0xFFFAAE33)

    /**
     * Ink or white, whichever reads better on [fill]. The crossover is where both contrasts are
     * equal (relative luminance ≈ 0.2); ember sits just under it and keeps white, the classic
     * pairing, while gold and grey take ink.
     */
    fun glyphOn(fill: Color): Color = if (fill.luminance() > 0.225f) GlyphInk else GlyphLight
}
