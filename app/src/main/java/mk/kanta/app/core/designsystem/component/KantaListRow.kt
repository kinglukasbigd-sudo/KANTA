package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.core.designsystem.tabularFigures

/**
 * One row in a list: nearest containers, my reports, suggestions, municipality ranking.
 *
 * Rows separate with a 1dp hairline (§3.3), never a card or shadow. [trailing] is a slot so a
 * row can end in a distance, a status badge or a chevron without this component growing a
 * parameter per case.
 */
@Composable
fun KantaListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val haptics = rememberKantaHaptics()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button) {
                            haptics.tick()
                            onClick()
                        }
                    } else {
                        Modifier
                    },
                )
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                    content = { leading() },
                )
                Spacer(Modifier.width(Spacing.l))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(Spacing.l))
                trailing()
            }
        }

        if (showDivider) {
            HorizontalDivider(
                thickness = Spacing.hairline,
                color = KantaTheme.colors.outline,
                modifier = Modifier.padding(start = Spacing.screenHorizontal),
            )
        }
    }
}

/** Distance in metres, tabular so the digits do not jitter as the user walks (§3.2). */
@Composable
fun KantaDistanceLabel(metres: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$metres m",
        style = MaterialTheme.typography.labelLarge.tabularFigures(),
        color = KantaTheme.colors.onSurfaceMuted,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun ListRowSample() {
    KantaListRow(
        title = "SK-00412",
        subtitle = "Centar · Partizanska",
        leading = { KantaStatusDot(ContainerStatus.OK, ContainerKind.BIG) },
        trailing = { KantaDistanceLabel(80) },
        onClick = {},
    )
    KantaListRow(
        title = "SK-00187",
        subtitle = "Full for 31 h",
        leading = { KantaStatusDot(ContainerStatus.FULL, ContainerKind.BIG) },
        trailing = { KantaStatusBadge(ContainerStatus.FULL) },
        onClick = {},
    )
    KantaListRow(
        title = "Karpoš",
        subtitle = "Median 14 h to resolve",
        trailing = {
            Icon(
                imageVector = KantaIcons.ChevronRight,
                contentDescription = null,
                tint = KantaTheme.colors.onSurfaceMuted,
            )
        },
        onClick = {},
        showDivider = false,
    )
}

@Preview(name = "ListRow · light", widthDp = 400)
@Composable
private fun KantaListRowPreviewLight() = KantaPreview(darkTheme = false) { ListRowSample() }

@Preview(name = "ListRow · dark", widthDp = 400)
@Composable
private fun KantaListRowPreviewDark() = KantaPreview(darkTheme = true) { ListRowSample() }
