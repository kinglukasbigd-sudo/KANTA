package mk.kanta.app.feature.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.rememberKantaHaptics

/**
 * Step 3 of §4.3: the check, the success haptic, and a sentence that says exactly
 * what happened. A Full report moves on to the alternatives by itself; everything
 * else waits for "Done".
 */
@Composable
fun ReportDoneScreen(state: ReportUiState, onDone: () -> Unit) {
    val haptics = rememberKantaHaptics()
    val queued = state.outcome == SendOutcome.Queued
    LaunchedEffect(Unit) { haptics.success() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (queued) {
                // Not a check: nothing has been sent yet, and the icon should not
                // claim otherwise.
                Icon(
                    imageVector = KantaIcons.Offline,
                    contentDescription = null,
                    tint = KantaTheme.colors.onSurfaceMuted,
                    modifier = Modifier.size(72.dp),
                )
            } else {
                SuccessCheck(Modifier.size(96.dp))
            }

            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = when (val outcome = state.outcome) {
                    // §4.3: "X neighbours already reported this — we added your confirmation".
                    is SendOutcome.MergedMeToo -> pluralStringResource(
                        R.plurals.report_merged, outcome.neighbours, outcome.neighbours,
                    )
                    SendOutcome.Queued -> stringResource(R.string.report_queued)
                    SendOutcome.RequestSent -> stringResource(R.string.report_request_sent)
                    else -> stringResource(
                        if (state.presetFull) R.string.report_sent_full else R.string.report_sent,
                    )
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            if (!state.goToAlternatives) {
                Spacer(Modifier.height(Spacing.xxl))
                KantaPrimaryButton(
                    text = stringResource(R.string.report_done),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * A circle that springs in, then a check that draws itself (§3.3). Durations come
 * from the motion tokens, which the platform scales to zero when the user turns
 * animations off — the check then simply appears.
 */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, color: Color = KantaTheme.colors.brand) {
    val circle = remember { Animatable(0f) }
    val stroke = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { circle.animateTo(1f, spring(dampingRatio = Motion.SpringDamping)) }
        kotlinx.coroutines.delay(Motion.DurationFast.toLong())
        stroke.animateTo(1f, Motion.tweenSlow())
    }

    Canvas(modifier) {
        val radius = size.minDimension / 2f
        drawCircle(color = color.copy(alpha = 0.14f), radius = radius * circle.value)

        val check = Path().apply {
            moveTo(size.width * 0.30f, size.height * 0.52f)
            lineTo(size.width * 0.45f, size.height * 0.66f)
            lineTo(size.width * 0.71f, size.height * 0.38f)
        }
        val measure = PathMeasure().apply { setPath(check, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * stroke.value, partial, true)
        drawPath(
            path = partial,
            color = color,
            style = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round),
        )
    }
}
