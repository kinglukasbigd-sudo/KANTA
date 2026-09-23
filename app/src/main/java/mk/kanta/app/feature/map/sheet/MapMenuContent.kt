package mk.kanta.app.feature.map.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaActionTile
import mk.kanta.app.core.designsystem.component.KantaDistanceLabel
import mk.kanta.app.core.designsystem.component.KantaDragHandle
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRow
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSectionHeader
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusDot

/**
 * The collapsed part of the menu (§4.1): drag handle plus the three big actions.
 *
 * This is also the sheet's drag surface, so at ~140dp collapsed the entire
 * visible sheet responds to a drag.
 */
@Composable
fun ColumnScope.MapMenuHeader(
    onFull: () -> Unit,
    onReport: () -> Unit,
    onSuggest: () -> Unit,
) {
    KantaDragHandle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        KantaActionTile(
            label = stringResource(R.string.action_full),
            icon = KantaIcons.Full,
            accent = MarkerColors.Full,
            onClick = onFull,
            modifier = Modifier.weight(1f),
        )
        KantaActionTile(
            label = stringResource(R.string.action_report),
            icon = KantaIcons.Report,
            accent = MarkerColors.Broken,
            onClick = onReport,
            modifier = Modifier.weight(1f),
        )
        KantaActionTile(
            label = stringResource(R.string.action_suggest),
            icon = KantaIcons.Suggest,
            accent = KantaTheme.colors.brand,
            onClick = onSuggest,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(Spacing.l))
}

/** What the sheet adds at half and expanded (§4.1). */
@Composable
fun ColumnScope.MapMenuBody(
    nearest: NearestContainerUi?,
    nearestLoading: Boolean,
    onWhereToThrow: () -> Unit,
    onNearestClick: (String) -> Unit,
    onMyReports: () -> Unit,
    onCityStats: () -> Unit,
    onSuggestions: () -> Unit,
    onMapYourStreet: () -> Unit,
    showAdmin: Boolean,
    onAdmin: () -> Unit,
) {
    // §3.3: separate with tone and 1dp hairlines, never cards or shadows.
    Hairline()

    KantaSectionHeader(stringResource(R.string.menu_near_you))

    when {
        nearestLoading -> {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                KantaSkeleton(width = 200.dp, height = 16.dp)
                KantaSkeleton(width = 140.dp, height = 12.dp)
            }
        }

        nearest != null -> {
            KantaListRow(
                title = nearest.code,
                subtitle = stringResource(R.string.menu_nearest_not_full),
                leading = { KantaStatusDot(nearest.status, nearest.kind) },
                trailing = { KantaDistanceLabel(nearest.distanceMetres) },
                onClick = { onNearestClick(nearest.id) },
                showDivider = false,
            )
        }

        else -> {
            // §8: an honest empty state, not a blank row.
            Text(
                text = stringResource(R.string.menu_no_nearest),
                style = MaterialTheme.typography.bodyLarge,
                color = KantaTheme.colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            )
        }
    }

    Spacer(Modifier.height(Spacing.m))

    KantaSecondaryButton(
        text = stringResource(R.string.menu_where_to_throw),
        onClick = onWhereToThrow,
        icon = KantaIcons.Navigate,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    )

    Spacer(Modifier.height(Spacing.l))
    Hairline()

    MenuRow(
        title = stringResource(R.string.menu_my_reports),
        subtitle = stringResource(R.string.menu_my_reports_subtitle),
        icon = KantaIcons.Profile,
        onClick = onMyReports,
    )
    // §4.6: "permanently available … under My reports & profile → Map your street".
    MenuRow(
        title = stringResource(R.string.menu_map_street),
        subtitle = stringResource(R.string.menu_map_street_subtitle),
        icon = KantaIcons.MapStreet,
        onClick = onMapYourStreet,
    )
    Hairline()
    MenuRow(
        title = stringResource(R.string.menu_city_stats),
        subtitle = stringResource(R.string.menu_city_stats_subtitle),
        icon = KantaIcons.Stats,
        onClick = onCityStats,
    )
    Hairline()
    MenuRow(
        title = stringResource(R.string.menu_suggestions),
        subtitle = stringResource(R.string.menu_suggestions_subtitle),
        icon = KantaIcons.Suggest,
        onClick = onSuggestions,
    )

    // §4.6: hidden unless profiles.role = 'admin'. The server re-checks every call.
    if (showAdmin) {
        Hairline()
        MenuRow(
            title = stringResource(R.string.menu_admin),
            subtitle = stringResource(R.string.menu_admin_subtitle),
            icon = KantaIcons.Admin,
            onClick = onAdmin,
        )
    }

    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    KantaListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KantaTheme.colors.onSurfaceMuted,
                modifier = Modifier.size(22.dp),
            )
        },
        trailing = {
            Icon(
                imageVector = KantaIcons.ChevronRight,
                contentDescription = null,
                tint = KantaTheme.colors.outline,
            )
        },
        onClick = onClick,
        showDivider = false,
    )
}

@Composable
private fun Hairline() {
    HorizontalDivider(
        thickness = Spacing.hairline,
        color = KantaTheme.colors.outline,
    )
}

/** What the "Near you" row needs, decoupled from the DTO. */
data class NearestContainerUi(
    val id: String,
    val code: String,
    val kind: ContainerKind,
    val status: ContainerStatus,
    val distanceMetres: Int,
)
