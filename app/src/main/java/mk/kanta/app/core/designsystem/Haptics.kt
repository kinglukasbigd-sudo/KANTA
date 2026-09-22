package mk.kanta.app.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Haptics from KANTA_SPEC.md §3.3: "light tick on selection, confirm pattern on successful send."
 *
 * Wraps [HapticFeedback] so call sites say what happened ([tick] / [success]) rather than naming
 * a platform constant, and so the mapping can change in one place.
 */
class KantaHaptics internal constructor(private val haptics: HapticFeedback) {

    /** Light tick — selection, chip toggle, marker tap. */
    fun tick() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Confirm — a report was sent, a vote landed. */
    fun success() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberKantaHaptics(): KantaHaptics {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { KantaHaptics(haptics) }
}
