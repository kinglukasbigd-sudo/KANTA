package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing

/**
 * Small uppercase caption that opens a section (spec §3.2 Caption 12/600/+0.4 tracking).
 *
 * Uppercasing happens here rather than in `strings.xml` so translators write normal Macedonian
 * and Albanian; note that `uppercase()` is locale-aware, which matters for Albanian digraphs.
 * Marked as a heading so TalkBack users can jump between sections.
 */
@Composable
fun KantaSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.xl,
                bottom = Spacing.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KantaTheme.colors.onSurfaceMuted,
            modifier = Modifier.semantics { heading() },
        )
        if (action != null) {
            Spacer(Modifier.width(Spacing.m))
            action()
        }
    }
}

/**
 * The grab handle at the top of the bottom sheet (spec §4.1).
 * Purely decorative — the sheet itself carries the drag semantics, so this is hidden from
 * TalkBack rather than announced as a stray element.
 */
@Composable
fun KantaDragHandle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.m),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(KantaShape.pill),
            color = KantaTheme.colors.outline,
            content = {},
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeaderSample() {
    KantaDragHandle()
    KantaSectionHeader(text = "Near you")
    KantaSectionHeader(text = "City stats")
}

@Preview(name = "SectionHeader · light", widthDp = 400)
@Composable
private fun KantaSectionHeaderPreviewLight() = KantaPreview(darkTheme = false) { HeaderSample() }

@Preview(name = "SectionHeader · dark", widthDp = 400)
@Composable
private fun KantaSectionHeaderPreviewDark() = KantaPreview(darkTheme = true) { HeaderSample() }
