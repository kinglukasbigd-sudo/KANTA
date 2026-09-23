package mk.kanta.app.core.image

import kotlin.math.max
import kotlin.math.roundToInt

/** A pixel rectangle, free of android.graphics so the rules below are JVM-testable. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The pure rules behind photo processing (spec §2 "Images", §8 privacy).
 */
object PhotoMath {

    /** §2: "max 1600px long edge". */
    const val MAX_LONG_EDGE = 1_600

    /** §2: "JPEG ~80%". */
    const val START_QUALITY = 80

    /** §2: "target ≤ 300 KB". Quality steps down until it fits, but never below this. */
    const val TARGET_BYTES = 300 * 1_024
    const val MIN_QUALITY = 55
    const val QUALITY_STEP = 8

    /** Output size keeping aspect ratio; never upscales a small image. */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** The JPEG qualities to try, best first: 80, 72, 64, 56, then the floor. */
    fun qualityLadder(): List<Int> =
        generateSequence(START_QUALITY) { it - QUALITY_STEP }
            .takeWhile { it > MIN_QUALITY }
            .toList() + MIN_QUALITY

    /**
     * Grow a detected face box before blurring it (§8 "faces blurred").
     *
     * ML Kit's box hugs the face; hair, ears and the edge of a jaw sit outside it
     * and are still identifying. 35% on every side is generous on purpose: a
     * slightly larger blur costs nothing, a recognisable ear costs someone their
     * privacy. The result is clamped to the image.
     */
    fun expandFace(face: PixelRect, imageWidth: Int, imageHeight: Int, margin: Double = 0.35): PixelRect {
        val dx = (face.width * margin).roundToInt()
        val dy = (face.height * margin).roundToInt()
        return PixelRect(
            left = (face.left - dx).coerceIn(0, imageWidth),
            top = (face.top - dy).coerceIn(0, imageHeight),
            right = (face.right + dx).coerceIn(0, imageWidth),
            bottom = (face.bottom + dy).coerceIn(0, imageHeight),
        )
    }

    /**
     * How far to shrink a face region before scaling it back up. Shrinking to
     * about 8 px across and stretching back is an irreversible blur — unlike a
     * light Gaussian, it leaves nothing for a "deblur" tool to recover.
     */
    fun blurDownscaleFactor(regionWidth: Int, regionHeight: Int, targetCells: Int = 8): Int =
        (max(regionWidth, regionHeight) / targetCells).coerceAtLeast(2)
}
