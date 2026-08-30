package com.example.capstone.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a worksheet photo for on-device inference.
 *
 * Pipeline: decode -> apply EXIF orientation -> downscale to at most
 * [MAX_LONG_EDGE] on the long edge -> re-encode as PNG.
 *
 * PNG rather than JPEG on purpose: JPEG ringing artifacts destroy thin pen
 * strokes, which is exactly the signal the model needs to read handwriting.
 */
object ImagePrep {

    /** Maximum length of the longer edge handed to the model. */
    const val MAX_LONG_EDGE = 1024

    private const val TAG = "ImagePrep"

    /**
     * Converts arbitrary image [source] bytes (JPEG from camera or gallery, PNG,
     * WebP, ...) into a correctly oriented PNG no larger than [maxLongEdge] on
     * its long edge.
     *
     * @return the PNG bytes, or null if [source] is not a decodable image or
     *   memory ran out. Never throws for bad input.
     */
    fun toGradingPng(source: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): ByteArray? {
        if (source.isEmpty()) {
            Log.w(TAG, "empty input bytes")
            return null
        }

        // Pass 1: bounds only, so a 12MP photo never gets fully allocated.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "undecodable image data (w=${bounds.outWidth} h=${bounds.outHeight})")
            return null
        }

        // Pass 2: decode subsampled. max(w, h) is unchanged by rotation, so it is
        // safe to pick the sample size before the EXIF transform is applied.
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var working: Bitmap = try {
            BitmapFactory.decodeByteArray(source, 0, source.size, decodeOptions)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding image", e)
            null
        } ?: run {
            Log.w(TAG, "decode returned no bitmap")
            return null
        }

        return try {
            // Orientation BEFORE resizing, so the resize sees the true edges.
            working = applyOrientation(working, exifMatrix(source))
            working = scaleToLongEdge(working, maxLongEdge)

            ByteArrayOutputStream().use { out ->
                // PNG is lossless; the quality argument is ignored.
                if (!working.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    Log.w(TAG, "PNG compress failed")
                    return null
                }
                val png = out.toByteArray()
                Log.i(
                    TAG,
                    "prepared PNG ${working.width}x${working.height} " +
                        "inBytes=${source.size} outBytes=${png.size} sampleSize=${decodeOptions.inSampleSize}"
                )
                png
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory preparing image", e)
            null
        } finally {
            // Intermediates are recycled inside the helpers; this frees the last one.
            working.recycle()
        }
    }

    /**
     * Reads the EXIF orientation tag and returns the matrix that corrects it, or
     * null when the image is already upright, carries no EXIF (e.g. PNG), or the
     * tag cannot be read.
     */
    private fun exifMatrix(source: ByteArray): Matrix? {
        val orientation = try {
            ByteArrayInputStream(source).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (t: Throwable) {
            // No EXIF, truncated EXIF, unsupported container: not an error, just
            // means there is nothing to correct.
            Log.i(TAG, "no readable EXIF orientation (${t.javaClass.simpleName})")
            return null
        }

        val transform = orientationTransform(orientation) ?: return null
        Log.i(TAG, "applying EXIF orientation=$orientation ($transform)")
        return Matrix().apply {
            // Rotate first, then flip: the same order the EXIF spec describes.
            if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontal) postScale(-1f, 1f)
            if (transform.flipVertical) postScale(1f, -1f)
        }
    }

    /**
     * The transform an image with the given EXIF orientation tag needs to become
     * upright, or null when there is nothing to do (normal, undefined, or a value
     * outside the eight the spec defines).
     *
     * Pure lookup, deliberately separate from the [Matrix] it feeds, so the
     * mapping can be tested without a graphics stack.
     */
    internal fun orientationTransform(orientation: Int): OrientationTransform? = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> OrientationTransform(90)
        ExifInterface.ORIENTATION_ROTATE_180 -> OrientationTransform(180)
        ExifInterface.ORIENTATION_ROTATE_270 -> OrientationTransform(270)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationTransform(0, flipHorizontal = true)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationTransform(0, flipVertical = true)
        ExifInterface.ORIENTATION_TRANSPOSE -> OrientationTransform(90, flipHorizontal = true)
        ExifInterface.ORIENTATION_TRANSVERSE -> OrientationTransform(270, flipHorizontal = true)
        // ORIENTATION_NORMAL, ORIENTATION_UNDEFINED, anything unexpected.
        else -> null
    }

    /** Clockwise rotation in degrees, then optional mirroring. */
    internal data class OrientationTransform(
        val rotationDegrees: Int,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false
    )

    /** Applies [matrix], recycling [bitmap] when a new one is produced. */
    private fun applyOrientation(bitmap: Bitmap, matrix: Matrix?): Bitmap {
        if (matrix == null) return bitmap
        val transformed = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory rotating image; using unrotated bitmap", e)
            return bitmap
        }
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    /**
     * Scales so the long edge is exactly [maxLongEdge], preserving aspect ratio.
     * Images already within the limit are returned untouched — never upscaled.
     */
    private fun scaleToLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge) return bitmap

        val ratio = maxLongEdge.toDouble() / longEdge
        val width = max(1, (bitmap.width * ratio).roundToInt())
        val height = max(1, (bitmap.height * ratio).roundToInt())

        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory scaling image; using unscaled bitmap", e)
            return bitmap
        }
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Largest power-of-two subsample that keeps the long edge at or above
     * [maxLongEdge], so the exact scale afterwards is always a downscale.
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sampleSize = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            longEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }
}
