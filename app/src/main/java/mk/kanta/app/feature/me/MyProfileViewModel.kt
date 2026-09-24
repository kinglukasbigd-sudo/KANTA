package mk.kanta.app.feature.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.core.auth.AuthGate
import mk.kanta.app.core.auth.AuthRepository
import mk.kanta.app.core.auth.AuthState
import mk.kanta.app.core.auth.CurrentProfile
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.MyReportDto
import mk.kanta.app.core.data.remote.dto.MySuggestionDto
import mk.kanta.app.core.data.suggest.SuggestionVoting
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.util.isoToMillis
import mk.kanta.app.feature.map.MapRequests
import mk.kanta.app.feature.suggest.SuggestReason
import mk.kanta.app.feature.suggest.SuggestionState
import javax.inject.Inject

/** §5.4 impact counter. Only reports that actually got resolved count. */
data class ImpactUi(val emptied: Int, val repaired: Int, val placed: Int) {
    val isEmpty: Boolean get() = emptied == 0 && repaired == 0 && placed == 0
}

enum class MyReportState { Open, Resolved, Expired }

/** One of my reports (§4.5 screen 11). */
data class MyReportUi(
    val id: String,
    val containerId: String,
    val code: String,
    val containerKind: ContainerKind,
    val position: LatLon?,
    val municipalityId: Int?,
    /** full · damaged · destroyed · burning · missing · dumped_around. */
    val kind: String,
    val state: MyReportState,
    val photoPath: String,
    val createdAtMillis: Long?,
    val resolvedAtMillis: Long?,
    val meToo: Int,
    /** §5.4 "after" photo; null when neighbours resolved it without one. */
    val resolvedPhotoPath: String?,
)

/** A suggestion I made or voted for. */
data class MySuggestionUi(
    val id: String,
    val position: LatLon,
    val reason: SuggestReason?,
    val votes: Int,
    val state: SuggestionState,
    val municipalityId: Int?,
    val authored: Boolean,
)

/** One section's list with its own designed loading / error / empty states (§8). */
data class Section<T>(
    val loading: Boolean = true,
    val items: List<T> = emptyList(),
    val error: KantaError? = null,
)

data class MyProfileUiState(
    val auth: AuthState = AuthState.Unknown,
    val displayName: String? = null,
    val municipalityId: Int? = null,
    val memberSinceMillis: Long? = null,
    val impact: ImpactUi? = null,
    val impactLoading: Boolean = true,
    val impactError: KantaError? = null,
    val reports: Section<MyReportUi> = Section(),
    val suggestions: Section<MySuggestionUi> = Section(),
    /** The resolved report whose before/after is open. */
    val beforeAfterId: String? = null,
)

/**
 * "My reports & profile" (§4.5 screen 11, §5.4): who I am, what my reports
 * achieved, and every report, suggestion and vote with its current state.
 */
@HiltViewModel
class MyProfileViewModel @Inject constructor(
    private val repository: KantaRepository,
    private val gate: AuthGate,
    private val mapRequests: MapRequests,
    auth: AuthRepository,
    currentProfile: CurrentProfile,
    voting: SuggestionVoting,
) : ViewModel() {

    private val content = MutableStateFlow(MyProfileUiState())

    val state: StateFlow<MyProfileUiState> =
        combine(content, auth.authState, currentProfile.profile) { s, authState, profile ->
            s.copy(
                auth = authState,
                displayName = profile?.displayName?.takeIf { it.isNotBlank() },
                municipalityId = profile?.municipalityId,
                memberSinceMillis = isoToMillis(profile?.createdAt),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MyProfileUiState())

    init {
        // Load once signed in — also right after signing in from this screen.
        auth.authState
            .map { it is AuthState.SignedIn }
            .distinctUntilChanged()
            .onEach { signedIn -> if (signedIn) load() }
            .launchIn(viewModelScope)
        // A vote or suggestion made elsewhere changes the lower list.
        voting.changes.drop(1).onEach { loadSuggestions() }.launchIn(viewModelScope)
    }

    fun signIn() = gate.requestLoginOnly()

    fun retry() = load()

    fun toggleBeforeAfter(reportId: String) = content.update {
        it.copy(beforeAfterId = if (it.beforeAfterId == reportId) null else reportId)
    }

    /** §4.5: "tap → container detail" — on the map, with the camera on it. */
    fun showOnMap(report: MyReportUi) = mapRequests.showContainer(report.containerId, report.position)

    fun showOnMap(suggestion: MySuggestionUi) = mapRequests.showSuggestion(suggestion.id, suggestion.position)

    private fun load() {
        loadImpact()
        loadReports()
        loadSuggestions()
    }

    private fun loadImpact() {
        viewModelScope.launch {
            repository.myImpact().collect { result ->
                content.update { s ->
                    when (result) {
                        is KantaResult.Loading -> s.copy(impactLoading = true, impactError = null)
                        is KantaResult.Success -> s.copy(
                            impactLoading = false,
                            impact = ImpactUi(
                                emptied = result.data.emptied.toInt(),
                                repaired = result.data.repaired.toInt(),
                                placed = result.data.containersPlaced.toInt(),
                            ),
                        )
                        is KantaResult.Failure -> s.copy(impactLoading = false, impactError = result.error)
                    }
                }
            }
        }
    }

    private fun loadReports() {
        viewModelScope.launch {
            repository.myReports().collect { result ->
                content.update { s ->
                    s.copy(
                        reports = when (result) {
                            is KantaResult.Loading -> s.reports.copy(loading = true, error = null)
                            is KantaResult.Success -> Section(loading = false, items = result.data.map { it.toUi() })
                            is KantaResult.Failure -> s.reports.copy(loading = false, error = result.error)
                        },
                    )
                }
            }
        }
    }

    private fun loadSuggestions() {
        viewModelScope.launch {
            repository.mySuggestions().collect { result ->
                content.update { s ->
                    s.copy(
                        suggestions = when (result) {
                            is KantaResult.Loading -> s.suggestions.copy(loading = true, error = null)
                            is KantaResult.Success -> Section(
                                loading = false,
                                items = result.data.map { it.toUi() }.filter { it.state != SuggestionState.Rejected },
                            )
                            is KantaResult.Failure -> s.suggestions.copy(loading = false, error = result.error)
                        },
                    )
                }
            }
        }
    }
}

internal fun MyReportDto.toUi() = MyReportUi(
    id = reportId,
    containerId = containerId,
    code = containerCode,
    containerKind = if (containerKind == "small") ContainerKind.SMALL else ContainerKind.BIG,
    position = if (containerLat != null && containerLon != null) LatLon(containerLat, containerLon) else null,
    municipalityId = municipalityId,
    kind = kind,
    state = when (state) {
        "resolved" -> MyReportState.Resolved
        "expired" -> MyReportState.Expired
        else -> MyReportState.Open
    },
    photoPath = photoPath,
    createdAtMillis = isoToMillis(createdAt),
    resolvedAtMillis = isoToMillis(resolvedAt),
    meToo = meTooCount.toInt(),
    resolvedPhotoPath = resolvedPhotoPath?.takeIf { it.isNotBlank() },
)

internal fun MySuggestionDto.toUi() = MySuggestionUi(
    id = id,
    position = LatLon(lat, lon),
    reason = SuggestReason.from(reason),
    votes = votes,
    state = SuggestionState.from(state),
    municipalityId = municipalityId,
    authored = iAuthored,
)
