package mk.kanta.app.core.data.suggest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.core.auth.AuthGate
import mk.kanta.app.core.auth.AuthRepository
import mk.kanta.app.core.auth.PendingAction
import mk.kanta.app.core.data.di.ApplicationScope
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.location.LatLon
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the phone knows about a vote before (or instead of) a fresh list from the
 * server: laid over any suggestion shown anywhere — list card, map sheet — so a
 * tap shows at once and every screen agrees.
 */
data class VoteOverlay(
    val voted: Boolean,
    /** Null when the count is not known here; the screen keeps its own. */
    val votes: Int?,
    /** True until the server has answered. */
    val pending: Boolean,
)

/** What the Suggest flow needs from voting — an interface so it is faked in tests. */
interface SuggestionVotes {
    fun vote(suggestionId: String, currentVotes: Int)
    fun notifyChanged()
    fun notifySuggested(at: LatLon)
}

/**
 * §5.3 voting, in one place for the whole app.
 *
 * - **Optimistic:** the count goes up and the button turns to "Voted" the moment
 *   it is tapped; the server's answer then confirms or corrects it.
 * - **One vote per user** is the server's rule (`vote_suggestion`, KA008). If it
 *   says "already voted", the vote was already counted — so the optimistic +1 is
 *   taken back and the button stays "Voted".
 * - **Signed out:** the vote goes through the auth gate (§4.2) and runs here
 *   after sign-in, wherever the user is by then.
 */
@Singleton
class SuggestionVoting @Inject constructor(
    private val repository: KantaRepository,
    private val auth: AuthRepository,
    private val gate: AuthGate,
    @ApplicationScope private val scope: CoroutineScope,
) : SuggestionVotes {
    private val _overlays = MutableStateFlow<Map<String, VoteOverlay>>(emptyMap())
    val overlays: StateFlow<Map<String, VoteOverlay>> = _overlays.asStateFlow()

    /** A vote the server refused for a reason worth telling the user. */
    private val _errors = MutableSharedFlow<KantaError>(extraBufferCapacity = 4)
    val errors: SharedFlow<KantaError> = _errors.asSharedFlow()

    /** Suggestions changed (sent, merged): lists and the map should re-read. */
    private val _changes = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** A suggestion was just sent here: the map shows suggestions and goes there. */
    private val _suggestedAt = MutableSharedFlow<LatLon>(extraBufferCapacity = 1)
    val suggestedAt: SharedFlow<LatLon> = _suggestedAt.asSharedFlow()

    /** Last known counts, so a vote that waited for sign-in can still be optimistic. */
    private val knownVotes = mutableMapOf<String, Int>()

    init {
        gate.ready
            .filterIsInstance<PendingAction.Vote>()
            .onEach { action ->
                gate.consume(action)
                send(action.suggestionId, knownVotes[action.suggestionId])
            }
            .launchIn(scope)
    }

    override fun vote(suggestionId: String, currentVotes: Int) {
        knownVotes[suggestionId] = currentVotes
        if (!auth.isSignedIn) {
            gate.request(PendingAction.Vote(suggestionId))
            return
        }
        send(suggestionId, currentVotes)
    }

    /**
     * A fresh read from the server now carries these votes itself; drop the
     * answered overlays so a later vote by someone else is not hidden behind ours.
     */
    fun forgetConfirmed(suggestionIds: Collection<String>) {
        val ids = suggestionIds.toSet()
        _overlays.update { overlays -> overlays.filterNot { (id, o) -> id in ids && !o.pending } }
    }

    override fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    override fun notifySuggested(at: LatLon) {
        _changes.tryEmit(Unit)
        _suggestedAt.tryEmit(at)
    }

    private fun send(suggestionId: String, currentVotes: Int?) {
        if (_overlays.value[suggestionId]?.pending == true) return
        _overlays.update {
            it + (suggestionId to VoteOverlay(voted = true, votes = currentVotes?.plus(1), pending = true))
        }
        scope.launch {
            val result = repository.voteSuggestion(suggestionId).first { it !is KantaResult.Loading }
            _overlays.update { overlays ->
                when (result) {
                    is KantaResult.Success ->
                        overlays + (suggestionId to VoteOverlay(true, result.data.votes, pending = false))
                    is KantaResult.Failure -> when (result.error) {
                        // Already counted: keep "Voted", drop our +1.
                        KantaError.AlreadyDone ->
                            overlays + (suggestionId to VoteOverlay(true, currentVotes, pending = false))
                        else -> overlays - suggestionId
                    }
                    KantaResult.Loading -> overlays
                }
            }
            if (result is KantaResult.Failure && result.error != KantaError.AlreadyDone) {
                _errors.tryEmit(result.error)
            }
        }
    }
}
