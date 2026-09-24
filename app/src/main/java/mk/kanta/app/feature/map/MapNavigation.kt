package mk.kanta.app.feature.map

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.BrandDark
import mk.kanta.app.core.designsystem.BrandLight
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.abs

/**
 * The my-location button's two modes (§4.1):
 * - [Centered]: the first tap puts the user in the middle of the map, once.
 * - [Following]: the second tap keeps them there and turns the map with the
 *   direction the phone is facing; the location dot grows a heading cone.
 * Any pan, or a tap on the compass, drops back to [Off].
 */
enum class LocationMode { Off, Centered, Following }

/**
 * Rotation, tilt, the compass and follow mode for the main map (§4.1).
 *
 * The location dot, the heading cone and the camera tracking are MapLibre's own
 * LocationComponent, which reads the phone's compass itself; this class only
 * switches it between modes and keeps the Kanta colours.
 */
object MapNavigation {

    /** §4.1: "tilts it slightly (max 45°)". */
    const val MAX_TILT = 45.0

    /**
     * Degrees the fingers must twist before the map starts to rotate. MapLibre's
     * default is ~15°; a little more keeps a plain pinch-zoom from turning the map.
     */
    private const val ROTATE_THRESHOLD_DEGREES = 20f

    /** §4.1: back to north and flat in 300 ms. */
    const val RESET_MS = 300

    /** Below this the map counts as facing north and flat, and the compass leaves. */
    private const val NORTH_TOLERANCE = 0.5

    private const val FOLLOW_ZOOM = 17.0

    fun configureGestures(map: MapLibreMap) {
        map.uiSettings.apply {
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
            // A pinch raises the bar for rotation, so zooming never twists the map.
            isIncreaseRotateThresholdWhenScaling = true
            // Our own compass (§4.1), in the button stack.
            isCompassEnabled = false
        }
        map.setMaxPitchPreference(MAX_TILT)
        map.gesturesManager.rotateGestureDetector.angleThreshold = ROTATE_THRESHOLD_DEGREES
    }

    /** True once the map is turned or tilted enough that the compass should show. */
    fun isOffNorth(bearing: Double, tilt: Double): Boolean {
        val b = ((bearing % 360) + 360) % 360
        return minOf(b, 360 - b) > NORTH_TOLERANCE || abs(tilt) > NORTH_TOLERANCE
    }

    /**
     * The location dot (and, in follow mode, its heading cone). Safe to call on
     * every style load; does nothing without location permission.
     */
    @SuppressLint("MissingPermission") // guarded by hasPermission
    fun activateLocation(
        context: Context,
        map: MapLibreMap,
        style: Style,
        darkTheme: Boolean,
        hasPermission: Boolean,
        /** One instance per screen: re-activation after a theme change must not add a second. */
        trackingListener: OnCameraTrackingChangedListener,
    ) {
        if (!hasPermission) return
        val brand = (if (darkTheme) BrandDark else BrandLight).toArgb()
        val options = LocationComponentOptions.builder(context)
            .foregroundTintColor(brand)
            .foregroundStaleTintColor(brand)
            .backgroundTintColor(android.graphics.Color.WHITE)
            .bearingDrawable(R.drawable.location_heading_cone)
            .bearingTintColor(brand)
            .accuracyColor(brand)
            .accuracyAlpha(0.10f)
            .elevation(0f)
            .pulseEnabled(false)
            .build()

        val component = map.locationComponent
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(true)
                .build(),
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.NORMAL
        component.cameraMode = CameraMode.NONE
        component.removeOnCameraTrackingChangedListener(trackingListener)
        component.addOnCameraTrackingChangedListener(trackingListener)
    }

    /** §4.1 second tap: the map turns with the phone; the dot shows a heading cone. */
    fun follow(map: MapLibreMap) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) return
        component.renderMode = RenderMode.COMPASS
        component.setCameraMode(
            CameraMode.TRACKING_COMPASS,
            RESET_MS.toLong(),
            FOLLOW_ZOOM,
            null,
            null,
            null,
        )
    }

    /** Stops following without moving the camera. */
    fun stopFollowing(map: MapLibreMap) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) return
        component.cameraMode = CameraMode.NONE
        component.renderMode = RenderMode.NORMAL
    }

    /** §4.1 compass tap: smoothly back to north and flat. */
    fun resetNorth(map: MapLibreMap) {
        stopFollowing(map)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(map.cameraPosition).bearing(0.0).tilt(0.0).build(),
            ),
            RESET_MS,
        )
    }
}

/**
 * The compass needle: brand-coloured north half, muted south half. Turned by
 * [bearing] so it always points at real north on the rotated map.
 */
@Composable
fun CompassNeedle(bearing: Float, north: Color, south: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(22.dp)
            .graphicsLayer { rotationZ = -bearing },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.height * 0.46f
        val width = size.width * 0.2f
        val northHalf = Path().apply {
            moveTo(cx, cy - half)
            lineTo(cx + width, cy)
            lineTo(cx - width, cy)
            close()
        }
        val southHalf = Path().apply {
            moveTo(cx, cy + half)
            lineTo(cx + width, cy)
            lineTo(cx - width, cy)
            close()
        }
        drawPath(northHalf, north)
        drawPath(southHalf, south)
        drawCircle(Color.White, radius = width * 0.45f, center = Offset(cx, cy))
    }
}
