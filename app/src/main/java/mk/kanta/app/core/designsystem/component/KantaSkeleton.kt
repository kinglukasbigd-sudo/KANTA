package mk.kanta.app.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing

/**
 * Shimmer placeholder. Spec §8: "designed loading (skeletons, not spinners)".
 *
 * The shimmer is a slow highlight sweeping across the muted surface tone — no colour, so a
 * loading list stays as quiet as the loaded one. Hidden from TalkBack: a screen reader should
 * hear the loading state announced once by the screen, not read a dozen placeholder boxes.
 */
@Composable
fun KantaSkeleton(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 16.dp,
    shape: Shape = KantaShape.chip,
) {
    val base = KantaTheme.colors.surfaceMuted
    // Lift the highlight toward the theme's own text colour rather than pure white, so the
    // shimmer stays in the green family instead of flashing grey.
    val highlight = lerp(base, if (KantaTheme.colors.isDark) Color.White else Color.Black, 0.06f)

    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Slow and quiet — a skeleton should not compete with content that is about to appear.
            animation = tween(durationMillis = 1200, easing = Motion.FastOutSlowIn),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonProgress",
    )

    val sized = modifier
        .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
        .height(height)

    Spacer(
        modifier = sized
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(x = -400f + progress * 1200f, y = 0f),
                    end = Offset(x = progress * 1200f, y = 0f),
                ),
            )
            .clearAndSetSemantics {},
    )
}

/** A skeleton shaped like [KantaListRow], for loading lists. */
@Composable
fun KantaListRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KantaSkeleton(width = 32.dp, height = 32.dp, shape = KantaShape.chip)
        Spacer(Modifier.width(Spacing.l))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            KantaSkeleton(width = 140.dp, height = 16.dp)
            KantaSkeleton(width = 200.dp, height = 12.dp)
        }
        Spacer(Modifier.width(Spacing.l))
        KantaSkeleton(width = 48.dp, height = 14.dp)
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun SkeletonSample() {
    KantaSkeleton(width = 180.dp, height = 22.dp)
    KantaSkeleton()
    KantaListRowSkeleton()
    KantaListRowSkeleton()
}

@Preview(name = "Skeleton · light", widthDp = 400)
@Composable
private fun KantaSkeletonPreviewLight() = KantaPreview(darkTheme = false) { SkeletonSample() }

@Preview(name = "Skeleton · dark", widthDp = 400)
@Composable
private fun KantaSkeletonPreviewDark() = KantaPreview(darkTheme = true) { SkeletonSample() }
