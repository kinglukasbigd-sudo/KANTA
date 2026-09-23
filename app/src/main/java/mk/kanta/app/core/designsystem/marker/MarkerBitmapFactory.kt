package mk.kanta.app.core.designsystem.marker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.MarkerColors

/**
 * Draws the map markers from KANTA_SPEC.md §3.4 as [Bitmap]s, to be registered once as MapLibre
 * style images and then referenced by id from a symbol layer.
 *
 * Spec §3.4 is explicit that markers must NOT be Android views — with 10,000+ containers the only
 * thing that stays at 60fps is a GeoJSON source plus style images. So this factory runs once at
 * style load, produces one bitmap per (kind × status × category) combination that can appear, and
 * is never touched again while panning.
 *
 * Sizes are given in dp at zoom 16 (the spec's reference zoom); MapLibre scales them per zoom.
 */
/** One entry in the marker image registry. */
data class MarkerVariant(
    val kind: ContainerKind,
    val status: ContainerStatus,
    val category: ContainerCategory,
    val selected: Boolean,
    val unverified: Boolean,
    val badge: Boolean,
)

class MarkerBitmapFactory(private val density: Float) {

    private fun dp(value: Float): Float = value * density

    // Fresh Paints per call on purpose: these run once at style load, and a shared Paint would
    // leak a stale pathEffect or strokeCap from one marker into the next.
    private fun fillPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Stable id for a marker image, used both as the MapLibre style-image key and as the value a
     * symbol layer's `icon-image` expression resolves to.
     */
    fun idFor(
        kind: ContainerKind,
        status: ContainerStatus,
        category: ContainerCategory = ContainerCategory.GENERAL,
        selected: Boolean = false,
        unverified: Boolean = false,
        badge: Boolean = false,
    ): String = buildString {
        append("kanta-")
        append(kind.name.lowercase())
        append('-')
        append(status.name.lowercase())
        if (category.isRecycling) {
            append('-')
            append(category.name.lowercase())
        }
        if (unverified) append("-unverified")
        // §3.4: the "?" badge is drawn only at zoom >= 16, so the two variants are separate
        // style images and the symbol layer picks between them with a step(zoom) expression.
        if (unverified && badge) append("-q")
        if (selected) append("-selected")
    }

    /**
     * Every marker image the map can need. Generated once at style load.
     *
     * Recycling categories only vary the inner dot, and only on big containers that are OK —
     * a destroyed glass container reads as destroyed first — so the product stays small.
     */
    fun buildAll(darkTheme: Boolean): Map<String, Bitmap> = buildMap {
        for (variant in variants()) {
            put(
                idFor(
                    variant.kind, variant.status, variant.category,
                    variant.selected, variant.unverified, variant.badge,
                ),
                marker(
                    variant.kind, variant.status, variant.category, darkTheme,
                    variant.selected, variant.unverified, variant.badge,
                ),
            )
        }
        put(SUGGESTION_ID, suggestionMarker(darkTheme))
        put(SUGGESTION_ID + "-selected", suggestionMarker(darkTheme, selected = true))
    }

    /**
     * Every (kind, status, category, selected, unverified, badge) combination the
     * map can ask for.
     *
     * This is the single definition shared by [buildAll], which registers the
     * bitmaps, and the map's feature builder, which names them. If the two ever
     * disagreed, MapLibre would silently drop the marker rather than complain —
     * so they read from the same list.
     */
    fun variants(): List<MarkerVariant> = buildList {
        for (kind in ContainerKind.entries) {
            for (status in ContainerStatus.entries) {
                for (selected in listOf(false, true)) {
                    // The recycling dot (§3.4) is only meaningful on a big
                    // container that is otherwise fine — a broken glass container
                    // must read as broken first.
                    val categories = if (kind == ContainerKind.BIG && status == ContainerStatus.OK) {
                        ContainerCategory.entries
                    } else {
                        listOf(ContainerCategory.GENERAL)
                    }

                    for (category in categories) {
                        // MISSING is hollow, and hollow already means gone, so it
                        // never also takes the unverified treatment (§3.4).
                        val unverifiedOptions = if (status == ContainerStatus.MISSING) {
                            listOf(false)
                        } else {
                            listOf(false, true)
                        }

                        for (unverified in unverifiedOptions) {
                            val badges = if (unverified) listOf(false, true) else listOf(false)
                            for (badge in badges) {
                                add(MarkerVariant(kind, status, category, selected, unverified, badge))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * One container marker.
     *
     * Spec §3.4 shapes: big container = rounded rectangle 14×10dp, small can = rounded triangle
     * 10dp. Spec §3.1: FULL renders 15% larger with a 2dp white (dark theme: background) border
     * so orange can never be mistaken for the small-can yellow. MISSING is hollow and dashed.
     */
    fun marker(
        kind: ContainerKind,
        status: ContainerStatus,
        category: ContainerCategory = ContainerCategory.GENERAL,
        darkTheme: Boolean = false,
        selected: Boolean = false,
        unverified: Boolean = false,
        badge: Boolean = false,
    ): Bitmap {
        val isFull = status == ContainerStatus.FULL
        val isMissing = status == ContainerStatus.MISSING
        // §3.4: "filled = it exists, hollow = it's gone". MISSING wins over UNVERIFIED — a
        // user-added container reported missing is hollow like any other missing one, so the
        // unverified dashed border is never drawn on top of a hollow shape.
        val showUnverified = unverified && !isMissing

        // §3.1: full markers render 15% larger. §3.4: selected scales 1.4x with a brand halo.
        val scale = (if (isFull) 1.15f else 1f) * (if (selected) 1.4f else 1f)

        val shapeW: Float
        val shapeH: Float
        when (kind) {
            ContainerKind.BIG -> {
                shapeW = dp(14f) * scale
                shapeH = dp(10f) * scale
            }
            ContainerKind.SMALL -> {
                shapeW = dp(10f) * scale
                shapeH = dp(10f) * scale
            }
        }

        val border = if (isFull) dp(2f) else 0f
        val haloWidth = if (selected) dp(3f) else 0f
        val missingStroke = if (isMissing) dp(1.5f) else 0f
        val unverifiedStroke = if (showUnverified) dp(1.5f) else 0f
        // The "?" badge overhangs the top-right corner, so it needs its own headroom.
        val badgeRadius = if (showUnverified && badge) dp(3.5f) * scale else 0f
        // Pad for whichever outer decoration is widest, plus a pixel of antialias headroom.
        val pad = maxOf(border, haloWidth, missingStroke, unverifiedStroke, badgeRadius) + dp(2f)

        val width = Math.ceil((shapeW + pad * 2).toDouble()).toInt().coerceAtLeast(1)
        val height = Math.ceil((shapeH + pad * 2).toDouble()).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(pad, pad, pad + shapeW, pad + shapeH)

        @ColorInt val color = statusColorInt(status, kind)

        if (selected) {
            // §3.4: soft halo in brand colour behind the selected marker.
            drawShape(
                canvas, kind, inflate(rect, haloWidth),
                fillPaint().apply {
                    this.color = MarkerColors.Suggestion.toArgb()
                    alpha = 56
                },
            )
        }

        if (isFull) {
            // Border sits outside the shape, drawn as a slightly larger filled copy underneath.
            drawShape(
                canvas, kind, inflate(rect, border),
                fillPaint().apply { this.color = MarkerColors.fullBorder(darkTheme).toArgb() },
            )
        }

        if (isMissing) {
            // §3.1: hollow, dashed grey outline only.
            drawShape(
                canvas, kind, rect,
                strokePaint().apply {
                    this.color = color
                    strokeWidth = missingStroke
                    pathEffect = DashPathEffect(floatArrayOf(dp(2.5f), dp(2f)), 0f)
                },
            )
        } else {
            // §3.4: an unverified container keeps its normal fill, at 85% opacity.
            drawShape(
                canvas, kind, rect,
                fillPaint().apply {
                    this.color = color
                    if (showUnverified) alpha = UNVERIFIED_ALPHA
                },
            )

            if (showUnverified) {
                // 1.5dp dashed border, white in light / background colour in dark, so the
                // "not yet confirmed" state reads without changing the status colour.
                //
                // Offset by half the stroke width so the dashes sit ENTIRELY OUTSIDE the fill.
                // Stroked on the edge itself they cut into the shape and it reads as a
                // perforated stamp rather than a filled container with a dashed ring.
                drawShape(
                    canvas, kind, inflate(rect, unverifiedStroke / 2f),
                    strokePaint().apply {
                        this.color = MarkerColors.fullBorder(darkTheme).toArgb()
                        strokeWidth = unverifiedStroke
                        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(1.5f)), 0f)
                    },
                )
            }

            // §3.4: recycling material dot, 3dp, only meaningful on big containers.
            val dot = recyclingDotColor(category)
            if (dot != null && kind == ContainerKind.BIG) {
                // A contrasting ring under the dot. Without it the glass green would vanish
                // against the identical green of an OK big container.
                canvas.drawCircle(
                    rect.centerX(), rect.centerY(), dp(2.1f) * scale,
                    fillPaint().apply { this.color = MarkerColors.fullBorder(darkTheme).toArgb() },
                )
                canvas.drawCircle(
                    rect.centerX(), rect.centerY(), dp(1.5f) * scale,
                    fillPaint().apply { this.color = dot },
                )
            }
        }

        if (showUnverified && badge) {
            drawQuestionBadge(canvas, rect.right, rect.top, badgeRadius, color, darkTheme)
        }

        return bitmap
    }

    /**
     * The tiny "?" badge on an unverified container (§3.4), sitting on the shape's top-right
     * corner. Only ever drawn into the zoom >= 16 variant of the image.
     */
    private fun drawQuestionBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        @ColorInt statusColor: Int,
        darkTheme: Boolean,
    ) {
        @ColorInt val plate = MarkerColors.fullBorder(darkTheme).toArgb()

        canvas.drawCircle(cx, cy, radius, fillPaint().apply { color = plate })
        canvas.drawCircle(
            cx, cy, radius,
            strokePaint().apply {
                color = statusColor
                strokeWidth = dp(0.75f)
            },
        )

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = statusColor
            textSize = radius * 1.6f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        // Centre the glyph on the badge by its own metrics rather than its baseline.
        val metrics = text.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText("?", cx, baseline, text)
    }

    /**
     * Spec §3.1/§3.4: a suggestion is a brand-green hollow circle with a "+".
     * Never filled — it marks a place where a container is absent, not a container.
     */
    fun suggestionMarker(darkTheme: Boolean = false, selected: Boolean = false): Bitmap {
        val scale = if (selected) 1.4f else 1f
        val diameter = dp(14f) * scale
        val stroke = dp(1.75f) * scale
        val halo = if (selected) dp(3f) else 0f
        val pad = stroke + halo + dp(2f)

        val size = Math.ceil((diameter + pad * 2).toDouble()).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = size / 2f
        val cy = size / 2f
        val radius = diameter / 2f
        @ColorInt val color = MarkerColors.Suggestion.toArgb()

        if (selected) {
            canvas.drawCircle(
                cx, cy, radius + halo,
                fillPaint().apply {
                    this.color = color
                    alpha = 56
                },
            )
        }

        // Fill the disc with the map background tone so the "+" stays readable over dark tiles.
        canvas.drawCircle(
            cx, cy, radius,
            fillPaint().apply { this.color = MarkerColors.fullBorder(darkTheme).toArgb() },
        )

        val ring = strokePaint().apply {
            this.color = color
            strokeWidth = stroke
        }
        canvas.drawCircle(cx, cy, radius, ring)

        // The plus.
        val arm = radius * 0.46f
        val plus = strokePaint().apply {
            this.color = color
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx - arm, cy, cx + arm, cy, plus)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, plus)

        return bitmap
    }

    /**
     * Spec §3.4: cluster below zoom 14 — a soft circle with a count, coloured by the worst
     * status inside. The count itself is drawn by MapLibre as a text layer, so this is just
     * the disc.
     */
    fun clusterCircle(worstStatus: ContainerStatus, darkTheme: Boolean, diameterDp: Float): Bitmap {
        val diameter = dp(diameterDp)
        val ring = dp(2f)
        val pad = ring + dp(2f)
        val size = Math.ceil((diameter + pad * 2).toDouble()).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val radius = diameter / 2f

        canvas.drawCircle(
            centre, centre, radius,
            fillPaint().apply { color = statusColorInt(worstStatus, ContainerKind.BIG) },
        )
        canvas.drawCircle(
            centre, centre, radius,
            strokePaint().apply {
                color = MarkerColors.fullBorder(darkTheme).toArgb()
                strokeWidth = ring
            },
        )

        return bitmap
    }

    // -----------------------------------------------------------------------------------------

    private fun drawShape(canvas: Canvas, kind: ContainerKind, rect: RectF, paint: Paint) {
        when (kind) {
            ContainerKind.BIG -> {
                val r = rect.height() * 0.28f
                canvas.drawRoundRect(rect, r, r, paint)
            }
            ContainerKind.SMALL -> canvas.drawPath(roundedTriangle(rect), paint)
        }
    }

    /**
     * Rounded triangle pointing up. Corners are rounded by walking the three vertices and
     * cutting each with a quadratic — a plain Path with a CornerPathEffect would round the
     * dashed MISSING outline unevenly.
     */
    private fun roundedTriangle(rect: RectF): Path {
        val radius = rect.height() * 0.18f
        val apex = floatArrayOf(rect.centerX(), rect.top)
        val right = floatArrayOf(rect.right, rect.bottom)
        val left = floatArrayOf(rect.left, rect.bottom)
        val points = listOf(apex, right, left)

        return Path().apply {
            for (i in points.indices) {
                val current = points[i]
                val next = points[(i + 1) % points.size]
                val previous = points[(i + points.size - 1) % points.size]

                val toPrev = normalize(previous[0] - current[0], previous[1] - current[1])
                val toNext = normalize(next[0] - current[0], next[1] - current[1])

                val startX = current[0] + toPrev[0] * radius
                val startY = current[1] + toPrev[1] * radius
                val endX = current[0] + toNext[0] * radius
                val endY = current[1] + toNext[1] * radius

                if (i == 0) moveTo(startX, startY) else lineTo(startX, startY)
                quadTo(current[0], current[1], endX, endY)
            }
            close()
        }
    }

    private fun normalize(dx: Float, dy: Float): FloatArray {
        val length = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return if (length == 0f) floatArrayOf(0f, 0f) else floatArrayOf(dx / length, dy / length)
    }

    private fun inflate(rect: RectF, by: Float) =
        RectF(rect.left - by, rect.top - by, rect.right + by, rect.bottom + by)

    @ColorInt
    private fun recyclingDotColor(category: ContainerCategory): Int? = when (category) {
        ContainerCategory.GLASS -> MarkerColors.RecyclingGlass.toArgb()
        ContainerCategory.PAPER -> MarkerColors.RecyclingPaper.toArgb()
        ContainerCategory.PLASTIC -> MarkerColors.RecyclingPlastic.toArgb()
        ContainerCategory.GENERAL, ContainerCategory.MIXED_RECYCLING -> null
    }

    companion object {
        /** §3.4: unverified containers render at 85% opacity. */
        private const val UNVERIFIED_ALPHA = 217  // 0.85 * 255

        const val SUGGESTION_ID = "kanta-suggestion"

        /** Same mapping as the UI's `statusColor`, resolved to an Android colour int. */
        @ColorInt
        fun statusColorInt(status: ContainerStatus, kind: ContainerKind): Int = when (status) {
            ContainerStatus.OK -> when (kind) {
                ContainerKind.BIG -> MarkerColors.BigOk
                ContainerKind.SMALL -> MarkerColors.SmallOk
            }
            ContainerStatus.FULL -> MarkerColors.Full
            ContainerStatus.BROKEN -> MarkerColors.Broken
            ContainerStatus.DESTROYED -> MarkerColors.Destroyed
            ContainerStatus.MISSING -> MarkerColors.Missing
        }.toArgb()
    }
}
