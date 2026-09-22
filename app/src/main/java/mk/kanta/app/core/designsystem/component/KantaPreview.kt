package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing

/**
 * Shared scaffold for the component previews in this package: applies the theme, paints the
 * app background (so dark previews are not rendered on white) and lays children out on the
 * spacing scale. Every component in this package previews in both light and dark through this.
 */
@Composable
internal fun KantaPreview(
    darkTheme: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    KantaTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
                content = content,
            )
        }
    }
}
