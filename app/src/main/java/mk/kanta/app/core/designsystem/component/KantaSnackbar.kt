package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing

/** What a toast is telling the user — drives the leading icon only, never a loud fill. */
enum class KantaToastKind { INFO, SUCCESS, ERROR }

/**
 * Kanta's snackbar style: a quiet surface card with a hairline, not Material's dark inverse
 * slab. Sits on the app's own surface tokens so it reads as part of the sheet it interrupts.
 */
@Composable
fun KantaSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
    kind: KantaToastKind = KantaToastKind.INFO,
) {
    val icon: ImageVector? = when (kind) {
        KantaToastKind.INFO -> null
        KantaToastKind.SUCCESS -> KantaIcons.Success
        KantaToastKind.ERROR -> KantaIcons.Error
    }
    val iconTint = when (kind) {
        KantaToastKind.INFO -> KantaTheme.colors.onSurfaceMuted
        KantaToastKind.SUCCESS -> KantaTheme.colors.brand
        KantaToastKind.ERROR -> MarkerColors.Broken
    }

    Snackbar(
        modifier = modifier.padding(Spacing.l),
        shape = KantaShape.card,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        actionContentColor = KantaTheme.colors.brand,
        dismissActionContentColor = KantaTheme.colors.onSurfaceMuted,
        action = data.visuals.actionLabel?.let { label ->
            {
                TextButton(onClick = { data.performAction() }) {
                    Text(text = label, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Drop-in host that applies the Kanta snackbar style. */
@Composable
fun KantaSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    kind: KantaToastKind = KantaToastKind.INFO,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        KantaSnackbar(data = data, kind = kind)
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

/** Minimal stand-in so the snackbar can be previewed without a live host. */
private class PreviewSnackbarData(
    private val text: String,
    private val action: String?,
) : SnackbarData {
    override val visuals = object : androidx.compose.material3.SnackbarVisuals {
        override val actionLabel = action
        override val duration = androidx.compose.material3.SnackbarDuration.Short
        override val message = text
        override val withDismissAction = false
    }

    override fun performAction() = Unit
    override fun dismiss() = Unit
}

@Composable
private fun SnackbarSample() {
    KantaSnackbar(
        data = PreviewSnackbarData("Report sent. Thanks.", null),
        kind = KantaToastKind.SUCCESS,
    )
    KantaSnackbar(
        data = PreviewSnackbarData("Will send when online", "Undo"),
        kind = KantaToastKind.INFO,
    )
    KantaSnackbar(
        data = PreviewSnackbarData("Move closer to the container", "Retry"),
        kind = KantaToastKind.ERROR,
    )
}

@Preview(name = "Snackbar · light", widthDp = 400)
@Composable
private fun KantaSnackbarPreviewLight() = KantaPreview(darkTheme = false) { SnackbarSample() }

@Preview(name = "Snackbar · dark", widthDp = 400)
@Composable
private fun KantaSnackbarPreviewDark() = KantaPreview(darkTheme = true) { SnackbarSample() }
