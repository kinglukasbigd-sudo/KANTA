package mk.kanta.app.feature

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton

/**
 * Stand-in for a screen that has not been built yet (spec §4.5).
 *
 * Deliberately an honest empty state rather than a blank page or a TODO: §8 says
 * every screen has a designed empty state with a clear next action, and "not
 * built yet" is just another thing to say plainly.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            KantaEmptyState(
                title = title,
                message = stringResource(R.string.placeholder_message),
                icon = KantaIcons.Empty,
                action = {
                    KantaSecondaryButton(
                        text = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                },
            )
        }
    }
}
