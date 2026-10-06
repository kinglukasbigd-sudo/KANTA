package mk.kanta.app.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.core.designsystem.tabularFigures

/*
 * The page-level pieces every screen is built from (KANTA_SPEC.md §3.3):
 *
 *  - [KantaTopBar] and [KantaPageTitle]: one header for every full screen — a square back button,
 *    then a mono caption over a big, tight title.
 *  - [KantaCard] and [KantaGroup]: content sits on white cards with an oat hairline on the ivory
 *    page, and lists are grouped inside one card instead of running edge to edge.
 *  - [KantaIconTile], [KantaStatTile], [KantaBanner], [KantaTextField], [KantaIconButton].
 */

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

/**
 * A square icon button with a stone outline — back, settings, close. 44dp, so a row of them
 * stays inside the §8 touch-target rule without looking heavy.
 */
@Composable
fun KantaIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = rememberKantaHaptics()
    Surface(
        onClick = {
            haptics.tick()
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = KantaShape.button,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else KantaTheme.colors.onSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The bar at the top of every full screen: back on the left, optional actions on the right, and
 * optionally a small mono [title] between them for screens that scroll their big title away.
 */
@Composable
fun KantaTopBar(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String? = null,
    backEnabled: Boolean = true,
    /** "Close" (an X) instead of a back arrow, for flows that cancel rather than go back. */
    close: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (onBack != null) {
            KantaIconButton(
                icon = if (close) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(if (close) R.string.action_cancel else R.string.action_back),
                onClick = onBack,
                enabled = backEnabled,
            )
        }
        Box(Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KantaTheme.colors.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * The big title that opens a screen: a mono [eyebrow] caption, the title set tight and heavy,
 * and an optional one-line [subtitle] in muted body text.
 */
@Composable
fun KantaPageTitle(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.s, bottom = Spacing.l),
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = KantaTheme.colors.brand,
            )
            Spacer(Modifier.height(Spacing.s))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = KantaTheme.colors.onSurfaceMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------------------------

/**
 * A white card with an oat hairline on the ivory page (carbon-on-carbon in dark). No shadow
 * (§3.3): tone and the hairline do the separating. Clickable when [onClick] is set.
 */
@Composable
fun KantaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    border: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val stroke = if (border) BorderStroke(Spacing.hairline, KantaTheme.colors.outline) else null
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }
    if (onClick != null) {
        val haptics = rememberKantaHaptics()
        Surface(
            onClick = {
                haptics.tick()
                onClick()
            },
            modifier = modifier,
            shape = KantaShape.card,
            color = color,
            border = stroke,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = KantaShape.card,
            color = color,
            border = stroke,
            content = body,
        )
    }
}

/**
 * A grouped list: rows inside one card, inset from the screen edge. Use [KantaListRow] with
 * `inset = true` inside it, and [KantaGroupDivider] between rows.
 */
@Composable
fun KantaGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    KantaCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        contentPadding = PaddingValues(0.dp),
        content = content,
    )
}

/** The hairline between two rows of a [KantaGroup], inset to line up with the row text. */
@Composable
fun KantaGroupDivider(startInset: Dp = Spacing.l) {
    HorizontalDivider(
        thickness = Spacing.hairline,
        color = KantaTheme.colors.outline,
        modifier = Modifier.padding(start = startInset),
    )
}

/**
 * An icon on a tinted rounded square: menu rows, banners, empty states. The tint is the icon's
 * own colour at low strength, so a row of tiles stays calm.
 */
@Composable
fun KantaIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = KantaTheme.colors.brand,
    size: Dp = 36.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = KantaShape.button,
        color = tint.copy(alpha = if (KantaTheme.colors.isDark) 0.18f else 0.10f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * One big figure with a mono caption under it — impact counters, city stats. The figure is
 * tabular so a changing count never jitters.
 */
@Composable
fun KantaStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    KantaCard(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall.tabularFigures(),
            color = accent,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KantaTheme.colors.onSurfaceMuted,
            maxLines = 2,
        )
    }
}

/** What a [KantaBanner] is saying — drives the icon tile's colour. */
enum class KantaBannerTone { INFO, SUCCESS, ERROR }

/**
 * A one-line message on a card: offline, a send that failed, a thank-you. The colour lives in
 * the icon tile; the text stays ink so it always reads.
 */
@Composable
fun KantaBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: KantaBannerTone = KantaBannerTone.INFO,
    icon: ImageVector = when (tone) {
        KantaBannerTone.INFO -> KantaIcons.Offline
        KantaBannerTone.SUCCESS -> KantaIcons.Success
        KantaBannerTone.ERROR -> KantaIcons.Error
    },
    onDismiss: (() -> Unit)? = null,
) {
    val tint = when (tone) {
        KantaBannerTone.INFO -> KantaTheme.colors.onSurfaceMuted
        KantaBannerTone.SUCCESS -> KantaTheme.colors.brand
        KantaBannerTone.ERROR -> KantaTheme.colors.error
    }
    KantaCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KantaIconTile(icon = icon, tint = tint, size = 32.dp)
            Spacer(Modifier.width(Spacing.m))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Spacer(Modifier.width(Spacing.s))
                KantaIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    onClick = onDismiss,
                    tint = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------------------------

/**
 * The one text-field style in the app: white field, stone outline that turns brand on focus,
 * 12dp corners, the label floating into the border.
 */
@Composable
fun KantaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = KantaShape.button,
        textStyle = MaterialTheme.typography.bodyLarge,
        supportingText = supportingText?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.tabularFigures(),
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = KantaTheme.colors.surfaceMuted,
            errorContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = KantaTheme.colors.brand,
            unfocusedBorderColor = KantaTheme.colors.outlineStrong,
            disabledBorderColor = KantaTheme.colors.outline,
            errorBorderColor = KantaTheme.colors.error,
            focusedLabelColor = KantaTheme.colors.brand,
            unfocusedLabelColor = KantaTheme.colors.onSurfaceMuted,
            errorLabelColor = KantaTheme.colors.error,
            cursorColor = KantaTheme.colors.brand,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun LayoutSample() {
    KantaTopBar(onBack = {}, title = "Profile") {
        KantaIconButton(icon = KantaIcons.Settings, contentDescription = "Settings", onClick = {})
    }
    KantaPageTitle(title = "Your reports", eyebrow = "Kanta · Skopje", subtitle = "What your reports got done.")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        KantaStatTile(value = "12", label = "Emptied", modifier = Modifier.weight(1f), accent = MarkerColors.BigOk)
        KantaStatTile(value = "3", label = "Repaired", modifier = Modifier.weight(1f))
    }
    KantaGroup {
        KantaListRow(title = "Map your street", subtitle = "Check the bins around you", inset = true, onClick = {})
        KantaGroupDivider()
        KantaListRow(title = "City stats", subtitle = "How fast each area fixes things", inset = true, onClick = {})
    }
    KantaBanner(text = "You're offline. The map shows what was saved.", onDismiss = {})
}

@Preview(name = "Layout · light", widthDp = 400, heightDp = 900)
@Composable
private fun KantaLayoutPreviewLight() = KantaPreview(darkTheme = false) { LayoutSample() }

@Preview(name = "Layout · dark", widthDp = 400, heightDp = 900)
@Composable
private fun KantaLayoutPreviewDark() = KantaPreview(darkTheme = true) { LayoutSample() }
