package mk.kanta.app.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import mk.kanta.app.R

/**
 * Typography (KANTA_SPEC.md §3.2): two families with two jobs.
 *
 *  - **Manrope** carries everything people read: headings set tight and heavy, body open and
 *    regular. A clean grotesque, so the app reads as a civic tool rather than a toy.
 *  - **JetBrains Mono** carries the field-guide chrome: section captions, container codes,
 *    distances, timestamps, counts. Monospaced, so figures never jitter.
 *
 * Both ship as single variable fonts from Google Fonts (`wght` axis), so every weight comes from
 * one file. Both cover Macedonian Cyrillic (including Ѓ, Ќ, Ѕ, Џ) and Albanian ë/ç, and Manrope has
 * the `tnum` feature [tabularFigures] relies on. Licences: the OFL_LICENSE files in assets/fonts.
 */
private fun manrope(weight: FontWeight) = Font(
    resId = R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun jetBrainsMono(weight: FontWeight) = Font(
    resId = R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.Normal),     // 400
    manrope(FontWeight.Medium),     // 500
    manrope(FontWeight.SemiBold),   // 600
    manrope(FontWeight.Bold),       // 700
    manrope(FontWeight.ExtraBold),  // 800
)

val KantaMono = FontFamily(
    jetBrainsMono(FontWeight.Normal),   // 400
    jetBrainsMono(FontWeight.Medium),   // 500
    jetBrainsMono(FontWeight.SemiBold), // 600
)

/** Kept as an alias so call sites read as "the app font". */
val KantaFontFamily: FontFamily = Manrope

/**
 * Trim the extra first-line/last-line padding Android adds, so our spacing scale (§3.3) is what
 * actually shows up rather than being padded by font metrics.
 */
private val KantaLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun kantaStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Double = 0.0,
    family: FontFamily = Manrope,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = KantaLineHeightStyle,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * The scale, mapped onto the Material 3 slots that components actually read. Each step is filled
 * in at more than one slot so that stock M3 components (which pick their own slot) still land on
 * a Kanta style rather than a Material default.
 *
 * Large sizes are set tight (negative tracking, line height close to the size) the way editorial
 * display type is; body text stays open for reading on the move.
 */
val KantaTypography = Typography(
    // --- Display 34 / 800, tight ---
    displayLarge = kantaStyle(34, 38, FontWeight.ExtraBold, letterSpacing = -0.8),
    displayMedium = kantaStyle(34, 38, FontWeight.ExtraBold, letterSpacing = -0.8),
    displaySmall = kantaStyle(34, 38, FontWeight.ExtraBold, letterSpacing = -0.8),

    // --- Title 22 / 800 ---
    headlineLarge = kantaStyle(22, 28, FontWeight.ExtraBold, letterSpacing = -0.4),
    headlineMedium = kantaStyle(22, 28, FontWeight.ExtraBold, letterSpacing = -0.4),
    titleLarge = kantaStyle(22, 28, FontWeight.ExtraBold, letterSpacing = -0.4),

    // --- Headline 18 / 700 ---
    headlineSmall = kantaStyle(18, 24, FontWeight.Bold, letterSpacing = -0.2),
    titleMedium = kantaStyle(18, 24, FontWeight.Bold, letterSpacing = -0.2),

    // --- Body 16 / 400 ---
    bodyLarge = kantaStyle(16, 24, FontWeight.Normal),
    bodyMedium = kantaStyle(16, 24, FontWeight.Normal),

    // --- Label 15 / 600 (buttons, row titles) ---
    titleSmall = kantaStyle(15, 20, FontWeight.SemiBold),
    labelLarge = kantaStyle(15, 20, FontWeight.SemiBold),
    bodySmall = kantaStyle(14, 20, FontWeight.Normal),

    // --- Caption: mono 11–12 / 500, tracked out (section captions, codes, metadata) ---
    labelMedium = kantaStyle(12, 16, FontWeight.Medium, letterSpacing = 0.4, family = KantaMono),
    labelSmall = kantaStyle(11, 14, FontWeight.Medium, letterSpacing = 0.8, family = KantaMono),
)

/**
 * Spec §3.2: "Numbers in stats use tabular figures where available."
 * Manrope ships the `tnum` OpenType feature (JetBrains Mono is tabular by nature); apply this to
 * any style showing a figure that changes (counters, hours-to-resolve, distances).
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Container codes ("SK-00412") and other identifiers: the mono face at this style's size. */
fun TextStyle.mono(): TextStyle = copy(fontFamily = KantaMono, letterSpacing = 0.sp)
