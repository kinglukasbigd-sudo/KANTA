package mk.kanta.app.feature.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mk.kanta.app.R
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.kantaSoftShadow
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.core.location.LatLon
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * The main screen (§4.1): a full-screen map with quiet chrome on top.
 *
 * The bottom sheet from §4.1 lands in a later step; this screen delivers the map,
 * the markers, the overlays, the detail sheet and the loading/offline/error
 * states.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onProfileClick: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraTarget by viewModel.cameraTarget.collectAsStateWithLifecycle()
    val darkTheme = KantaTheme.colors.isDark
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val haptics = rememberKantaHaptics()

    val markerFactory = remember(density) { MarkerBitmapFactory(density) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onLocationPermissionResult(grants.values.any { it })
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ---------------------------------------------------------------------------------
        // The map
        // ---------------------------------------------------------------------------------
        val mapView = rememberMapViewWithLifecycle()

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { /* state is pushed through the effects below */ },
        )

        // Style load. Keyed on the theme so flipping dark mode rebuilds the map
        // with the other palette and a fresh set of marker bitmaps (§3.4 border
        // colour differs per theme).
        DisposableEffect(mapView, darkTheme) {
            mapView.getMapAsync { map ->
                mapRef = map
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                // MapLibre's own attribution and logo are replaced by ours (§7).
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = false

                val styleJson = KantaMapStyle.load(context, darkTheme)
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    styleRef = style
                    MapLayers.install(style, markerFactory, darkTheme)
                }

                map.addOnCameraIdleListener {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    viewModel.onCameraIdle(
                        Bbox(
                            minLon = bounds.longitudeWest,
                            minLat = bounds.latitudeSouth,
                            maxLon = bounds.longitudeEast,
                            maxLat = bounds.latitudeNorth,
                        ),
                    )
                }

                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val slop = context.markerTouchSlopPx()

                    val containerId = MapLayers.containerAt(map, screenPoint, slop)
                    if (containerId != null) {
                        haptics.tick()
                        viewModel.onContainerSelected(containerId)
                        return@addOnMapClickListener true
                    }

                    // Tapping a cluster zooms into it rather than doing nothing.
                    val cluster = MapLayers.clusterAt(map, screenPoint, slop)
                    if (cluster != null) {
                        haptics.tick()
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(cluster.latitude(), cluster.longitude()),
                                (map.cameraPosition.zoom + 2.0).coerceAtMost(18.0),
                            ),
                        )
                        return@addOnMapClickListener true
                    }
                    false
                }
            }
            onDispose { mapRef = null; styleRef = null }
        }

        // Push markers into the GeoJSON source whenever they change.
        LaunchedEffect(state.containers, state.selectedId, styleRef) {
            val style = styleRef ?: return@LaunchedEffect
            MapLayers.updateContainers(
                style,
                MapLayers.toFeatureCollection(state.containers, markerFactory, state.selectedId),
            )
        }

        LaunchedEffect(state.suggestions, state.showSuggestions, styleRef) {
            val style = styleRef ?: return@LaunchedEffect
            MapLayers.updateSuggestions(
                style,
                MapLayers.suggestionsToFeatureCollection(state.suggestions),
            )
            MapLayers.setSuggestionsVisible(style, state.showSuggestions)
        }

        // Fly to a new camera target once, then clear it.
        LaunchedEffect(cameraTarget, mapRef) {
            val target = cameraTarget ?: return@LaunchedEffect
            val map = mapRef ?: return@LaunchedEffect
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(target.lat, target.lon))
                        .zoom(DEFAULT_ZOOM)
                        .build(),
                ),
            )
            viewModel.onCameraMoved()
        }

        // ---------------------------------------------------------------------------------
        // Overlays (§4.1)
        // ---------------------------------------------------------------------------------
        MapOverlays(
            showSuggestions = state.showSuggestions,
            onProfileClick = onProfileClick,
            onMyLocationClick = {
                if (state.hasLocationPermission) {
                    viewModel.onMyLocationClick()
                } else {
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                }
            },
            onToggleSuggestions = viewModel::toggleSuggestions,
        )

        // Loading skeleton over the map until the cache has produced a first frame.
        AnimatedVisibility(
            visible = state.loading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            MapLoadingSkeleton()
        }

        // §8 offline / error state: a banner, never a blank map. Cached markers
        // stay visible underneath.
        AnimatedVisibility(
            visible = state.error != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        ) {
            state.error?.let { error ->
                MapErrorBanner(error = error, onDismiss = viewModel::dismissError)
            }
        }
    }

    // §4.1: tapping a marker opens the container detail sheet.
    state.detail?.let { detail ->
        ContainerDetailSheet(
            state = detail,
            onDismiss = viewModel::dismissDetail,
        )
    }
}

private const val DEFAULT_ZOOM = 15.0

// -------------------------------------------------------------------------------------------
// Overlays
// -------------------------------------------------------------------------------------------

@Composable
private fun MapOverlays(
    showSuggestions: Boolean,
    onProfileClick: () -> Unit,
    onMyLocationClick: () -> Unit,
    onToggleSuggestions: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {

        // Top-left wordmark (§4.1).
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = Spacing.l, top = Spacing.m)
                .kantaSoftShadow(KantaShape.pill),
            shape = KantaShape.pill,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                color = KantaTheme.colors.brand,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }

        // Top-right round profile button (§4.1).
        MapCircleButton(
            icon = KantaIcons.Profile,
            contentDescription = stringResource(R.string.map_profile),
            onClick = onProfileClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = Spacing.l, top = Spacing.m),
        )

        // Right side: suggestions toggle and my-location (§4.1).
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            MapCircleButton(
                icon = KantaIcons.Suggest,
                contentDescription = stringResource(R.string.map_toggle_suggestions),
                onClick = onToggleSuggestions,
                tint = if (showSuggestions) {
                    KantaTheme.colors.brand
                } else {
                    KantaTheme.colors.onSurfaceMuted
                },
            )
            MapCircleButton(
                icon = KantaIcons.MyLocation,
                contentDescription = stringResource(R.string.map_my_location),
                onClick = onMyLocationClick,
            )
        }

        // §7: attribution is a licence condition, not decoration. Small, but it
        // must stay legible — hence the surface behind it.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Spacing.s, bottom = Spacing.s),
            shape = KantaShape.chip,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        ) {
            Text(
                text = stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = KantaTheme.colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MapCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = KantaTheme.colors.onSurfaceMuted,
) {
    val haptics = rememberKantaHaptics()
    Surface(
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier
            .size(Spacing.minTouchTarget)
            .kantaSoftShadow(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// States (§8)
// -------------------------------------------------------------------------------------------

@Composable
private fun MapLoadingSkeleton() {
    Surface(
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(Spacing.xl),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            KantaSkeleton(width = 160.dp, height = 16.dp)
            KantaSkeleton(width = 120.dp, height = 12.dp)
        }
    }
}

@Composable
private fun MapErrorBanner(error: KantaError, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l)
            .kantaSoftShadow(KantaShape.card),
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (error == KantaError.Offline) KantaIcons.Offline else KantaIcons.Error,
                contentDescription = null,
                tint = KantaTheme.colors.onSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.m))
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = KantaIcons.Success,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }
    }
}
