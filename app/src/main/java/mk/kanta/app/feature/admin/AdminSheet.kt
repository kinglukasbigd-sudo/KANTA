package mk.kanta.app.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaCompactAction
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaErrorState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRow
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.kantaSoftShadow
import mk.kanta.app.core.network.publicPhotoUrl

/**
 * The Admin sheet (§4.5 screen 16, §4.6): review queue, unverified list and
 * coverage, as tabs inside the one persistent sheet. The map above does the
 * spatial part — the item being reviewed is ringed there, and Coverage shades it.
 */
@Composable
fun ColumnScope.AdminContent(
    state: AdminUiState,
    onTab: (AdminTab) -> Unit,
    onRetry: () -> Unit,
    onFocus: (id: String, at: mk.kanta.app.core.location.LatLon) -> Unit,
    onApprove: (PendingRequestUi) -> Unit,
    onReject: (PendingRequestUi) -> Unit,
    onVerify: (UnverifiedUi) -> Unit,
    onDelete: (UnverifiedUi) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text = stringResource(R.string.admin_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AdminTab.entries.forEach { tab ->
                KantaChip(
                    label = stringResource(tab.label()),
                    selected = state.tab == tab,
                    onClick = { onTab(tab) },
                )
            }
        }

        // The last action's result, or the server's reason for refusing it.
        val line = state.error?.let { stringResource(it.messageRes) } ?: state.notice?.let { stringResource(it) }
        if (line != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) MarkerColors.Broken else KantaTheme.colors.brand,
            )
        }
        Spacer(Modifier.height(Spacing.s))
    }

    when (state.tab) {
        AdminTab.Requests -> RequestsTab(state, onRetry, onFocus, onApprove, onReject)
        AdminTab.Unverified -> UnverifiedTab(state, onRetry, onFocus, onVerify, onDelete)
        AdminTab.Coverage -> CoverageTab(state, onRetry)
    }

    state.confirmDelete?.let { container ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.admin_delete_title)) },
            text = { Text(stringResource(R.string.admin_delete_message, container.code)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(stringResource(R.string.admin_delete), color = MarkerColors.Broken)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun AdminTab.label() = when (this) {
    AdminTab.Requests -> R.string.admin_tab_requests
    AdminTab.Unverified -> R.string.admin_tab_unverified
    AdminTab.Coverage -> R.string.admin_tab_coverage
}

/** Room at the bottom so the last item clears the expanded sheet's edge and the nav bar. */
private val ListBottomPadding = 120.dp

// -------------------------------------------------------------------------------------------
// Review queue
// -------------------------------------------------------------------------------------------

@Composable
private fun RequestsTab(
    state: AdminUiState,
    onRetry: () -> Unit,
    onFocus: (String, mk.kanta.app.core.location.LatLon) -> Unit,
    onApprove: (PendingRequestUi) -> Unit,
    onReject: (PendingRequestUi) -> Unit,
) {
    val list = state.requests
    when {
        list.loading && list.items.isEmpty() -> Skeletons()
        list.error != null && list.items.isEmpty() -> KantaErrorState(
            title = stringResource(list.error.messageRes),
            message = stringResource(R.string.container_detail_error_hint),
            onRetry = onRetry,
        )
        list.loaded && list.items.isEmpty() -> KantaEmptyState(
            title = stringResource(R.string.admin_requests_empty_title),
            message = stringResource(R.string.admin_requests_empty_message),
            icon = KantaIcons.Success,
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                bottom = ListBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            items(list.items, key = { it.id }) { request ->
                RequestCard(
                    request = request,
                    focused = state.focusedId == request.id,
                    busy = request.id in state.busy,
                    onFocus = { onFocus(request.id, request.position) },
                    onApprove = { onApprove(request) },
                    onReject = { onReject(request) },
                )
            }
        }
    }
}

@Composable
private fun RequestCard(
    request: PendingRequestUi,
    focused: Boolean,
    busy: Boolean,
    onFocus: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        shape = KantaShape.card,
        color = if (focused) KantaTheme.colors.surfaceMuted else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            Spacing.hairline,
            if (focused) KantaTheme.colors.brand else KantaTheme.colors.outline,
        ),
    ) {
        Column(Modifier.clickable(onClick = onFocus)) {
            // The evidence the whole decision rests on: the photo, big.
            AsyncImage(
                model = publicPhotoUrl(request.photoPath),
                contentDescription = stringResource(R.string.admin_request_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(KantaShape.card),
            )
            Column(Modifier.padding(Spacing.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KantaStatusDot(ContainerStatus.OK, request.kind)
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        text = kindLine(request.kind, request.category),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = ageLabel(request.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                request.note?.let { note ->
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = "“$note”",
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                Spacer(Modifier.height(Spacing.s))
                // The pin: tapping the card rings it on the map above.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = KantaIcons.Place,
                        contentDescription = null,
                        tint = if (focused) KantaTheme.colors.brand else KantaTheme.colors.onSurfaceMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(if (focused) R.string.admin_on_map else R.string.admin_show_on_map),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) KantaTheme.colors.brand else KantaTheme.colors.onSurfaceMuted,
                    )
                }
                Spacer(Modifier.height(Spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    KantaSecondaryButton(
                        text = stringResource(R.string.admin_reject),
                        onClick = onReject,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    KantaPrimaryButton(
                        text = stringResource(if (busy) R.string.report_sending else R.string.admin_approve),
                        onClick = onApprove,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Unverified containers
// -------------------------------------------------------------------------------------------

@Composable
private fun UnverifiedTab(
    state: AdminUiState,
    onRetry: () -> Unit,
    onFocus: (String, mk.kanta.app.core.location.LatLon) -> Unit,
    onVerify: (UnverifiedUi) -> Unit,
    onDelete: (UnverifiedUi) -> Unit,
) {
    val list = state.unverified
    when {
        list.loading && list.items.isEmpty() -> Skeletons()
        list.error != null && list.items.isEmpty() -> KantaErrorState(
            title = stringResource(list.error.messageRes),
            message = stringResource(R.string.container_detail_error_hint),
            onRetry = onRetry,
        )
        list.loaded && list.items.isEmpty() -> KantaEmptyState(
            title = stringResource(R.string.admin_unverified_empty_title),
            message = stringResource(R.string.admin_unverified_empty_message),
            icon = KantaIcons.Success,
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(bottom = ListBottomPadding),
        ) {
            items(list.items, key = { it.id }) { container ->
                val busy = container.id in state.busy
                val focused = state.focusedId == container.id
                KantaListRow(
                    title = container.code,
                    subtitle = listOf(
                        kindLine(container.kind, container.category),
                        pluralStringResource(
                            R.plurals.admin_confirmations,
                            container.confirmations,
                            container.confirmations,
                        ),
                        ageLabel(container.createdAt),
                    ).joinToString(" · "),
                    leading = { KantaStatusDot(ContainerStatus.OK, container.kind) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KantaCompactAction(
                                text = stringResource(R.string.admin_delete),
                                onClick = { onDelete(container) },
                                enabled = !busy,
                                contentColor = MarkerColors.Broken,
                            )
                            Spacer(Modifier.width(Spacing.s))
                            KantaCompactAction(
                                text = stringResource(R.string.admin_verify),
                                onClick = { onVerify(container) },
                                emphasis = true,
                                enabled = !busy,
                            )
                        }
                    },
                    onClick = { onFocus(container.id, container.position) },
                    // The row being looked at on the map is tinted, not boxed (§3.3).
                    modifier = if (focused) Modifier.background(KantaTheme.colors.surfaceMuted) else Modifier,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Coverage
// -------------------------------------------------------------------------------------------

@Composable
private fun CoverageTab(state: AdminUiState, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        when {
            state.coverageLoading && state.coverage == null -> {
                Spacer(Modifier.height(Spacing.m))
                KantaSkeleton(width = 220.dp, height = 16.dp)
                Spacer(Modifier.height(Spacing.s))
                KantaSkeleton(width = 160.dp, height = 12.dp)
            }
            state.coverageError != null && state.coverage == null -> KantaErrorState(
                title = stringResource(state.coverageError.messageRes),
                message = stringResource(R.string.container_detail_error_hint),
                onRetry = onRetry,
            )
            else -> {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = pluralStringResource(
                        R.plurals.admin_coverage_summary,
                        state.coverageCount,
                        state.coverageCount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = stringResource(R.string.admin_coverage_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
                Spacer(Modifier.height(Spacing.l))
                CoverageLegendRows()
            }
        }
    }
}

/** The legend as rows, for the sheet. The map carries a compact copy of it. */
@Composable
private fun CoverageLegendRows() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        LegendRow(checked = true, text = stringResource(R.string.admin_coverage_legend_checked))
        LegendRow(checked = false, text = stringResource(R.string.admin_coverage_legend_unchecked))
    }
}

@Composable
fun CoverageLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.kantaSoftShadow(KantaShape.card),
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            LegendRow(checked = true, text = stringResource(R.string.admin_coverage_legend_checked))
            LegendRow(checked = false, text = stringResource(R.string.admin_coverage_legend_unchecked))
        }
    }
}

@Composable
private fun LegendRow(checked: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .then(
                    if (checked) {
                        // Same green, same softness as the map layer.
                        Modifier.background(MarkerColors.BigOk.copy(alpha = 0.35f), CircleShape)
                    } else {
                        Modifier.border(Spacing.hairline, KantaTheme.colors.outline, CircleShape)
                    },
                ),
        )
        Spacer(Modifier.width(Spacing.s))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// -------------------------------------------------------------------------------------------

@Composable
private fun Skeletons() {
    Column {
        repeat(3) { KantaListRowSkeleton() }
    }
}

@Composable
private fun kindLine(kind: ContainerKind, category: ContainerCategory): String {
    val kindText = stringResource(
        if (kind == ContainerKind.BIG) R.string.container_kind_big else R.string.container_kind_small,
    )
    if (kind == ContainerKind.SMALL || category == ContainerCategory.GENERAL) return kindText
    val categoryText = stringResource(
        when (category) {
            ContainerCategory.GLASS -> R.string.category_glass
            ContainerCategory.PAPER -> R.string.category_paper
            ContainerCategory.PLASTIC -> R.string.category_plastic
            else -> R.string.category_general
        },
    )
    return "$kindText · $categoryText"
}

/** "3 h", "2 d" — how long something has waited, coarse on purpose. */
private fun ageLabel(isoTimestamp: String): String {
    val hours = runCatching {
        val instant = java.time.OffsetDateTime.parse(isoTimestamp).toInstant()
        java.time.Duration.between(instant, java.time.Instant.now()).toMinutes() / 60.0
    }.getOrNull() ?: return ""
    return when {
        hours < 1.0 -> "<1 h"
        hours < 48.0 -> "${hours.toInt()} h"
        else -> "${(hours / 24).toInt()} d"
    }
}
