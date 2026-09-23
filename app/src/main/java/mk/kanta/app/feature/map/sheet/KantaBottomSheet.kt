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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
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

    internal var anchors: Map<KantaSheetValue, Float> = emptyMap()

    var currentValue: KantaSheetValue by mutableStateOf(initialValue)
        internal set

    /** True while the user's finger is on the sheet. */
    internal var dragging by mutableStateOf(false)

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
 * [peekHeight] is the collapsed height *above* the navigation bar; the sheet adds
 * the inset itself, so the three action tiles never sit under the gesture pill.
 *
 * [header] is the drag surface — handle plus tiles — and is always visible.
 * [body] appears below it and only matters once the sheet is off its collapsed
 * anchor.
 */
@Composable
fun KantaBottomSheetScaffold(
    sheetState: KantaSheetState,
    modifier: Modifier = Modifier,
    peekHeight: Dp = 140.dp,
    expandedTopInset: Dp = 72.dp,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    var containerHeight by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeight = it.height.toFloat() },
    ) {
        // The map. Nothing above it consumes gestures outside the sheet itself,
        // so panning and marker taps keep working while the sheet is up (§4.1).
        content()

        var sheetHeight by remember { mutableFloatStateOf(0f) }

        // Anchors are recomputed whenever the container or the sheet resizes —
        // rotation, keyboard, a taller body after data loads.
        LaunchedEffect(containerHeight, sheetHeight, density) {
            if (containerHeight <= 0f) return@LaunchedEffect

            val peekPx = with(density) { peekHeight.toPx() }
            val topInsetPx = with(density) { expandedTopInset.toPx() }

            val collapsed = containerHeight - peekPx
            val expanded = topInsetPx.coerceAtMost(collapsed)
            // Half sits between the two rather than at a fixed fraction, so it
            // stays sensible on a short phone and a tall one alike.
            val half = ((collapsed + expanded) / 2f).coerceIn(expanded, collapsed)

            sheetState.anchors = mapOf(
                KantaSheetValue.Collapsed to collapsed,
                KantaSheetValue.Half to half,
                KantaSheetValue.Expanded to expanded,
            )

            // First layout: place the sheet without animating in from nowhere.
            if (sheetState.offset.value == 0f) {
                sheetState.offset.snapTo(sheetState.anchors[sheetState.currentValue] ?: collapsed)
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

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, sheetState.offset.value.roundToInt()) }
                .kantaSoftShadow(KantaShape.bottomSheet),
            shape = KantaShape.bottomSheet,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { sheetHeight = it.height.toFloat() }
                    // Nav bar inset lives inside the sheet so its background
                    // reaches the screen edge while content stays clear of it.
                    .navigationBarsPadding(),
            ) {
                // Drag lives on the header only. The body can then hold its own
                // scrolling or clickable rows without fighting the sheet gesture,
                // and in the collapsed state the header IS the whole visible
                // sheet, so dragging from anywhere visible still works.
                Column(
                    modifier = Modifier.draggable(
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
                body()
            }
        }
    }
}
