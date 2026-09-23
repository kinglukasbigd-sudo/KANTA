package mk.kanta.app.feature.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * A [MapView] that follows the composition's lifecycle.
 *
 * MapLibre's MapView is a classic Android view with a hand-managed lifecycle: miss
 * onStop or onDestroy and it leaks its GL surface and keeps rendering in the
 * background. This wires the callbacks to the owning lifecycle so a Compose caller
 * never has to think about it.
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        // Must happen before any MapView is constructed.
        MapLibre.getInstance(context)
        MapView(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // The composable can leave before the lifecycle ends (navigation),
            // so tear the view down here too rather than waiting for ON_DESTROY.
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    return mapView
}

/** Density-aware touch slop for marker hit-testing (§8: targets ≥ 48dp). */
fun Context.markerTouchSlopPx(): Float = 24f * resources.displayMetrics.density
