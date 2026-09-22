package mk.kanta.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics

/**
 * Selectable chip — the report-kind and suggestion-reason pickers (spec §4.3, §4.4).
 *
 * Selection is carried by fill + outline, not by a checkmark, to keep the surface quiet.
 * The [Role.RadioButton] semantics mean TalkBack announces selected state without us adding a
 * "selected" string, and the 48dp min height keeps it within the §8 touch-target rule.
 */
@Composable
fun KantaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberKantaHaptics()

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> KantaTheme.colors.surfaceMuted
            selected -> MaterialTheme.colorScheme.primary
            else -> KantaTheme.colors.surfaceMuted
        },
        animationSpec = Motion.tweenFast(),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> KantaTheme.colors.onSurfaceMuted
            selected -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = Motion.tweenFast(),
        label = "chipContent",
    )

    Surface(
        selected = selected,
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier
            .defaultMinSize(minHeight = Spacing.minTouchTarget)
            .semantics { role = Role.RadioButton },
        enabled = enabled,
        shape = KantaShape.chip,
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.s))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(name = "Chip · light")
@Composable
private fun KantaChipPreviewLight() = KantaPreview(darkTheme = false) {
    KantaChip(label = "Damaged", selected = true, onClick = {})
    KantaChip(label = "Destroyed", selected = false, onClick = {})
    KantaChip(label = "Burning", selected = false, onClick = {}, enabled = false)
}

@Preview(name = "Chip · dark")
@Composable
private fun KantaChipPreviewDark() = KantaPreview(darkTheme = true) {
    KantaChip(label = "Оштетен", selected = true, onClick = {})
    KantaChip(label = "Уништен", selected = false, onClick = {})
    KantaChip(label = "Гори", selected = false, onClick = {}, enabled = false)
}
