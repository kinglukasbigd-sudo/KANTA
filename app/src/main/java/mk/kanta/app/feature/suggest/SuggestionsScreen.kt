package mk.kanta.app.feature.suggest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mk.kanta.app.R
import mk.kanta.app.core.auth.Municipalities
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaCompactAction
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaErrorState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.tabularFigures
import mk.kanta.app.feature.map.rememberMapSnapshot

/**
 * Suggestions (§4.5 screen 9, §5.3): sorted by votes, filterable by municipality.
 * Each card shows the spot on a small map, the reason, the votes and the state.
 */
@Composable
fun SuggestionsScreen(
    onBack: () -> Unit,
    onSuggest: () -> Unit,
    viewModel: SuggestionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(3_500)
            viewModel.dismissMessage()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.padding(Spacing.s)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.menu_suggestions),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }

            MunicipalityFilter(selected = state.municipalityId, onSelect = viewModel::filter)

            state.message?.let { error ->
                Text(
                    text = stringResource(error.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MarkerColors.Broken,
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.s),
                )
            }

            when {
                state.loading && state.items.isEmpty() -> Column(Modifier.padding(Spacing.screenHorizontal)) {
                    repeat(2) {
                        KantaSkeleton(Modifier.fillMaxWidth(), height = 140.dp, shape = KantaShape.card)
                        Spacer(Modifier.height(Spacing.l))
                    }
                }

                state.error != null && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KantaErrorState(
                        title = stringResource(state.error!!.messageRes),
                        message = stringResource(R.string.container_detail_error_hint),
                        onRetry = viewModel::retry,
                    )
                }

                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KantaEmptyState(
                        title = stringResource(R.string.suggestions_empty_title),
                        message = stringResource(R.string.suggestions_empty_message),
                        icon = KantaIcons.Suggest,
                        action = {
                            KantaPrimaryButton(
                                text = stringResource(R.string.action_suggest),
                                onClick = onSuggest,
                                icon = KantaIcons.Suggest,
                            )
                        },
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.screenHorizontal,
                        end = Spacing.screenHorizontal,
                        top = Spacing.s,
                        bottom = Spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        SuggestionCard(item = item, onVote = { viewModel.vote(item) })
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

@Composable
private fun MunicipalityFilter(selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        KantaChip(
            label = stringResource(R.string.suggestions_filter_all),
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        Municipalities.all.forEach { municipality ->
            KantaChip(
                label = municipality.localizedName(),
                selected = selected == municipality.id,
                onClick = { onSelect(municipality.id) },
            )
        }
    }
}

@Composable
fun SuggestionCard(item: SuggestionUi, onVote: () -> Unit) {
    val snapshot by rememberMapSnapshot(item.position, width = SNAPSHOT_W.dp, height = SNAPSHOT_H.dp)
    val reason = item.reason?.let { stringResource(it.label) } ?: ""
    val votes = pluralStringResource(R.plurals.suggest_votes, item.votes, item.votes)
    val municipality = Municipalities.byId(item.municipalityId)?.localizedName()

    Surface(
        shape = KantaShape.card,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Spacing.hairline, KantaTheme.colors.outline),
    ) {
        Column {
            // §5.3 card: the spot, on a small still map.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SNAPSHOT_H.dp)
                    .clip(KantaShape.card)
                    .background(KantaTheme.colors.surfaceMuted),
            ) {
                snapshot?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                StateBadge(item.state, Modifier.align(Alignment.TopEnd).padding(Spacing.s))
            }

            Column(Modifier.padding(Spacing.l)) {
                Text(reason, style = MaterialTheme.typography.titleMedium)
                if (municipality != null) {
                    Text(
                        text = municipality,
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                item.note?.let { note ->
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = "“$note”",
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                }
                Spacer(Modifier.height(Spacing.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = votes,
                        style = MaterialTheme.typography.titleSmall.tabularFigures(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "$reason, $votes" },
                    )
                    VoteButton(item, onVote)
                }
            }
        }
    }
}

/** One vote per user (§5.3); votes go to open suggestions only. */
@Composable
fun VoteButton(item: SuggestionUi, onVote: () -> Unit) {
    when {
        item.state != SuggestionState.Open -> Unit
        item.iVoted -> KantaCompactAction(
            text = stringResource(R.string.suggestions_voted),
            onClick = {},
            enabled = false,
        )
        else -> KantaCompactAction(
            text = stringResource(R.string.suggestions_vote),
            onClick = onVote,
            emphasis = true,
            enabled = !item.voting,
        )
    }
}

/** open → sent to city → placed (§5.3), as a quiet label on the snapshot. */
@Composable
fun StateBadge(state: SuggestionState, modifier: Modifier = Modifier) {
    val (label, tint) = when (state) {
        SuggestionState.Open -> R.string.suggestion_state_open to KantaTheme.colors.onSurfaceMuted
        SuggestionState.Sent -> R.string.suggestion_state_sent to MarkerColors.RecyclingPaper
        SuggestionState.Placed -> R.string.suggestion_state_placed to KantaTheme.colors.brand
        SuggestionState.Rejected -> R.string.suggestion_state_rejected to KantaTheme.colors.onSurfaceMuted
    }
    Surface(modifier = modifier, shape = KantaShape.pill, color = MaterialTheme.colorScheme.surface) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
        )
    }
}

private const val SNAPSHOT_W = 360
private const val SNAPSHOT_H = 140
