package mk.kanta.app.feature.alternatives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaCompactAction
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaErrorState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.component.statusLabel
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.core.designsystem.tabularFigures
import mk.kanta.app.feature.map.openInMaps

/**
 * "Nearest containers with space" (§5.2) — the sheet content. The map above
 * highlights the same containers; a row tap takes the camera there, "Navigate"
 * hands it to a maps app.
 */
@Composable
fun ColumnScope.AlternativesContent(
    state: AlternativesUiState,
    onRowClick: (AlternativeUi) -> Unit,
    onRetry: () -> Unit,
    onSuggestHere: () -> Unit,
) {
    Header(state)

    when {
        state.loading && state.items.isEmpty() -> Column(Modifier.padding(top = Spacing.s)) {
            repeat(4) { KantaListRowSkeleton() }
        }

        state.locationMissing -> Scrollable {
            KantaErrorState(
                title = stringResource(R.string.alternatives_location_title),
                message = stringResource(R.string.alternatives_location_needed),
                icon = KantaIcons.MyLocation,
                onRetry = onRetry,
            )
        }

        state.error != null && state.items.isEmpty() -> Scrollable {
            KantaErrorState(
                title = stringResource(state.error.messageRes),
                message = stringResource(R.string.container_detail_error_hint),
                onRetry = onRetry,
            )
        }

        // §5.2: the honest empty state, and something the user can do about it.
        state.empty -> Scrollable {
            KantaEmptyState(
                title = stringResource(R.string.alternatives_empty_title),
                message = stringResource(
                    if (state.origin is AlternativesOrigin.Container) {
                        R.string.alternatives_empty_message
                    } else {
                        R.string.alternatives_empty_message_here
                    },
                ),
                icon = KantaIcons.Place,
                action = {
                    KantaPrimaryButton(
                        text = stringResource(R.string.alternatives_suggest_here),
                        onClick = onSuggestHere,
                        icon = KantaIcons.Suggest,
                    )
                },
            )
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            items(state.items, key = { it.id }) { item ->
                AlternativeRow(
                    item = item,
                    focused = state.focusedId == item.id,
                    onClick = { onRowClick(item) },
                )
            }
        }
    }
}

@Composable
private fun Header(state: AlternativesUiState) {
    Column(
        Modifier
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(bottom = Spacing.m),
    ) {
        Text(
            text = stringResource(R.string.alternatives_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        val origin = state.origin
        val from = if (origin is AlternativesOrigin.Container) {
            stringResource(R.string.alternatives_from_container, origin.code)
        } else {
            stringResource(R.string.alternatives_from_you)
        }
        // §5.2 "same category": say which, when it is a recycling material.
        val material = when (state.category) {
            ContainerCategory.GLASS -> stringResource(R.string.category_glass)
            ContainerCategory.PAPER -> stringResource(R.string.category_paper)
            ContainerCategory.PLASTIC -> stringResource(R.string.category_plastic)
            ContainerCategory.MIXED_RECYCLING -> stringResource(R.string.category_mixed_recycling)
            ContainerCategory.GENERAL -> null
        }
        Text(
            text = listOfNotNull(from, material).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = KantaTheme.colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun AlternativeRow(
    item: AlternativeUi,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberKantaHaptics()
    val status = statusLabel(item.status)
    val distance = stringResource(R.string.alternatives_distance, item.distanceMetres, item.walkingMinutes)
    val closeLabel = stringResource(R.string.alternatives_close)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tone, not a box (§3.3): the row being looked at on the map is tinted.
            .background(if (focused) KantaTheme.colors.surfaceMuted else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .heightIn(min = Spacing.minTouchTarget)
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.m)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    item.code, status, distance, closeLabel.takeIf { item.close },
                ).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KantaStatusDot(item.status, item.kind)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.code,
                    style = MaterialTheme.typography.titleSmall.tabularFigures(),
                )
                // §5.2: "highlight those ≤ 300 m".
                if (item.close) {
                    Spacer(Modifier.width(Spacing.s))
                    CloseTag(closeLabel)
                }
            }
            Text(
                text = "$status · $distance",
                style = MaterialTheme.typography.bodySmall.tabularFigures(),
                color = if (item.close) KantaTheme.colors.brand else KantaTheme.colors.onSurfaceMuted,
            )
        }
        Spacer(Modifier.width(Spacing.s))
        KantaCompactAction(
            text = stringResource(R.string.container_action_navigate),
            onClick = { context.openInMaps(item.position.lat, item.position.lon, item.code) },
            emphasis = item.close,
        )
    }
}

@Composable
private fun CloseTag(text: String) {
    Surface(shape = KantaShape.pill, color = KantaTheme.colors.brand.copy(alpha = 0.14f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = KantaTheme.colors.brand,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp),
        )
    }
}

@Composable
private fun Scrollable(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState())) {
        content()
        Spacer(Modifier.height(Spacing.xl))
    }
}

