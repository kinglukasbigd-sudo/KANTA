package mk.kanta.app.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography from KANTA_SPEC.md §3.2.
 *
 * TODO(design-system): swap [KantaFontFamily] for Nunito (weights 400/600/700/800, Cyrillic +
 * Latin Extended). Everything else here already matches the spec scale, so bundling the font
 * files is a one-line change.
 */
val KantaFontFamily: FontFamily = FontFamily.Default

val KantaTypography = Typography(
    // Display 34 / 800
    displayLarge = TextStyle(
        fontFamily = KantaFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    // Title 22 / 700
    titleLarge = TextStyle(
        fontFamily = KantaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // Headline 18 / 700
    headlineSmall = TextStyle(
        fontFamily = KantaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    // Body 16 / 400
    bodyLarge = TextStyle(
        fontFamily = KantaFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // Label 14 / 600
    labelLarge = TextStyle(
        fontFamily = KantaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Caption 12 / 600 / +0.4 letter-spacing (small uppercase labels)
    labelSmall = TextStyle(
        fontFamily = KantaFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)
