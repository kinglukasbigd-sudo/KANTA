package mk.kanta.app.feature.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.network.publicPhotoUrl

/**
 * §5.4 before/after: my photo and the one taken when it was fixed, one over the
 * other, with a divider the user drags across. The two photos are of the same
 * container from roughly the same spot, so a wipe shows the change better than
 * two small pictures side by side.
 */
@Composable
fun BeforeAfter(beforePath: String, afterPath: String, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }
    val density = LocalDensity.current
    val beforeLabel = stringResource(R.string.me_before)
    val afterLabel = stringResource(R.string.me_after)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(KantaShape.card)
            .semantics { contentDescription = "$beforeLabel / $afterLabel" },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val drag = rememberDraggableState { delta ->
            split = (split + delta / widthPx).coerceIn(0.05f, 0.95f)
        }

        AsyncImage(
            model = publicPhotoUrl(afterPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        AsyncImage(
            model = publicPhotoUrl(beforePath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                },
        )

        // The divider and its grip; the whole picture is the drag target.
        Canvas(
            Modifier
                .fillMaxSize()
                .draggable(drag, Orientation.Horizontal),
        ) {
            val x = size.width * split
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 14.dp.toPx(), center = Offset(x, size.height / 2f))
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = Offset(x, size.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }

        Tag(beforeLabel, Modifier.align(Alignment.TopStart))
        Tag(afterLabel, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(Spacing.s),
        shape = KantaShape.pill,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp),
        )
    }
}

/** Resolved without a photo (two neighbours' word, §5.1): only "before" exists. */
@Composable
fun BeforeOnly(beforePath: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(180.dp).clip(KantaShape.card)) {
        AsyncImage(
            model = publicPhotoUrl(beforePath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Tag(stringResource(R.string.me_before), Modifier.align(Alignment.TopStart))
    }
}
