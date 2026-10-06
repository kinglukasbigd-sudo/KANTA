package mk.kanta.app.core.designsystem.marker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.designsystem.MarkerColors

/** One entry in the marker image registry. */
data class MarkerVariant(
    val kind: ContainerKind,
    val status: ContainerStatus,
    val category: ContainerCategory,
    val selected: Boolean,
    val unverified: Boolean,
    val badge: Boolean,
)

/**
 * Draws the map markers from KANTA_SPEC.md §3.4 as [Bitmap]s, to be registered once as MapLibre
 * style images and then referenced by id from a symbol layer.
 *
 * Spec §3.4 is explicit that markers must NOT be Android views — with 10,000+ containers the only
 * thing that stays at 60fps is a GeoJSON source plus style images. So this factory runs once at
 * style load, produces one bitmap per (kind × status × category) combination that can appear, and
 * is never touched again while panning.
 *
 * The bin marks themselves (round universal bin, tall small can, wide big container) come from
 * [BinMarkerPainter]; this class adds what only the map needs: the ring that keeps a marker
 * legible on any tile, the selection halo, the full and unverified treatments, the recycling dot
 * and the "?" badge.
 *
 * Sizes are given in dp at zoom 16 (the spec's reference zoom); MapLibre scales them per zoom.
 */
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

    /** Every marker image the map can need. Generated once at style load. */
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
                    val categories = if (showsCategory(kind, status)) {
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
     * §3.1: FULL renders 15% larger with a 2dp ring. MISSING is hollow and dashed. Unverified
     * keeps its fill at 85% with a dashed ring instead of a solid one. Every other marker sits
     * on a solid 1.25dp ring (white on light tiles, ivory on dark), with a soft ink hairline
     * outside it on light tiles so gold and grey hold their edge.
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
        // §3.4: "filled = it exists, hollow = it's gone". MISSING wins over UNVERIFIED.
        val showUnverified = unverified && !isMissing

        // §3.1: full markers render 15% larger. §3.4: selected scales 1.4x with a brand halo.
        val scale = (if (isFull) 1.15f else 1f) * (if (selected) 1.4f else 1f)

        val (bodyW, bodyH) = BinMarkerPainter.bodySizeDp(kind)
        val shapeW = dp(bodyW) * scale
        val shapeH = dp(bodyH) * scale

        val ring = if (isFull) dp(2f) else dp(1.25f)
        val hairline = if (!darkTheme && !isMissing && !showUnverified) dp(0.75f) else 0f
        val halo = if (selected) dp(3f) else 0f
        val missingStroke = if (isMissing) dp(1.5f) else 0f
        // The "?" badge overhangs the top-right corner, the recycling dot the bottom-left.
        val badgeRadius = if (showUnverified && badge) dp(3.5f) * scale else 0f
        val dot = recyclingDotColor(category).takeIf { showsCategory(kind, status) }
        val dotRadius = if (dot != null) dp(2.6f) * scale else 0f
        // Pad for whichever outer decoration is widest, plus a pixel of antialias headroom.
        val pad = maxOf(ring + hairline, halo, missingStroke, badgeRadius, dotRadius) + dp(2f)

        val width = Math.ceil((shapeW + pad * 2).toDouble()).toInt().coerceAtLeast(1)
        val height = Math.ceil((shapeH + pad * 2).toDouble()).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(pad, pad, pad + shapeW, pad + shapeH)

        @ColorInt val color = statusColorInt(status, kind)
        @ColorInt val ringColor = MarkerColors.ring(darkTheme).toArgb()

        if (selected) {
            // §3.4: soft halo in brand colour behind the selected marker.
            canvas.drawPath(
                BinMarkerPainter.bodyPath(kind, inflate(rect, ring + halo)),
                fillPaint().apply {
                    this.color = MarkerColors.Suggestion.toArgb()
                    alpha = 64
                },
            )
        }

        if (isMissing) {
            // §3.1: hollow, dashed grey outline, with the glyph faint inside so the size still
            // reads on a container that is gone.
            canvas.drawPath(
                BinMarkerPainter.bodyPath(kind, rect),
                strokePaint().apply {
                    this.color = color
                    strokeWidth = missingStroke
                    pathEffect = DashPathEffect(floatArrayOf(dp(2.5f), dp(2f)), 0f)
                },
            )
            BinMarkerPainter.drawGlyph(canvas, kind, rect, color, knockout = null, alpha = 150)
        } else {
            if (hairline > 0f) {
                canvas.drawPath(
                    BinMarkerPainter.bodyPath(kind, inflate(rect, ring + hairline)),
                    fillPaint().apply { this.color = MarkerColors.Shadow.toArgb() },
                )
            }

            if (showUnverified) {
                // §3.4: "not yet confirmed" reads as a dashed ring, sitting entirely outside the
                // fill so the body stays whole rather than looking perforated.
                canvas.drawPath(
                    BinMarkerPainter.bodyPath(kind, inflate(rect, ring / 2f)),
                    strokePaint().apply {
                        this.color = ringColor
                        strokeWidth = ring
                        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(1.5f)), 0f)
                    },
                )
            } else {
                canvas.drawPath(
                    BinMarkerPainter.bodyPath(kind, inflate(rect, ring)),
                    fillPaint().apply { this.color = ringColor },
                )
            }

            // §3.4: an unverified container keeps its normal fill, at 85% opacity.
            val bodyAlpha = if (showUnverified) UNVERIFIED_ALPHA else 255
            canvas.drawPath(
                BinMarkerPainter.bodyPath(kind, rect),
                fillPaint().apply {
                    this.color = color
                    alpha = bodyAlpha
                },
            )
            BinMarkerPainter.drawGlyph(
                canvas, kind, rect,
                glyph = MarkerColors.glyph(status, kind).toArgb(),
                knockout = color,
                alpha = bodyAlpha,
            )

            // §3.4: recycling material dot, on the bottom-left corner of an otherwise-fine
            // container, ringed so glass green never vanishes against a green body.
            if (dot != null) {
                val cx = rect.left + dotRadius * 0.6f
                val cy = rect.bottom - dotRadius * 0.6f
                canvas.drawCircle(cx, cy, dotRadius, fillPaint().apply { this.color = ringColor })
                canvas.drawCircle(cx, cy, dotRadius * 0.68f, fillPaint().apply { this.color = dot })
            }
        }

        if (showUnverified && badge) {
            drawQuestionBadge(
                canvas,
                cx = rect.right - badgeRadius * 0.3f,
                cy = rect.top + badgeRadius * 0.3f,
                radius = badgeRadius,
                statusColor = color,
                darkTheme = darkTheme,
            )
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
        @ColorInt val plate = MarkerColors.ring(darkTheme).toArgb()

        canvas.drawCircle(cx, cy, radius, fillPaint().apply { color = plate })
        canvas.drawCircle(
            cx, cy, radius,
            strokePaint().apply {
                color = statusColor
                strokeWidth = dp(0.75f)
            },
        )

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Charcoal on the ivory plate whatever the status, so the "?" never turns gold-on-white.
            color = MarkerColors.GlyphInk.toArgb()
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
                    alpha = 64
                },
            )
        }

        // Fill the disc with the ring tone so the "+" stays readable over dark tiles.
        canvas.drawCircle(
            cx, cy, radius,
            fillPaint().apply { this.color = MarkerColors.ring(darkTheme).toArgb() },
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
                color = MarkerColors.ring(darkTheme).toArgb()
                strokeWidth = ring
            },
        )

        return bitmap
    }

    // -----------------------------------------------------------------------------------------

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

        /**
         * The recycling dot (§3.4) only means something on a container that is otherwise fine,
         * and never on a small can. A bin of unknown size keeps it: a glass bank is still a
         * glass bank while nobody has said how big it is.
         */
        fun showsCategory(kind: ContainerKind, status: ContainerStatus): Boolean =
            kind != ContainerKind.SMALL && status == ContainerStatus.OK

        /** Same mapping as the UI's `statusColor`, resolved to an Android colour int. */
        @ColorInt
        fun statusColorInt(status: ContainerStatus, kind: ContainerKind): Int =
            MarkerColors.status(status, kind).toArgb()
    }
}
