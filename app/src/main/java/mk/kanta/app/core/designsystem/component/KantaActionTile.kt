package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics

/**
 * The three big choices in the collapsed bottom sheet (spec §4.1): Full · Report · Suggest.
 *
 * A white card with the action's colour only in its icon tile (orange for Full, red for Report,
 * brand green for Suggest), and the label set heavy underneath, left-aligned like a dashboard
 * tile. Three of them side by side read as a calm row of choices, not three loud buttons.
 */
@Composable
fun KantaActionTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberKantaHaptics()

    Surface(
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier
            .defaultMinSize(minHeight = 104.dp)
            .semantics { role = Role.Button },
        enabled = enabled,
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.m),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            KantaIconTile(icon = icon, tint = accent, size = 40.dp)
            Spacer(Modifier.height(Spacing.l))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun TileRowSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        KantaActionTile(
            label = "Full",
            icon = KantaIcons.Full,
            accent = MarkerColors.Full,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
        KantaActionTile(
            label = "Report",
            icon = KantaIcons.Report,
            accent = MarkerColors.Broken,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
        KantaActionTile(
            label = "Suggest",
            icon = KantaIcons.Suggest,
            accent = KantaTheme.colors.brand,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "ActionTile · light", widthDp = 400)
@Composable
private fun KantaActionTilePreviewLight() = KantaPreview(darkTheme = false) { TileRowSample() }

@Preview(name = "ActionTile · dark", widthDp = 400)
@Composable
private fun KantaActionTilePreviewDark() = KantaPreview(darkTheme = true) { TileRowSample() }
