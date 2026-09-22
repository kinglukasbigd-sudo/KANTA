package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing

/**
 * Spec §8: "Every screen has designed loading, empty and error states with a clear next action."
 *
 * Both states below share one layout so empty and error feel like the same app, and both take an
 * action slot — the spec's "clear next action" is a parameter, not an afterthought.
 */
@Composable
private fun KantaStateLayout(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = iconTint.copy(alpha = if (KantaTheme.colors.isDark) 0.20f else 0.10f),
                content = {},
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.s))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = KantaTheme.colors.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )

        if (action != null) {
            Spacer(Modifier.height(Spacing.xl))
            action()
        }
    }
}

/** Nothing here yet — and what the user can do about it. */
@Composable
fun KantaEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = KantaIcons.Empty,
    action: (@Composable () -> Unit)? = null,
) = KantaStateLayout(
    icon = icon,
    iconTint = KantaTheme.colors.onSurfaceMuted,
    title = title,
    message = message,
    modifier = modifier,
    action = action,
)

/**
 * Something went wrong. The retry action is a [KantaSecondaryButton], not primary — an error is
 * not the moment for the loudest thing on screen.
 */
@Composable
fun KantaErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = KantaIcons.Error,
    onRetry: (() -> Unit)? = null,
) = KantaStateLayout(
    icon = icon,
    iconTint = MarkerColors.Broken,
    title = title,
    message = message,
    modifier = modifier,
    action = onRetry?.let {
        {
            KantaSecondaryButton(
                text = stringResource(R.string.action_retry),
                onClick = it,
                icon = KantaIcons.Retry,
            )
        }
    },
)

/** Offline is an error the user can act on differently, so it gets its own entry point. */
@Composable
fun KantaOfflineState(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) = KantaErrorState(
    title = stringResource(R.string.state_offline_title),
    message = stringResource(R.string.state_offline_message),
    icon = KantaIcons.Offline,
    modifier = modifier,
    onRetry = onRetry,
)

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(name = "States · light", widthDp = 400, heightDp = 900)
@Composable
private fun KantaStatesPreviewLight() = KantaPreview(darkTheme = false) {
    KantaEmptyState(
        title = "No free container within 600 m",
        message = "Your report helps the city see this area needs more.",
        action = { KantaPrimaryButton(text = "Suggest a spot", onClick = {}) },
    )
    KantaErrorState(
        title = "Could not load the map",
        message = "Check your connection and try again.",
        onRetry = {},
    )
    KantaOfflineState(onRetry = {})
}

@Preview(name = "States · dark", widthDp = 400, heightDp = 900)
@Composable
private fun KantaStatesPreviewDark() = KantaPreview(darkTheme = true) {
    KantaEmptyState(
        title = "Нема слободен контејнер во 600 м",
        message = "Твојата пријава помага градот да види дека овде треба повеќе.",
        action = { KantaPrimaryButton(text = "Предложи место", onClick = {}) },
    )
    KantaErrorState(
        title = "Мапата не се вчита",
        message = "Провери ја врската и обиди се повторно.",
        onRetry = {},
    )
    KantaOfflineState(onRetry = {})
}
