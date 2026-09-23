package mk.kanta.app.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mk.kanta.app.R
import mk.kanta.app.core.auth.AuthGate
import mk.kanta.app.core.auth.PendingAction
import mk.kanta.app.core.data.local.ContainerDao
import mk.kanta.app.core.data.local.ContainerEntity
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.PublicReportDto
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.location.LocationProvider
import mk.kanta.app.feature.map.sheet.NearestContainerUi
import kotlin.math.roundToInt
import javax.inject.Inject

/** The viewport, in the form `containers_in_bbox` wants. */
data class Bbox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    /**
     * Grow the box before asking the server, so a small pan lands inside data we
     * already hold instead of triggering another round trip.
     */
    fun padded(factor: Double = 0.25): Bbox {
        val padLon = (maxLon - minLon) * factor
        val padLat = (maxLat - minLat) * factor
        return Bbox(minLon - padLon, minLat - padLat, maxLon + padLon, maxLat + padLat)
    }
}

data class ContainerDetailState(
    val loading: Boolean = true,
    val code: String = "",
    val kind: ContainerKind = ContainerKind.BIG,
    val category: ContainerCategory = ContainerCategory.GENERAL,
    val status: ContainerStatus = ContainerStatus.OK,
    val statusSinceHours: Double? = null,
    val verified: Boolean = true,
    val municipality: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val unconfirmedFull: Long = 0,
    val reports: List<PublicReportDto> = emptyList(),
    val error: KantaError? = null,
)

data class MapUiState(
    /** True only before the first draw; the cache usually fills this instantly. */
    val loading: Boolean = true,
    val containers: List<MapLayers.ContainerFeature> = emptyList(),
    val suggestions: List<Triple<String, Double, Double>> = emptyList(),
    val showSuggestions: Boolean = false,
    val selectedId: String? = null,
    val detail: ContainerDetailState? = null,
    val userLocation: LatLon? = null,
    val hasLocationPermission: Boolean = false,
    /** Set when a refresh failed. The cached map stays on screen underneath. */
    val error: KantaError? = null,
    val refreshing: Boolean = false,
    /** §4.1 "Near you": the nearest container that is not full. */
    val nearest: NearestContainerUi? = null,
    val nearestLoading: Boolean = false,
    /** A one-line success message (string resource), e.g. after "Me too". */
    val notice: Int? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: KantaRepository,
    private val containerDao: ContainerDao,
    private val locationProvider: LocationProvider,
    private val authGate: AuthGate,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /** The viewport, republished on every camera idle. */
    private val viewport = MutableStateFlow<Bbox?>(null)

    /** Initial camera target: the user if we have them, Skopje centre otherwise (§4.1). */
    private val _cameraTarget = MutableStateFlow<LatLon?>(null)
    val cameraTarget: StateFlow<LatLon?> = _cameraTarget.asStateFlow()

    init {
        observeCache()
        refreshOnIdle()
        resolveInitialCamera()
        runConfirmationsWhenReady()
    }

    // -----------------------------------------------------------------------------------------
    // Me too / It's been emptied (§4.1, §5.1) — through the auth gate (§4.2)
    // -----------------------------------------------------------------------------------------

    fun onMeToo() = requestConfirmation(kind = "me_too")

    fun onEmptied() = requestConfirmation(kind = "resolved")

    /**
     * Both buttons act on the open `full` report — they are only offered while the
     * container is full. Signed out, the gate stores this and it runs after sign-in.
     */
    private fun requestConfirmation(kind: String) {
        val detail = _state.value.detail ?: return
        val containerId = _state.value.selectedId ?: return
        val report = detail.reports.firstOrNull { it.state == "open" && it.kind == "full" }
        if (report == null) {
            _state.value = _state.value.copy(error = KantaError.ReportNotOpen)
            return
        }
        authGate.request(PendingAction.ConfirmReport(containerId, report.id, kind))
    }

    private fun runConfirmationsWhenReady() {
        authGate.ready
            .filterIsInstance<PendingAction.ConfirmReport>()
            .onEach { action ->
                authGate.consume(action)
                confirm(action)
            }
            .launchIn(viewModelScope)
    }

    private suspend fun confirm(action: PendingAction.ConfirmReport) {
        repository.confirmReport(action.reportId, action.kind).collect { result ->
            when (result) {
                is KantaResult.Loading -> Unit
                is KantaResult.Failure -> _state.value = _state.value.copy(error = result.error)
                is KantaResult.Success -> {
                    // Say what actually happened. A resolved confirmation without a
                    // photo is one of two (§5.1) — pretending it closed the report
                    // would be a small lie the user discovers later.
                    val notice = when {
                        action.kind == "me_too" -> R.string.notice_me_too
                        result.data.reportState == "resolved" -> R.string.notice_emptied_closed
                        else -> R.string.notice_emptied_pending
                    }
                    _state.value = _state.value.copy(notice = notice)
                    if (_state.value.selectedId == action.containerId) {
                        loadDetail(action.containerId)
                        loadReports(action.containerId)
                    }
                    viewport.value?.let { refresh(it) }
                }
            }
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /**
     * §4.1/§5.2: the nearest container that is not full, from wherever the user
     * is. Recomputed when we get a location rather than on every camera move —
     * "near you" means near the person, not near the viewport.
     */
    private fun loadNearest(from: LatLon) {
        viewModelScope.launch {
            _state.value = _state.value.copy(nearestLoading = true)
            repository.nearestContainers(
                lon = from.lon,
                lat = from.lat,
                limit = 1,
            ).collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Failure ->
                        _state.value = _state.value.copy(nearestLoading = false, nearest = null)
                    is KantaResult.Success -> {
                        val row = result.data.firstOrNull()
                        _state.value = _state.value.copy(
                            nearestLoading = false,
                            nearest = row?.let {
                                NearestContainerUi(
                                    id = it.id,
                                    code = it.code,
                                    kind = if (it.kind == "small") ContainerKind.SMALL else ContainerKind.BIG,
                                    status = it.status.toStatus(),
                                    distanceMetres = it.distanceM.roundToInt(),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * The map draws from Room, always. The network only ever writes into Room, so
     * there is exactly one path to the screen and the cached markers appear on
     * the first frame rather than after a round trip (§8: interactive in < 2 s).
     */
    private fun observeCache() {
        viewport
            .filterNotNull()
            .distinctUntilChanged()
            .flatMapLatest { box ->
                val padded = box.padded()
                containerDao.observeInBbox(
                    padded.minLon, padded.minLat, padded.maxLon, padded.maxLat,
                )
            }
            .map { entities -> entities.map { it.toFeature() } }
            .onEach { features ->
                _state.value = _state.value.copy(containers = features, loading = false)
            }
            .launchIn(viewModelScope)
    }

    /**
     * §4.1 brief: debounce 300 ms on camera idle. Panning across the city fires a
     * camera-idle for every stop; without this, each one is a request.
     */
    private fun refreshOnIdle() {
        viewport
            .filterNotNull()
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { box -> refresh(box) }
            .launchIn(viewModelScope)
    }

    private suspend fun refresh(box: Bbox) {
        val padded = box.padded()
        _state.value = _state.value.copy(refreshing = true)

        repository.containersInBbox(
            padded.minLon, padded.minLat, padded.maxLon, padded.maxLat,
        ).collect { result ->
            when (result) {
                is KantaResult.Loading -> Unit
                is KantaResult.Success -> {
                    val entities = result.data.map { dto ->
                        ContainerEntity(
                            id = dto.id,
                            code = dto.code,
                            kind = dto.kind,
                            category = dto.category,
                            status = dto.status,
                            verified = dto.verified,
                            lon = dto.lon,
                            lat = dto.lat,
                        )
                    }
                    containerDao.upsertAll(entities)
                    // Anything the server did not return for this box is gone —
                    // soft-deleted (§4.6) or moved — so drop it from the cache.
                    containerDao.pruneBbox(
                        padded.minLon, padded.minLat, padded.maxLon, padded.maxLat,
                        entities.map { it.id },
                    )
                    _state.value = _state.value.copy(refreshing = false, error = null)
                }
                is KantaResult.Failure -> {
                    // The cached markers stay on screen; the banner explains why
                    // they may be stale rather than emptying the map.
                    _state.value = _state.value.copy(refreshing = false, error = result.error)
                }
            }
        }
    }

    private fun resolveInitialCamera() {
        viewModelScope.launch {
            val hasPermission = locationProvider.hasPermission()
            val location = if (hasPermission) locationProvider.current() else null
            _state.value = _state.value.copy(
                hasLocationPermission = hasPermission,
                userLocation = location,
            )
            _cameraTarget.value = location ?: LatLon.SKOPJE_CENTRE
            loadNearest(location ?: LatLon.SKOPJE_CENTRE)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Events from the map
    // -----------------------------------------------------------------------------------------

    fun onCameraIdle(box: Bbox) {
        viewport.value = box
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(hasLocationPermission = granted)
        if (granted) {
            viewModelScope.launch {
                val location = locationProvider.current()
                _state.value = _state.value.copy(userLocation = location)
                if (location != null) {
                    _cameraTarget.value = location
                    loadNearest(location)
                }
            }
        }
    }

    fun onMyLocationClick() {
        viewModelScope.launch {
            val location = locationProvider.current() ?: return@launch
            _state.value = _state.value.copy(userLocation = location)
            _cameraTarget.value = location
            loadNearest(location)
        }
    }

    /** Camera target is consumed once so a recomposition does not re-fly the map. */
    fun onCameraMoved() {
        _cameraTarget.value = null
    }

    fun toggleSuggestions() {
        val showing = !_state.value.showSuggestions
        _state.value = _state.value.copy(showSuggestions = showing)
        if (showing && _state.value.suggestions.isEmpty()) loadSuggestions()
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            repository.suggestions(limit = 200).collect { result ->
                if (result is KantaResult.Success) {
                    _state.value = _state.value.copy(
                        suggestions = result.data.map { Triple(it.id, it.lon, it.lat) },
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Container detail sheet (§4.1)
    // -----------------------------------------------------------------------------------------

    fun onContainerSelected(containerId: String) {
        _state.value = _state.value.copy(
            selectedId = containerId,
            detail = ContainerDetailState(loading = true),
        )
        loadDetail(containerId)
        loadReports(containerId)
    }

    private fun loadDetail(containerId: String) {
        viewModelScope.launch {
            repository.containerDetail(containerId).collect { result ->
                val current = _state.value.detail ?: ContainerDetailState()
                _state.value = when (result) {
                    is KantaResult.Loading -> _state.value
                    is KantaResult.Failure ->
                        _state.value.copy(detail = current.copy(loading = false, error = result.error))
                    is KantaResult.Success -> {
                        val dto = result.data
                        if (dto == null) {
                            _state.value.copy(
                                detail = current.copy(
                                    loading = false,
                                    error = KantaError.ContainerNotFound,
                                ),
                            )
                        } else {
                            _state.value.copy(
                                detail = current.copy(
                                    loading = false,
                                    code = dto.code,
                                    kind = if (dto.kind == "small") ContainerKind.SMALL else ContainerKind.BIG,
                                    category = dto.category.toCategory(),
                                    status = dto.status.toStatus(),
                                    statusSinceHours = hoursSince(dto.statusSince),
                                    verified = dto.verified,
                                    municipality = dto.municipalityName,
                                    lat = dto.lat,
                                    lon = dto.lon,
                                    unconfirmedFull = dto.unconfirmedFull,
                                    error = null,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadReports(containerId: String) {
        viewModelScope.launch {
            repository.reportsForContainer(containerId).collect { result ->
                if (result is KantaResult.Success) {
                    val current = _state.value.detail ?: return@collect
                    _state.value = _state.value.copy(detail = current.copy(reports = result.data))
                }
            }
        }
    }

    fun dismissDetail() {
        _state.value = _state.value.copy(selectedId = null, detail = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}

// -------------------------------------------------------------------------------------------
// Conversions
// -------------------------------------------------------------------------------------------

private fun ContainerEntity.toFeature() = MapLayers.ContainerFeature(
    id = id,
    code = code,
    kind = if (kind == "small") ContainerKind.SMALL else ContainerKind.BIG,
    category = category.toCategory(),
    status = status.toStatus(),
    verified = verified,
    lon = lon,
    lat = lat,
)

private fun String.toCategory(): ContainerCategory = when (this) {
    "glass" -> ContainerCategory.GLASS
    "paper" -> ContainerCategory.PAPER
    "plastic" -> ContainerCategory.PLASTIC
    "mixed_recycling" -> ContainerCategory.MIXED_RECYCLING
    else -> ContainerCategory.GENERAL
}

private fun String.toStatus(): ContainerStatus = when (this) {
    "full" -> ContainerStatus.FULL
    "broken" -> ContainerStatus.BROKEN
    "destroyed" -> ContainerStatus.DESTROYED
    "missing" -> ContainerStatus.MISSING
    else -> ContainerStatus.OK
}

/**
 * Hours since an ISO-8601 timestamp, for "full for 31 h" (§4.1).
 * Returns null rather than throwing on a shape we did not expect — a missing
 * duration is a smaller problem than a crashed sheet.
 */
private fun hoursSince(isoTimestamp: String): Double? = runCatching {
    val instant = java.time.OffsetDateTime.parse(isoTimestamp).toInstant()
    java.time.Duration.between(instant, java.time.Instant.now()).toMinutes() / 60.0
}.getOrNull()
