package mk.kanta.app.feature.map

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.dto.PublicReportDto
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaCard
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaGroupDivider
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSectionHeader
import mk.kanta.app.core.designsystem.component.KantaSizeChooser
import mk.kanta.app.core.designsystem.component.KantaStatusBadge
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.component.kindLabel
import mk.kanta.app.core.designsystem.mono
import mk.kanta.app.core.network.publicPhotoUrl
import mk.kanta.app.core.util.compactDuration
import kotlin.math.roundToInt

/**
 * Container detail content (§4.1): "type, ID, municipality, current status + how
 * long, photo timeline of reports, buttons: Me too, still full / It's been
 * emptied / Report other problem / Navigate".
 *
 * §4.1 says this "replaces the menu temporarily", so it is plain content that the
 * persistent sheet swaps in — not a second sheet stacked over the first. Back and
 * a downward swipe both return to the menu, handled by the sheet's owner.
 */
@Composable
fun ColumnScope.ContainerDetailContent(
    state: ContainerDetailState,
    onMeToo: () -> Unit = {},
    onEmptied: () -> Unit = {},
    onReportOther: () -> Unit = {},
    onConfirmExists: () -> Unit = {},
    /** §4.6 "Bin size": Small or Big. */
    onChooseSize: (ContainerKind) -> Unit = {},
    /** §5.2: from a full container, the nearest ones that still have space. */
    onNearestWithSpace: () -> Unit = {},
    /** An admin's answer sets the size at once, from anywhere (0020). */
    isAdmin: Boolean = false,
    onAdminSetKind: (ContainerKind) -> Unit = {},
) {
    val context = LocalContext.current

    if (state.loading) {
        DetailSkeleton()
        return
    }

    if (state.error != null) {
        KantaEmptyState(
            title = stringResource(state.error.messageRes),
            message = stringResource(R.string.container_detail_error_hint),
            icon = KantaIcons.Error,
        )
        return
    }

    DetailHeader(state)

    Spacer(Modifier.height(Spacing.l))

    val sizeSection: @Composable () -> Unit = {
        SizeSection(
            state = state,
            isAdmin = isAdmin,
            onChoose = { kind -> if (isAdmin) onAdminSetKind(kind) else onChooseSize(kind) },
        )
    }
    // §4.6 "Bin size": while nobody has said how big it is, that is the most useful thing
    // anyone at the bin can do, so it comes first. Once known, it sits below the actions.
    val sizeUnknown = state.kind == ContainerKind.UNKNOWN
    if (sizeUnknown) {
        sizeSection()
        Spacer(Modifier.height(Spacing.l))
    }

    // §4.1 photo timeline.
    KantaSectionHeader(stringResource(R.string.container_detail_timeline))
    if (state.reports.isEmpty()) {
        KantaCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal),
            color = KantaTheme.colors.surfaceMuted,
            border = false,
        ) {
            Text(
                text = stringResource(R.string.container_detail_no_reports),
                style = MaterialTheme.typography.bodySmall,
                color = KantaTheme.colors.onSurfaceMuted,
            )
        }
    } else {
        PhotoTimeline(state.reports)
    }

    Spacer(Modifier.height(Spacing.xl))

    DetailActions(
        state = state,
        onMeToo = onMeToo,
        onEmptied = onEmptied,
        onReportOther = onReportOther,
        onConfirmExists = onConfirmExists,
        onNavigate = { context.openInMaps(state.lat, state.lon, state.code) },
        onNearestWithSpace = onNearestWithSpace,
    )

    if (!sizeUnknown) {
        Spacer(Modifier.height(Spacing.xl))
        sizeSection()
    }

    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun DetailHeader(state: ContainerDetailState) {
    KantaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KantaStatusDot(state.status, state.kind, Modifier.size(44.dp))
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.code,
                    style = MaterialTheme.typography.titleLarge.mono(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = listOfNotNull(
                        kindLabel(state.kind),
                        state.municipality,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))
        KantaGroupDivider(startInset = 0.dp)
        Spacer(Modifier.height(Spacing.m))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            KantaStatusBadge(status = state.status, kind = state.kind)

            // §4.1: "current status + how long" — "full for 31 h".
            val hours = state.statusSinceHours
            if (hours != null && state.status != ContainerStatus.OK) {
                Text(
                    text = compactDuration(hours),
                    style = MaterialTheme.typography.labelMedium,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }

        // §5.1: "1 person says it's full" before the marker turns orange — the
        // sheet is allowed to be more informative than the map.
        if (state.status != ContainerStatus.FULL && state.unconfirmedFull > 0) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = stringResource(R.string.container_detail_unconfirmed_full),
                style = MaterialTheme.typography.bodySmall,
                color = KantaTheme.colors.onSurfaceMuted,
            )
        }

        if (!state.verified) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = stringResource(
                    when {
                        state.addedByMe -> R.string.container_detail_unverified_mine
                        state.iConfirmed -> R.string.container_detail_unverified_confirmed
                        else -> R.string.container_detail_unverified
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = KantaTheme.colors.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun PhotoTimeline(reports: List<PublicReportDto>) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Spacing.screenHorizontal,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        items(reports, key = { it.id }) { report ->
            Column(modifier = Modifier.width(140.dp)) {
                AsyncImage(
                    model = publicPhotoUrl(report.photoPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(KantaShape.card)
                        .border(Spacing.hairline, KantaTheme.colors.outline, KantaShape.card),
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = compactDuration(report.ageHours),
                    style = MaterialTheme.typography.labelSmall,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
                if (report.meTooCount > 0) {
                    Text(
                        text = "+${report.meTooCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailActions(
    state: ContainerDetailState,
    onMeToo: () -> Unit,
    onEmptied: () -> Unit,
    onReportOther: () -> Unit,
    onConfirmExists: () -> Unit,
    onNavigate: () -> Unit,
    onNearestWithSpace: () -> Unit,
) {
    // On a bin of unknown size the Small/Big tiles above are the main action.
    val sizeUnknown = state.kind == ContainerKind.UNKNOWN

    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        // §4.6: one tap to confirm an unverified container. Offered only when the
        // server says it would accept it (within 50 m, not your own, not twice). On a bin
        // of unknown size, answering Small/Big above confirms it too, so it steps back.
        if (state.canConfirmExists) {
            val label = stringResource(
                if (state.confirmingExists) R.string.report_sending else R.string.container_action_exists,
            )
            if (sizeUnknown) {
                KantaSecondaryButton(
                    text = label,
                    onClick = onConfirmExists,
                    icon = KantaIcons.Success,
                    enabled = !state.confirmingExists,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                KantaPrimaryButton(
                    text = label,
                    onClick = onConfirmExists,
                    icon = KantaIcons.Success,
                    enabled = !state.confirmingExists,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // The primary action depends on what is wrong: confirming a problem when
        // there is one, otherwise reporting it (§4.1).
        if (state.status == ContainerStatus.FULL) {
            KantaPrimaryButton(
                text = stringResource(R.string.container_action_me_too),
                onClick = onMeToo,
                modifier = Modifier.fillMaxWidth(),
            )
            // §5.2: a full container's detail leads to the ones that still have space.
            KantaSecondaryButton(
                text = stringResource(R.string.container_action_nearest_space),
                onClick = onNearestWithSpace,
                icon = KantaIcons.Place,
                modifier = Modifier.fillMaxWidth(),
            )
            KantaSecondaryButton(
                text = stringResource(R.string.container_action_emptied),
                onClick = onEmptied,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.canConfirmExists || sizeUnknown) {
            KantaSecondaryButton(
                text = stringResource(R.string.container_action_report_other),
                onClick = onReportOther,
                icon = KantaIcons.Camera,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            KantaPrimaryButton(
                text = stringResource(R.string.container_action_report_other),
                onClick = onReportOther,
                icon = KantaIcons.Camera,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Navigate is always available — you may want directions to a container
        // whatever state it is in.
        KantaSecondaryButton(
            text = stringResource(R.string.container_action_navigate),
            onClick = onNavigate,
            icon = KantaIcons.Navigate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * §4.6 "Bin size": "How big is this bin?" with the Small/Big tiles.
 *
 * The tiles are live when the server would take the answer (signed in, within 50 m), when the
 * person is signed out (tapping signs in first, then sends it), and always for an admin, whose
 * answer sets the size from anywhere. Otherwise they stay visible but still, with a line saying
 * to get closer.
 */
@Composable
private fun SizeSection(
    state: ContainerDetailState,
    isAdmin: Boolean,
    onChoose: (ContainerKind) -> Unit,
) {
    val unknown = state.kind == ContainerKind.UNKNOWN
    val enabled = isAdmin || state.canVoteSize || !state.signedIn

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        SizeTitle(
            stringResource(if (unknown) R.string.size_title_unknown else R.string.size_title_known),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(
                when {
                    isAdmin -> R.string.size_hint_admin
                    !enabled -> R.string.size_hint_far
                    unknown && !state.verified && state.canConfirmExists -> R.string.size_hint_unknown_confirms
                    unknown -> R.string.size_hint_unknown
                    else -> R.string.size_hint_known
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = KantaTheme.colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(Spacing.m))
        KantaSizeChooser(
            selected = state.mySizeVote,
            onChoose = onChoose,
            enabled = enabled,
            sending = state.sizeSending,
            votesSmall = state.sizeVotesSmall,
            votesBig = state.sizeVotesBig,
        )
    }
}

/** The section's title: the size question reads as a heading, not a caption. */
@Composable
private fun SizeTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun DetailSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        KantaListRowSkeleton()
        Box(Modifier.height(Spacing.l))
        KantaListRowSkeleton()
    }
}
