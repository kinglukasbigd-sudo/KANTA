package mk.kanta.app.core.designsystem.component

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.marker.BinMarkerPainter

/**
 * Status colour for a container (spec §3.1/§3.4) — the same mapping the map markers use.
 * OK depends on the size (green big, gold small, charcoal while unknown); every problem status
 * reads the same whatever the size.
 */
fun statusColor(status: ContainerStatus, kind: ContainerKind): Color = MarkerColors.status(status, kind)

@Composable
fun statusLabel(status: ContainerStatus): String = stringResource(
    when (status) {
        ContainerStatus.OK -> R.string.status_ok
        ContainerStatus.FULL -> R.string.status_full
        ContainerStatus.BROKEN -> R.string.status_broken
        ContainerStatus.DESTROYED -> R.string.status_destroyed
        ContainerStatus.MISSING -> R.string.status_missing
    },
)

/** "Big container" / "Small can" / "Size not known yet". */
@Composable
fun kindLabel(kind: ContainerKind): String = stringResource(
    when (kind) {
        ContainerKind.BIG -> R.string.container_kind_big
        ContainerKind.SMALL -> R.string.container_kind_small
        ContainerKind.UNKNOWN -> R.string.container_kind_unknown
    },
)

/**
 * The bin mark at list size — the same picture as the map marker ([BinMarkerPainter]): a round
 * universal bin while the size is unknown, a tall tile for a small can, a wide tile for a big
 * container, filled in the status colour. MISSING draws hollow and dashed, matching the map.
 */
@Composable
fun KantaStatusDot(
    status: ContainerStatus,
    kind: ContainerKind,
    modifier: Modifier = Modifier,
) {
    val fill = statusColor(status, kind).toArgb()
    val glyph = MarkerColors.glyph(status, kind).toArgb()
    val ring = MarkerColors.ring(KantaTheme.colors.isDark).toArgb()
    val hollow = status == ContainerStatus.MISSING

    Canvas(modifier = modifier.size(20.dp)) {
        val ringWidth = 1.dp.toPx()
        val body = BinMarkerPainter.fit(
            kind,
            RectF(ringWidth, ringWidth, size.width - ringWidth, size.height - ringWidth),
        )
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (hollow) {
                native.drawPath(
                    BinMarkerPainter.bodyPath(kind, body),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = fill
                        strokeWidth = 1.5.dp.toPx()
                        pathEffect = DashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.dp.toPx()), 0f)
                    },
                )
                BinMarkerPainter.drawGlyph(native, kind, body, fill, knockout = null, alpha = 150)
            } else {
                // The ring only shows where the surface is dark enough to need it, exactly as on
                // the map: it is what keeps the charcoal universal mark visible in dark mode.
                val ringed = RectF(body).apply { inset(-ringWidth, -ringWidth) }
                native.drawPath(
                    BinMarkerPainter.bodyPath(kind, ringed),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring },
                )
                native.drawPath(
                    BinMarkerPainter.bodyPath(kind, body),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill },
                )
                BinMarkerPainter.drawGlyph(native, kind, body, glyph, knockout = fill)
            }
        }
    }
}

/**
 * Status as a quiet pill: a status dot and the label in ink on a neutral surface. Colour lives in
 * the dot only, so the label keeps full contrast whatever the status (gold or ember text on a
 * tint of itself never reads well). The whole badge is one semantics node so TalkBack reads
 * "Full" once.
 */
@Composable
fun KantaStatusBadge(
    status: ContainerStatus,
    modifier: Modifier = Modifier,
    kind: ContainerKind = ContainerKind.BIG,
) {
    val label = statusLabel(status)

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        shape = KantaShape.pill,
        color = KantaTheme.colors.surfaceMuted,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.s, end = Spacing.m, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(statusColor(status, kind)) }
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun BadgeSample() {
    ContainerStatus.entries.forEach { status ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            KantaStatusBadge(status = status, kind = ContainerKind.BIG)
            KantaStatusDot(status = status, kind = ContainerKind.UNKNOWN, modifier = Modifier.size(28.dp))
            KantaStatusDot(status = status, kind = ContainerKind.SMALL, modifier = Modifier.size(28.dp))
            KantaStatusDot(status = status, kind = ContainerKind.BIG, modifier = Modifier.size(28.dp))
        }
    }
}

@Preview(name = "StatusBadge · light", widthDp = 400)
@Composable
private fun KantaStatusBadgePreviewLight() = KantaPreview(darkTheme = false) { BadgeSample() }

@Preview(name = "StatusBadge · dark", widthDp = 400)
@Composable
private fun KantaStatusBadgePreviewDark() = KantaPreview(darkTheme = true) { BadgeSample() }
