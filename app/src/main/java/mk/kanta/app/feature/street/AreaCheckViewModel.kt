package mk.kanta.app.feature.street

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.core.auth.AuthGate
import mk.kanta.app.core.auth.AuthRepository
import mk.kanta.app.core.auth.AuthState
import mk.kanta.app.core.auth.PendingAction
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.UnverifiedContainerDto
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.location.LocationProvider
import javax.inject.Inject
import kotlin.math.roundToInt

enum class AreaCheckPhase {
    /** The question and its three answers (§4.6 step 1–2). */
    Asking,

    /** "One on the map is not here" → the user taps that marker. */
    PickingMissing,

    /** "Yes, everything is there" is being saved. */
    Saving,

    /** The thank-you moment, then the sheet closes by itself. */
    Thanks,
}

/** An unverified container inside the ring (§4.6 step 3). */
data class UnverifiedNearbyUi(
    val id: String,
    val code: String,
    val kind: ContainerKind,
    val status: ContainerStatus,
    val distanceMetres: Int,
    /** From the server (0015): within 50 m, not yours, not already confirmed. */
    val canConfirm: Boolean,
    val isMine: Boolean,
    val iConfirmed: Boolean,
)

data class AreaCheckUiState(
    val active: Boolean = false,
    val phase: AreaCheckPhase = AreaCheckPhase.Asking,
    /** The centre of the 150 m ring — where the user is standing. */
    val centre: LatLon? = null,
    val locating: Boolean = false,
    val locationMissing: Boolean = false,
    val unverified: List<UnverifiedNearbyUi> = emptyList(),
    val confirmingId: String? = null,
    val error: KantaError? = null,
)

/** Things the map screen does on the check's behalf. */
sealed interface AreaCheckEvent {
    /** "One is missing" → the Add container flow (camera first). */
    data object AddContainer : AreaCheckEvent

    /** A marker was picked as not being there → the report flow, kind `missing`. */
    data class ReportMissing(val containerId: String) : AreaCheckEvent

    /** Something on the map changed (a container just got verified). */
    data object RefreshMap : AreaCheckEvent
}

/**
 * "Map your street" (§4.6): when to ask, and the check itself.
 *
 * The check lives on the main map rather than a separate screen — the map is
 * already the best picture of "what's near me", so the ring is drawn on it and
 * the sheet (the app's only menu, §4.1) holds the question. "Tap the one that's
 * not here" then works on the real markers.
 *
 * Every rule is the server's: whether to prompt (`should_prompt_area_check`),
 * whether a container may be confirmed (`can_confirm` from `unverified_nearby`),
 * and whether a confirmation is accepted. This class only asks and shows.
 */
@HiltViewModel
class AreaCheckViewModel @Inject constructor(
    private val repository: KantaRepository,
    private val location: LocationProvider,
    private val prompter: AreaCheckPrompter,
    private val gate: AuthGate,
    auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AreaCheckUiState())
    val state: StateFlow<AreaCheckUiState> = _state.asStateFlow()

    private val _events = Channel<AreaCheckEvent>(Channel.BUFFERED)
    val events: Flow<AreaCheckEvent> = _events.receiveAsFlow()

    /** What the map reports about itself: is the plain menu showing, and where is the user. */
    private data class MapContext(val menuShowing: Boolean, val location: LatLon?)

    private val mapContext = MutableStateFlow(MapContext(menuShowing = false, location = null))

    private var askingServer = false
    private var closeJob: Job? = null

    init {
        // "Map your street" from the menu, after sign-in if it was needed.
        gate.ready
            .filterIsInstance<PendingAction.OpenAreaCheck>()
            .onEach { action ->
                gate.consume(action)
                prompter.markHandled()
                start(from = null)
            }
            .launchIn(viewModelScope)

        combine(
            auth.authState,
            gate.idle,
            prompter.firstLoginDue,
            prompter.handledThisSession,
            mapContext,
        ) { authState, gateIdle, firstLoginDue, handled, map ->
            val decision = AreaCheckPromptPolicy.decide(
                signedIn = authState is AuthState.SignedIn,
                idle = gateIdle && map.menuShowing && !_state.value.active,
                hasLocation = map.location != null,
                handledThisSession = handled,
                firstLoginDue = firstLoginDue,
            )
            decision to map.location
        }
            .onEach { (decision, at) -> act(decision, at) }
            .launchIn(viewModelScope)
    }

    /** Called by the map whenever what it shows, or where the user is, changes. */
    fun onMapContext(menuShowing: Boolean, location: LatLon?) {
        mapContext.value = MapContext(menuShowing, location)
    }

    private fun act(decision: AreaCheckPromptPolicy.Decision, at: LatLon?) {
        when (decision) {
            AreaCheckPromptPolicy.Decision.ShowNow -> {
                prompter.markHandled()
                start(from = at)
            }
            AreaCheckPromptPolicy.Decision.AskServer -> {
                if (askingServer || at == null) return
                askingServer = true
                // Once per session, whatever the answer (§4.6: "never more than once").
                prompter.markHandled()
                viewModelScope.launch {
                    repository.shouldPromptAreaCheck(at.lon, at.lat).collect { result ->
                        if (result is KantaResult.Success && result.data && mapContext.value.menuShowing) {
                            start(from = at)
                        }
                    }
                    askingServer = false
                }
            }
            AreaCheckPromptPolicy.Decision.Wait, AreaCheckPromptPolicy.Decision.Done -> Unit
        }
    }

    // -----------------------------------------------------------------------------------------
    // Opening
    // -----------------------------------------------------------------------------------------

    /** The permanent "Map your street" row (§4.6). Signing in first if needed. */
    fun requestFromMenu() = gate.request(PendingAction.OpenAreaCheck)

    private fun start(from: LatLon?) {
        closeJob?.cancel()
        _state.value = AreaCheckUiState(active = true, centre = from, locating = true)
        viewModelScope.launch {
            // The ring and the saved check are about where the user stands now,
            // so ask for a fresh fix even when the map had one.
            val here = location.fresh() ?: from
            if (here == null) {
                _state.update { it.copy(locating = false, locationMissing = true) }
                return@launch
            }
            _state.update { it.copy(centre = here, locating = false, locationMissing = false) }
            loadUnverified(here)
        }
    }

    private suspend fun loadUnverified(at: LatLon) {
        repository.unverifiedNearby(at.lon, at.lat, RADIUS_M).collect { result ->
            if (result is KantaResult.Success) {
                _state.update { it.copy(unverified = result.data.map { dto -> dto.toUi() }) }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The three answers (§4.6 step 2)
    // -----------------------------------------------------------------------------------------

    fun answerAllPresent() {
        val centre = _state.value.centre ?: return
        _state.update { it.copy(phase = AreaCheckPhase.Saving, error = null) }
        viewModelScope.launch {
            repository.submitAreaCheck(centre.lon, centre.lat, RESULT_ALL_PRESENT).collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Success -> thankAndClose()
                    is KantaResult.Failure -> _state.update {
                        it.copy(phase = AreaCheckPhase.Asking, error = result.error)
                    }
                }
            }
        }
    }

    /** The Add container flow records the check itself when it succeeds. */
    fun answerMissingOne() {
        _events.trySend(AreaCheckEvent.AddContainer)
        close()
    }

    fun answerNotHere() = _state.update { it.copy(phase = AreaCheckPhase.PickingMissing, error = null) }

    fun cancelPicking() = _state.update { it.copy(phase = AreaCheckPhase.Asking) }

    /**
     * A marker tap while picking. Returns true when the check took it, so the map
     * does not also open that container's detail.
     */
    fun onMarkerTapped(containerId: String): Boolean {
        if (!_state.value.active || _state.value.phase != AreaCheckPhase.PickingMissing) return false
        _events.trySend(AreaCheckEvent.ReportMissing(containerId))
        close()
        return true
    }

    // -----------------------------------------------------------------------------------------
    // "Yes, it's here" (§4.6 step 3)
    // -----------------------------------------------------------------------------------------

    fun confirmExists(containerId: String) {
        if (_state.value.confirmingId != null) return
        _state.update { it.copy(confirmingId = containerId, error = null) }
        viewModelScope.launch {
            val here = location.fresh() ?: _state.value.centre
            if (here == null) {
                _state.update { it.copy(confirmingId = null, error = KantaError.TooFarToConfirm(null)) }
                return@launch
            }
            repository.confirmContainerExists(containerId, here.lon, here.lat).collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Failure -> _state.update { it.copy(confirmingId = null, error = result.error) }
                    is KantaResult.Success -> {
                        _state.update { it.copy(confirmingId = null) }
                        _events.trySend(AreaCheckEvent.RefreshMap)
                        _state.value.centre?.let { loadUnverified(it) }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Closing
    // -----------------------------------------------------------------------------------------

    /** "Later" (§4.6: always skippable), Back, or a swipe down. */
    fun later() = close()

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun thankAndClose() {
        _state.update { it.copy(phase = AreaCheckPhase.Thanks) }
        closeJob = viewModelScope.launch {
            delay(THANKS_HOLD_MS)
            close()
        }
    }

    private fun close() {
        closeJob?.cancel()
        _state.value = AreaCheckUiState()
    }

    companion object {
        /** §4.6: "a small map of a 150 m radius around the user". */
        const val RADIUS_M = 150

        private const val RESULT_ALL_PRESENT = "all_present"
        private const val THANKS_HOLD_MS = 1_800L
    }
}

private fun UnverifiedContainerDto.toUi() = UnverifiedNearbyUi(
    id = id,
    code = code,
    kind = if (kind == "small") ContainerKind.SMALL else ContainerKind.BIG,
    status = when (status) {
        "full" -> ContainerStatus.FULL
        "broken" -> ContainerStatus.BROKEN
        "destroyed" -> ContainerStatus.DESTROYED
        "missing" -> ContainerStatus.MISSING
        else -> ContainerStatus.OK
    },
    distanceMetres = distanceM.roundToInt(),
    canConfirm = canConfirm,
    isMine = isMine,
    iConfirmed = iConfirmed,
)
