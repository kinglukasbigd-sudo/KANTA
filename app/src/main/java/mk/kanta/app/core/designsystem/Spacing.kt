package mk.kanta.app.core.designsystem

import androidx.compose.ui.unit.dp

/** Spacing scale and screen padding from KANTA_SPEC.md §3.3. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Screen side padding. */
    val screenHorizontal = 20.dp

    /** Spec §8: every touch target is at least this tall. */
    val minTouchTarget = 48.dp

    /** Spec §3.3: separate things with 1dp hairlines, not elevation. */
    val hairline = 1.dp
}
