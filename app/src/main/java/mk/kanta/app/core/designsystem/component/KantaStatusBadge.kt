package mk.kanta.app.core.designsystem.component

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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

/**
 * Status colour for a container (spec §3.1/§3.4).
 *
 * OK is the only status whose colour depends on the container kind — big containers are green,
 * small street cans yellow. Every problem status reads the same regardless of kind.
 */
fun statusColor(status: ContainerStatus, kind: ContainerKind): Color = when (status) {
    ContainerStatus.OK -> when (kind) {
        ContainerKind.BIG -> MarkerColors.BigOk
        ContainerKind.SMALL -> MarkerColors.SmallOk
    }
    ContainerStatus.FULL -> MarkerColors.Full
    ContainerStatus.BROKEN -> MarkerColors.Broken
    ContainerStatus.DESTROYED -> MarkerColors.Destroyed
    ContainerStatus.MISSING -> MarkerColors.Missing
}

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

/**
 * A small mark carrying the same shape language as the map (§3.4): rounded rectangle for a big
 * container, rounded triangle for a small can, so a row and its marker are recognisably the same
 * thing. MISSING draws hollow and dashed, matching the map.
 */
@Composable
fun KantaStatusDot(
    status: ContainerStatus,
    kind: ContainerKind,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(status, kind)
    val hollow = status == ContainerStatus.MISSING

    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        when (kind) {
            ContainerKind.BIG -> {
                val w = size.width * 0.86f
                val h = size.height * 0.62f
                val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
                val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                if (hollow) {
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(w, h),
                        cornerRadius = corner,
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(3.dp.toPx(), 2.dp.toPx()),
                            ),
                        ),
                    )
                } else {
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(w, h),
                        cornerRadius = corner,
                    )
                }
            }

            ContainerKind.SMALL -> {
                val path = trianglePath(size.width, size.height)
                if (hollow) {
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(3.dp.toPx(), 2.dp.toPx()),
                            ),
                        ),
                    )
                } else {
                    drawPath(path = path, color = color)
                }
            }
        }
    }
}

/** Rounded-ish triangle pointing up, inset inside the given box. */
private fun trianglePath(width: Float, height: Float): Path {
    val inset = width * 0.08f
    return Path().apply {
        moveTo(width / 2f, inset)
        lineTo(width - inset, height - inset)
        lineTo(inset, height - inset)
        close()
    }
}

/**
 * Status as a tinted pill with its label — used on the container detail sheet and in lists.
 * The whole badge is one semantics node so TalkBack reads "Full" once, not shape-then-text.
 */
@Composable
fun KantaStatusBadge(
    status: ContainerStatus,
    modifier: Modifier = Modifier,
    kind: ContainerKind = ContainerKind.BIG,
) {
    val color = statusColor(status, kind)
    val label = statusLabel(status)

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        shape = KantaShape.pill,
        color = color.copy(alpha = if (KantaTheme.colors.isDark) 0.24f else 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            KantaStatusDot(status = status, kind = kind, modifier = Modifier.size(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                // Full-strength status colour on a tint of itself keeps AA contrast in both themes.
                color = color,
            )
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
            KantaStatusDot(status = status, kind = ContainerKind.BIG)
            KantaStatusDot(status = status, kind = ContainerKind.SMALL)
        }
    }
}

@Preview(name = "StatusBadge · light", widthDp = 400)
@Composable
private fun KantaStatusBadgePreviewLight() = KantaPreview(darkTheme = false) { BadgeSample() }

@Preview(name = "StatusBadge · dark", widthDp = 400)
@Composable
private fun KantaStatusBadgePreviewDark() = KantaPreview(darkTheme = true) { BadgeSample() }
