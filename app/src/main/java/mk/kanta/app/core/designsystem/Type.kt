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
 * Typography from KANTA_SPEC.md §3.2.
 *
 * Nunito ships from Google Fonts as a single variable font (`wght` axis 200..1000), so all four
 * weights the spec asks for come from one 277 KB file instead of four statics. Verified to cover
 * Macedonian Cyrillic (including Ќ/Џ) and Albanian ë/ç. Licence: assets/fonts/NUNITO_OFL_LICENSE.txt
 */
private fun nunito(weight: FontWeight) = Font(
    resId = R.font.nunito,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Nunito = FontFamily(
    nunito(FontWeight.Normal),     // 400
    nunito(FontWeight.SemiBold),   // 600
    nunito(FontWeight.Bold),       // 700
    nunito(FontWeight.ExtraBold),  // 800
)

/** Kept as an alias so call sites read as "the app font". */
val KantaFontFamily: FontFamily = Nunito

/**
 * Trim the extra first-line/last-line padding Android adds, so our generous spacing scale
 * (§3.3) is what actually shows up rather than being padded by font metrics.
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
) = TextStyle(
    fontFamily = Nunito,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = KantaLineHeightStyle,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * The spec's six-step scale, mapped onto the Material 3 slots that components actually read.
 * Each spec step is filled in at more than one slot so that stock M3 components (which pick
 * their own slot) still land on a Kanta style rather than a Material default.
 */
val KantaTypography = Typography(
    // --- Display 34 / 800 ---
    displayLarge = kantaStyle(34, 40, FontWeight.ExtraBold),
    displayMedium = kantaStyle(34, 40, FontWeight.ExtraBold),
    displaySmall = kantaStyle(34, 40, FontWeight.ExtraBold),

    // --- Title 22 / 700 ---
    headlineLarge = kantaStyle(22, 28, FontWeight.Bold),
    headlineMedium = kantaStyle(22, 28, FontWeight.Bold),
    titleLarge = kantaStyle(22, 28, FontWeight.Bold),

    // --- Headline 18 / 700 ---
    headlineSmall = kantaStyle(18, 24, FontWeight.Bold),
    titleMedium = kantaStyle(18, 24, FontWeight.Bold),

    // --- Body 16 / 400 ---
    bodyLarge = kantaStyle(16, 24, FontWeight.Normal),
    bodyMedium = kantaStyle(16, 24, FontWeight.Normal),

    // --- Label 14 / 600 ---
    titleSmall = kantaStyle(14, 20, FontWeight.SemiBold),
    labelLarge = kantaStyle(14, 20, FontWeight.SemiBold),
    bodySmall = kantaStyle(14, 20, FontWeight.Normal),

    // --- Caption 12 / 600 / +0.4 tracking (small uppercase labels) ---
    labelMedium = kantaStyle(12, 16, FontWeight.SemiBold, letterSpacing = 0.4),
    labelSmall = kantaStyle(12, 16, FontWeight.SemiBold, letterSpacing = 0.4),
)

/**
 * Spec §3.2: "Numbers in stats use tabular figures where available."
 * Nunito ships the `tnum` OpenType feature; apply this to any style showing a figure that
 * changes (counters, hours-to-resolve, distances) so digits do not jitter.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
