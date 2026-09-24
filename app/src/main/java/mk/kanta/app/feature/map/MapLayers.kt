package mk.kanta.app.feature.map

import android.graphics.PointF
import androidx.compose.ui.graphics.toArgb
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.BrandDark
import mk.kanta.app.core.designsystem.BrandLight
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.marker.MarkerBitmapFactory
import mk.kanta.app.core.location.LatLon
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Builds the container and suggestion layers (§3.4).
 *
 * §3.4 is explicit that this must be a GeoJSON source plus style layers, not
 * hundreds of Android views, so the map stays smooth with 10,000+ points. Every
 * marker bitmap is registered once as a style image; after that, panning costs
 * nothing but GPU work.
 *
 * There is no clustering and no number anywhere on the map (§3.4). Instead every
 * container is always drawn, and its size follows the zoom:
 *
 *   z < 14        a 4 dp dot in its status colour (circle layer)
 *   14 – 15.5     the dot grows to 6 dp
 *   15.2 – 15.8   dots fade out while the real shapes fade in — no popping
 *   z ≥ 15.5      rectangles and triangles, growing smoothly with the zoom
 *
 * The shape band is three symbol layers, each baked with its zoom-dependent
 * details — the "?" badge at z ≥ 16, the recycling dot at z ≥ 17 — and they too
 * cross-fade over a short overlap rather than switch.
 *
 * Draw order: features are written OK first, problems last, and the symbol layers
 * sort by the same rank, so a red or orange marker is never hidden under a green one.
 */
object MapLayers {

    const val CONTAINER_SOURCE = "kanta-containers"
    const val SUGGESTION_SOURCE = "kanta-suggestions"

    const val LAYER_DOTS = "kanta-container-dots"
    const val LAYER_CONTAINERS_FAR = "kanta-containers-far"
    const val LAYER_CONTAINERS_MID = "kanta-containers-mid"
    const val LAYER_CONTAINERS_NEAR = "kanta-containers-near"
    const val LAYER_SUGGESTIONS = "kanta-suggestions-layer"

    /** §4.6: the 150 m "near you" ring of an area check. */
    const val RING_SOURCE = "kanta-ring"
    const val LAYER_RING = "kanta-ring-layer"

    /** §4.6 admin: where area checks happened in the last 90 days. */
    const val COVERAGE_SOURCE = "kanta-coverage"
    const val LAYER_COVERAGE = "kanta-coverage-layer"

    /** Admin review: a ring around the request or container being looked at. */
    const val FOCUS_SOURCE = "kanta-focus"
    const val LAYER_FOCUS = "kanta-focus-layer"

    /** Radius in metres, carried on ring and coverage features. */
    private const val PROP_RADIUS = "r"

    /**
     * Metres per logical pixel at zoom 0 on the equator (MapLibre's 512 px world).
     * Dividing by cos(latitude) gives the local scale.
     */
    private const val METRES_PER_PX_Z0 = 78_271.517

    /** Skopje's latitude, for the coverage layer: the whole city is within 0.1°. */
    private const val SKOPJE_LAT = 42.0

    /** Feature properties. Kept short: they are repeated 10,000 times. */
    const val PROP_ID = "id"
    const val PROP_CODE = "code"
    const val PROP_KIND = "kind"
    const val PROP_STATUS = "status"
    const val PROP_RANK = "rank"
    private const val PROP_COLOR = "color"
    private const val PROP_OPACITY = "opacity"
    private const val PROP_ICON_FAR = "iconFar"
    private const val PROP_ICON_MID = "iconMid"
    private const val PROP_ICON_NEAR = "iconNear"

    /** §3.4: dots below this band, shapes above it; the two cross-fade inside it. */
    private const val SHAPES_FADE_IN_START = 15.2f
    private const val SHAPES_FADE_IN_END = 15.8f
    private const val BADGE_ZOOM = 16f
    private const val DOT_ZOOM = 17f

    /** Half-width of the overlap in which two shape bands cross-fade. */
    private const val BAND_FADE = 0.1f

    /** All layers Kanta owns, for hit-testing. Dots too: at city zoom they are the markers. */
    val containerLayers = listOf(LAYER_CONTAINERS_NEAR, LAYER_CONTAINERS_MID, LAYER_CONTAINERS_FAR, LAYER_DOTS)

    // -----------------------------------------------------------------------------------------
    // Features
    // -----------------------------------------------------------------------------------------

    data class ContainerFeature(
        val id: String,
        val code: String,
        val kind: ContainerKind,
        val category: ContainerCategory,
        val status: ContainerStatus,
        val verified: Boolean,
        val lon: Double,
        val lat: Double,
    )

    /**
     * Turns containers into a FeatureCollection, naming the three zoom-band icons
     * per feature. The ids come from the same [MarkerBitmapFactory.idFor] the
     * image registry uses, so a name can never point at a bitmap that was not
     * generated.
     */
    fun toFeatureCollection(
        containers: List<ContainerFeature>,
        factory: MarkerBitmapFactory,
        selectedId: String?,
    ): FeatureCollection {
        // §3.4: problems draw on top. Within a tile MapLibre draws features in
        // source order, so sorting here is what puts red and orange above green.
        val ordered = containers.sortedBy { drawRank(it, selectedId) }
        val features = ordered.map { container ->
            val selected = container.id == selectedId
            val unverified = !container.verified

            Feature.fromGeometry(Point.fromLngLat(container.lon, container.lat)).apply {
                addStringProperty(PROP_ID, container.id)
                addStringProperty(PROP_CODE, container.code)
                addStringProperty(PROP_KIND, container.kind.name)
                addStringProperty(PROP_STATUS, container.status.name)
                addNumberProperty(PROP_RANK, drawRank(container, selectedId))
                // The dot band (z < 15.5) has no shapes, only colour.
                addStringProperty(PROP_COLOR, dotColor(container))
                // §3.4: unverified containers read at 85% opacity, dots included.
                addNumberProperty(PROP_OPACITY, if (unverified && container.status != ContainerStatus.MISSING) 0.85 else 1.0)

                addStringProperty(
                    PROP_ICON_FAR,
                    factory.idFor(
                        container.kind, container.status, ContainerCategory.GENERAL,
                        selected, unverified, badge = false,
                    ),
                )
                addStringProperty(
                    PROP_ICON_MID,
                    factory.idFor(
                        container.kind, container.status, ContainerCategory.GENERAL,
                        selected, unverified, badge = unverified,
                    ),
                )
                addStringProperty(
                    PROP_ICON_NEAR,
                    factory.idFor(
                        container.kind, container.status,
                        // Only an otherwise-fine big container shows its material
                        // dot; anything else reads as its problem first.
                        if (container.kind == ContainerKind.BIG &&
                            container.status == ContainerStatus.OK
                        ) {
                            container.category
                        } else {
                            ContainerCategory.GENERAL
                        },
                        selected, unverified, badge = unverified,
                    ),
                )
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    /**
     * Higher draws later, i.e. on top: OK < gone (destroyed, missing) < full <
     * broken — the red and orange problems §3.4 wants visible first — and the
     * selected marker above everything.
     */
    private fun drawRank(container: ContainerFeature, selectedId: String?): Int = when {
        container.id == selectedId -> 10
        else -> when (container.status) {
            ContainerStatus.OK -> 0
            ContainerStatus.DESTROYED, ContainerStatus.MISSING -> 1
            ContainerStatus.FULL -> 2
            ContainerStatus.BROKEN -> 3
        }
    }

    /** §3.4 dot colours: the same status rules as the shapes. */
    private fun dotColor(container: ContainerFeature): String {
        val color = when (container.status) {
            ContainerStatus.OK ->
                if (container.kind == ContainerKind.BIG) MarkerColors.BigOk else MarkerColors.SmallOk
            ContainerStatus.FULL -> MarkerColors.Full
            ContainerStatus.BROKEN -> MarkerColors.Broken
            ContainerStatus.DESTROYED -> MarkerColors.Destroyed
            ContainerStatus.MISSING -> MarkerColors.Missing
        }
        return String.format("#%06X", color.toArgb() and 0xFFFFFF)
    }

    fun suggestionsToFeatureCollection(
        suggestions: List<Triple<String, Double, Double>>,
    ): FeatureCollection = FeatureCollection.fromFeatures(
        suggestions.map { (id, lon, lat) ->
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty(PROP_ID, id)
            }
        },
    )

    // -----------------------------------------------------------------------------------------
    // Style setup
    // -----------------------------------------------------------------------------------------

    /** Registers every marker bitmap and adds the sources and layers. Call once per style. */
    fun install(style: Style, factory: MarkerBitmapFactory, darkTheme: Boolean) {
        factory.buildAll(darkTheme).forEach { (id, bitmap) -> style.addImage(id, bitmap) }

        // §3.4: no clustering — every container is always its own marker.
        style.addSource(
            GeoJsonSource(
                CONTAINER_SOURCE,
                FeatureCollection.fromFeatures(emptyList()),
                GeoJsonOptions().withBuffer(64),
            ),
        )
        style.addSource(GeoJsonSource(SUGGESTION_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(COVERAGE_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(RING_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(FOCUS_SOURCE, FeatureCollection.fromFeatures(emptyList())))

        // Area shading sits under every marker; the focus ring sits on top.
        addCoverageLayer(style, darkTheme)
        addRingLayer(style, darkTheme)
        addDotLayer(style)
        addContainerLayers(style)
        addSuggestionLayer(style)
        addFocusLayer(style, darkTheme)
    }

    // -----------------------------------------------------------------------------------------
    // §4.6 area layers — real-world radii, so they are drawn in metres, not pixels
    // -----------------------------------------------------------------------------------------

    /**
     * circle-radius for a radius in metres. Pixels per metre double with every
     * zoom level, so an exponential(2) interpolation between two far-apart stops
     * is exact at every zoom in between.
     */
    private fun metresToPixels(metres: Expression, latitude: Double): Expression {
        val pxPerMetreZ0 = 1.0 / (METRES_PER_PX_Z0 * kotlin.math.cos(Math.toRadians(latitude)))
        return Expression.interpolate(
            Expression.exponential(2),
            Expression.zoom(),
            Expression.stop(0, Expression.product(metres, Expression.literal(pxPerMetreZ0))),
            Expression.stop(22, Expression.product(metres, Expression.literal(pxPerMetreZ0 * (1 shl 22)))),
        )
    }

    private fun brand(darkTheme: Boolean) = if (darkTheme) BrandDark else BrandLight

    /** §4.6 admin coverage: soft green where someone checked; everything else untouched. */
    private fun addCoverageLayer(style: Style, darkTheme: Boolean) {
        style.addLayer(
            CircleLayer(LAYER_COVERAGE, COVERAGE_SOURCE).apply {
                withProperties(
                    PropertyFactory.circleRadius(
                        metresToPixels(Expression.toNumber(Expression.get(PROP_RADIUS)), SKOPJE_LAT),
                    ),
                    PropertyFactory.circleColor(MarkerColors.BigOk.toArgb()),
                    PropertyFactory.circleOpacity(if (darkTheme) 0.26f else 0.18f),
                    PropertyFactory.circleBlur(0.15f),
                    PropertyFactory.circlePitchAlignment("map"),
                    PropertyFactory.visibility("none"),
                )
            },
        )
    }

    private fun addRingLayer(style: Style, darkTheme: Boolean) {
        style.addLayer(
            CircleLayer(LAYER_RING, RING_SOURCE).apply {
                withProperties(
                    PropertyFactory.circleRadius(
                        metresToPixels(Expression.toNumber(Expression.get(PROP_RADIUS)), SKOPJE_LAT),
                    ),
                    PropertyFactory.circleColor(brand(darkTheme).toArgb()),
                    PropertyFactory.circleOpacity(0.06f),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleStrokeColor(brand(darkTheme).toArgb()),
                    PropertyFactory.circleStrokeOpacity(0.7f),
                    PropertyFactory.circlePitchAlignment("map"),
                )
            },
        )
    }

    private fun addFocusLayer(style: Style, darkTheme: Boolean) {
        style.addLayer(
            CircleLayer(LAYER_FOCUS, FOCUS_SOURCE).apply {
                withProperties(
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(brand(darkTheme).toArgb()),
                )
            },
        )
    }

    /** Draws the check ring around [centre], or clears it with null. */
    fun setRing(style: Style, centre: LatLon?, radiusMetres: Int) {
        val source = style.getSourceAs<GeoJsonSource>(RING_SOURCE) ?: return
        if (centre == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        // The layer's metres-to-pixels scale is for Skopje's latitude; re-set it
        // for the exact centre so the ring is true wherever the user stands.
        style.getLayer(LAYER_RING)?.setProperties(
            PropertyFactory.circleRadius(
                metresToPixels(Expression.toNumber(Expression.get(PROP_RADIUS)), centre.lat),
            ),
        )
        source.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(centre.lon, centre.lat)).apply {
                addNumberProperty(PROP_RADIUS, radiusMetres)
            },
        )
    }

    /**
     * Shows admin_coverage()'s GeoJSON (points with `radius_m`), or hides the
     * layer with null. The server's property is renamed to [PROP_RADIUS] here so
     * the layer does not depend on a SQL column name.
     */
    fun setCoverage(style: Style, geoJson: String?) {
        val source = style.getSourceAs<GeoJsonSource>(COVERAGE_SOURCE) ?: return
        val layer = style.getLayer(LAYER_COVERAGE) ?: return
        if (geoJson == null) {
            layer.setProperties(PropertyFactory.visibility("none"))
            return
        }
        val parsed = runCatching { FeatureCollection.fromJson(geoJson) }.getOrNull() ?: return
        val features = parsed.features().orEmpty().map { feature ->
            val radius = feature.getNumberProperty("radius_m")?.toDouble() ?: 150.0
            Feature.fromGeometry(feature.geometry()).apply { addNumberProperty(PROP_RADIUS, radius) }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        layer.setProperties(PropertyFactory.visibility("visible"))
    }

    /** Rings one point for the admin review, or clears it with null. */
    fun setFocus(style: Style, point: LatLon?) {
        val source = style.getSourceAs<GeoJsonSource>(FOCUS_SOURCE) ?: return
        source.setGeoJson(
            if (point == null) {
                FeatureCollection.fromFeatures(emptyList())
            } else {
                FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat))))
            },
        )
    }

    /**
     * §3.4 city zoom: every container a small dot in its status colour — no border,
     * no shape — 4 dp below zoom 14, 6 dp by 15.5, then fading out as the shapes
     * fade in.
     */
    private fun addDotLayer(style: Style) {
        style.addLayer(
            CircleLayer(LAYER_DOTS, CONTAINER_SOURCE).apply {
                maxZoom = SHAPES_FADE_IN_END + 0.01f
                withProperties(
                    PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                    PropertyFactory.circleRadius(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(13.5, 2f),
                            Expression.stop(14.5, 3f),
                            Expression.stop(SHAPES_FADE_IN_END, 3.4f),
                        ),
                    ),
                    PropertyFactory.circleOpacity(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(SHAPES_FADE_IN_START, Expression.toNumber(Expression.get(PROP_OPACITY))),
                            Expression.stop(SHAPES_FADE_IN_END, 0f),
                        ),
                    ),
                    PropertyFactory.circleStrokeWidth(0f),
                    PropertyFactory.circlePitchAlignment("map"),
                )
            },
        )
    }

    private fun addContainerLayers(style: Style) {
        // Order matters: the near layer is added last so it draws on top when
        // zoom ranges overlap during a cross-fade.
        style.addLayer(
            symbolLayer(
                LAYER_CONTAINERS_FAR, PROP_ICON_FAR,
                fadeIn = SHAPES_FADE_IN_START to SHAPES_FADE_IN_END,
                fadeOut = BADGE_ZOOM - BAND_FADE to BADGE_ZOOM + BAND_FADE,
            ),
        )
        style.addLayer(
            symbolLayer(
                LAYER_CONTAINERS_MID, PROP_ICON_MID,
                fadeIn = BADGE_ZOOM - BAND_FADE to BADGE_ZOOM + BAND_FADE,
                fadeOut = DOT_ZOOM - BAND_FADE to DOT_ZOOM + BAND_FADE,
            ),
        )
        style.addLayer(
            symbolLayer(
                LAYER_CONTAINERS_NEAR, PROP_ICON_NEAR,
                fadeIn = DOT_ZOOM - BAND_FADE to DOT_ZOOM + BAND_FADE,
                fadeOut = null,
            ),
        )
    }

    /**
     * One shape band. Visible only between [fadeIn] start and [fadeOut] end, with
     * its opacity ramping across both, so neighbouring bands cross-fade.
     */
    private fun symbolLayer(
        id: String,
        iconProperty: String,
        fadeIn: Pair<Float, Float>,
        fadeOut: Pair<Float, Float>?,
    ) = SymbolLayer(id, CONTAINER_SOURCE).apply {
        minZoom = fadeIn.first
        if (fadeOut != null) maxZoom = fadeOut.second

        val opacityStops = buildList {
            add(Expression.stop(fadeIn.first, 0f))
            add(Expression.stop(fadeIn.second, 1f))
            if (fadeOut != null) {
                add(Expression.stop(fadeOut.first, 1f))
                add(Expression.stop(fadeOut.second, 0f))
            }
        }

        withProperties(
            PropertyFactory.iconImage(Expression.get(iconProperty)),
            // Markers must never be dropped for overlap: a hidden container is
            // one the user cannot report.
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            // §3.4: problems on top of OK ones.
            PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get(PROP_RANK))),
            PropertyFactory.iconOpacity(
                Expression.interpolate(Expression.linear(), Expression.zoom(), *opacityStops.toTypedArray()),
            ),
            // §3.4: shapes grow smoothly with the zoom; 1.0 is the drawn size (14×10 dp at z16).
            PropertyFactory.iconSize(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(SHAPES_FADE_IN_START, 0.6f),
                    Expression.stop(BADGE_ZOOM, 1.0f),
                    Expression.stop(19, 1.35f),
                ),
            ),
        )
    }

    private fun addSuggestionLayer(style: Style) {
        style.addLayer(
            SymbolLayer(LAYER_SUGGESTIONS, SUGGESTION_SOURCE).apply {
                withProperties(
                    PropertyFactory.iconImage(MarkerBitmapFactory.SUGGESTION_ID),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(13, 0.7f),
                            Expression.stop(16, 1.0f),
                            Expression.stop(19, 1.2f),
                        ),
                    ),
                )
            },
        )
    }

    // -----------------------------------------------------------------------------------------
    // Updates and hit testing
    // -----------------------------------------------------------------------------------------

    fun updateContainers(style: Style, collection: FeatureCollection) {
        (style.getSourceAs<GeoJsonSource>(CONTAINER_SOURCE))?.setGeoJson(collection)
    }

    fun updateSuggestions(style: Style, collection: FeatureCollection) {
        (style.getSourceAs<GeoJsonSource>(SUGGESTION_SOURCE))?.setGeoJson(collection)
    }

    fun setSuggestionsVisible(style: Style, visible: Boolean) {
        style.getLayer(LAYER_SUGGESTIONS)?.setProperties(
            PropertyFactory.visibility(if (visible) "visible" else "none"),
        )
    }

    /**
     * Which container was tapped, if any.
     *
     * The tap box is padded to roughly a fingertip because the markers are
     * deliberately small (14×10dp at z16) and §8 requires real touch targets.
     */
    fun containerAt(map: MapLibreMap, point: PointF, touchSlopPx: Float): String? {
        val box = android.graphics.RectF(
            point.x - touchSlopPx,
            point.y - touchSlopPx,
            point.x + touchSlopPx,
            point.y + touchSlopPx,
        )
        val hits = map.queryRenderedFeatures(box, *containerLayers.toTypedArray())
        return hits.firstOrNull { it.hasProperty(PROP_ID) }?.getStringProperty(PROP_ID)
    }
}
