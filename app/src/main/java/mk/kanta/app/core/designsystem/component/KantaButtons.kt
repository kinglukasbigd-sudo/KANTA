package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.kantaSoftShadow
import mk.kanta.app.core.designsystem.rememberKantaHaptics

/**
 * The one loud action on a screen (spec §3: "colour is spent almost only on container markers
 * and one primary action"). Full pill, brand fill.
 *
 * [elevated] adds the app's only shadow (§3.3) and is reserved for the primary *floating*
 * button over the map — not for buttons sitting inside a sheet or card.
 */
@Composable
fun KantaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    elevated: Boolean = false,
) {
    val haptics = rememberKantaHaptics()

    Button(
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier
            .then(if (elevated) Modifier.kantaSoftShadow(KantaShape.pill) else Modifier)
            .defaultMinSize(minHeight = Spacing.minTouchTarget),
        enabled = enabled,
        shape = KantaShape.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = KantaTheme.colors.surfaceMuted,
            disabledContentColor = KantaTheme.colors.onSurfaceMuted,
        ),
        // Shadow, when wanted, comes from kantaSoftShadow; Material's own elevation stays off (§3.3).
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.m),
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/** Quiet companion action — hairline outline, no fill (spec §3.3). */
@Composable
fun KantaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberKantaHaptics()

    OutlinedButton(
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier.defaultMinSize(minHeight = Spacing.minTouchTarget),
        enabled = enabled,
        shape = KantaShape.pill,
        border = BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = KantaTheme.colors.onSurfaceMuted,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.m),
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                // Decorative: the button's own text already labels the action for TalkBack.
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.s))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(name = "Buttons · light")
@Composable
private fun KantaButtonsPreviewLight() = KantaPreview(darkTheme = false) {
    KantaPrimaryButton(text = "Send", onClick = {})
    KantaPrimaryButton(text = "Send", onClick = {}, elevated = true)
    KantaPrimaryButton(text = "Send", onClick = {}, enabled = false)
    KantaSecondaryButton(text = "Cancel", onClick = {})
    KantaSecondaryButton(text = "Cancel", onClick = {}, enabled = false)
}

@Preview(name = "Buttons · dark")
@Composable
private fun KantaButtonsPreviewDark() = KantaPreview(darkTheme = true) {
    KantaPrimaryButton(text = "Испрати", onClick = {})
    KantaPrimaryButton(text = "Испрати", onClick = {}, elevated = true)
    KantaPrimaryButton(text = "Испрати", onClick = {}, enabled = false)
    KantaSecondaryButton(text = "Откажи", onClick = {})
    KantaSecondaryButton(text = "Откажи", onClick = {}, enabled = false)
}
