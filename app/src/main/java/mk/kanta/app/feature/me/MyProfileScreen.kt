package mk.kanta.app.feature.me

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import mk.kanta.app.R
import mk.kanta.app.core.auth.AuthState
import mk.kanta.app.core.auth.Municipalities
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaErrorState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRow
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSectionHeader
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import mk.kanta.app.core.designsystem.component.KantaStatusDot
import mk.kanta.app.core.designsystem.tabularFigures
import mk.kanta.app.core.network.publicPhotoUrl
import mk.kanta.app.core.util.compactDuration
import mk.kanta.app.core.util.hoursBetween
import mk.kanta.app.feature.suggest.StateBadge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "My reports & profile" (§4.5 screen 11). The impact counter is the heart of it
 * (§5.4): what the user's reports actually got done, in big calm numbers.
 */
@Composable
fun MyProfileScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onFirstReport: () -> Unit,
    /** The map was asked to show a container or suggestion; go back to it. */
    onShowOnMap: () -> Unit,
    viewModel: MyProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.padding(Spacing.s)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings, modifier = Modifier.padding(Spacing.s)) {
                    Icon(KantaIcons.Settings, contentDescription = stringResource(R.string.me_settings))
                }
            }

            when (state.auth) {
                AuthState.Unknown -> Column(Modifier.padding(top = Spacing.xl)) {
                    KantaListRowSkeleton()
                    KantaListRowSkeleton()
                }

                AuthState.SignedOut -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KantaEmptyState(
                        title = stringResource(R.string.profile_signed_out_title),
                        message = stringResource(R.string.me_signed_out_message),
                        icon = KantaIcons.Profile,
                        action = {
                            KantaPrimaryButton(
                                text = stringResource(R.string.profile_sign_in),
                                onClick = viewModel::signIn,
                            )
                        },
                    )
                }

                is AuthState.SignedIn -> SignedInContent(
                    state = state,
                    onRetry = viewModel::retry,
                    onReport = { viewModel.showOnMap(it); onShowOnMap() },
                    onSuggestion = { viewModel.showOnMap(it); onShowOnMap() },
                    onToggleBeforeAfter = viewModel::toggleBeforeAfter,
                    onFirstReport = onFirstReport,
                    onSettings = onSettings,
                )
            }
        }
    }
}

@Composable
private fun SignedInContent(
    state: MyProfileUiState,
    onRetry: () -> Unit,
    onReport: (MyReportUi) -> Unit,
    onSuggestion: (MySuggestionUi) -> Unit,
    onToggleBeforeAfter: (String) -> Unit,
    onFirstReport: () -> Unit,
    onSettings: () -> Unit,
) {
    // §4.5: "open 31 h" keeps counting while the screen is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item { Header(state) }
        item { ImpactCard(state, onRetry) }

        item { KantaSectionHeader(stringResource(R.string.me_reports_title)) }
        reportsSection(state, now, onRetry, onReport, onToggleBeforeAfter, onFirstReport)

        // Only when there is something: an empty "suggestions" block under an
        // empty "reports" block would say the same thing twice.
        if (state.suggestions.items.isNotEmpty() || state.suggestions.error != null) {
            item { KantaSectionHeader(stringResource(R.string.me_suggestions_title)) }
            if (state.suggestions.error != null && state.suggestions.items.isEmpty()) {
                item {
                    KantaErrorState(
                        title = stringResource(state.suggestions.error.messageRes),
                        message = stringResource(R.string.container_detail_error_hint),
                        onRetry = onRetry,
                    )
                }
            }
            items(state.suggestions.items, key = { "s-" + it.id }) { SuggestionRow(it, onSuggestion) }
        }

        item {
            Spacer(Modifier.height(Spacing.xl))
            HorizontalDivider(thickness = Spacing.hairline, color = KantaTheme.colors.outline)
            // §4.5 screen 11: "Link to Settings".
            KantaListRow(
                title = stringResource(R.string.me_settings),
                subtitle = stringResource(R.string.me_settings_subtitle),
                leading = {
                    Icon(KantaIcons.Settings, null, tint = KantaTheme.colors.onSurfaceMuted, modifier = Modifier.size(22.dp))
                },
                trailing = { Icon(KantaIcons.ChevronRight, null, tint = KantaTheme.colors.outline) },
                onClick = onSettings,
                showDivider = false,
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

// -------------------------------------------------------------------------------------------
// Header: name, municipality, member since
// -------------------------------------------------------------------------------------------

@Composable
private fun Header(state: MyProfileUiState) {
    val name = state.displayName ?: stringResource(R.string.me_name_fallback)
    Row(
        modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(KantaTheme.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = KantaTheme.colors.brand,
            )
        }
        Spacer(Modifier.width(Spacing.l))
        Column {
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val details = listOfNotNull(
                Municipalities.byId(state.municipalityId)?.localizedName(),
                state.memberSinceMillis?.let { stringResource(R.string.me_member_since, monthYear(it)) },
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
            }
        }
    }
}

private fun monthYear(millis: Long): String =
    DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

// -------------------------------------------------------------------------------------------
// §5.4 impact counter — the emotional centre of the screen
// -------------------------------------------------------------------------------------------

@Composable
private fun ImpactCard(state: MyProfileUiState, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l),
        shape = KantaShape.card,
        color = KantaTheme.colors.surfaceMuted,
    ) {
        Column(Modifier.padding(Spacing.xl)) {
            val impact = state.impact
            when {
                state.impactLoading && impact == null -> {
                    KantaSkeleton(width = 180.dp, height = 14.dp)
                    Spacer(Modifier.height(Spacing.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        repeat(3) { KantaSkeleton(width = 56.dp, height = 40.dp) }
                    }
                }

                state.impactError != null && impact == null -> KantaErrorState(
                    title = stringResource(state.impactError.messageRes),
                    message = stringResource(R.string.container_detail_error_hint),
                    onRetry = onRetry,
                )

                impact != null -> {
                    val emptied = pluralStringResource(R.plurals.me_impact_emptied, impact.emptied, impact.emptied)
                    val repaired = pluralStringResource(R.plurals.me_impact_repaired, impact.repaired, impact.repaired)
                    val placed = pluralStringResource(R.plurals.me_impact_placed, impact.placed, impact.placed)
                    Text(
                        text = stringResource(R.string.me_impact_lead),
                        style = MaterialTheme.typography.bodyLarge,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                    Spacer(Modifier.height(Spacing.l))
                    // TalkBack hears the whole §5.4 sentence, not three loose numbers.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = listOf(emptied, repaired, placed).joinToString(" · ")
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ImpactNumber(impact.emptied, R.plurals.me_impact_emptied_label, Modifier.weight(1f))
                        ImpactNumber(impact.repaired, R.plurals.me_impact_repaired_label, Modifier.weight(1f))
                        ImpactNumber(impact.placed, R.plurals.me_impact_placed_label, Modifier.weight(1f))
                    }
                    if (impact.isEmpty) {
                        Spacer(Modifier.height(Spacing.l))
                        Text(
                            text = stringResource(R.string.me_impact_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = KantaTheme.colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One big number that counts up from zero once (§5.4 brief: "animate numbers
 * counting up once") — not again on every return to the screen.
 */
@Composable
private fun ImpactNumber(value: Int, label: Int, modifier: Modifier = Modifier) {
    // What was last shown survives a trip to the map and back, so the count-up
    // plays once — and again only if the number itself changed.
    var shown by rememberSaveable { mutableIntStateOf(0) }
    val animated = remember { Animatable(shown.toFloat()) }
    LaunchedEffect(value) {
        animated.animateTo(value.toFloat(), tween(COUNT_UP_MS, easing = Motion.FastOutSlowIn))
        shown = value
    }
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = animated.value.toInt().toString(),
            style = MaterialTheme.typography.displaySmall.tabularFigures(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // §3.1: accent is for "tiny highlights only (badges, impact counter)" —
        // a short underline under each number, never the text itself.
        Box(
            Modifier
                .padding(top = Spacing.xs)
                .size(width = 20.dp, height = 3.dp)
                .clip(KantaShape.pill)
                .background(KantaTheme.colors.accent),
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = pluralStringResource(label, value),
            style = MaterialTheme.typography.bodySmall,
            color = KantaTheme.colors.onSurfaceMuted,
        )
    }
}

private const val COUNT_UP_MS = 900

// -------------------------------------------------------------------------------------------
// My reports
// -------------------------------------------------------------------------------------------

private fun LazyListScope.reportsSection(
    state: MyProfileUiState,
    now: Long,
    onRetry: () -> Unit,
    onReport: (MyReportUi) -> Unit,
    onToggleBeforeAfter: (String) -> Unit,
    onFirstReport: () -> Unit,
) {
    val reports = state.reports
    when {
        reports.loading && reports.items.isEmpty() -> items(3) { KantaListRowSkeleton() }

        reports.error != null && reports.items.isEmpty() -> item {
            KantaErrorState(
                title = stringResource(reports.error.messageRes),
                message = stringResource(R.string.container_detail_error_hint),
                onRetry = onRetry,
            )
        }

        reports.items.isEmpty() -> item {
            KantaEmptyState(
                title = stringResource(R.string.me_empty_title),
                message = stringResource(R.string.me_empty_message),
                icon = KantaIcons.Camera,
                action = {
                    KantaPrimaryButton(
                        text = stringResource(R.string.me_first_report),
                        onClick = onFirstReport,
                        icon = KantaIcons.Camera,
                    )
                },
            )
        }

        else -> items(reports.items, key = { "r-" + it.id }) { report ->
            ReportRow(
                report = report,
                now = now,
                expanded = state.beforeAfterId == report.id,
                onClick = { onReport(report) },
                onToggleBeforeAfter = { onToggleBeforeAfter(report.id) },
            )
        }
    }
}

@Composable
private fun ReportRow(
    report: MyReportUi,
    now: Long,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggleBeforeAfter: () -> Unit,
) {
    val municipality = Municipalities.byId(report.municipalityId)?.localizedName()
    val stateLine = reportStateLine(report, now)
    val stateColor = when (report.state) {
        MyReportState.Open -> if (report.kind == "full") MarkerColors.Full else MarkerColors.Broken
        MyReportState.Resolved -> KantaTheme.colors.brand
        MyReportState.Expired -> KantaTheme.colors.onSurfaceMuted
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = Spacing.minTouchTarget)
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = publicPhotoUrl(report.photoPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(KantaShape.chip)
                    .background(KantaTheme.colors.surfaceMuted),
            )
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KantaStatusDot(ContainerStatus.OK, report.containerKind, Modifier.size(14.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = listOfNotNull(report.code, municipality).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall.tabularFigures(),
                    )
                }
                Text(
                    text = listOfNotNull(
                        reportKindLabel(report.kind),
                        report.meToo.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.me_report_me_too, it, it)
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KantaTheme.colors.onSurfaceMuted,
                )
                Text(
                    text = stateLine,
                    style = MaterialTheme.typography.bodySmall.tabularFigures(),
                    color = stateColor,
                )
            }
            Icon(KantaIcons.ChevronRight, null, tint = KantaTheme.colors.outline)
        }

        // §5.4 before/after, for what actually got fixed.
        if (report.state == MyReportState.Resolved) {
            TextButton(
                onClick = onToggleBeforeAfter,
                modifier = Modifier.padding(start = Spacing.screenHorizontal + 56.dp),
            ) {
                Text(
                    text = stringResource(if (expanded) R.string.me_hide_before_after else R.string.me_before_after),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.s)) {
                    val after = report.resolvedPhotoPath
                    if (after != null) {
                        BeforeAfter(beforePath = report.photoPath, afterPath = after)
                    } else {
                        BeforeOnly(beforePath = report.photoPath)
                        Spacer(Modifier.height(Spacing.s))
                        Text(
                            text = stringResource(R.string.me_no_after_photo),
                            style = MaterialTheme.typography.bodySmall,
                            color = KantaTheme.colors.onSurfaceMuted,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun reportStateLine(report: MyReportUi, now: Long): String {
    val created = report.createdAtMillis
    return when (report.state) {
        MyReportState.Open ->
            if (created == null) {
                stringResource(R.string.me_report_open_plain)
            } else {
                stringResource(R.string.me_report_open, compactDuration(hoursBetween(created, now)))
            }
        MyReportState.Resolved -> {
            val resolved = report.resolvedAtMillis
            val took = if (created != null && resolved != null) compactDuration(hoursBetween(created, resolved)) else null
            when {
                took == null -> stringResource(R.string.me_report_resolved_plain)
                report.kind == "full" -> stringResource(R.string.me_report_emptied_after, took)
                else -> stringResource(R.string.me_report_fixed_after, took)
            }
        }
        MyReportState.Expired -> stringResource(R.string.me_report_expired)
    }
}

@Composable
private fun reportKindLabel(kind: String): String = stringResource(
    when (kind) {
        "full" -> R.string.status_full
        "damaged" -> R.string.report_kind_damaged
        "destroyed" -> R.string.report_kind_destroyed
        "burning" -> R.string.report_kind_burning
        "missing" -> R.string.report_kind_missing
        else -> R.string.report_kind_dumped_around
    },
)

// -------------------------------------------------------------------------------------------
// My suggestions & votes
// -------------------------------------------------------------------------------------------

@Composable
private fun SuggestionRow(item: MySuggestionUi, onClick: (MySuggestionUi) -> Unit) {
    KantaListRow(
        title = item.reason?.let { stringResource(it.label) } ?: "",
        subtitle = listOfNotNull(
            stringResource(if (item.authored) R.string.me_suggestion_mine else R.string.me_suggestion_voted),
            pluralStringResource(R.plurals.suggest_votes, item.votes, item.votes),
            Municipalities.byId(item.municipalityId)?.localizedName(),
        ).joinToString(" · "),
        leading = { Icon(KantaIcons.Suggest, null, tint = KantaTheme.colors.brand, modifier = Modifier.size(22.dp)) },
        trailing = { StateBadge(item.state) },
        onClick = { onClick(item) },
        showDivider = false,
    )
}
