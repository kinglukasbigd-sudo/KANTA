package mk.kanta.app.feature.alternatives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.NearbyContainerDto
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.location.LocationProvider
import javax.inject.Inject
import kotlin.math.roundToInt

/** One row of "Nearest containers with space" (§5.2). */
data class AlternativeUi(
    val id: String,
    val code: String,
    val kind: ContainerKind,
    val status: ContainerStatus,
    val position: LatLon,
    val distanceMetres: Int,
    val walkingMinutes: Int,
    /** §5.2: ≤ 300 m — highlighted as "close". */
    val close: Boolean,
)

data class AlternativesUiState(
    val open: Boolean = false,
    val origin: AlternativesOrigin? = null,
    /** Where the distances are measured from: the full container, or the user. */
    val from: LatLon? = null,
    val category: ContainerCategory = ContainerCategory.GENERAL,
    val loading: Boolean = false,
    val items: List<AlternativeUi> = emptyList(),
    val error: KantaError? = null,
    /** "Where can I throw this?" without a location fix. */
    val locationMissing: Boolean = false,
    /** The row the camera went to, ringed on the map. */
    val focusedId: String? = null,
) {
    val empty: Boolean get() = open && !loading && error == null && !locationMissing && items.isEmpty()
}

/**
 * "Nearest containers with space" (§5.2). It lives on the main map: the sheet
 * holds the list, the map above highlights those containers and dims the rest,
 * and a row tap moves the camera there.
 *
 * The rules — same category, big containers only, not full/destroyed/missing,
 * within 600 m — are the server's (`nearest_containers`); this asks and shows.
 */
@HiltViewModel
class AlternativesViewModel @Inject constructor(
    private val repository: KantaRepository,
    private val location: LocationProvider,
    private val launcher: AlternativesLauncher,
) : ViewModel() {

    private val _state = MutableStateFlow(AlternativesUiState())
    val state: StateFlow<AlternativesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // §4.3: a sent Full report comes back to the map with its origin waiting.
        viewModelScope.launch {
            launcher.pending.collect { pending ->
                if (pending != null) launcher.take()?.let(::open)
            }
        }
    }

    fun open(origin: AlternativesOrigin) {
        val category = AlternativesRules.categoryFor(
            (origin as? AlternativesOrigin.Container)?.category,
        )
        _state.value = AlternativesUiState(open = true, origin = origin, category = category, loading = true)
        load()
    }

    fun retry() = load()

    fun focus(item: AlternativeUi) = _state.update { it.copy(focusedId = item.id) }

    fun close() {
        loadJob?.cancel()
        _state.value = AlternativesUiState()
    }

    private fun load() {
        val origin = _state.value.origin ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, locationMissing = false) }

            val from = origin.position ?: location.fresh()
            if (from == null) {
                _state.update { it.copy(loading = false, locationMissing = true) }
                return@launch
            }
            _state.update { it.copy(from = from) }

            repository.nearestContainers(
                lon = from.lon,
                lat = from.lat,
                category = _state.value.category,
                kind = ContainerKind.BIG,
                limit = AlternativesRules.LIMIT,
                maxMetres = AlternativesRules.MAX_METRES,
            ).collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Success -> _state.update { s ->
                        s.copy(
                            loading = false,
                            // The full container itself is excluded by the server
                            // (it is full); filtered again so it can never be
                            // offered as its own alternative.
                            items = result.data
                                .filter { it.id != (origin as? AlternativesOrigin.Container)?.id }
                                .map { it.toUi() },
                        )
                    }
                    is KantaResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
                }
            }
        }
    }
}

private fun NearbyContainerDto.toUi(): AlternativeUi {
    val metres = distanceM.roundToInt()
    return AlternativeUi(
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
        position = LatLon(lat, lon),
        distanceMetres = metres,
        walkingMinutes = AlternativesRules.walkingMinutes(metres),
        close = AlternativesRules.isClose(metres),
    )
}
