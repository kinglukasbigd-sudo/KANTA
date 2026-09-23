package mk.kanta.app.feature.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaDragHandle
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.tabularFigures
import mk.kanta.app.core.location.LatLon
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp

/**
 * "Not on the map — add this container", which §4.3 routes through the §4.6 Add
 * container flow: allowance shown up front, the 30 m rule and the duplicate check
 * enforced by the server, and "Send for review" once the 2-container limit is used.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddContainerSheet(
    state: AddContainerUiState,
    onKind: (ContainerKind) -> Unit,
    onCategory: (ContainerCategory) -> Unit,
    onSubmit: () -> Unit,
    onConfirmDifferent: () -> Unit,
    onUseExisting: () -> Unit,
    onSendForReview: () -> Unit,
    onPinMoved: (LatLon) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The pin map inside pans with a drag; the sheet must not steal that
        // drag. Back and a tap outside still close it.
        sheetGesturesEnabled = false,
        shape = KantaShape.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { KantaDragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xxl),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.s))

            // §4.6: "Show remaining count before adding".
            when {
                state.loading -> KantaSkeleton(width = 180.dp, height = 14.dp)
                state.isAdmin -> Muted(stringResource(R.string.add_allowance_admin))
                state.limitReached -> Muted(stringResource(R.string.add_limit_reached))
                state.remaining != null -> Muted(
                    if (state.remaining >= 2) {
                        stringResource(R.string.add_allowance_two)
                    } else {
                        pluralStringResource(R.plurals.add_allowance_more, state.remaining, state.remaining)
                    },
                )
            }

            // --- Pin (§4.6: "user drags the pin to the exact spot") ---------------------
            val start = state.device
            if (start == null) {
                Spacer(Modifier.height(Spacing.l))
                Muted(stringResource(R.string.report_location_needed))
            } else {
                Spacer(Modifier.height(Spacing.l))
                PinPickerMap(
                    start = start,
                    device = state.device,
                    nearby = state.nearby,
                    onPinMoved = onPinMoved,
                    contentDescription = stringResource(R.string.add_pin_map),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp)
                        .clip(KantaShape.card),
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = state.pinDistanceMetres?.let {
                        stringResource(R.string.add_pin_distance, it.roundToInt())
                    } ?: stringResource(R.string.add_pin_here),
                    style = MaterialTheme.typography.bodySmall.tabularFigures(),
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }

            // --- "Is it this one?" (§4.6 duplicate check) ------------------------------
            state.duplicateOf?.let { existing ->
                Spacer(Modifier.height(Spacing.l))
                Surface(shape = KantaShape.card, color = KantaTheme.colors.surfaceMuted) {
                    Column(Modifier.padding(Spacing.l)) {
                        Text(stringResource(R.string.add_duplicate_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(Spacing.s))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KantaStatusDot(existing.status, existing.kind)
                            Spacer(Modifier.width(Spacing.m))
                            Text(
                                text = stringResource(
                                    R.string.report_container_line,
                                    existing.code,
                                    existing.distanceMetres.roundToInt(),
                                ),
                                style = MaterialTheme.typography.bodyLarge.tabularFigures(),
                            )
                        }
                        Spacer(Modifier.height(Spacing.m))
                        KantaPrimaryButton(
                            text = stringResource(R.string.add_duplicate_use),
                            onClick = onUseExisting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(Spacing.s))
                        KantaSecondaryButton(
                            text = stringResource(R.string.add_duplicate_different),
                            onClick = onConfirmDifferent,
                            enabled = !state.submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                return@Column
            }

            // --- Type -------------------------------------------------------------------
            Caption(stringResource(R.string.add_type))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                KantaChip(
                    label = stringResource(R.string.container_kind_big),
                    selected = state.kind == ContainerKind.BIG,
                    onClick = { onKind(ContainerKind.BIG) },
                )
                KantaChip(
                    label = stringResource(R.string.container_kind_small),
                    selected = state.kind == ContainerKind.SMALL,
                    onClick = { onKind(ContainerKind.SMALL) },
                )
            }

            // §4.6: "for big: category General / Glass / Paper / Plastic".
            if (state.kind == ContainerKind.BIG) {
                Caption(stringResource(R.string.add_category))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    listOf(
                        ContainerCategory.GENERAL to R.string.category_general,
                        ContainerCategory.GLASS to R.string.category_glass,
                        ContainerCategory.PAPER to R.string.category_paper,
                        ContainerCategory.PLASTIC to R.string.category_plastic,
                    ).forEach { (category, label) ->
                        KantaChip(
                            label = stringResource(label),
                            selected = state.category == category,
                            onClick = { onCategory(category) },
                        )
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(Spacing.m))
                Text(stringResource(it.messageRes), style = MaterialTheme.typography.bodySmall, color = MarkerColors.Broken)
            }

            Spacer(Modifier.height(Spacing.xl))
            if (state.limitReached) {
                // §4.6: "the user can send a container request … that does NOT appear on the map".
                KantaPrimaryButton(
                    text = stringResource(if (state.submitting) R.string.report_sending else R.string.add_send_for_review),
                    onClick = onSendForReview,
                    enabled = state.kind != null && !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                KantaPrimaryButton(
                    text = stringResource(if (state.submitting) R.string.report_sending else R.string.add_submit),
                    onClick = onSubmit,
                    enabled = state.canSubmit && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = KantaTheme.colors.onSurfaceMuted)

@Composable
private fun Caption(text: String) {
    Spacer(Modifier.height(Spacing.xl))
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = KantaTheme.colors.onSurfaceMuted)
    Spacer(Modifier.height(Spacing.s))
}
