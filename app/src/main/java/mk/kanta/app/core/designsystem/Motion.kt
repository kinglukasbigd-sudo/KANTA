package mk.kanta.app.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion from KANTA_SPEC.md §3.3: 200–300ms, FastOutSlowIn / spring (dampingRatio 0.85).
 *
 * "Respect the remove-animations system setting" is handled by the platform: Compose scales
 * these durations by Settings.Global.ANIMATOR_DURATION_SCALE, so when a user turns animations
 * off these all collapse to zero without any extra branching here.
 */
object Motion {

    /** Fast feedback: selection, chip toggles. */
    const val DurationFast = 200

    /** Default transition: sheets, fades, marker appearance. */
    const val DurationMedium = 250

    /** The slow end of the spec's range: larger surfaces. */
    const val DurationSlow = 300

    /** Material's FastOutSlowIn curve, spelled out so it cannot drift. */
    val FastOutSlowIn: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Spec §3.3 spring: dampingRatio 0.85. */
    const val SpringDamping = 0.85f

    fun <T> tweenFast() = tween<T>(durationMillis = DurationFast, easing = FastOutSlowIn)

    fun <T> tweenMedium() = tween<T>(durationMillis = DurationMedium, easing = FastOutSlowIn)

    fun <T> tweenSlow() = tween<T>(durationMillis = DurationSlow, easing = FastOutSlowIn)

    fun <T> spring() = spring<T>(
        dampingRatio = SpringDamping,
        stiffness = Spring.StiffnessMediumLow,
    )
}
