package mk.kanta.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import mk.kanta.app.feature.map.MapScreen

/**
 * Type-safe navigation routes (spec §4.5). Only the map exists so far; the remaining
 * destinations are added as their features land.
 */
@Serializable
object MapRoute

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
        composable<MapRoute> { MapScreen() }
    }
}
