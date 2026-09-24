package mk.kanta.app.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.location.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * Small still images of the map (§5.3 suggestion cards: "mini map snapshot").
 *
 * A live MapView per list card would mean dozens of GL surfaces in a scrolling
 * list; MapLibre's snapshotter renders the same Kanta style off-screen into a
 * bitmap instead. Snapshots are made one at a time (each spins up a renderer) and
 * kept in a small memory cache, so scrolling back up costs nothing.
 */
object MapSnapshots {

    private val cache = LruCache<String, Bitmap>(48)
    private val oneAtATime = Mutex()

    suspend fun snapshot(
        context: Context,
        at: LatLon,
        widthDp: Int,
        heightDp: Int,
        density: Float,
        dark: Boolean,
        zoom: Double = 16.0,
    ): Bitmap? {
        val key = "${at.lat},${at.lon},$widthDp,$heightDp,$dark,$zoom"
        cache.get(key)?.let { return it }

        return oneAtATime.withLock {
            cache.get(key) ?: render(context, at, widthDp, heightDp, density, dark, zoom)
                ?.also { cache.put(key, it) }
        }
    }

    private suspend fun render(
        context: Context,
        at: LatLon,
        widthDp: Int,
        heightDp: Int,
        density: Float,
        dark: Boolean,
        zoom: Double,
    ): Bitmap? = withContext(Dispatchers.Main) {
        MapLibre.getInstance(context)
        val options = MapSnapshotter.Options(widthDp, heightDp)
            .withPixelRatio(density)
            .withStyleBuilder(Style.Builder().fromJson(KantaMapStyle.load(context, dark)))
            .withCameraPosition(
                CameraPosition.Builder().target(LatLng(at.lat, at.lon)).zoom(zoom).build(),
            )
            .withLogo(false)

        val snapshotter = MapSnapshotter(context, options)
        val map: Bitmap? = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { snapshotter.cancel() }
            snapshotter.start(
                { snapshot -> if (continuation.isActive) continuation.resume(snapshot.bitmap) },
                { _ -> if (continuation.isActive) continuation.resume(null) },
            )
        }
        map?.let { withPin(it, density, dark) }
    }

    /** The suggestion marker, in the centre — the spot the card is about. */
    private fun withPin(map: Bitmap, density: Float, dark: Boolean): Bitmap {
        val out = map.copy(Bitmap.Config.ARGB_8888, true)
        val pin = MarkerBitmapFactory(density).suggestionMarker(dark)
        Canvas(out).drawBitmap(
            pin,
            (out.width - pin.width) / 2f,
            (out.height - pin.height) / 2f,
            null,
        )
        return out
    }
}

/** A snapshot of [at], or null while it renders (or if it could not be made). */
@Composable
fun rememberMapSnapshot(at: LatLon, width: Dp, height: Dp): State<ImageBitmap?> {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val dark = KantaTheme.colors.isDark
    return produceState<ImageBitmap?>(initialValue = null, at, width, height, dark) {
        value = MapSnapshots.snapshot(
            context.applicationContext,
            at,
            width.value.toInt(),
            height.value.toInt(),
            density,
            dark,
        )?.asImageBitmap()
    }
}
