package mk.kanta.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics

/**
 * Small or Big — the main thing a person does at a bin (§4.6 "Bin size"), and the size choice in
 * the Add container flow.
 *
 * Two equal tiles, each showing the mark the bin will get on the map, its name and what it looks
 * like. The person's own answer is the selected tile (mint, brand border, check). Answer counts
 * show under each tile when there are any, so it is clear the map follows the neighbours.
 */
@Composable
fun KantaSizeChooser(
    selected: ContainerKind?,
    onChoose: (ContainerKind) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The tile whose answer is on its way; both tiles hold still meanwhile. */
    sending: ContainerKind? = null,
    votesSmall: Long = 0,
    votesBig: Long = 0,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        for (kind in listOf(ContainerKind.SMALL, ContainerKind.BIG)) {
            SizeTile(
                kind = kind,
                selected = selected == kind,
                sending = sending == kind,
                enabled = enabled && sending == null,
                votes = if (kind == ContainerKind.SMALL) votesSmall else votesBig,
                onClick = { onChoose(kind) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SizeTile(
    kind: ContainerKind,
    selected: Boolean,
    sending: Boolean,
    enabled: Boolean,
    votes: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKantaHaptics()
    val colors = KantaTheme.colors

    val container by animateColorAsState(
        targetValue = if (selected) colors.brandContainer else MaterialTheme.colorScheme.surface,
        animationSpec = Motion.tweenFast(),
        label = "sizeTileContainer",
    )
    val border = when {
        selected -> BorderStroke(1.5.dp, colors.brand)
        enabled -> BorderStroke(Spacing.hairline, colors.outlineStrong)
        else -> BorderStroke(Spacing.hairline, colors.outline)
    }
    val titleColor = when {
        selected -> colors.brand
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> colors.onSurfaceMuted
    }

    Surface(
        selected = selected,
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier
            .defaultMinSize(minHeight = 128.dp)
            .semantics { role = Role.RadioButton },
        enabled = enabled,
        shape = KantaShape.button,
        color = container,
        border = border,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.m, vertical = Spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KantaStatusDot(ContainerStatus.OK, kind, Modifier.size(44.dp))
                Spacer(Modifier.height(Spacing.m))
                Text(
                    text = kindLabel(kind),
                    style = MaterialTheme.typography.labelLarge,
                    color = titleColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(
                        when {
                            sending -> R.string.report_sending
                            kind == ContainerKind.SMALL -> R.string.size_small_caption
                            else -> R.string.size_big_caption
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
                if (votes > 0) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = pluralStringResource(R.plurals.size_answers, votes.toInt(), votes.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) colors.brand else colors.onSurfaceMuted,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = KantaIcons.Success,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.s)
                        .size(18.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun ChooserSample() {
    KantaSizeChooser(selected = null, onChoose = {})
    KantaSizeChooser(selected = ContainerKind.BIG, onChoose = {}, votesBig = 2, votesSmall = 1)
    KantaSizeChooser(selected = null, onChoose = {}, enabled = false)
}

@Preview(name = "SizeChooser · light", widthDp = 400)
@Composable
private fun KantaSizeChooserPreviewLight() = KantaPreview(darkTheme = false) { ChooserSample() }

@Preview(name = "SizeChooser · dark", widthDp = 400)
@Composable
private fun KantaSizeChooserPreviewDark() = KantaPreview(darkTheme = true) { ChooserSample() }
