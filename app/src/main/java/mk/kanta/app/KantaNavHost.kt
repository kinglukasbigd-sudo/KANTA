package mk.kanta.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import mk.kanta.app.feature.PlaceholderScreen
import mk.kanta.app.feature.map.MapScreen

/**
 * Type-safe navigation routes (spec §4.5).
 *
 * The map is real; the destinations the bottom sheet points at are placeholders
 * until their own steps land. They exist now so the menu's rows actually go
 * somewhere and the back stack can be tested.
 */
@Serializable object MapRoute

@Serializable object ProfileRoute

@Serializable object MyReportsRoute

@Serializable object CityStatsRoute

@Serializable object SuggestionsRoute

@Serializable object ReportRoute

@Serializable object SuggestRoute

@Composable
fun KantaNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = MapRoute,
        modifier = modifier,
    ) {
        composable<MapRoute> {
            MapScreen(
                onProfileClick = { navController.navigate(ProfileRoute) },
                // "Full" and "Report" both open the report flow (§4.3); the
                // difference is the pre-set status, which that step will carry.
                onFull = { navController.navigate(ReportRoute) },
                onReport = { navController.navigate(ReportRoute) },
                onSuggest = { navController.navigate(SuggestRoute) },
                onMyReports = { navController.navigate(MyReportsRoute) },
                onCityStats = { navController.navigate(CityStatsRoute) },
                onSuggestionsList = { navController.navigate(SuggestionsRoute) },
            )
        }

        composable<ProfileRoute> {
            PlaceholderScreen(stringResource(R.string.menu_my_reports), navController::popBackStack)
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
            PlaceholderScreen(stringResource(R.string.action_report), navController::popBackStack)
        }
        composable<SuggestRoute> {
            PlaceholderScreen(stringResource(R.string.action_suggest), navController::popBackStack)
        }
    }
}
