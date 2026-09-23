package mk.kanta.app.feature.map

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.dto.PublicReportDto
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSectionHeader
import mk.kanta.app.core.designsystem.component.KantaStatusBadge
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.tabularFigures
import mk.kanta.app.core.network.publicPhotoUrl
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

    // §4.1 photo timeline.
    KantaSectionHeader(stringResource(R.string.container_detail_timeline))
    if (state.reports.isEmpty()) {
        Text(
            text = stringResource(R.string.container_detail_no_reports),
            style = MaterialTheme.typography.bodyLarge,
            color = KantaTheme.colors.onSurfaceMuted,
            modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        )
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
        onNavigate = {
            // §5.2: "Navigate (opens external maps app via geo: intent — no API
            // needed)". The label makes the pin show a name rather than raw
            // coordinates in whatever app handles it.
            val label = Uri.encode(state.code)
            val uri = "geo:${state.lat},${state.lon}?q=${state.lat},${state.lon}($label)"
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            }.onFailure { error ->
                // A phone with no maps app is rare but real; crashing over it
                // would be absurd.
                if (error !is ActivityNotFoundException) throw error
            }
        },
    )

    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun DetailHeader(state: ContainerDetailState) {
    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KantaStatusDot(state.status, state.kind, Modifier.size(28.dp))
            Spacer(Modifier.width(Spacing.m))
            Column {
                Text(
                    text = state.code,
                    style = MaterialTheme.typography.titleLarge.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        stringResource(
                            if (state.kind == ContainerKind.BIG) {
                                R.string.container_kind_big
                            } else {
                                R.string.container_kind_small
                            },
                        ),
                        state.municipality,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }

        Spacer(Modifier.height(Spacing.l))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            KantaStatusBadge(status = state.status, kind = state.kind)

            // §4.1: "current status + how long" — "full for 31 h".
            val hours = state.statusSinceHours
            if (hours != null && state.status != ContainerStatus.OK) {
                Text(
                    text = formatDuration(hours),
                    style = MaterialTheme.typography.bodySmall.tabularFigures(),
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
                        .clip(KantaShape.card),
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = formatDuration(report.ageHours),
                    style = MaterialTheme.typography.labelSmall.tabularFigures(),
                    color = KantaTheme.colors.onSurfaceMuted,
                )
                if (report.meTooCount > 0) {
                    Text(
                        text = "+${report.meTooCount}",
                        style = MaterialTheme.typography.labelSmall.tabularFigures(),
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
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        // §4.6: one tap to confirm an unverified container. Offered only when the
        // server says it would accept it (within 50 m, not your own, not twice);
        // then it is the most useful thing on the sheet, so it leads.
        if (state.canConfirmExists) {
            KantaPrimaryButton(
                text = stringResource(
                    if (state.confirmingExists) R.string.report_sending else R.string.container_action_exists,
                ),
                onClick = onConfirmExists,
                icon = KantaIcons.Success,
                enabled = !state.confirmingExists,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // The primary action depends on what is wrong: confirming a problem when
        // there is one, otherwise reporting it (§4.1).
        if (state.status == ContainerStatus.FULL) {
            KantaPrimaryButton(
                text = stringResource(R.string.container_action_me_too),
                onClick = onMeToo,
                modifier = Modifier.fillMaxWidth(),
            )
            KantaSecondaryButton(
                text = stringResource(R.string.container_action_emptied),
                onClick = onEmptied,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.canConfirmExists) {
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

/**
 * "31 h", "3 d", "just now". Deliberately coarse: §4.1 wants a sense of how long
 * a problem has been ignored, not a stopwatch.
 */
private fun formatDuration(hours: Double): String = when {
    hours < 1.0 -> "<1 h"
    hours < 48.0 -> "${hours.roundToInt()} h"
    else -> "${(hours / 24).roundToInt()} d"
}
