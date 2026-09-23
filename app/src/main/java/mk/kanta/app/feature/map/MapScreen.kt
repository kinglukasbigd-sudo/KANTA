package mk.kanta.app.feature.map

import android.Manifest
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.rememberUpdatedState
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
import mk.kanta.app.core.designsystem.component.KantaDragHandle
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.kantaSoftShadow
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.feature.admin.AdminContent
import mk.kanta.app.feature.admin.AdminEvent
import mk.kanta.app.feature.admin.AdminTab
import mk.kanta.app.feature.admin.AdminViewModel
import mk.kanta.app.feature.admin.CoverageLegend
import mk.kanta.app.feature.map.sheet.KantaBottomSheetScaffold
import mk.kanta.app.feature.map.sheet.KantaSheetValue
import mk.kanta.app.feature.map.sheet.MapMenuBody
import mk.kanta.app.feature.map.sheet.MapMenuHeader
import mk.kanta.app.feature.map.sheet.rememberKantaSheetState
import mk.kanta.app.feature.street.AreaCheckContent
import mk.kanta.app.feature.street.AreaCheckEvent
import mk.kanta.app.feature.street.AreaCheckPhase
import mk.kanta.app.feature.street.AreaCheckViewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * The main screen (§4.1): a full-screen map with quiet chrome on top and the one
 * persistent sheet below. The sheet shows the menu, a container's detail, the
 * §4.6 area check, or Admin — one at a time, each replacing the menu, with the
 * map above doing the spatial part (the ring, the coverage, the reviewed pin).
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onProfileClick: () -> Unit = {},
    onFull: () -> Unit = {},
    onReport: () -> Unit = {},
    onSuggest: () -> Unit = {},
    onMyReports: () -> Unit = {},
    onCityStats: () -> Unit = {},
    onSuggestionsList: () -> Unit = {},
    /** §4.6 "One is missing" → camera → Add container. */
    onAddContainerFromCheck: () -> Unit = {},
    /** §4.6 "One on the map is not here" → that marker → report, kind missing. */
    onReportMissing: (containerId: String) -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
    areaCheckViewModel: AreaCheckViewModel = hiltViewModel(),
    adminViewModel: AdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val areaCheck by areaCheckViewModel.state.collectAsStateWithLifecycle()
    val admin by adminViewModel.state.collectAsStateWithLifecycle()
    val isAdmin by adminViewModel.isAdmin.collectAsStateWithLifecycle()
    val cameraTarget by viewModel.cameraTarget.collectAsStateWithLifecycle()
    val darkTheme = KantaTheme.colors.isDark
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val haptics = rememberKantaHaptics()
    // Room for the wordmark and profile button when framing something on the map.
    val topInsetPx = with(LocalDensity.current) {
        (WindowInsets.statusBars.getTop(this) + 64.dp.roundToPx()).toFloat()
    }

    val markerFactory = remember(density) { MarkerBitmapFactory(density) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onLocationPermissionResult(grants.values.any { it })
    }

    val sheetState = rememberKantaSheetState()

    // What the one sheet (§4.1) is showing. The check outranks everything while it
    // runs; a container detail sits on top of Admin, so closing it returns there.
    val mode = when {
        areaCheck.active -> SheetMode.AreaCheck
        state.detail != null -> SheetMode.Detail
        admin.open -> SheetMode.Admin
        else -> SheetMode.Menu
    }

    // §4.1: anything that replaces the menu lifts the sheet to half, so it is
    // readable without burying the map it is about.
    LaunchedEffect(mode) {
        if (mode != SheetMode.Menu && sheetState.isCollapsed) {
            sheetState.animateTo(KantaSheetValue.Half)
        }
    }

    // Swiping all the way down means "done with this": back to the menu. For
    // the check that is "Later" (§4.6: always skippable).
    LaunchedEffect(sheetState.currentValue, mode) {
        if (sheetState.currentValue == KantaSheetValue.Collapsed) {
            when (mode) {
                SheetMode.Detail -> viewModel.dismissDetail()
                SheetMode.AreaCheck -> areaCheckViewModel.later()
                SheetMode.Admin -> adminViewModel.close()
                SheetMode.Menu -> Unit
            }
        }
    }

    // Back steps out one level at a time, and only then falls through to the system.
    BackHandler(enabled = mode != SheetMode.Menu || !sheetState.isCollapsed) {
        when (mode) {
            SheetMode.AreaCheck ->
                if (areaCheck.phase == AreaCheckPhase.PickingMissing) {
                    areaCheckViewModel.cancelPicking()
                } else {
                    areaCheckViewModel.later()
                }
            SheetMode.Detail -> viewModel.dismissDetail()
            SheetMode.Admin -> adminViewModel.close()
            SheetMode.Menu -> sheetState.collapse()
        }
    }

    // §4.6 prompt timing: the check only asks when the plain menu is showing,
    // i.e. whatever the user was doing is finished (§4.2).
    LaunchedEffect(mode, state.userLocation) {
        areaCheckViewModel.onMapContext(menuShowing = mode == SheetMode.Menu, location = state.userLocation)
    }

    LaunchedEffect(Unit) {
        areaCheckViewModel.events.collect { event ->
            when (event) {
                AreaCheckEvent.AddContainer -> onAddContainerFromCheck()
                is AreaCheckEvent.ReportMissing -> onReportMissing(event.containerId)
                AreaCheckEvent.RefreshMap -> viewModel.refreshNow()
            }
        }
    }
    LaunchedEffect(Unit) {
        adminViewModel.events.collect { event ->
            when (event) {
                AdminEvent.RefreshMap -> viewModel.refreshNow()
            }
        }
    }

    // Marker taps, read through the latest state: the map's click listener is
    // registered once per style, long before the check or Admin opens.
    val onMarkerTap by rememberUpdatedState { containerId: String ->
        when {
            // §4.6 "One on the map is not here" → the user taps that marker.
            areaCheckViewModel.onMarkerTapped(containerId) -> Unit
            // The check is about the ring as a whole; a stray tap should not
            // replace it with a detail sheet.
            areaCheck.active -> Unit
            else -> viewModel.onContainerSelected(containerId)
        }
    }

    KantaBottomSheetScaffold(
        sheetState = sheetState,
        modifier = modifier,
        header = {
            if (mode == SheetMode.Menu) {
                MapMenuHeader(onFull = onFull, onReport = onReport, onSuggest = onSuggest)
            } else {
                // Its own handle, so the sheet stays draggable whatever it shows.
                KantaDragHandle()
            }
        },
        body = {
            when (mode) {
                SheetMode.Menu -> MapMenuBody(
                    nearest = state.nearest,
                    nearestLoading = state.nearestLoading,
                    onWhereToThrow = onSuggestionsList,
                    onNearestClick = viewModel::onContainerSelected,
                    onMyReports = onMyReports,
                    onCityStats = onCityStats,
                    onSuggestions = onSuggestionsList,
                    onMapYourStreet = areaCheckViewModel::requestFromMenu,
                    showAdmin = isAdmin,
                    onAdmin = adminViewModel::open,
                )
                SheetMode.Detail -> state.detail?.let { detail ->
                    ContainerDetailContent(
                        state = detail,
                        onMeToo = viewModel::onMeToo,
                        onEmptied = viewModel::onEmptied,
                        onReportOther = onReport,
                        onConfirmExists = viewModel::onConfirmExists,
                    )
                }
                SheetMode.AreaCheck -> AreaCheckContent(
                    state = areaCheck,
                    onAllPresent = areaCheckViewModel::answerAllPresent,
                    onMissingOne = areaCheckViewModel::answerMissingOne,
                    onNotHere = areaCheckViewModel::answerNotHere,
                    onCancelPicking = areaCheckViewModel::cancelPicking,
                    onConfirmExists = areaCheckViewModel::confirmExists,
                    onLater = areaCheckViewModel::later,
                )
                SheetMode.Admin -> AdminContent(
                    state = admin,
                    onTab = adminViewModel::selectTab,
                    onRetry = adminViewModel::retry,
                    onFocus = adminViewModel::focus,
                    onApprove = adminViewModel::approve,
                    onReject = adminViewModel::reject,
                    onVerify = adminViewModel::verify,
                    onDelete = adminViewModel::askDelete,
                    onConfirmDelete = adminViewModel::confirmDelete,
                    onDismissDelete = adminViewModel::dismissDelete,
                )
            }
        },
    ) {

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
                        onMarkerTap(containerId)
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

        // §4.6: the 150 m ring while the check runs.
        LaunchedEffect(styleRef, areaCheck.active, areaCheck.centre) {
            val style = styleRef ?: return@LaunchedEffect
            MapLayers.setRing(
                style,
                if (areaCheck.active) areaCheck.centre else null,
                AreaCheckViewModel.RADIUS_M,
            )
        }

        // Frame the ring in the part of the map the half sheet leaves visible —
        // "a small map of a 150 m radius around the user".
        LaunchedEffect(mapRef, areaCheck.active, areaCheck.centre) {
            val map = mapRef ?: return@LaunchedEffect
            val centre = areaCheck.centre ?: return@LaunchedEffect
            if (!areaCheck.active) return@LaunchedEffect
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    framedAboveSheet(
                        centre = centre,
                        radiusMetres = AreaCheckViewModel.RADIUS_M.toDouble(),
                        mapWidthPx = mapView.width,
                        mapHeightPx = mapView.height,
                        sheetTopPx = sheetState.anchors[KantaSheetValue.Half] ?: (mapView.height * 0.55f),
                        topInsetPx = topInsetPx,
                        density = density,
                    ),
                ),
                CAMERA_MS,
            )
        }

        // §4.6 admin coverage: shaded only while the Coverage tab is open.
        LaunchedEffect(styleRef, admin.open, admin.tab, admin.coverage) {
            val style = styleRef ?: return@LaunchedEffect
            val show = admin.open && admin.tab == AdminTab.Coverage && mode == SheetMode.Admin
            MapLayers.setCoverage(style, if (show) admin.coverage else null)
        }
        LaunchedEffect(mapRef, admin.open, admin.tab) {
            val map = mapRef ?: return@LaunchedEffect
            if (admin.open && admin.tab == AdminTab.Coverage) {
                // The whole city, so the unchecked parts are what stands out.
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(LatLon.SKOPJE_CENTRE.lat, LatLon.SKOPJE_CENTRE.lon),
                        CITY_ZOOM,
                    ),
                    CAMERA_MS,
                )
            }
        }

        // Admin review: ring the request or container being looked at, and go there.
        LaunchedEffect(styleRef, mapRef, admin.open, admin.focus) {
            val style = styleRef ?: return@LaunchedEffect
            val focus = if (admin.open) admin.focus else null
            MapLayers.setFocus(style, focus)
            val map = mapRef ?: return@LaunchedEffect
            if (focus != null) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        framedAboveSheet(
                            centre = focus,
                            radiusMetres = FOCUS_RADIUS_M,
                            mapWidthPx = mapView.width,
                            mapHeightPx = mapView.height,
                            sheetTopPx = sheetState.offset.value,
                            topInsetPx = topInsetPx,
                            density = density,
                        ),
                    ),
                    CAMERA_MS,
                )
            }
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

        // §4.6: "with a legend" — on the map itself, where the colour is.
        AnimatedVisibility(
            visible = mode == SheetMode.Admin && admin.tab == AdminTab.Coverage,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        ) {
            CoverageLegend()
        }

        // Loading skeleton over the map until the cache has produced a first frame.
        AnimatedVisibility(
            visible = state.loading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            MapLoadingSkeleton()
        }

        // Success notices ("Thanks — noted."), auto-dismissed after a few seconds.
        LaunchedEffect(state.notice) {
            if (state.notice != null) {
                haptics.success()
                kotlinx.coroutines.delay(3_500)
                viewModel.dismissNotice()
            }
        }
        AnimatedVisibility(
            visible = state.notice != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        ) {
            state.notice?.let { MapNoticeBanner(textRes = it) }
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
}

private const val DEFAULT_ZOOM = 15.0
private const val CITY_ZOOM = 11.4
private const val CAMERA_MS = 600

/** Enough map around a reviewed pin to see what else stands there. */
private const val FOCUS_RADIUS_M = 60.0

/** What the one sheet is showing (§4.1: it is the only menu). */
private enum class SheetMode { Menu, Detail, AreaCheck, Admin }

/**
 * A camera that fits a circle of [radiusMetres] around [centre] into the map
 * area left between the top chrome and the sheet's top edge — so the ring is
 * framed where the user can see it, not behind the sheet.
 *
 * MapLibre's zoom scale is in density-independent pixels: at zoom z one dp
 * covers 78 271.517 · cos(lat) / 2^z metres.
 */
private fun framedAboveSheet(
    centre: LatLon,
    radiusMetres: Double,
    mapWidthPx: Int,
    mapHeightPx: Int,
    sheetTopPx: Float,
    topInsetPx: Float,
    density: Float,
): CameraPosition {
    val visibleHeightPx = (sheetTopPx - topInsetPx).coerceAtLeast(mapHeightPx * 0.25f)
    val fitPx = minOf(visibleHeightPx, mapWidthPx.toFloat()) * 0.86f
    val fitDp = (fitPx / density).coerceAtLeast(1f)
    val metresPerDp = (radiusMetres * 2.0) / fitDp
    val zoom = kotlin.math.log2(78_271.517 * kotlin.math.cos(Math.toRadians(centre.lat)) / metresPerDp)
    return CameraPosition.Builder()
        .target(LatLng(centre.lat, centre.lon))
        .zoom(zoom.coerceIn(12.0, 19.0))
        .padding(0.0, topInsetPx.toDouble(), 0.0, (mapHeightPx - sheetTopPx).toDouble().coerceAtLeast(0.0))
        .build()
}

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

@Composable
private fun MapNoticeBanner(textRes: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l)
            .kantaSoftShadow(KantaShape.card),
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = KantaIcons.Success,
                contentDescription = null,
                tint = KantaTheme.colors.brand,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.m))
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
