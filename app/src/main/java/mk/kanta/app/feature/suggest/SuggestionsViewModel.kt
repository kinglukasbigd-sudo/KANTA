package mk.kanta.app.feature.suggest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.SuggestionDto
import mk.kanta.app.core.data.suggest.SuggestionVoting
import mk.kanta.app.core.data.suggest.VoteOverlay
import mk.kanta.app.core.location.LatLon
import javax.inject.Inject

/** §5.3 state as the app shows it. `rejected` is not listed. */
enum class SuggestionState { Open, Sent, Placed, Rejected;

    companion object {
        fun from(wire: String) = when (wire) {
            "sent" -> Sent
            "placed" -> Placed
            "rejected" -> Rejected
            else -> Open
        }
    }
}

/** One suggestion, with any vote the user just made laid over it. */
data class SuggestionUi(
    val id: String,
    val position: LatLon,
    val reason: SuggestReason?,
    val note: String?,
    val photoPath: String?,
    val votes: Int,
    val state: SuggestionState,
    val municipalityId: Int?,
    val createdAt: String,
    val iVoted: Boolean,
    /** The vote is on its way to the server. */
    val voting: Boolean = false,
) {
    /** §5.3: votes go to open suggestions only; one per user. */
    val canVote: Boolean get() = state == SuggestionState.Open && !iVoted
}

internal fun SuggestionDto.toUi() = SuggestionUi(
    id = id,
    position = LatLon(lat, lon),
    reason = SuggestReason.from(reason),
    note = note?.takeIf { it.isNotBlank() },
    photoPath = photoPath?.takeIf { it.isNotBlank() },
    votes = votes,
    state = SuggestionState.from(state),
    municipalityId = municipalityId,
    createdAt = createdAt,
    iVoted = iVoted,
)

/** The optimistic vote wins until a fresh read from the server replaces it. */
internal fun SuggestionUi.with(overlay: VoteOverlay?): SuggestionUi = if (overlay == null) {
    this
} else {
    copy(
        iVoted = iVoted || overlay.voted,
        votes = overlay.votes ?: votes,
        voting = overlay.pending,
    )
}

data class SuggestionsUiState(
    val municipalityId: Int? = null,
    val loading: Boolean = true,
    val items: List<SuggestionUi> = emptyList(),
    val error: KantaError? = null,
    /** A refused vote, said once. */
    val message: KantaError? = null,
)

/**
 * The Suggestions screen (§4.5 screen 9, §5.3): sorted by votes, filterable by
 * municipality. Votes are optimistic and shared with the map through
 * [SuggestionVoting].
 */
@HiltViewModel
class SuggestionsViewModel @Inject constructor(
    private val repository: KantaRepository,
    private val voting: SuggestionVoting,
) : ViewModel() {

    private val base = MutableStateFlow(SuggestionsUiState())

    val state: StateFlow<SuggestionsUiState> = combine(base, voting.overlays) { s, overlays ->
        s.copy(items = s.items.map { it.with(overlays[it.id]) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SuggestionsUiState())

    private var loadJob: Job? = null

    init {
        load()
        voting.errors.onEach { error -> base.update { it.copy(message = error) } }.launchIn(viewModelScope)
        // Something was suggested or merged elsewhere: re-read. The replayed last
        // change is skipped — the first load already covers it.
        voting.changes.drop(1).onEach { load() }.launchIn(viewModelScope)
    }

    fun filter(municipalityId: Int?) {
        if (municipalityId == base.value.municipalityId) return
        base.update { it.copy(municipalityId = municipalityId) }
        load()
    }

    fun retry() = load()

    fun vote(item: SuggestionUi) {
        if (!item.canVote || item.voting) return
        voting.vote(item.id, item.votes)
    }

    fun dismissMessage() = base.update { it.copy(message = null) }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.suggestions(municipalityId = base.value.municipalityId, limit = LIMIT).collect { result ->
                if (result is KantaResult.Success) voting.forgetConfirmed(result.data.map { it.id })
                base.update { s ->
                    when (result) {
                        is KantaResult.Loading -> s.copy(loading = true, error = null)
                        is KantaResult.Success -> s.copy(
                            loading = false,
                            // §5.3 states the list shows: open, sent to the city, placed.
                            items = result.data.map { it.toUi() }.filter { it.state != SuggestionState.Rejected },
                        )
                        is KantaResult.Failure -> s.copy(loading = false, error = result.error)
                    }
                }
            }
        }
    }

    private companion object {
        const val LIMIT = 100
    }
}
