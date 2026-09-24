package mk.kanta.app.feature.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.data.suggest.SuggestionPin
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.feature.map.KantaMapStyle
import mk.kanta.app.feature.map.MapLayers
import mk.kanta.app.feature.map.rememberMapViewWithLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * §4.6: "user drags the pin to the exact spot".
 *
 * The pin stays fixed in the middle and the map moves under it — the pattern
 * people know from ride and delivery apps, and far easier to aim than dragging a
 * 14 dp marker with a thumb that covers it. [onPinMoved] fires when the map
 * settles, with the point under the pin.
 *
 * Existing containers are drawn underneath, so a duplicate is visible before the
 * server has to say "Is it this one?"; a small dot marks where the phone is.
 */
@Composable
fun PinPickerMap(
    start: LatLon,
    device: LatLon?,
    nearby: List<ContainerCandidate>,
    onPinMoved: (LatLon) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** §4.4 pick mode: a crosshair instead of a pin — the spot, not a thing on it. */
    crosshair: Boolean = false,
    /** Open suggestions, so the user sees what is already asked for. */
    suggestions: List<SuggestionPin> = emptyList(),
    startZoom: Double = 18.0,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val dark = KantaTheme.colors.isDark
    val brand = KantaTheme.colors.brand
    val factory = remember(density) { MarkerBitmapFactory(density) }
    val mapView = rememberMapViewWithLifecycle()
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnPinMoved by rememberUpdatedState(onPinMoved)

    Box(modifier.semantics { this.contentDescription = contentDescription }) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (crosshair) {
            // A thin cross with a gap in the middle, so the exact spot stays visible.
            Canvas(Modifier.align(Alignment.Center).size(44.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val gap = 5.dp.toPx()
                val arm = size.minDimension / 2f
                val stroke = 2.dp.toPx()
                listOf(
                    Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f),
                ).forEach { d ->
                    val start = Offset(c.x + d.x * gap, c.y + d.y * gap)
                    val end = Offset(c.x + d.x * arm, c.y + d.y * arm)
                    // A dark underlay keeps the cross readable on light and dark maps.
                    drawLine(Color.Black.copy(alpha = 0.35f), start, end, stroke * 2.2f, StrokeCap.Round)
                    drawLine(brand, start, end, stroke, StrokeCap.Round)
                }
                drawCircle(brand, radius = 2.dp.toPx(), center = c)
            }
        } else {
            // The pin: a brand dot on a short stem whose tip is the exact map centre.
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 28.dp, height = 40.dp)
                    // Lift by half the height so the stem's tip, not the box centre,
                    // sits on the point that is sent.
                    .offset(y = (-20).dp),
            ) {
                val head = 9.dp.toPx()
                val centre = Offset(size.width / 2f, head + 2.dp.toPx())
                drawLine(
                    color = brand,
                    start = centre,
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(color = Color.White, radius = head + 2.dp.toPx(), center = centre)
                drawCircle(color = brand, radius = head, center = centre)
            }
        }
    }

    DisposableEffect(mapView, dark) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
                isCompassEnabled = false
            }
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(start.lat, start.lon))
                .zoom(startZoom)
                .build()
            map.setStyle(Style.Builder().fromJson(KantaMapStyle.load(context, dark))) { loaded ->
                MapLayers.install(loaded, factory, dark)
                loaded.addSource(GeoJsonSource(YOU_SOURCE))
                loaded.addLayer(
                    CircleLayer(YOU_LAYER, YOU_SOURCE).withProperties(
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleColor(brand.copy(alpha = 0.9f).toArgb()),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    ),
                )
                style = loaded
            }
            map.addOnCameraIdleListener {
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                currentOnPinMoved(LatLon(target.latitude, target.longitude))
            }
        }
        onDispose { style = null }
    }

    LaunchedEffect(style, nearby) {
        val loaded = style ?: return@LaunchedEffect
        MapLayers.updateContainers(
            loaded,
            MapLayers.toFeatureCollection(
                nearby.map {
                    MapLayers.ContainerFeature(
                        id = it.id,
                        code = it.code,
                        kind = it.kind,
                        category = it.category,
                        status = it.status,
                        verified = it.verified,
                        lon = it.position.lon,
                        lat = it.position.lat,
                    )
                },
                factory,
                selectedId = null,
            ),
        )
    }

    LaunchedEffect(style, suggestions) {
        val loaded = style ?: return@LaunchedEffect
        MapLayers.updateSuggestions(
            loaded,
            MapLayers.suggestionsToFeatureCollection(
                suggestions.map { Triple(it.id, it.position.lon, it.position.lat) },
            ),
        )
    }

    LaunchedEffect(style, device) {
        val loaded = style ?: return@LaunchedEffect
        val at = device ?: return@LaunchedEffect
        loaded.getSourceAs<GeoJsonSource>(YOU_SOURCE)?.setGeoJson(Point.fromLngLat(at.lon, at.lat))
    }
}

private const val YOU_SOURCE = "kanta-you"
private const val YOU_LAYER = "kanta-you-layer"
