package mk.kanta.app.core.designsystem.marker

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.ColorInt
import mk.kanta.app.core.data.model.ContainerKind
import kotlin.math.min

/**
 * The three bin marks (KANTA_SPEC.md §3.4), drawn in one place and used in two: the map's marker
 * bitmaps ([MarkerBitmapFactory]) and the in-app status dots (`KantaStatusDot`), so a row in a
 * list and its marker on the map are always the same picture.
 *
 *  - **Unknown size** — a round badge with the universal bin pictogram (lid, handle, ribbed
 *    body). Every bin starts here until someone standing next to it says Small or Big.
 *  - **Small can** — a tall tile with a slim street can.
 *  - **Big container** — a wide tile with a wheeled 1,100-litre container.
 *
 * Shape tells size at a glance (round / tall / wide), even where the glyph is too small to read;
 * colour stays free to tell status. Plain `android.graphics` so it runs both at style load and
 * inside a Compose `drawIntoCanvas`.
 */
object BinMarkerPainter {

    /** Body size at zoom 16, in dp, as width to height. */
    fun bodySizeDp(kind: ContainerKind): Pair<Float, Float> = when (kind) {
        ContainerKind.UNKNOWN -> 14f to 14f
        ContainerKind.SMALL -> 11f to 15f
        ContainerKind.BIG -> 18f to 13f
    }

    /** Aspect ratio (width / height) of the body, for fitting it into a box. */
    fun aspect(kind: ContainerKind): Float = bodySizeDp(kind).let { (w, h) -> w / h }

    /** The largest body of this kind that fits [box], centred in it. */
    fun fit(kind: ContainerKind, box: RectF): RectF {
        val aspect = aspect(kind)
        val w: Float
        val h: Float
        if (box.width() / box.height() > aspect) {
            h = box.height()
            w = h * aspect
        } else {
            w = box.width()
            h = w / aspect
        }
        val left = box.centerX() - w / 2f
        val top = box.centerY() - h / 2f
        return RectF(left, top, left + w, top + h)
    }

    /** The outline of the body: a circle, or a tile with softly rounded corners. */
    fun bodyPath(kind: ContainerKind, rect: RectF): Path = Path().apply {
        when (kind) {
            ContainerKind.UNKNOWN -> addOval(rect, Path.Direction.CW)
            ContainerKind.SMALL, ContainerKind.BIG -> {
                val r = min(rect.width(), rect.height()) * CORNER
                addRoundRect(rect, r, r, Path.Direction.CW)
            }
        }
    }

    /**
     * The pictogram inside a body of [rect]. [knockout] is the body's own colour, used to cut the
     * ribs and bands that make the glyph read as a bin; null on a hollow body, which has no fill
     * to cut into.
     */
    fun drawGlyph(
        canvas: Canvas,
        kind: ContainerKind,
        rect: RectF,
        @ColorInt glyph: Int,
        @ColorInt knockout: Int?,
        alpha: Int = 255,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = glyph
            this.alpha = alpha
        }
        val cut = knockout?.let { colour ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                color = colour
                this.alpha = alpha
            }
        }
        when (kind) {
            ContainerKind.UNKNOWN -> universalBin(canvas, rect, fill, cut)
            ContainerKind.SMALL -> streetCan(canvas, rect, fill, cut)
            ContainerKind.BIG -> wheeledContainer(canvas, rect, fill, cut)
        }
    }

    /** 🗑 — the bin everyone recognises: handle, lid, tapered body with two ribs. */
    private fun universalBin(canvas: Canvas, rect: RectF, fill: Paint, cut: Paint?) {
        val s = min(rect.width(), rect.height()) * 0.54f
        val left = rect.centerX() - s / 2f
        val top = rect.centerY() - s / 2f - s * 0.02f

        canvas.drawRoundRect(
            RectF(left + 0.36f * s, top, left + 0.64f * s, top + 0.14f * s),
            0.05f * s, 0.05f * s, fill,
        )
        canvas.drawRoundRect(
            RectF(left, top + 0.12f * s, left + s, top + 0.24f * s),
            0.06f * s, 0.06f * s, fill,
        )
        val body = Path().apply {
            moveTo(left + 0.1f * s, top + 0.3f * s)
            lineTo(left + 0.9f * s, top + 0.3f * s)
            lineTo(left + 0.8f * s, top + s)
            lineTo(left + 0.2f * s, top + s)
            close()
        }
        canvas.drawPath(body, fill.withCorners(0.08f * s))

        cut?.let {
            it.strokeWidth = 0.08f * s
            canvas.drawLine(left + 0.4f * s, top + 0.44f * s, left + 0.42f * s, top + 0.86f * s, it)
            canvas.drawLine(left + 0.6f * s, top + 0.44f * s, left + 0.58f * s, top + 0.86f * s, it)
        }
    }

    /** A slim street can: domed lid over a straight body with one band. */
    private fun streetCan(canvas: Canvas, rect: RectF, fill: Paint, cut: Paint?) {
        val gw = rect.width() * 0.5f
        val gh = rect.height() * 0.62f
        val left = rect.centerX() - gw / 2f
        val top = rect.centerY() - gh / 2f

        canvas.drawRoundRect(
            RectF(left - 0.05f * gw, top, left + 1.05f * gw, top + 0.24f * gh),
            0.12f * gh, 0.12f * gh, fill,
        )
        canvas.drawRoundRect(
            RectF(left + 0.06f * gw, top + 0.3f * gh, left + 0.94f * gw, top + gh),
            0.14f * gw, 0.14f * gw, fill,
        )

        cut?.let {
            it.strokeWidth = 0.09f * gh
            canvas.drawLine(left + 0.26f * gw, top + 0.56f * gh, left + 0.74f * gw, top + 0.56f * gh, it)
        }
    }

    /**
     * A 1,100-litre container: a heavy lid over a near-square body with two ribs, on two small
     * wheels. The sides barely taper — any more and it reads as a shopping trolley.
     */
    private fun wheeledContainer(canvas: Canvas, rect: RectF, fill: Paint, cut: Paint?) {
        val gw = rect.width() * 0.62f
        val gh = rect.height() * 0.64f
        val left = rect.centerX() - gw / 2f
        val top = rect.centerY() - gh / 2f - rect.height() * 0.03f

        canvas.drawRoundRect(
            RectF(left - 0.04f * gw, top, left + 1.04f * gw, top + 0.2f * gh),
            0.08f * gh, 0.08f * gh, fill,
        )
        val body = Path().apply {
            moveTo(left + 0.02f * gw, top + 0.26f * gh)
            lineTo(left + 0.98f * gw, top + 0.26f * gh)
            lineTo(left + 0.95f * gw, top + 0.88f * gh)
            lineTo(left + 0.05f * gw, top + 0.88f * gh)
            close()
        }
        canvas.drawPath(body, fill.withCorners(0.06f * gh))

        val wheel = 0.1f * gh
        canvas.drawCircle(left + 0.18f * gw, top + 0.96f * gh, wheel, fill)
        canvas.drawCircle(left + 0.82f * gw, top + 0.96f * gh, wheel, fill)

        cut?.let {
            it.strokeWidth = 0.08f * gw
            canvas.drawLine(left + 0.36f * gw, top + 0.4f * gh, left + 0.36f * gw, top + 0.76f * gh, it)
            canvas.drawLine(left + 0.64f * gw, top + 0.4f * gh, left + 0.64f * gw, top + 0.76f * gh, it)
        }
    }

    private fun Paint.withCorners(radius: Float) = Paint(this).apply { pathEffect = CornerPathEffect(radius) }

    /** Tile corner radius as a share of the shorter side. */
    private const val CORNER = 0.3f
}
