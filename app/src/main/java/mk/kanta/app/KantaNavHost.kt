package mk.kanta.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import mk.kanta.app.core.auth.PendingAction
import mk.kanta.app.feature.PlaceholderScreen
import mk.kanta.app.feature.auth.AuthViewModel
import mk.kanta.app.feature.auth.LoginSheetHost
import mk.kanta.app.feature.map.MapScreen
import mk.kanta.app.feature.profile.ProfileScreen
import mk.kanta.app.feature.report.ReportScreen

/** Type-safe navigation routes (spec §4.5). */
@Serializable object MapRoute

@Serializable object ProfileRoute

@Serializable object MyReportsRoute

@Serializable object CityStatsRoute

@Serializable object SuggestionsRoute

@Serializable data class ReportRoute(val presetFull: Boolean = false)

@Serializable object SuggestRoute

/** §5.2 "Nearest containers with space" — the next step; a placeholder for now. */
@Serializable object NearestAlternativesRoute

/**
 * Navigation plus the one app-wide sign-in sheet.
 *
 * Every action that needs an account (§4.2: Full, Report, Suggest, Me too, It's
 * been emptied, Vote) goes through the auth gate rather than straight to its
 * screen. Signed in, it runs at once; signed out, the sheet opens and the action
 * runs when sign-in completes. The screens themselves never check auth.
 */
@Composable
fun KantaNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // Scoped to the activity (we are outside NavHost here), so the sheet's state
    // and the pending intent survive navigating between screens.
    val authViewModel: AuthViewModel = hiltViewModel()
    val ready by authViewModel.readyAction.collectAsStateWithLifecycle()

    // Run intents that belong to navigation. ConfirmReport is left on the gate:
    // the map's ViewModel owns the detail it refreshes, so it consumes that one.
    LaunchedEffect(ready) {
        when (val action = ready) {
            is PendingAction.OpenReport -> {
                authViewModel.consume(action)
                navController.navigate(ReportRoute(presetFull = action.presetFull))
            }
            PendingAction.OpenSuggest -> {
                authViewModel.consume(PendingAction.OpenSuggest)
                navController.navigate(SuggestRoute)
            }
            is PendingAction.Vote -> {
                authViewModel.consume(action)
                authViewModel.vote(action.suggestionId)
            }
            is PendingAction.ConfirmReport, null -> Unit
        }
    }

    Box(modifier = modifier) {
        NavHost(navController = navController, startDestination = MapRoute) {
            composable<MapRoute> {
                MapScreen(
                    onProfileClick = { navController.navigate(ProfileRoute) },
                    onFull = { authViewModel.request(PendingAction.OpenReport(presetFull = true)) },
                    onReport = { authViewModel.request(PendingAction.OpenReport(presetFull = false)) },
                    onSuggest = { authViewModel.request(PendingAction.OpenSuggest) },
                    onMyReports = { navController.navigate(MyReportsRoute) },
                    onCityStats = { navController.navigate(CityStatsRoute) },
                    onSuggestionsList = { navController.navigate(SuggestionsRoute) },
                )
            }

            composable<ProfileRoute> {
                ProfileScreen(
                    onBack = navController::popBackStack,
                    // The account is gone; the only sensible place left is the map.
                    onAccountDeleted = { navController.popBackStack(MapRoute, inclusive = false) },
                )
            }
            composable<MyReportsRoute> {
                PlaceholderScreen(stringResource(R.string.menu_my_reports), navController::popBackStack)
            }
            composable<CityStatsRoute> {
                PlaceholderScreen(stringResource(R.string.menu_city_stats), navController::popBackStack)
            }
            composable<SuggestionsRoute> {
                PlaceholderScreen(stringResource(R.string.menu_suggestions), navController::popBackStack)
            }
            composable<ReportRoute> {
                ReportScreen(
                    onClose = navController::popBackStack,
                    // §4.3: a sent Full report goes straight on to the alternatives.
                    // The report screen is removed from the stack, so Back from the
                    // alternatives returns to the map rather than to a spent camera.
                    onFullSent = {
                        navController.navigate(NearestAlternativesRoute) {
                            popUpTo<ReportRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<NearestAlternativesRoute> {
                PlaceholderScreen(stringResource(R.string.alternatives_title), navController::popBackStack)
            }
            composable<SuggestRoute> {
                PlaceholderScreen(stringResource(R.string.action_suggest), navController::popBackStack)
            }
        }

        LoginSheetHost(authViewModel)
    }
}
