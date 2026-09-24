package mk.kanta.app.feature.suggest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.R
import mk.kanta.app.core.auth.Municipalities
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.suggest.SuggestionVoting
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.tabularFigures
import mk.kanta.app.core.network.publicPhotoUrl
import mk.kanta.app.feature.map.openInMaps
import javax.inject.Inject

data class SuggestionDetailState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val suggestion: SuggestionUi? = null,
    val error: KantaError? = null,
)

/**
 * A suggestion tapped on the map (§4.1 / §5.3): the small sheet with its reason,
 * votes and state, and a vote button — the same optimistic, one-per-user vote as
 * the Suggestions screen, through [SuggestionVoting].
 */
@HiltViewModel
class SuggestionDetailViewModel @Inject constructor(
    private val repository: KantaRepository,
    private val voting: SuggestionVoting,
) : ViewModel() {

    private val base = MutableStateFlow(SuggestionDetailState())

    val state: StateFlow<SuggestionDetailState> = combine(base, voting.overlays) { s, overlays ->
        s.copy(suggestion = s.suggestion?.let { it.with(overlays[it.id]) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SuggestionDetailState())

    private var loadJob: Job? = null

    fun open(suggestionId: String) {
        base.value = SuggestionDetailState(open = true, loading = true)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.suggestionDetail(suggestionId).collect { result ->
                when (result) {
                    is KantaResult.Loading -> Unit
                    is KantaResult.Success -> {
                        result.data?.let { voting.forgetConfirmed(listOf(it.id)) }
                        base.update {
                            it.copy(
                                loading = false,
                                suggestion = result.data?.toUi(),
                                error = if (result.data == null) KantaError.SuggestionNotFound else null,
                            )
                        }
                    }
                    is KantaResult.Failure -> base.update { it.copy(loading = false, error = result.error) }
                }
            }
        }
    }

    fun vote() {
        val suggestion = state.value.suggestion ?: return
        if (!suggestion.canVote || suggestion.voting) return
        voting.vote(suggestion.id, suggestion.votes)
    }

    fun close() {
        loadJob?.cancel()
        base.value = SuggestionDetailState()
    }
}

@Composable
fun ColumnScope.SuggestionDetailContent(state: SuggestionDetailState, onVote: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        val suggestion = state.suggestion
        when {
            state.loading -> {
                KantaListRowSkeleton()
                KantaListRowSkeleton()
            }

            suggestion == null -> KantaEmptyState(
                title = stringResource((state.error ?: KantaError.SuggestionNotFound).messageRes),
                message = stringResource(R.string.container_detail_error_hint),
                icon = KantaIcons.Suggest,
            )

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = suggestion.reason?.let { stringResource(it.label) } ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Spacer(Modifier.width(Spacing.m))
                    StateBadge(suggestion.state)
                }
                Municipalities.byId(suggestion.municipalityId)?.let {
                    Text(
                        text = it.localizedName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                suggestion.note?.let { note ->
                    Spacer(Modifier.height(Spacing.m))
                    Text(
                        text = "“$note”",
                        style = MaterialTheme.typography.bodyLarge,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                suggestion.photoPath?.let { path ->
                    Spacer(Modifier.height(Spacing.l))
                    AsyncImage(
                        model = publicPhotoUrl(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(KantaShape.card),
                    )
                }

                Spacer(Modifier.height(Spacing.xl))
                Text(
                    text = pluralStringResource(R.plurals.suggest_votes, suggestion.votes, suggestion.votes),
                    style = MaterialTheme.typography.headlineSmall.tabularFigures(),
                )
                Spacer(Modifier.height(Spacing.l))

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    // §5.3: one vote per user, open suggestions only.
                    if (suggestion.state == SuggestionState.Open) {
                        if (suggestion.iVoted) {
                            KantaSecondaryButton(
                                text = stringResource(R.string.suggestions_voted),
                                onClick = {},
                                icon = KantaIcons.Success,
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            KantaPrimaryButton(
                                text = stringResource(R.string.suggestions_vote),
                                onClick = onVote,
                                icon = KantaIcons.Success,
                                enabled = !suggestion.voting,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    KantaSecondaryButton(
                        text = stringResource(R.string.container_action_navigate),
                        onClick = {
                            context.openInMaps(suggestion.position.lat, suggestion.position.lon, "")
                        },
                        icon = KantaIcons.Navigate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}
