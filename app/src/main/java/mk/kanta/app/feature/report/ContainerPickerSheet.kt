package mk.kanta.app.feature.report

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import mk.kanta.app.R
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaDistanceLabel
import mk.kanta.app.core.designsystem.component.KantaDragHandle
import mk.kanta.app.core.designsystem.component.KantaListRow
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.feature.map.KantaMapStyle
import mk.kanta.app.feature.map.MapLayers
import mk.kanta.app.feature.map.markerTouchSlopPx
import mk.kanta.app.feature.map.rememberMapViewWithLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/**
 * "Tap to change on a mini map" (§4.3).
 *
 * The map is deliberately static — no pan, no zoom. Inside a bottom sheet a
 * pannable map fights the sheet's own drag, and at this scale (the few containers
 * within ~100 m) there is nothing to pan to. Every candidate is also listed below
 * it, so the choice never depends on hitting a 14 dp marker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerPickerSheet(
    candidates: List<ContainerCandidate>,
    selectedId: String?,
    device: LatLon?,
    onPick: (ContainerCandidate) -> Unit,
    onAddNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = KantaShape.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { KantaDragHandle() },
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.report_pick_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            )
            Spacer(Modifier.height(Spacing.m))

            if (device != null) {
                MiniMap(
                    candidates = candidates,
                    selectedId = selectedId,
                    centre = device,
                    onPick = onPick,
                    modifier = Modifier
                        .padding(horizontal = Spacing.screenHorizontal)
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(KantaShape.card),
                )
                Spacer(Modifier.height(Spacing.m))
            }

            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.report_no_container),
                    style = MaterialTheme.typography.bodyLarge,
                    color = KantaTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                )
            } else {
                LazyColumn(Modifier.height(240.dp)) {
                    items(candidates, key = { it.id }) { c ->
                        val tooFar = c.distanceMetres > SendOutcomes.MAX_REPORT_DISTANCE_M
                        KantaListRow(
                            title = c.code,
                            subtitle = if (tooFar) stringResource(R.string.report_pick_too_far) else null,
                            leading = { KantaStatusDot(c.status, c.kind) },
                            trailing = { KantaDistanceLabel(c.distanceMetres.roundToInt()) },
                            onClick = { onPick(c) },
                        )
                    }
                }
            }

            TextButton(
                onClick = onAddNew,
                modifier = Modifier.padding(horizontal = Spacing.m),
            ) {
                Text(stringResource(R.string.report_not_on_map), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(Spacing.l))
        }
    }
}

@Composable
private fun MiniMap(
    candidates: List<ContainerCandidate>,
    selectedId: String?,
    centre: LatLon,
    onPick: (ContainerCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val dark = KantaTheme.colors.isDark
    val factory = remember(density) { MarkerBitmapFactory(density) }
    val mapView = rememberMapViewWithLifecycle()
    var style by remember { mutableStateOf<Style?>(null) }
    val currentCandidates by rememberUpdatedState(candidates)
    val currentOnPick by rememberUpdatedState(onPick)

    AndroidView(factory = { mapView }, modifier = modifier)

    DisposableEffect(mapView, dark) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isScrollGesturesEnabled = false
                isZoomGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
                isCompassEnabled = false
            }
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(centre.lat, centre.lon))
                .zoom(17.5)
                .build()
            map.setStyle(Style.Builder().fromJson(KantaMapStyle.load(context, dark))) { loaded ->
                MapLayers.install(loaded, factory, dark)
                style = loaded
            }
            map.addOnMapClickListener { latLng ->
                val id = MapLayers.containerAt(
                    map,
                    map.projection.toScreenLocation(latLng),
                    context.markerTouchSlopPx(),
                )
                currentCandidates.firstOrNull { it.id == id }?.let(currentOnPick)
                id != null
            }
        }
        onDispose { style = null }
    }

    LaunchedEffect(style, candidates, selectedId) {
        val loaded = style ?: return@LaunchedEffect
        MapLayers.updateContainers(
            loaded,
            MapLayers.toFeatureCollection(
                candidates.map {
                    MapLayers.ContainerFeature(
                        id = it.id,
                        code = it.code,
                        kind = it.kind,
                        category = it.category,
                        status = it.status,
                        verified = it.verified,
                        lon = it.position.lon,
                        lat = it.position.lat,
                    )
                },
                factory,
                selectedId,
            ),
        )
    }
}
