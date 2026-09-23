package mk.kanta.app.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.R
import mk.kanta.app.core.auth.CurrentProfile
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.AdminUnverifiedDto
import mk.kanta.app.core.data.remote.dto.PendingRequestDto
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mk.kanta.app.core.location.LatLon
import javax.inject.Inject

enum class AdminTab { Requests, Unverified, Coverage }

/** A container request waiting in the review queue (§4.6). */
data class PendingRequestUi(
    val id: String,
    val position: LatLon,
    val kind: ContainerKind,
    val category: ContainerCategory,
    val photoPath: String,
    val note: String?,
    val createdAt: String,
)

/** A user-added container nobody has confirmed yet (§4.6). */
data class UnverifiedUi(
    val id: String,
    val code: String,
    val kind: ContainerKind,
    val category: ContainerCategory,
    val position: LatLon,
    val confirmations: Int,
    val createdAt: String,
)

/** One tab's list: loading, loaded, or failed — each with its own designed state (§8). */
data class AdminList<T>(
    val loading: Boolean = false,
    val items: List<T> = emptyList(),
    val error: KantaError? = null,
    val loaded: Boolean = false,
)

data class AdminUiState(
    val open: Boolean = false,
    val tab: AdminTab = AdminTab.Requests,
    val requests: AdminList<PendingRequestUi> = AdminList(),
    val unverified: AdminList<UnverifiedUi> = AdminList(),
    /** admin_coverage(90) as GeoJSON, handed straight to the map layer. */
    val coverage: String? = null,
    val coverageCount: Int = 0,
    val coverageLoading: Boolean = false,
    val coverageError: KantaError? = null,
    /** The request or container being looked at, ringed on the map. */
    val focus: LatLon? = null,
    val focusedId: String? = null,
    /** Rows with an action in flight, so their buttons cannot be pressed twice. */
    val busy: Set<String> = emptySet(),
    /** "Remove this container from the map?" — soft delete still deserves a confirm. */
    val confirmDelete: UnverifiedUi? = null,
    /** A one-line result (string resource) or the server's friendly error. */
    val notice: Int? = null,
    val error: KantaError? = null,
)

sealed interface AdminEvent {
    /** A container appeared, was verified or was removed: redraw the map. */
    data object RefreshMap : AdminEvent
}

/**
 * The hidden Admin row (§4.6): review queue, unverified list, coverage map.
 *
 * Shown only when `profiles.role = 'admin'`, but that is a courtesy — every RPC
 * behind these buttons checks the role again on the server (KA007), so the
 * visibility check here protects nothing and does not need to.
 */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val repository: KantaRepository,
    currentProfile: CurrentProfile,
) : ViewModel() {

    val isAdmin: StateFlow<Boolean> = currentProfile.profile
        .map { it?.isAdmin == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    private val _events = Channel<AdminEvent>(Channel.BUFFERED)
    val events: Flow<AdminEvent> = _events.receiveAsFlow()

    fun open() {
        _state.value = AdminUiState(open = true)
        loadRequests()
    }

    fun close() {
        _state.value = AdminUiState()
    }

    fun selectTab(tab: AdminTab) {
        _state.update { it.copy(tab = tab, focus = null, focusedId = null, error = null) }
        when (tab) {
            AdminTab.Requests -> if (!_state.value.requests.loaded) loadRequests()
            AdminTab.Unverified -> if (!_state.value.unverified.loaded) loadUnverified()
            AdminTab.Coverage -> loadCoverage()
        }
    }

    fun retry() = when (_state.value.tab) {
        AdminTab.Requests -> loadRequests()
        AdminTab.Unverified -> loadUnverified()
        AdminTab.Coverage -> loadCoverage()
    }

    fun focus(id: String, at: LatLon) = _state.update { it.copy(focus = at, focusedId = id) }

    fun dismissNotice() = _state.update { it.copy(notice = null, error = null) }

    // -----------------------------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------------------------

    private fun loadRequests() {
        viewModelScope.launch {
            repository.adminPendingRequests().collect { result ->
                _state.update { s ->
                    s.copy(
                        requests = when (result) {
                            is KantaResult.Loading -> s.requests.copy(loading = true, error = null)
                            is KantaResult.Success -> AdminList(items = result.data.map { it.toUi() }, loaded = true)
                            is KantaResult.Failure -> s.requests.copy(loading = false, error = result.error)
                        },
                    )
                }
            }
        }
    }

    private fun loadUnverified() {
        viewModelScope.launch {
            repository.adminUnverifiedContainers().collect { result ->
                _state.update { s ->
                    s.copy(
                        unverified = when (result) {
                            is KantaResult.Loading -> s.unverified.copy(loading = true, error = null)
                            is KantaResult.Success -> AdminList(items = result.data.map { it.toUi() }, loaded = true)
                            is KantaResult.Failure -> s.unverified.copy(loading = false, error = result.error)
                        },
                    )
                }
            }
        }
    }

    private fun loadCoverage() {
        viewModelScope.launch {
            repository.adminCoverage(COVERAGE_DAYS).collect { result ->
                _state.update { s ->
                    when (result) {
                        is KantaResult.Loading -> s.copy(coverageLoading = true, coverageError = null)
                        is KantaResult.Success -> s.copy(
                            coverageLoading = false,
                            coverage = result.data,
                            coverageCount = countFeatures(result.data),
                        )
                        is KantaResult.Failure -> s.copy(coverageLoading = false, coverageError = result.error)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Review queue
    // -----------------------------------------------------------------------------------------

    fun approve(request: PendingRequestUi) = review(request, approve = true)

    fun reject(request: PendingRequestUi) = review(request, approve = false)

    private fun review(request: PendingRequestUi, approve: Boolean) {
        if (request.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + request.id, error = null) }
        viewModelScope.launch {
            repository.adminReviewRequest(request.id, approve).collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Success -> {
                        _state.update { s ->
                            s.copy(
                                busy = s.busy - request.id,
                                requests = s.requests.copy(items = s.requests.items.filterNot { it.id == request.id }),
                                focus = if (s.focusedId == request.id) null else s.focus,
                                notice = if (approve) R.string.notice_admin_approved else R.string.notice_admin_rejected,
                            )
                        }
                        if (approve) _events.trySend(AdminEvent.RefreshMap)
                    }
                    is KantaResult.Failure -> {
                        _state.update { it.copy(busy = it.busy - request.id, error = result.error) }
                        // Already reviewed elsewhere (KA015): the list is stale, so reload it.
                        if (result.error == KantaError.RequestAlreadyReviewed) loadRequests()
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Unverified containers
    // -----------------------------------------------------------------------------------------

    fun verify(container: UnverifiedUi) {
        runOnContainer(container, R.string.notice_admin_verified) {
            repository.adminVerifyContainer(container.id)
        }
    }

    fun askDelete(container: UnverifiedUi) = _state.update { it.copy(confirmDelete = container) }

    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val container = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        runOnContainer(container, R.string.notice_admin_deleted) {
            repository.adminDeleteContainer(container.id)
        }
    }

    private fun runOnContainer(
        container: UnverifiedUi,
        doneNotice: Int,
        call: () -> Flow<KantaResult<Boolean>>,
    ) {
        if (container.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + container.id, error = null) }
        viewModelScope.launch {
            call().collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Success -> {
                        _state.update { s ->
                            s.copy(
                                busy = s.busy - container.id,
                                unverified = s.unverified.copy(
                                    items = s.unverified.items.filterNot { it.id == container.id },
                                ),
                                focus = if (s.focusedId == container.id) null else s.focus,
                                notice = doneNotice,
                            )
                        }
                        _events.trySend(AdminEvent.RefreshMap)
                    }
                    is KantaResult.Failure ->
                        _state.update { it.copy(busy = it.busy - container.id, error = result.error) }
                }
            }
        }
    }

    private companion object {
        /** §4.6: "green = checked in last 90 days". */
        const val COVERAGE_DAYS = 90

        /** How many checks the summary line mentions. Malformed JSON counts as none. */
        fun countFeatures(geoJson: String): Int = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(geoJson)
                .jsonObject["features"]?.jsonArray?.size ?: 0
        }.getOrDefault(0)
    }
}

private fun String.toKind() = if (this == "small") ContainerKind.SMALL else ContainerKind.BIG

private fun String.toCategory() = when (this) {
    "glass" -> ContainerCategory.GLASS
    "paper" -> ContainerCategory.PAPER
    "plastic" -> ContainerCategory.PLASTIC
    "mixed_recycling" -> ContainerCategory.MIXED_RECYCLING
    else -> ContainerCategory.GENERAL
}

private fun PendingRequestDto.toUi() = PendingRequestUi(
    id = id,
    position = LatLon(lat, lon),
    kind = kind.toKind(),
    category = category.toCategory(),
    photoPath = photoPath,
    note = note?.takeIf { it.isNotBlank() },
    createdAt = createdAt,
)

private fun AdminUnverifiedDto.toUi() = UnverifiedUi(
    id = id,
    code = code,
    kind = kind.toKind(),
    category = category.toCategory(),
    position = LatLon(lat, lon),
    confirmations = confirmationCount.toInt(),
    createdAt = createdAt,
)
