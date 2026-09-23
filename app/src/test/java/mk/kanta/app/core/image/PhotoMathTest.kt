package mk.kanta.app.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §2 "Images" and §8 privacy — the rules the photo pipeline applies. */
class PhotoMathTest {

    @Test fun `a 4000x3000 photo becomes 1600x1200`() = assertEquals(1600 to 1200, PhotoMath.targetSize(4000, 3000))

    @Test fun `portrait keeps its orientation`() = assertEquals(1200 to 1600, PhotoMath.targetSize(3000, 4000))

    @Test fun `a small photo is never upscaled`() = assertEquals(800 to 600, PhotoMath.targetSize(800, 600))

    @Test fun `exactly 1600 is left alone`() = assertEquals(1600 to 900, PhotoMath.targetSize(1600, 900))

    @Test(expected = IllegalArgumentException::class)
    fun `an empty image is rejected`() { PhotoMath.targetSize(0, 100) }

    @Test fun `quality starts at 80 and never goes below the floor`() {
        val ladder = PhotoMath.qualityLadder()
        assertEquals(80, ladder.first())
        assertEquals(PhotoMath.MIN_QUALITY, ladder.last())
        assertTrue(ladder.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun `face box grows by the margin on every side`() {
        val grown = PhotoMath.expandFace(PixelRect(400, 400, 500, 500), 1600, 1200, margin = 0.35)
        assertEquals(PixelRect(365, 365, 535, 535), grown)
    }

    @Test fun `face box at the edge is clamped to the image`() {
        val grown = PhotoMath.expandFace(PixelRect(0, 0, 100, 100), 1600, 1200)
        assertEquals(0, grown.left)
        assertEquals(0, grown.top)
    }

    @Test fun `face box at the far corner is clamped too`() {
        val grown = PhotoMath.expandFace(PixelRect(1550, 1150, 1600, 1200), 1600, 1200)
        assertEquals(1600, grown.right)
        assertEquals(1200, grown.bottom)
    }

    @Test fun `blur shrinks a face to about eight cells across`() =
        assertEquals(20, PhotoMath.blurDownscaleFactor(160, 120))

    @Test fun `a tiny face still gets a real blur`() = assertEquals(2, PhotoMath.blurDownscaleFactor(6, 6))
}
