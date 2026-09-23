package mk.kanta.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import mk.kanta.app.core.data.di.DefaultDispatcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ProcessedPhoto(
    val file: File,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val facesBlurred: Int,
)

/** Thrown when a photo cannot be made safe to publish. Never silently skipped (§8). */
class PhotoPrivacyException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface PhotoProcessor {
    /** Turns a raw camera file into a publishable one. Deletes [raw] on success. */
    suspend fun process(raw: File): ProcessedPhoto
}

/**
 * Makes a photo safe and small enough to publish (spec §2 "Images", §8 privacy):
 *
 *  1. read the camera's orientation tag and bake the rotation into the pixels —
 *     after step 5 there is no tag left to read, so a portrait shot would
 *     otherwise publish sideways;
 *  2. resize so the long edge is at most 1600 px;
 *  3. find faces with ML Kit's bundled (on-device, no network) detector;
 *  4. blur each face, generously padded, beyond recovery;
 *  5. re-encode as JPEG, stepping quality down from 80 until ≤ 300 KB.
 *
 * Step 5 is also what strips EXIF: `Bitmap.compress` writes pixels only. GPS
 * position, device model, timestamps — none of it survives, by construction
 * rather than by trying to delete tags one at a time.
 *
 * If face detection itself fails, the photo is REJECTED, not published unblurred.
 * "We could not check" must never quietly become "there was nothing to blur".
 */
@Singleton
class AndroidPhotoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : PhotoProcessor {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Accuracy over speed: this runs once per report, in the background.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                // Small faces in the distance are still faces.
                .setMinFaceSize(0.05f)
                .build(),
        )
    }

    private val outputDir: File
        // filesDir, not cacheDir: the offline queue (§4.3) needs the photo to
        // survive until it is uploaded, and the system may clear the cache.
        get() = File(context.filesDir, "report_photos").apply { mkdirs() }

    override suspend fun process(raw: File): ProcessedPhoto = withContext(cpu) {
        val upright = decodeUpright(raw)
        val (w, h) = PhotoMath.targetSize(upright.width, upright.height)
        val scaled = if (w == upright.width && h == upright.height) {
            upright
        } else {
            Bitmap.createScaledBitmap(upright, w, h, true).also { upright.recycle() }
        }

        val faces = detectFaces(scaled)
        val working = scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
        faces.forEach { blurRegion(working, PhotoMath.expandFace(it, working.width, working.height)) }

        val bytes = encodeWithinBudget(working)
        val out = File(outputDir, "${UUID.randomUUID()}.jpg")
        out.writeBytes(bytes)
        working.recycle()
        raw.delete()

        ProcessedPhoto(out, w, h, bytes.size, faces.size)
    }

    // -----------------------------------------------------------------------------------------

    private fun decodeUpright(raw: File): Bitmap {
        // Decode at a power-of-two reduction first, so a 50 MP sensor does not
        // allocate 200 MB just to be shrunk to 1600 px.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PhotoPrivacyException("unreadable photo")
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PhotoMath.MAX_LONG_EDGE) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw PhotoPrivacyException("unreadable photo")

        val degrees = when (
            ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<PixelRect> = try {
        detector.process(InputImage.fromBitmap(bitmap, 0)).await().map { face ->
            val box = face.boundingBox
            PixelRect(box.left, box.top, box.right, box.bottom)
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        throw PhotoPrivacyException("face check failed", t)
    }

    /**
     * Shrink the region to ~8 px across and stretch it back. Unlike a light
     * Gaussian, nothing recoverable survives.
     */
    private fun blurRegion(bitmap: Bitmap, region: PixelRect) {
        if (region.width < 2 || region.height < 2) return
        val factor = PhotoMath.blurDownscaleFactor(region.width, region.height)
        val crop = Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height)
        val tiny = Bitmap.createScaledBitmap(
            crop,
            (region.width / factor).coerceAtLeast(1),
            (region.height / factor).coerceAtLeast(1),
            true,
        )
        val smeared = Bitmap.createScaledBitmap(tiny, region.width, region.height, true)
        Canvas(bitmap).drawBitmap(
            smeared,
            null,
            Rect(region.left, region.top, region.right, region.bottom),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        crop.recycle()
        tiny.recycle()
        smeared.recycle()
    }

    private fun encodeWithinBudget(bitmap: Bitmap): ByteArray {
        var last = ByteArray(0)
        for (quality in PhotoMath.qualityLadder()) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            last = out.toByteArray()
            if (last.size <= PhotoMath.TARGET_BYTES) return last
        }
        // A very busy scene may still be over budget at the floor quality. The
        // 1 MB bucket limit (§6) is the hard stop; 300 KB is a target, so ship it.
        return last
    }
}
