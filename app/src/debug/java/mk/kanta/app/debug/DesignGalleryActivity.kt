package mk.kanta.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaActionTile
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaDistanceLabel
import mk.kanta.app.core.designsystem.component.KantaDragHandle
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaErrorState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRow
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaOfflineState
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaSectionHeader
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusBadge
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.component.KantaToastKind
import mk.kanta.app.core.designsystem.component.KantaSnackbar
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.designsystem.tabularFigures

/**
 * Debug-only design gallery (KANTA_SPEC.md §3).
 *
 * Lives in `src/debug`, so it is compiled out of release builds entirely — there is no flag to
 * get it wrong. Shows every component and every marker side by side in light and dark so the
 * two themes can be compared without changing a system setting.
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DesignGalleryScreen() }
    }
}

@Composable
private fun DesignGalleryScreen() {
    // Both themes are rendered at once, side by side, rather than toggled: comparing them is
    // the whole point of the gallery.
    Row(Modifier.fillMaxSize()) {
        GalleryColumn(darkTheme = false, modifier = Modifier.weight(1f))
        GalleryColumn(darkTheme = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GalleryColumn(darkTheme: Boolean, modifier: Modifier = Modifier) {
    KantaTheme(darkTheme = darkTheme) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.xxxl),
            ) {
                GalleryHeader(if (darkTheme) "DARK" else "LIGHT")
                TypographySection()
                ColorSection()
                ButtonSection()
                ChipSection()
                ActionTileSection()
                ListRowSection()
                StatusSection()
                SkeletonSection()
                StateSection()
                SnackbarSection()
                MarkerSection(darkTheme = darkTheme)
            }
        }
    }
}

@Composable
private fun GalleryHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = KantaTheme.colors.onSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxl, bottom = Spacing.s),
    )
}

@Composable
private fun TypographySection() {
    KantaSectionHeader("Type · Nunito")
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text("Канта 34", style = MaterialTheme.typography.displayLarge)
        Text("Наслов 22", style = MaterialTheme.typography.titleLarge)
        Text("Headline 18", style = MaterialTheme.typography.headlineSmall)
        Text("Body 16 — ëçËÇ Ќ Џ ш њ", style = MaterialTheme.typography.bodyLarge)
        Text("Label 14", style = MaterialTheme.typography.labelLarge)
        Text("CAPTION 12", style = MaterialTheme.typography.labelSmall)
        Text(
            "1234567890 tabular",
            style = MaterialTheme.typography.bodyLarge.tabularFigures(),
        )
    }
}

@Composable
private fun ColorSection() {
    KantaSectionHeader("Colour tokens")
    val c = KantaTheme.colors
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Swatch("brand", c.brand)
        Swatch("accent", c.accent)
        Swatch("background", MaterialTheme.colorScheme.background)
        Swatch("surface", MaterialTheme.colorScheme.surface)
        Swatch("surfaceMuted", c.surfaceMuted)
        Swatch("onSurface", MaterialTheme.colorScheme.onSurface)
        Swatch("onSurfaceMuted", c.onSurfaceMuted)
        Swatch("outline", c.outline)
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .background(color, KantaShape.chip),
        )
        Spacer(Modifier.width(Spacing.m))
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ButtonSection() {
    KantaSectionHeader("Buttons")
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        KantaPrimaryButton("Испрати", onClick = {}, icon = KantaIcons.Send)
        KantaPrimaryButton("Elevated", onClick = {}, elevated = true)
        KantaPrimaryButton("Disabled", onClick = {}, enabled = false)
        KantaSecondaryButton("Откажи", onClick = {})
        KantaSecondaryButton("Disabled", onClick = {}, enabled = false)
    }
}

@Composable
private fun ChipSection() {
    KantaSectionHeader("Chips")
    var selected by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        listOf("Оштетен", "Уништен", "Гори").forEachIndexed { index, label ->
            KantaChip(
                label = label,
                selected = selected == index,
                onClick = { selected = index },
            )
        }
        KantaChip(label = "Disabled", selected = false, onClick = {}, enabled = false)
    }
}

@Composable
private fun ActionTileSection() {
    KantaSectionHeader("Action tiles")
    Row(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        KantaActionTile(
            label = "Полн",
            icon = KantaIcons.Full,
            accent = MarkerColors.Full,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
        KantaActionTile(
            label = "Пријави",
            icon = KantaIcons.Report,
            accent = MarkerColors.Broken,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
        KantaActionTile(
            label = "Предложи",
            icon = KantaIcons.Suggest,
            accent = KantaTheme.colors.brand,
            onClick = {},
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ListRowSection() {
    KantaSectionHeader("List rows")
    KantaListRow(
        title = "SK-00412",
        subtitle = "Centar",
        leading = { KantaStatusDot(ContainerStatus.OK, ContainerKind.BIG) },
        trailing = { KantaDistanceLabel(80) },
        onClick = {},
    )
    KantaListRow(
        title = "SK-00187",
        subtitle = "Full for 31 h",
        leading = { KantaStatusDot(ContainerStatus.FULL, ContainerKind.BIG) },
        trailing = { KantaStatusBadge(ContainerStatus.FULL) },
        onClick = {},
        showDivider = false,
    )
    KantaDragHandle()
}

@Composable
private fun StatusSection() {
    KantaSectionHeader("Status badges")
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        ContainerStatus.entries.forEach { status ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                KantaStatusBadge(status)
                KantaStatusDot(status, ContainerKind.BIG)
                KantaStatusDot(status, ContainerKind.SMALL)
            }
        }
    }
}

@Composable
private fun SkeletonSection() {
    KantaSectionHeader("Skeletons")
    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        KantaSkeleton(width = 160.dp, height = 22.dp)
        Spacer(Modifier.height(Spacing.s))
        KantaSkeleton()
    }
    KantaListRowSkeleton()
}

@Composable
private fun StateSection() {
    KantaSectionHeader("States")
    KantaEmptyState(
        title = "Нема контејнер во 600 м",
        message = "Твојата пријава помага.",
        action = { KantaPrimaryButton("Предложи", onClick = {}) },
    )
    KantaErrorState(
        title = "Грешка",
        message = "Обиди се повторно.",
        onRetry = {},
    )
    KantaOfflineState(onRetry = {})
}

@Composable
private fun SnackbarSection() {
    KantaSectionHeader("Toasts")
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        KantaSnackbar(GalleryToast("Пријавата е испратена.", null), kind = KantaToastKind.SUCCESS)
        KantaSnackbar(GalleryToast("Ќе се испрати кога ќе има интернет", "Undo"), kind = KantaToastKind.INFO)
        KantaSnackbar(GalleryToast("Пријди поблиску", "Retry"), kind = KantaToastKind.ERROR)
    }
}

/**
 * Every marker bitmap the map can show (spec §3.4), drawn at 4x so the 2dp border, the dashed
 * MISSING outline and the 15%-larger FULL rule are all actually inspectable on a phone screen.
 */
@Composable
private fun MarkerSection(darkTheme: Boolean) {
    KantaSectionHeader("Markers · §3.4")

    val density = LocalDensity.current.density
    val factory = remember(density) { MarkerBitmapFactory(density) }

    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ContainerKind.entries.forEach { kind ->
            Text(
                text = if (kind == ContainerKind.BIG) "Big · rectangle" else "Small · triangle",
                style = MaterialTheme.typography.labelSmall,
                color = KantaTheme.colors.onSurfaceMuted,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.Bottom,
            ) {
                ContainerStatus.entries.forEach { status ->
                    MarkerSwatch(
                        label = status.name,
                        bitmapProvider = { factory.marker(kind, status, darkTheme = darkTheme) },
                    )
                }
            }
        }

        Text(
            text = "Recycling dots · selected · suggestion",
            style = MaterialTheme.typography.labelSmall,
            color = KantaTheme.colors.onSurfaceMuted,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(
                ContainerCategory.GLASS,
                ContainerCategory.PAPER,
                ContainerCategory.PLASTIC,
            ).forEach { category ->
                MarkerSwatch(
                    label = category.name,
                    bitmapProvider = {
                        factory.marker(
                            ContainerKind.BIG,
                            ContainerStatus.OK,
                            category,
                            darkTheme,
                        )
                    },
                )
            }
            MarkerSwatch(
                label = "SEL",
                bitmapProvider = {
                    factory.marker(
                        ContainerKind.BIG,
                        ContainerStatus.OK,
                        ContainerCategory.GENERAL,
                        darkTheme,
                        selected = true,
                    )
                },
            )
            MarkerSwatch(
                label = "SUGG",
                bitmapProvider = { factory.suggestionMarker(darkTheme) },
            )
        }

        Text(
            text = "Clusters · worst status inside",
            style = MaterialTheme.typography.labelSmall,
            color = KantaTheme.colors.onSurfaceMuted,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(ContainerStatus.OK, ContainerStatus.FULL, ContainerStatus.BROKEN)
                .forEach { status ->
                    MarkerSwatch(
                        label = status.name,
                        bitmapProvider = { factory.clusterCircle(status, darkTheme, 28f) },
                        displaySize = 56.dp,
                    )
                }
        }
    }
}

@Composable
private fun MarkerSwatch(
    label: String,
    bitmapProvider: () -> android.graphics.Bitmap,
    displaySize: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val bitmap = remember(label, displaySize) { bitmapProvider().asImageBitmap() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            // Nearest-neighbour-ish upscale of a small bitmap; we want to see the real pixels.
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(displaySize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = KantaTheme.colors.onSurfaceMuted,
        )
    }
}

/** Minimal SnackbarData so the gallery can show the toast style without a live host. */
private class GalleryToast(
    private val text: String,
    private val action: String?,
) : androidx.compose.material3.SnackbarData {
    override val visuals = object : androidx.compose.material3.SnackbarVisuals {
        override val actionLabel = action
        override val duration = androidx.compose.material3.SnackbarDuration.Short
        override val message = text
        override val withDismissAction = false
    }

    override fun performAction() = Unit
    override fun dismiss() = Unit
}
