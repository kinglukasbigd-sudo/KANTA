package mk.kanta.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii (KANTA_SPEC.md §3.3). Restrained and consistent: 8dp chips, 12dp buttons and
 * choice tiles, 16dp cards, 24dp sheet. Buttons are rounded rectangles rather than pills, which
 * reads as a tool rather than a toy and lines up with the cards they sit in.
 */
object KantaShape {
    /** Chips, inputs, small badges. */
    val chip = RoundedCornerShape(8.dp)

    /** Buttons and the Small/Big choice tiles. */
    val button = RoundedCornerShape(12.dp)

    /** Cards, tiles. */
    val card = RoundedCornerShape(16.dp)

    /** Large surfaces. */
    val sheet = RoundedCornerShape(24.dp)

    /** Bottom sheet — only the top corners are rounded. */
    val bottomSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    /** Fully round ends: status pills, the drag handle, compact actions. */
    val pill = RoundedCornerShape(percent = 50)
}

val KantaShapes = Shapes(
    extraSmall = KantaShape.chip,
    small = KantaShape.chip,
    medium = KantaShape.card,
    large = KantaShape.sheet,
    extraLarge = KantaShape.sheet,
)
