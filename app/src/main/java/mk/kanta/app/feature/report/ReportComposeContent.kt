package mk.kanta.app.feature.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusBadge
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.tabularFigures
import kotlin.math.roundToInt

/** Step 2 of §4.3: photo, container, what's wrong, optional note, Send. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportComposeContent(
    state: ReportUiState,
    onRetake: () -> Unit,
    onSelectKind: (ReportKind) -> Unit,
    onNoteChange: (String) -> Unit,
    onChangeContainer: () -> Unit,
    onAddContainer: () -> Unit,
    onSend: () -> Unit,
    onDismissOutcome: () -> Unit,
) {
    val sending = state.stage == ReportStage.Sending

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRetake, enabled = !sending) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.report_retake))
                }
                Text(
                    text = stringResource(if (state.presetFull) R.string.report_title_full else R.string.report_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(Modifier.height(Spacing.m))

            // --- The photo ----------------------------------------------------------------
            state.photo?.let { photo ->
                AsyncImage(
                    model = photo.file,
                    contentDescription = stringResource(R.string.report_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(KantaShape.card),
                )
                // Saying it out loud: the privacy promise (§8) is visible, not implied.
                if (photo.facesBlurred > 0) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = pluralStringResource(R.plurals.report_faces_blurred, photo.facesBlurred, photo.facesBlurred),
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
            }

            // --- Which container ------------------------------------------------------------
            SectionCaption(stringResource(R.string.report_container))
            when {
                state.snapping -> {
                    KantaSkeleton(width = 220.dp, height = 18.dp)
                }
                state.locationUnavailable -> {
                    Text(
                        text = stringResource(R.string.report_location_needed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                state.container != null -> {
                    val c = state.container
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KantaShape.chip)
                            .clickable(enabled = !sending, onClick = onChangeContainer)
                            .padding(vertical = Spacing.s),
                    ) {
                        KantaStatusDot(c.status, c.kind)
                        Spacer(Modifier.width(Spacing.m))
                        Text(
                            // §4.3: "Container SK-00412 · 12 m"
                            text = stringResource(R.string.report_container_line, c.code, c.distanceMetres.roundToInt()),
                            style = MaterialTheme.typography.bodyLarge.tabularFigures(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.report_change),
                            style = MaterialTheme.typography.labelLarge,
                            color = KantaTheme.colors.brand,
                        )
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.report_no_container),
                        style = MaterialTheme.typography.bodyLarge,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
            }
            TextButton(onClick = onAddContainer, enabled = !sending && state.device != null) {
                Text(stringResource(R.string.report_not_on_map), style = MaterialTheme.typography.labelLarge)
            }

            // --- What's wrong -------------------------------------------------------------
            SectionCaption(stringResource(R.string.report_whats_wrong))
            if (state.presetFull) {
                // §4.3: "For Full the status is already set."
                KantaStatusBadge(ContainerStatus.FULL)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    ReportKind.chips.forEach { kind ->
                        KantaChip(
                            label = stringResource(kind.labelRes()),
                            selected = state.kind == kind,
                            onClick = { onSelectKind(kind) },
                            enabled = !sending,
                        )
                    }
                }
            }

            // --- Note ----------------------------------------------------------------------
            SectionCaption(stringResource(R.string.report_note))
            TextField(
                value = state.note,
                onValueChange = onNoteChange,
                enabled = !sending,
                placeholder = { Text(stringResource(R.string.report_note_hint)) },
                supportingText = {
                    Text(
                        text = "${state.noteLength} / ${ReportViewModel.MAX_NOTE}",
                        style = MaterialTheme.typography.labelSmall.tabularFigures(),
                        color = KantaTheme.colors.onSurfaceMuted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                minLines = 2,
                maxLines = 5,
                shape = KantaShape.chip,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = KantaTheme.colors.surfaceMuted,
                    unfocusedContainerColor = KantaTheme.colors.surfaceMuted,
                    disabledContainerColor = KantaTheme.colors.surfaceMuted,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = KantaTheme.colors.brand,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // --- A refusal to explain, if the last Send did not go through --------------
            state.outcome?.takeIf { !it.isFinal }?.let { outcome ->
                Spacer(Modifier.height(Spacing.l))
                OutcomeCard(outcome, onAddContainer = onAddContainer, onDismiss = onDismissOutcome)
            }

            Spacer(Modifier.height(Spacing.xl))
            KantaPrimaryButton(
                text = stringResource(if (sending) R.string.report_sending else R.string.action_send),
                onClick = onSend,
                enabled = state.canSend && !sending,
                icon = KantaIcons.Send,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** §4.3: a friendly error with a way forward, never a dead end. */
@Composable
private fun OutcomeCard(outcome: SendOutcome, onAddContainer: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = KantaShape.card, color = KantaTheme.colors.surfaceMuted, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l)) {
            val message = when (outcome) {
                is SendOutcome.TooFar -> outcome.metres
                    ?.let { stringResource(R.string.report_too_far_metres, it) }
                    ?: stringResource(R.string.error_too_far_from_container)
                SendOutcome.RateLimited -> stringResource(R.string.error_daily_report_limit)
                is SendOutcome.Failed -> stringResource(outcome.error.messageRes)
                else -> ""
            }
            Row(verticalAlignment = Alignment.Top) {
                androidx.compose.material3.Icon(
                    imageVector = KantaIcons.Error,
                    contentDescription = null,
                    tint = MarkerColors.Broken,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.m))
                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            if (outcome is SendOutcome.TooFar) {
                // §4.3: '"Move closer" or "Add a new container here"'. Moving closer
                // needs no button — walking is the action — so only the add is one.
                Spacer(Modifier.height(Spacing.m))
                KantaSecondaryButton(
                    text = stringResource(R.string.report_add_here),
                    onClick = onAddContainer,
                    icon = KantaIcons.Suggest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_dismiss), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Spacer(Modifier.height(Spacing.xl))
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KantaTheme.colors.onSurfaceMuted,
    )
    Spacer(Modifier.height(Spacing.s))
}

fun ReportKind.labelRes(): Int = when (this) {
    ReportKind.Full -> R.string.status_full
    ReportKind.Damaged -> R.string.report_kind_damaged
    ReportKind.Destroyed -> R.string.report_kind_destroyed
    ReportKind.Burning -> R.string.report_kind_burning
    ReportKind.Missing -> R.string.report_kind_missing
    ReportKind.DumpedAround -> R.string.report_kind_dumped_around
}
