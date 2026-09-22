package mk.kanta.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii from KANTA_SPEC.md §3.3: 12dp chips/inputs, 20dp cards,
 * 28dp bottom-sheet top corners, big buttons = full pill.
 */
object KantaShape {
    /** Chips, inputs. */
    val chip = RoundedCornerShape(12.dp)

    /** Cards, tiles. */
    val card = RoundedCornerShape(20.dp)

    /** Large surfaces. */
    val sheet = RoundedCornerShape(28.dp)

    /** Bottom sheet — only the top corners are rounded. */
    val bottomSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Big buttons are full pills, so the radius tracks the height. */
    val pill = RoundedCornerShape(percent = 50)
}

val KantaShapes = Shapes(
    extraSmall = KantaShape.chip,
    small = KantaShape.chip,
    medium = KantaShape.card,
    large = KantaShape.sheet,
    extraLarge = KantaShape.sheet,
)
