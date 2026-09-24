package mk.kanta.app.feature.map

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import mk.kanta.app.core.designsystem.Motion
import org.maplibre.android.location.OnCameraTrackingChangedListener
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import mk.kanta.app.feature.map.sheet.KantaSheetState
import kotlin.math.roundToInt
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
import mk.kanta.app.feature.alternatives.AlternativesContent
import mk.kanta.app.feature.alternatives.AlternativesOrigin
import mk.kanta.app.feature.alternatives.AlternativesViewModel
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
import mk.kanta.app.feature.suggest.SuggestionDetailContent
import mk.kanta.app.feature.suggest.SuggestionDetailViewModel
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
    alternativesViewModel: AlternativesViewModel = hiltViewModel(),
    suggestionViewModel: SuggestionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val areaCheck by areaCheckViewModel.state.collectAsStateWithLifecycle()
    val admin by adminViewModel.state.collectAsStateWithLifecycle()
    val isAdmin by adminViewModel.isAdmin.collectAsStateWithLifecycle()
    val alternatives by alternativesViewModel.state.collectAsStateWithLifecycle()
    val suggestion by suggestionViewModel.state.collectAsStateWithLifecycle()
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

    // §4.1 rotation: what the compass shows, and which my-location mode is on.
    var bearing by remember { mutableFloatStateOf(0f) }
    var offNorth by remember { mutableStateOf(false) }
    var locationMode by remember { mutableStateOf(LocationMode.Off) }
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
    // The alternatives sit above a detail: opened from a full container, closing
    // them returns to that container.
    val mode = when {
        areaCheck.active -> SheetMode.AreaCheck
        alternatives.open -> SheetMode.Alternatives
        suggestion.open -> SheetMode.Suggestion
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
                SheetMode.Alternatives -> alternativesViewModel.close()
                SheetMode.Suggestion -> suggestionViewModel.close()
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
            SheetMode.Alternatives -> alternativesViewModel.close()
            SheetMode.Suggestion -> suggestionViewModel.close()
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

    val onSuggestionTap by rememberUpdatedState { suggestionId: String ->
        if (!areaCheck.active && !alternatives.open) {
            // The suggestion replaces a container detail rather than sitting on it.
            viewModel.dismissDetail()
            suggestionViewModel.open(suggestionId)
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
                SheetMode.Menu -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MapMenuBody(
                    nearest = state.nearest,
                    nearestLoading = state.nearestLoading,
                    // §5.2: the same list, from where the user is.
                    onWhereToThrow = { alternativesViewModel.open(AlternativesOrigin.MyLocation) },
                    onNearestClick = viewModel::onContainerSelected,
                    onMyReports = onMyReports,
                    onCityStats = onCityStats,
                    onSuggestions = onSuggestionsList,
                    onMapYourStreet = areaCheckViewModel::requestFromMenu,
                    showAdmin = isAdmin,
                    onAdmin = adminViewModel::open,
                    )
                }
                SheetMode.Detail -> state.detail?.let { detail ->
                    // Keyed per container, so opening another one starts at the top.
                    key(state.selectedId) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            ContainerDetailContent(
                                state = detail,
                                onMeToo = viewModel::onMeToo,
                                onEmptied = viewModel::onEmptied,
                                onReportOther = onReport,
                                onConfirmExists = viewModel::onConfirmExists,
                                onNearestWithSpace = {
                                    state.selectedId?.let { id ->
                                        alternativesViewModel.open(
                                            AlternativesOrigin.Container(
                                                id = id,
                                                code = detail.code,
                                                position = LatLon(detail.lat, detail.lon),
                                                category = detail.category,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                SheetMode.Suggestion -> SuggestionDetailContent(
                    state = suggestion,
                    onVote = suggestionViewModel::vote,
                )
                SheetMode.Alternatives -> AlternativesContent(
                    state = alternatives,
                    onRowClick = alternativesViewModel::focus,
                    onRetry = alternativesViewModel::retry,
                    onSuggestHere = onSuggest,
                )
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
        // One instance: re-activating the location dot after a theme change must
        // not stack a second listener.
        val trackingListener = remember {
            object : OnCameraTrackingChangedListener {
                // §4.1: a pan while following ends follow mode.
                override fun onCameraTrackingDismissed() {
                    mapRef?.let(MapNavigation::stopFollowing)
                    locationMode = LocationMode.Off
                }

                override fun onCameraTrackingChanged(currentMode: Int) = Unit
            }
        }

        DisposableEffect(mapView, darkTheme) {
            var registered: MapLibreMap? = null
            val onIdle = MapLibreMap.OnCameraIdleListener {
                val map = registered ?: return@OnCameraIdleListener
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
            // §4.1: the compass needle turns with the map and appears once it is
            // rotated or tilted.
            val onMove = MapLibreMap.OnCameraMoveListener {
                val camera = registered?.cameraPosition ?: return@OnCameraMoveListener
                bearing = camera.bearing.toFloat()
                offNorth = MapNavigation.isOffNorth(camera.bearing, camera.tilt)
            }
            // A pan by the user ends "centred on me" (§4.1); follow mode is ended by
            // MapLibre's own tracking, through [trackingListener].
            val onMoveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE &&
                    locationMode == LocationMode.Centered
                ) {
                    locationMode = LocationMode.Off
                }
            }
            val onClick = MapLibreMap.OnMapClickListener { latLng ->
                val map = registered ?: return@OnMapClickListener false
                val screenPoint = map.projection.toScreenLocation(latLng)
                val slop = context.markerTouchSlopPx()

                val containerId = MapLayers.containerAt(map, screenPoint, slop)
                if (containerId != null) {
                    haptics.tick()
                    onMarkerTap(containerId)
                    return@OnMapClickListener true
                }

                // §5.3: a suggestion marker opens its small sheet with a vote button.
                val suggestionId = MapLayers.suggestionAt(map, screenPoint, slop)
                if (suggestionId != null) {
                    haptics.tick()
                    onSuggestionTap(suggestionId)
                    return@OnMapClickListener true
                }

                false
            }

            mapView.getMapAsync { map ->
                registered = map
                mapRef = map
                // §4.1: twist to rotate, two-finger drag to tilt (max 45°).
                MapNavigation.configureGestures(map)
                // MapLibre's own attribution and logo are replaced by ours (§7).
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false

                val styleJson = KantaMapStyle.load(context, darkTheme)
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    styleRef = style
                    MapLayers.install(style, markerFactory, darkTheme)
                }

                map.addOnCameraIdleListener(onIdle)
                map.addOnCameraMoveListener(onMove)
                map.addOnCameraMoveStartedListener(onMoveStarted)
                map.addOnMapClickListener(onClick)
            }
            onDispose {
                registered?.let { map ->
                    map.removeOnCameraIdleListener(onIdle)
                    map.removeOnCameraMoveListener(onMove)
                    map.removeOnCameraMoveStartedListener(onMoveStarted)
                    map.removeOnMapClickListener(onClick)
                }
                mapRef = null
                styleRef = null
            }
        }

        // §4.1: the location dot (and follow mode's heading cone), once there is a
        // style and permission — including permission granted later.
        LaunchedEffect(styleRef, state.hasLocationPermission) {
            val style = styleRef ?: return@LaunchedEffect
            val map = mapRef ?: return@LaunchedEffect
            MapNavigation.activateLocation(
                context = context,
                map = map,
                style = style,
                darkTheme = darkTheme,
                hasPermission = state.hasLocationPermission,
                trackingListener = trackingListener,
            )
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

        // §5.2: the listed containers stay bright, every other one dims. The
        // container that is full stays bright too — it is what the list is about.
        LaunchedEffect(styleRef, alternatives.open, alternatives.loading, alternatives.items) {
            val style = styleRef ?: return@LaunchedEffect
            val ids = if (alternatives.open && !alternatives.loading) {
                alternatives.items.map { it.id } +
                    listOfNotNull((alternatives.origin as? AlternativesOrigin.Container)?.id)
            } else {
                null
            }
            MapLayers.setHighlight(style, ids)
        }

        // §5.2: once the list is in, show all of it — the origin and every result —
        // in the map area above the sheet.
        LaunchedEffect(mapRef, alternatives.open, alternatives.loading, alternatives.from) {
            val map = mapRef ?: return@LaunchedEffect
            val from = alternatives.from ?: return@LaunchedEffect
            if (!alternatives.open || alternatives.loading) return@LaunchedEffect
            val farthest = alternatives.items.maxOfOrNull { it.distanceMetres }?.toDouble() ?: 0.0
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    framedAboveSheet(
                        centre = from,
                        radiusMetres = (farthest * 1.1).coerceAtLeast(MIN_FRAME_RADIUS_M),
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

        // §5.2: "tapping a row moves the camera there".
        LaunchedEffect(mapRef, alternatives.focusedId) {
            val map = mapRef ?: return@LaunchedEffect
            val item = alternatives.items.firstOrNull { it.id == alternatives.focusedId } ?: return@LaunchedEffect
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    framedAboveSheet(
                        centre = item.position,
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
        val alternativeFocus = alternatives.items.firstOrNull { it.id == alternatives.focusedId }?.position
        // One ring on the map at most: the alternative or the admin item being looked at.
        LaunchedEffect(styleRef, mode, alternativeFocus, admin.focus, suggestion.suggestion?.position) {
            val style = styleRef ?: return@LaunchedEffect
            MapLayers.setFocus(
                style,
                when (mode) {
                    SheetMode.Alternatives -> alternativeFocus
                    SheetMode.Admin -> admin.focus
                    SheetMode.Suggestion -> suggestion.suggestion?.position
                    else -> null
                },
            )
        }
        LaunchedEffect(mapRef, admin.open, admin.focus) {
            val focus = if (admin.open) admin.focus else null
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
                        // Near the user: close enough for real shapes (§3.4). No fix:
                        // the city overview §4.1 asks for.
                        .zoom(if (target == LatLon.SKOPJE_CENTRE) CITY_OVERVIEW_ZOOM else USER_ZOOM)
                        .build(),
                ),
            )
            viewModel.onCameraMoved()
        }

        // ---------------------------------------------------------------------------------
        // Overlays (§4.1)
        // ---------------------------------------------------------------------------------
        MapOverlays(
            sheetState = sheetState,
            showSuggestions = state.showSuggestions,
            bearing = bearing,
            showCompass = offNorth,
            locationMode = locationMode,
            onProfileClick = onProfileClick,
            // §4.1: back to north and flat; also ends follow mode.
            onCompassClick = {
                mapRef?.let(MapNavigation::resetNorth)
                if (locationMode == LocationMode.Following) locationMode = LocationMode.Off
            },
            onMyLocationClick = {
                if (state.hasLocationPermission) {
                    // §4.1: 1st tap centres, 2nd follows the phone's heading, and a
                    // tap while following stops and faces north again.
                    when (locationMode) {
                        LocationMode.Off -> {
                            viewModel.onMyLocationClick()
                            locationMode = LocationMode.Centered
                        }
                        LocationMode.Centered -> {
                            mapRef?.let(MapNavigation::follow)
                            locationMode = LocationMode.Following
                        }
                        LocationMode.Following -> {
                            mapRef?.let(MapNavigation::resetNorth)
                            locationMode = LocationMode.Centered
                        }
                    }
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

/** Around the user: shapes, not dots (§3.4 switches at 15.5). */
private const val USER_ZOOM = 16.0

/** §4.1 fallback: Skopje centre at zoom 14. */
private const val CITY_OVERVIEW_ZOOM = 14.0
private const val CITY_ZOOM = 11.4
private const val CAMERA_MS = 600

/** Enough map around a reviewed pin to see what else stands there. */
private const val FOCUS_RADIUS_M = 60.0

/** §5.2 framing when there is nothing (or only something very close) to show. */
private const val MIN_FRAME_RADIUS_M = 200.0

/** What the one sheet is showing (§4.1: it is the only menu). */
private enum class SheetMode { Menu, Detail, AreaCheck, Alternatives, Suggestion, Admin }

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
    sheetState: KantaSheetState,
    showSuggestions: Boolean,
    bearing: Float,
    showCompass: Boolean,
    locationMode: LocationMode,
    onProfileClick: () -> Unit,
    onCompassClick: () -> Unit,
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

        // §4.1 map buttons and §7 attribution ride on the sheet's top edge: 16 dp
        // above it while it moves, fading out as it rises past half height, when
        // the map they act on is mostly covered anyway.
        val hidden by remember { derivedStateOf { sheetState.expansionAboveHalf > 0.5f } }
        var buttonsHeight by remember { mutableIntStateOf(0) }
        var attributionHeight by remember { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = Spacing.l)
                .onSizeChanged { buttonsHeight = it.height }
                .offset {
                    IntOffset(0, (sheetState.offset.value - Spacing.l.toPx() - buttonsHeight).roundToInt())
                }
                .graphicsLayer { alpha = 1f - sheetState.expansionAboveHalf },
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            MapCircleButton(
                icon = KantaIcons.Suggest,
                contentDescription = stringResource(R.string.map_toggle_suggestions),
                onClick = onToggleSuggestions,
                enabled = !hidden,
                tint = if (showSuggestions) {
                    KantaTheme.colors.brand
                } else {
                    KantaTheme.colors.onSurfaceMuted
                },
            )
            // §4.1: only while the map is rotated or tilted; fades once facing north.
            AnimatedVisibility(
                visible = showCompass,
                enter = fadeIn(Motion.tweenMedium()),
                exit = fadeOut(Motion.tweenMedium()),
            ) {
                MapCircleButton(
                    contentDescription = stringResource(R.string.map_compass),
                    onClick = onCompassClick,
                    enabled = !hidden,
                ) {
                    CompassNeedle(
                        bearing = bearing,
                        north = KantaTheme.colors.brand,
                        south = KantaTheme.colors.outline,
                    )
                }
            }
            // §4.1: the icon says which mode is on.
            MapCircleButton(
                icon = when (locationMode) {
                    LocationMode.Off -> KantaIcons.MyLocationIdle
                    LocationMode.Centered -> KantaIcons.MyLocation
                    LocationMode.Following -> KantaIcons.FollowHeading
                },
                contentDescription = stringResource(
                    when (locationMode) {
                        LocationMode.Off -> R.string.map_my_location
                        LocationMode.Centered -> R.string.map_follow_heading
                        LocationMode.Following -> R.string.map_stop_following
                    },
                ),
                onClick = onMyLocationClick,
                enabled = !hidden,
                tint = if (locationMode == LocationMode.Off) {
                    KantaTheme.colors.onSurfaceMuted
                } else {
                    KantaTheme.colors.brand
                },
            )
        }

        // §7: attribution is a licence condition, not decoration. Small, but it
        // must stay legible — hence the surface behind it.
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = Spacing.s)
                .onSizeChanged { attributionHeight = it.height }
                .offset {
                    IntOffset(0, (sheetState.offset.value - Spacing.s.toPx() - attributionHeight).roundToInt())
                }
                .graphicsLayer { alpha = 1f - sheetState.expansionAboveHalf },
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
    enabled: Boolean = true,
) = MapCircleButton(contentDescription, onClick, modifier, enabled) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun MapCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haptics = rememberKantaHaptics()
    Surface(
        onClick = {
            haptics.tick()
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .size(Spacing.minTouchTarget)
            .kantaSoftShadow(CircleShape)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
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
