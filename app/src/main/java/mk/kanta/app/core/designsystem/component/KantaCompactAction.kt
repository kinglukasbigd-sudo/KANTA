package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics

/**
 * A small pill action that sits at the end of a list row ("Yes, it's here",
 * "Verify"). Full-width buttons in every row would turn a list into a wall of
 * buttons; this keeps the row the thing and the action a quiet affordance.
 *
 * Still 48dp tall in touch terms (§8) — the visible pill is smaller, the target
 * is not. [emphasis] fills it with the brand colour for the one action a row is
 * for; without it the pill is a hairline outline.
 */
@Composable
fun KantaCompactAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    enabled: Boolean = true,
    contentColor: Color? = null,
) {
    val haptics = rememberKantaHaptics()
    Box(
        modifier = modifier.defaultMinSize(minHeight = Spacing.minTouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = {
                haptics.tick()
                onClick()
            },
            enabled = enabled,
            shape = KantaShape.pill,
            color = if (emphasis && enabled) MaterialTheme.colorScheme.primary else Color.Transparent,
            border = if (emphasis) null else BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .semantics { role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !enabled -> KantaTheme.colors.onSurfaceMuted
                        emphasis -> MaterialTheme.colorScheme.onPrimary
                        else -> contentColor ?: MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
        }
    }
}
