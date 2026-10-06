package mk.kanta.app.feature.report

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.BackgroundDark
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.OnSurfaceDark
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaBanner
import mk.kanta.app.core.designsystem.component.KantaCard
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaIconTile
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaPageTitle
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusBadge
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.component.KantaTextField
import mk.kanta.app.core.designsystem.component.KantaTopBar
import mk.kanta.app.core.designsystem.mono
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
                .verticalScroll(rememberScrollState()),
        ) {
            // Back is "retake": the photo is the first step of the flow (§4.3).
            KantaTopBar(onBack = onRetake, backEnabled = !sending)
            KantaPageTitle(
                title = stringResource(if (state.presetFull) R.string.report_title_full else R.string.report_title),
                eyebrow = stringResource(R.string.report_eyebrow),
            )

            Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
                // --- The photo ------------------------------------------------------------
                state.photo?.let { photo ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(KantaShape.card)
                            .border(Spacing.hairline, KantaTheme.colors.outline, KantaShape.card),
                    ) {
                        AsyncImage(
                            model = photo.file,
                            contentDescription = stringResource(R.string.report_photo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Saying it out loud: the privacy promise (§8) is visible, not implied.
                        if (photo.facesBlurred > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(Spacing.m),
                                shape = KantaShape.chip,
                                color = BackgroundDark.copy(alpha = 0.7f),
                            ) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.report_faces_blurred,
                                        photo.facesBlurred,
                                        photo.facesBlurred,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDark,
                                    modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                                )
                            }
                        }
                    }
                }

                // --- Which container --------------------------------------------------------
                SectionCaption(stringResource(R.string.report_container))
                when {
                    state.snapping -> KantaCard(Modifier.fillMaxWidth()) {
                        KantaSkeleton(width = 220.dp, height = 18.dp)
                    }
                    state.locationUnavailable -> KantaBanner(
                        text = stringResource(R.string.report_location_needed),
                        icon = KantaIcons.MyLocation,
                    )
                    state.container != null -> {
                        val c = state.container
                        KantaCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = if (sending) null else onChangeContainer,
                            contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KantaStatusDot(c.status, c.kind, Modifier.size(36.dp))
                                Spacer(Modifier.width(Spacing.m))
                                Text(
                                    // §4.3: "Container SK-00412 · 12 m"
                                    text = stringResource(
                                        R.string.report_container_line,
                                        c.code,
                                        c.distanceMetres.roundToInt(),
                                    ),
                                    style = MaterialTheme.typography.titleSmall.mono(),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.report_change),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = KantaTheme.colors.brand,
                                )
                            }
                        }
                    }
                    else -> KantaBanner(
                        text = stringResource(R.string.report_no_container),
                        icon = KantaIcons.Place,
                    )
                }
                TextButton(onClick = onAddContainer, enabled = !sending && state.device != null) {
                    Text(
                        text = stringResource(R.string.report_not_on_map),
                        style = MaterialTheme.typography.labelLarge,
                        color = KantaTheme.colors.brand,
                    )
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

                // --- Note ---------------------------------------------------------------------
                SectionCaption(stringResource(R.string.report_note))
                KantaTextField(
                    value = state.note,
                    onValueChange = onNoteChange,
                    label = stringResource(R.string.report_note_hint),
                    enabled = !sending,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    supportingText = "${state.noteLength} / ${ReportViewModel.MAX_NOTE}",
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
}

/** §4.3: a friendly error with a way forward, never a dead end. */
@Composable
private fun OutcomeCard(outcome: SendOutcome, onAddContainer: () -> Unit, onDismiss: () -> Unit) {
    KantaCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            val message = when (outcome) {
                is SendOutcome.TooFar -> outcome.metres
                    ?.let { stringResource(R.string.report_too_far_metres, it) }
                    ?: stringResource(R.string.error_too_far_from_container)
                SendOutcome.RateLimited -> stringResource(R.string.error_daily_report_limit)
                is SendOutcome.Failed -> stringResource(outcome.error.messageRes)
                else -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                KantaIconTile(icon = KantaIcons.Error, tint = KantaTheme.colors.error, size = 32.dp)
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
