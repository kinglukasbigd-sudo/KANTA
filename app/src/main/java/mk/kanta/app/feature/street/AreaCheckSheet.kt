package mk.kanta.app.feature.street

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaCompactAction
import mk.kanta.app.core.designsystem.component.KantaDistanceLabel
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRow
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSectionHeader
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.feature.report.SuccessCheck

/**
 * The "Map your street" sheet content (§4.6). It replaces the menu the way the
 * container detail does (§4.1), while the map above shows the 150 m ring.
 */
@Composable
fun ColumnScope.AreaCheckContent(
    state: AreaCheckUiState,
    onAllPresent: () -> Unit,
    onMissingOne: () -> Unit,
    onNotHere: () -> Unit,
    onCancelPicking: () -> Unit,
    onConfirmExists: (String) -> Unit,
    onLater: () -> Unit,
) {
    // Fills the sheet's body and scrolls when the content is taller than it.
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
    ) {
        AnimatedContent(
            targetState = state.phase.screen(),
            transitionSpec = { fadeIn(Motion.tweenMedium()) togetherWith fadeOut(Motion.tweenFast()) },
            label = "area-check",
        ) { screen ->
            when (screen) {
                Screen.Ask -> Asking(
                    state = state,
                    onAllPresent = onAllPresent,
                    onMissingOne = onMissingOne,
                    onNotHere = onNotHere,
                    onConfirmExists = onConfirmExists,
                    onLater = onLater,
                )
                Screen.Pick -> Picking(onCancel = onCancelPicking)
                Screen.Thanks -> Thanks()
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

private enum class Screen { Ask, Pick, Thanks }

private fun AreaCheckPhase.screen() = when (this) {
    AreaCheckPhase.Asking, AreaCheckPhase.Saving -> Screen.Ask
    AreaCheckPhase.PickingMissing -> Screen.Pick
    AreaCheckPhase.Thanks -> Screen.Thanks
}

@Composable
private fun Asking(
    state: AreaCheckUiState,
    onAllPresent: () -> Unit,
    onMissingOne: () -> Unit,
    onNotHere: () -> Unit,
    onConfirmExists: (String) -> Unit,
    onLater: () -> Unit,
) {
    Column {
        Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
            Text(
                text = stringResource(R.string.street_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = stringResource(R.string.street_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = KantaTheme.colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(Spacing.m))
            ShapeLegend()

            when {
                state.locating -> {
                    Spacer(Modifier.height(Spacing.l))
                    KantaSkeleton(width = 200.dp, height = 14.dp)
                }
                state.locationMissing -> {
                    Spacer(Modifier.height(Spacing.l))
                    Text(
                        text = stringResource(R.string.street_location_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
            }
        }

        // §4.6 step 3: unverified containers inside the ring, one tap to confirm.
        if (state.unverified.isNotEmpty()) {
            KantaSectionHeader(stringResource(R.string.street_unverified_title))
            state.unverified.forEach { item ->
                UnverifiedRow(
                    item = item,
                    confirming = state.confirmingId == item.id,
                    onConfirm = { onConfirmExists(item.id) },
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            state.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MarkerColors.Broken,
                )
            }

            val ready = state.centre != null && !state.locating
            val saving = state.phase == AreaCheckPhase.Saving
            KantaPrimaryButton(
                text = stringResource(if (saving) R.string.report_sending else R.string.street_yes),
                onClick = onAllPresent,
                icon = KantaIcons.Success,
                enabled = ready && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            KantaSecondaryButton(
                text = stringResource(R.string.street_missing),
                onClick = onMissingOne,
                icon = KantaIcons.Add,
                enabled = ready && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            KantaSecondaryButton(
                text = stringResource(R.string.street_not_here),
                onClick = onNotHere,
                icon = KantaIcons.Report,
                enabled = ready && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            // §4.6: "Always skippable ('Later')".
            TextButton(
                onClick = onLater,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(R.string.street_later),
                    style = MaterialTheme.typography.labelLarge,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }
    }
}

/** "Big containers = rectangles, small cans = triangles" — shown, not just said. */
@Composable
private fun ShapeLegend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        KantaStatusDot(ContainerStatus.OK, ContainerKind.BIG, Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.container_kind_big),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(Spacing.m))
        KantaStatusDot(ContainerStatus.OK, ContainerKind.SMALL, Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.container_kind_small),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UnverifiedRow(
    item: UnverifiedNearbyUi,
    confirming: Boolean,
    onConfirm: () -> Unit,
) {
    KantaListRow(
        title = item.code,
        subtitle = when {
            item.isMine -> stringResource(R.string.street_yours)
            item.iConfirmed -> stringResource(R.string.street_confirmed)
            !item.canConfirm -> stringResource(R.string.street_move_closer)
            else -> null
        },
        leading = { KantaStatusDot(item.status, item.kind) },
        trailing = {
            // The button exists only where the server said it would be accepted.
            if (item.canConfirm) {
                KantaCompactAction(
                    text = stringResource(
                        if (confirming) R.string.report_sending else R.string.container_action_exists,
                    ),
                    onClick = onConfirm,
                    emphasis = true,
                    enabled = !confirming,
                )
            } else {
                KantaDistanceLabel(item.distanceMetres)
            }
        },
        showDivider = false,
    )
}

@Composable
private fun Picking(onCancel: () -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text = stringResource(R.string.street_pick_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = stringResource(R.string.street_pick_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = KantaTheme.colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(Spacing.xl))
        KantaSecondaryButton(
            text = stringResource(R.string.action_back),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** §4.6: "Thank-you micro-animation". The sheet closes by itself a moment later. */
@Composable
private fun Thanks() {
    // §3.3: the confirm haptic on a successful send.
    val haptics = rememberKantaHaptics()
    LaunchedEffect(Unit) { haptics.success() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SuccessCheck(Modifier.size(80.dp))
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = stringResource(R.string.street_thanks),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
