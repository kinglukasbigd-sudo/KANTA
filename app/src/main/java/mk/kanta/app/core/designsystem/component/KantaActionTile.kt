package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
 * The tile surface itself stays quiet; the colour lives in a small tinted icon puck, which is
 * how the sheet can show three coloured actions without becoming loud. [accent] is the action's
 * own colour from §3.1 (orange for Full, red for Report, brand green for Suggest).
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
            .defaultMinSize(minHeight = 96.dp)
            .semantics { role = Role.Button },
        enabled = enabled,
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
    ) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.l, horizontal = Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    // A tint of the action colour, not the full colour: §3 restraint.
                    color = accent.copy(alpha = if (KantaTheme.colors.isDark) 0.22f else 0.12f),
                    content = {},
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(Spacing.m))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
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
