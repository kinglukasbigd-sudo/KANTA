package mk.kanta.app.feature.suggest

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.BackgroundDark
import mk.kanta.app.core.designsystem.BrandDark
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.OnSurfaceDark
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.SurfaceMutedDark
import mk.kanta.app.core.designsystem.component.KantaBanner
import mk.kanta.app.core.designsystem.component.KantaBannerTone
import mk.kanta.app.core.designsystem.component.KantaCard
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaPageTitle
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaTextField
import mk.kanta.app.core.designsystem.component.KantaTopBar
import mk.kanta.app.core.designsystem.kantaSoftShadow
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.feature.report.CameraCapture
import mk.kanta.app.feature.report.PinPickerMap
import mk.kanta.app.feature.report.SuccessCheck

/**
 * The Suggest flow (§4.4): map in pick mode with a centre crosshair → "Place here"
 * → reason → optional photo + note → Send. If an open suggestion is already within
 * 50 m, it is shown first with "Vote for this one instead".
 */
@Composable
fun SuggestScreen(
    onClose: () -> Unit,
    viewModel: SuggestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Back steps out one stage at a time: camera → details → map → out.
    BackHandler(enabled = state.stage != SuggestStage.Pick && state.stage != SuggestStage.Sending) {
        when (state.stage) {
            SuggestStage.Camera -> viewModel.closeCamera()
            SuggestStage.Details, SuggestStage.Nearby -> viewModel.pickAgain()
            SuggestStage.Done -> onClose()
            else -> Unit
        }
    }

    when (state.stage) {
        SuggestStage.Pick, SuggestStage.Nearby -> PickStage(
            state = state,
            onPinMoved = viewModel::movePin,
            onPlaceHere = viewModel::placeHere,
            onVoteInstead = viewModel::voteInstead,
            onPickAgain = viewModel::pickAgain,
            onClose = onClose,
        )

        SuggestStage.Details, SuggestStage.Sending -> DetailsStage(
            state = state,
            onBack = viewModel::backToPick,
            onReason = viewModel::selectReason,
            onNote = viewModel::onNoteChange,
            onAddPhoto = viewModel::openCamera,
            onRemovePhoto = viewModel::removePhoto,
            onSend = viewModel::send,
        )

        SuggestStage.Camera -> CameraStage(
            rejected = state.photoRejected,
            onCaptured = viewModel::onPhotoCaptured,
            onClose = viewModel::closeCamera,
        )

        SuggestStage.Processing -> Processing()

        SuggestStage.Done -> DoneStage(outcome = state.outcome, onDone = onClose)
    }
}

// -------------------------------------------------------------------------------------------
// Pick (§4.4: "map in pick mode with a centre crosshair → confirm spot")
// -------------------------------------------------------------------------------------------

@Composable
private fun PickStage(
    state: SuggestUiState,
    onPinMoved: (mk.kanta.app.core.location.LatLon) -> Unit,
    onPlaceHere: () -> Unit,
    onVoteInstead: () -> Unit,
    onPickAgain: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val start = state.start
        if (start == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                KantaSkeleton(width = 160.dp, height = 16.dp)
            }
        } else {
            PinPickerMap(
                // Coming back from the details step, reopen where the crosshair was.
                start = state.pin ?: start,
                device = state.device,
                nearby = state.containers,
                onPinMoved = onPinMoved,
                contentDescription = stringResource(R.string.suggest_map_description),
                crosshair = true,
                suggestions = state.suggestions,
                startZoom = 17.0,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Close, top left, on a surface like the map's own chrome.
        Surface(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = Spacing.l, top = Spacing.m)
                .size(Spacing.minTouchTarget)
                .kantaSoftShadow(KantaShape.button),
            shape = KantaShape.button,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .kantaSoftShadow(KantaShape.bottomSheet),
            shape = KantaShape.bottomSheet,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xl),
            ) {
                Text(
                    text = stringResource(R.string.suggest_eyebrow).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KantaTheme.colors.brand,
                )
                Spacer(Modifier.height(Spacing.s))
                val nearby = state.nearby
                if (state.stage == SuggestStage.Nearby && nearby != null) {
                    NearbyCard(nearby, onVoteInstead, onPickAgain)
                } else {
                    Text(
                        text = stringResource(R.string.suggest_pick_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = stringResource(R.string.suggest_pick_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    KantaPrimaryButton(
                        text = stringResource(if (state.checking) R.string.suggest_checking else R.string.suggest_place_here),
                        onClick = onPlaceHere,
                        icon = KantaIcons.Place,
                        enabled = state.pin != null && !state.checking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** §4.4: "If an open suggestion exists within 50 m, offer 'Vote for this one instead'". */
@Composable
private fun NearbyCard(nearby: NearbySuggestion, onVoteInstead: () -> Unit, onPickAgain: () -> Unit) {
    Text(
        text = stringResource(R.string.suggest_nearby_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Spacing.s))
    Text(
        text = listOfNotNull(
            nearby.reason?.let { stringResource(it.label) },
            stringResource(R.string.suggest_nearby_distance, nearby.distanceMetres),
            pluralStringResource(R.plurals.suggest_votes, nearby.votes, nearby.votes),
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = KantaTheme.colors.onSurfaceMuted,
    )
    nearby.note?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(Spacing.m))
        KantaCard(Modifier.fillMaxWidth()) {
            Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Spacer(Modifier.height(Spacing.xl))
    KantaPrimaryButton(
        text = stringResource(if (nearby.iVoted) R.string.suggest_already_voted else R.string.suggest_vote_instead),
        onClick = onVoteInstead,
        icon = KantaIcons.Success,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.m))
    // The server merges anything within 50 m, so "send mine anyway" would only
    // become a vote; the honest alternative is a different spot.
    KantaSecondaryButton(
        text = stringResource(R.string.suggest_pick_another),
        onClick = onPickAgain,
        modifier = Modifier.fillMaxWidth(),
    )
}

// -------------------------------------------------------------------------------------------
// Details: reason → optional photo + note → Send
// -------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsStage(
    state: SuggestUiState,
    onBack: () -> Unit,
    onReason: (SuggestReason) -> Unit,
    onNote: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSend: () -> Unit,
) {
    val sending = state.stage == SuggestStage.Sending
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onAddPhoto()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            KantaTopBar(onBack = onBack, backEnabled = !sending)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                KantaPageTitle(
                    title = stringResource(R.string.suggest_details_title),
                    eyebrow = stringResource(R.string.suggest_eyebrow),
                )
                Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
                    Caption(stringResource(R.string.suggest_reason_caption))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        SuggestReason.entries.forEach { reason ->
                            KantaChip(
                                label = stringResource(reason.label),
                                selected = state.reason == reason,
                                onClick = { onReason(reason) },
                                enabled = !sending,
                            )
                        }
                    }

                    Caption(stringResource(R.string.suggest_photo_caption))
                    val photo = state.photo
                    if (photo == null) {
                        KantaSecondaryButton(
                            text = stringResource(R.string.suggest_add_photo),
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) onAddPhoto() else cameraPermission.launch(Manifest.permission.CAMERA)
                            },
                            icon = KantaIcons.Camera,
                            enabled = !sending,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = photo.file,
                                contentDescription = stringResource(R.string.report_photo),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(KantaShape.card)
                                    .border(Spacing.hairline, KantaTheme.colors.outline, KantaShape.card),
                            )
                            Spacer(Modifier.width(Spacing.l))
                            TextButton(onClick = onRemovePhoto, enabled = !sending) {
                                Text(stringResource(R.string.suggest_remove_photo), color = KantaTheme.colors.onSurfaceMuted)
                            }
                        }
                    }

                    Caption(stringResource(R.string.report_note))
                    KantaTextField(
                        value = state.note,
                        onValueChange = onNote,
                        label = stringResource(R.string.suggest_note_hint),
                        enabled = !sending,
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                        supportingText = "${state.note.length} / ${SuggestViewModel.MAX_NOTE}",
                    )

                    state.error?.let { error ->
                        Spacer(Modifier.height(Spacing.m))
                        KantaBanner(
                            text = stringResource(error.messageRes),
                            tone = KantaBannerTone.ERROR,
                        )
                    }
                    Spacer(Modifier.height(Spacing.xl))
                }
            }

            KantaPrimaryButton(
                text = stringResource(if (sending) R.string.report_sending else R.string.action_send),
                onClick = onSend,
                icon = KantaIcons.Send,
                enabled = state.canSend && !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l),
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Spacer(Modifier.height(Spacing.xl))
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = KantaTheme.colors.onSurfaceMuted)
    Spacer(Modifier.height(Spacing.s))
}

// -------------------------------------------------------------------------------------------
// Photo, processing, done
// -------------------------------------------------------------------------------------------

@Composable
private fun CameraStage(rejected: Boolean, onCaptured: (java.io.File) -> Unit, onClose: () -> Unit) {
    CameraCapture(
        onCaptured = onCaptured,
        onClose = onClose,
        rejectedMessage = if (rejected) stringResource(R.string.report_photo_rejected) else null,
    )
}

@Composable
private fun Processing() {
    Surface(Modifier.fillMaxSize(), color = BackgroundDark) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                color = BrandDark,
                trackColor = SurfaceMutedDark,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.report_processing),
                color = OnSurfaceDark,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xl),
            )
        }
    }
}

@Composable
private fun DoneStage(outcome: SuggestOutcome?, onDone: () -> Unit) {
    val haptics = rememberKantaHaptics()
    LaunchedEffect(Unit) { haptics.success() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            SuccessCheck(Modifier.size(112.dp))
            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = when (outcome) {
                    is SuggestOutcome.Sent -> stringResource(R.string.suggest_done_sent)
                    is SuggestOutcome.Merged ->
                        pluralStringResource(R.plurals.suggest_done_merged, outcome.votes, outcome.votes)
                    is SuggestOutcome.Voted ->
                        pluralStringResource(R.plurals.suggest_done_voted, outcome.votes, outcome.votes)
                    SuggestOutcome.AlreadyVoted, null -> stringResource(R.string.suggest_done_already)
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            KantaPrimaryButton(
                text = stringResource(R.string.report_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
