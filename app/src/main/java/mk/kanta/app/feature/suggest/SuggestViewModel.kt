package mk.kanta.app.feature.suggest

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.R
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.data.suggest.SuggestGateway
import mk.kanta.app.core.data.suggest.SuggestionPin
import mk.kanta.app.core.data.suggest.SuggestionVotes
import mk.kanta.app.core.image.PhotoProcessor
import mk.kanta.app.core.image.ProcessedPhoto
import mk.kanta.app.core.location.LatLon
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** §4.4 reason chips, with the values `suggestions.reason` accepts. */
enum class SuggestReason(val wire: String, @StringRes val label: Int) {
    NoContainer("no_container_nearby", R.string.suggest_reason_no_container),
    AlwaysFull("always_full", R.string.suggest_reason_always_full),
    NewBuilding("new_building", R.string.suggest_reason_new_building),
    Dumping("dumping_spot", R.string.suggest_reason_dumping),
    ;

    companion object {
        fun from(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

enum class SuggestStage {
    /** Map in pick mode with a centre crosshair (§4.4). */
    Pick,

    /** An open suggestion is already within 50 m: vote for it instead? */
    Nearby,

    /** Reason chips, optional photo, note. */
    Details,

    /** The optional photo: camera, then privacy processing. */
    Camera,
    Processing,
    Sending,
    Done,
}

/** The suggestion a new one here would merge into (§4.4, §5.3). */
data class NearbySuggestion(
    val id: String,
    val reason: SuggestReason?,
    val note: String?,
    val votes: Int,
    val distanceMetres: Int,
    val iVoted: Boolean,
)

sealed interface SuggestOutcome {
    /** A new suggestion; [votes] is 1 — the author's own. */
    data class Sent(val votes: Int) : SuggestOutcome

    /** Someone suggested the same spot meanwhile: the server made it a vote. */
    data class Merged(val votes: Int) : SuggestOutcome

    /** "Vote for this one instead". */
    data class Voted(val votes: Int) : SuggestOutcome

    /** It is already in, with this user's vote. */
    data object AlreadyVoted : SuggestOutcome
}

data class SuggestUiState(
    val stage: SuggestStage = SuggestStage.Pick,
    /** Where the pick map opens: the user, or Skopje centre. */
    val start: LatLon? = null,
    val device: LatLon? = null,
    val pin: LatLon? = null,
    val containers: List<ContainerCandidate> = emptyList(),
    val suggestions: List<SuggestionPin> = emptyList(),
    val checking: Boolean = false,
    val nearby: NearbySuggestion? = null,
    val reason: SuggestReason? = null,
    val note: String = "",
    val photo: ProcessedPhoto? = null,
    val photoRejected: Boolean = false,
    val outcome: SuggestOutcome? = null,
    val error: KantaError? = null,
) {
    val canSend: Boolean get() = stage == SuggestStage.Details && pin != null && reason != null
}

/**
 * The Suggest flow (§4.4): pick a spot on the map → reason → optional photo and
 * note → send. The 50 m merge and one-vote-per-user are the server's rules
 * (`submit_suggestion`, `vote_suggestion`); this asks first so it can offer
 * "Vote for this one instead" rather than merge silently.
 */
@HiltViewModel
class SuggestViewModel @Inject constructor(
    private val gateway: SuggestGateway,
    private val processor: PhotoProcessor,
    private val voting: SuggestionVotes,
) : ViewModel() {

    private val _state = MutableStateFlow(SuggestUiState())
    val state: StateFlow<SuggestUiState> = _state.asStateFlow()

    /** The photo's storage name, so a retried send overwrites rather than duplicates. */
    private val draftId = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            val here = gateway.currentLocation()
            val start = here ?: LatLon.SKOPJE_CENTRE
            _state.update { it.copy(start = start, device = here, pin = start) }
            val containers = gateway.containersAround(start, CONTEXT_RADIUS_M)
            val suggestions = gateway.openSuggestionPins()
            _state.update { it.copy(containers = containers, suggestions = suggestions) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Pick
    // -----------------------------------------------------------------------------------------

    fun movePin(to: LatLon) = _state.update { it.copy(pin = to, error = null) }

    /** "Place here": first ask whether this spot is already asked for (§4.4). */
    fun placeHere() {
        val pin = _state.value.pin ?: return
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val nearby = when (val result = gateway.openSuggestionNear(pin)) {
                is KantaResult.Success -> result.data
                // Offline: carry on. The server merges within 50 m when it gets the
                // suggestion anyway; only the chance to ask first is lost.
                else -> null
            }
            _state.update {
                if (nearby != null) {
                    it.copy(
                        checking = false,
                        stage = SuggestStage.Nearby,
                        nearby = NearbySuggestion(
                            id = nearby.id,
                            reason = SuggestReason.from(nearby.reason),
                            note = nearby.note,
                            votes = nearby.votes,
                            distanceMetres = nearby.distanceM.roundToInt(),
                            iVoted = nearby.iVoted,
                        ),
                    )
                } else {
                    it.copy(checking = false, stage = SuggestStage.Details, nearby = null)
                }
            }
        }
    }

    /** §4.4: "Vote for this one instead" (one tap). */
    fun voteInstead() {
        val nearby = _state.value.nearby ?: return
        if (nearby.iVoted) {
            _state.update { it.copy(stage = SuggestStage.Done, outcome = SuggestOutcome.AlreadyVoted) }
            return
        }
        voting.vote(nearby.id, nearby.votes)
        voting.notifyChanged()
        _state.update {
            it.copy(stage = SuggestStage.Done, outcome = SuggestOutcome.Voted(nearby.votes + 1))
        }
    }

    /** Back to the map to choose a spot outside the 50 m. */
    fun pickAgain() = _state.update { it.copy(stage = SuggestStage.Pick, nearby = null, error = null) }

    // -----------------------------------------------------------------------------------------
    // Details
    // -----------------------------------------------------------------------------------------

    fun selectReason(reason: SuggestReason) = _state.update { it.copy(reason = reason, error = null) }

    fun onNoteChange(text: String) = _state.update { it.copy(note = text.take(MAX_NOTE)) }

    fun openCamera() = _state.update { it.copy(stage = SuggestStage.Camera, photoRejected = false) }

    fun closeCamera() = _state.update { it.copy(stage = SuggestStage.Details) }

    fun onPhotoCaptured(raw: File) {
        _state.update { it.copy(stage = SuggestStage.Processing) }
        viewModelScope.launch {
            runCatching { processor.process(raw) }
                .onSuccess { photo ->
                    _state.value.photo?.file?.delete()
                    _state.update { it.copy(stage = SuggestStage.Details, photo = photo) }
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    raw.delete()
                    // Same rule as reports (§8): a photo whose faces could not be
                    // checked is refused, never sent as it is.
                    _state.update { it.copy(stage = SuggestStage.Camera, photoRejected = true) }
                }
        }
    }

    fun removePhoto() {
        _state.value.photo?.file?.delete()
        _state.update { it.copy(photo = null) }
    }

    fun backToPick() = _state.update { it.copy(stage = SuggestStage.Pick, error = null) }

    // -----------------------------------------------------------------------------------------
    // Send
    // -----------------------------------------------------------------------------------------

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val pin = s.pin ?: return
        val reason = s.reason ?: return

        viewModelScope.launch {
            _state.update { it.copy(stage = SuggestStage.Sending, error = null) }

            val photoPath = s.photo?.let { photo ->
                when (val upload = gateway.uploadPhoto(draftId, photo.file)) {
                    is KantaResult.Success -> upload.data
                    is KantaResult.Failure -> return@launch fail(upload.error)
                    KantaResult.Loading -> null
                }
            }

            when (val result = gateway.submit(pin, reason.wire, s.note.trim().ifBlank { null }, photoPath)) {
                is KantaResult.Success -> {
                    voting.notifySuggested(pin)
                    _state.update {
                        it.copy(
                            stage = SuggestStage.Done,
                            outcome = if (result.data.merged) {
                                SuggestOutcome.Merged(result.data.votes)
                            } else {
                                SuggestOutcome.Sent(result.data.votes)
                            },
                        )
                    }
                }
                is KantaResult.Failure -> fail(result.error)
                KantaResult.Loading -> Unit
            }
        }
    }

    private fun fail(error: KantaError) =
        _state.update { it.copy(stage = SuggestStage.Details, error = error) }

    override fun onCleared() {
        _state.value.photo?.file?.delete()
    }

    companion object {
        const val MAX_NOTE = 280

        /** Containers drawn under the crosshair: enough to judge "is there one nearby?". */
        const val CONTEXT_RADIUS_M = 1_000.0
    }
}
