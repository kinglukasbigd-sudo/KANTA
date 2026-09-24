package mk.kanta.app.feature.map.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.kantaSoftShadow
import kotlin.math.abs
import kotlin.math.roundToInt

/** The three rest positions from spec §4.1. */
enum class KantaSheetValue { Collapsed, Half, Expanded }

/**
 * A persistent three-state bottom sheet.
 *
 * Material 3's own `BottomSheetScaffold` rests in two positions (peek and
 * expanded); §4.1 calls for three — collapsed, half, expanded — so this is built
 * directly on an [Animatable] offset and a vertical `draggable`. The upside is
 * that the settle animation is literally the spec's motion token
 * (`spring(dampingRatio = 0.85)`, §3.3) rather than whatever the component
 * chooses, and the map behind stays fully interactive because nothing here draws
 * a scrim or consumes touches outside the sheet.
 */
class KantaSheetState internal constructor(
    initialValue: KantaSheetValue,
    internal val scope: CoroutineScope,
) {
    /** Offset of the sheet's top edge from the top of the container, in pixels. */
    internal val offset: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** Observable: the sheet's height and the map buttons follow it. */
    internal var anchors: Map<KantaSheetValue, Float> by mutableStateOf(emptyMap())

    var currentValue: KantaSheetValue by mutableStateOf(initialValue)
        internal set

    /** True while the user's finger is on the sheet. */
    internal var dragging by mutableStateOf(false)

    /** Offset of the top edge at a rest position, or null before the first layout. */
    fun anchorOf(value: KantaSheetValue): Float? = anchors[value]

    /**
     * 0 at half height and below, 1 fully expanded — for things that should
     * give way as the sheet takes over the screen (§4.1 map buttons).
     */
    val expansionAboveHalf: Float
        get() {
            val half = anchors[KantaSheetValue.Half] ?: return 0f
            val expanded = anchors[KantaSheetValue.Expanded] ?: return 0f
            if (half <= expanded) return 0f
            return ((half - offset.value) / (half - expanded)).coerceIn(0f, 1f)
        }

    val isExpanded: Boolean get() = currentValue == KantaSheetValue.Expanded
    val isCollapsed: Boolean get() = currentValue == KantaSheetValue.Collapsed

    fun animateTo(value: KantaSheetValue) {
        val target = anchors[value] ?: return
        currentValue = value
        scope.launch {
            offset.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Motion.SpringDamping,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    /** Collapse, or if already collapsed do nothing. Used by the back handler. */
    fun collapse() = animateTo(KantaSheetValue.Collapsed)

    internal fun settle(velocity: Float) {
        val current = offset.value
        // A decisive flick wins over proximity: past this speed the user has
        // said which way they want to go, whatever the nearest anchor is.
        val flick = 900f

        val target = when {
            velocity > flick -> nextBelow(current)
            velocity < -flick -> nextAbove(current)
            else -> anchors.entries.minByOrNull { abs(it.value - current) }?.key
        } ?: currentValue

        animateTo(target)
    }

    private fun nextBelow(current: Float): KantaSheetValue? =
        anchors.entries.filter { it.value > current + 1f }.minByOrNull { it.value }?.key
            ?: anchors.entries.maxByOrNull { it.value }?.key

    private fun nextAbove(current: Float): KantaSheetValue? =
        anchors.entries.filter { it.value < current - 1f }.maxByOrNull { it.value }?.key
            ?: anchors.entries.minByOrNull { it.value }?.key
}

@Composable
fun rememberKantaSheetState(
    initialValue: KantaSheetValue = KantaSheetValue.Collapsed,
): KantaSheetState {
    val scope = rememberCoroutineScope()
    return remember { KantaSheetState(initialValue, scope) }
}

/**
 * Lays the sheet over [content].
 *
 * The three rest positions (§4.1):
 * - **Collapsed** shows exactly the [header] — handle plus the Full / Report /
 *   Suggest row — above the navigation bar. It is measured, not guessed, so the
 *   row can never sit under the gesture pill.
 * - **Expanded** puts the sheet's top edge [expandedTopGap] below the status bar,
 *   i.e. under the Kanta wordmark and profile button, and the sheet always reaches
 *   the very bottom of the screen, behind the navigation bar.
 * - **Half** sits between the two.
 *
 * The sheet is exactly as tall as it is when expanded, so at every position its
 * bottom edge is at or below the screen's — no gap, no map showing under it.
 * [body] gets the remaining height and is expected to scroll on its own.
 */
@Composable
fun KantaBottomSheetScaffold(
    sheetState: KantaSheetState,
    modifier: Modifier = Modifier,
    /** Status bar → sheet top when expanded: the top overlay (12 dp + 48 dp) plus 8 dp. */
    expandedTopGap: Dp = 68.dp,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    var containerHeight by remember { mutableFloatStateOf(0f) }
    var headerHeight by remember { mutableFloatStateOf(0f) }
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val navBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeight = it.height.toFloat() },
    ) {
        // The map. Nothing above it consumes gestures outside the sheet itself,
        // so panning and marker taps keep working while the sheet is up (§4.1).
        content()

        // Anchors follow the container and the header — rotation, a different
        // header when the sheet shows a detail — and the sheet glides to its
        // current rest position's new spot instead of jumping.
        LaunchedEffect(containerHeight, headerHeight, statusBarPx, navBarPx, density) {
            if (containerHeight <= 0f || headerHeight <= 0f) return@LaunchedEffect

            val expanded = (statusBarPx + with(density) { expandedTopGap.toPx() })
                .coerceAtMost(containerHeight)
            val collapsed = (containerHeight - headerHeight - navBarPx).coerceAtLeast(expanded)
            val half = ((collapsed + expanded) / 2f).coerceIn(expanded, collapsed)

            val first = sheetState.anchors.isEmpty()
            sheetState.anchors = mapOf(
                KantaSheetValue.Collapsed to collapsed,
                KantaSheetValue.Half to half,
                KantaSheetValue.Expanded to expanded,
            )

            val target = sheetState.anchors[sheetState.currentValue] ?: collapsed
            if (first) {
                // First layout: place the sheet without animating in from nowhere.
                sheetState.offset.snapTo(target)
            } else if (!sheetState.dragging && abs(sheetState.offset.value - target) > 1f) {
                sheetState.animateTo(sheetState.currentValue)
            }
        }

        val dragState = rememberDraggableState { delta ->
            val anchors = sheetState.anchors
            if (anchors.isEmpty()) return@rememberDraggableState
            val min = anchors.values.min()
            val max = anchors.values.max()
            sheetState.scope.launch {
                sheetState.offset.snapTo((sheetState.offset.value + delta).coerceIn(min, max))
            }
        }

        // Exactly the expanded height. Until the first measure, the full screen.
        val expandedTop = sheetState.anchors[KantaSheetValue.Expanded]
        val sheetHeight = if (expandedTop != null && containerHeight > 0f) {
            with(density) { (containerHeight - expandedTop).toDp() }
        } else {
            null
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sheetHeight != null) Modifier.height(sheetHeight) else Modifier.fillMaxSize())
                .offset { IntOffset(0, sheetState.offset.value.roundToInt()) }
                .kantaSoftShadow(KantaShape.bottomSheet),
            shape = KantaShape.bottomSheet,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Nav bar inset lives inside the sheet so its background
                    // reaches the screen edge while content stays clear of it.
                    .navigationBarsPadding(),
            ) {
                // Drag lives on the header only. The body can then scroll or hold
                // clickable rows without fighting the sheet gesture, and collapsed,
                // the header IS the whole visible sheet, so dragging from anywhere
                // visible still works.
                Column(
                    modifier = Modifier
                        .onSizeChanged { headerHeight = it.height.toFloat() }
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { sheetState.dragging = true },
                            onDragStopped = { velocity ->
                                sheetState.dragging = false
                                sheetState.settle(velocity)
                            },
                        ),
                    content = header,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // Collapsed, only the header should show; the body fades in
                        // as the sheet rises instead of peeking behind the nav bar.
                        .graphicsLayer {
                            val collapsed = sheetState.anchors[KantaSheetValue.Collapsed]
                            val half = sheetState.anchors[KantaSheetValue.Half]
                            alpha = if (collapsed == null || half == null || collapsed <= half) {
                                1f
                            } else {
                                ((collapsed - sheetState.offset.value) / (collapsed - half) * 1.5f).coerceIn(0f, 1f)
                            }
                        },
                    content = body,
                )
            }
        }
    }
}
