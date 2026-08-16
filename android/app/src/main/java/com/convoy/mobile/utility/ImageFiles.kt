package com.convoy.mobile.utility

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Turning a picked or captured image into something worth sending.
 *
 * Two jobs, both non-negotiable on this app's connection:
 *
 * COPY. The picker and the camera hand back a `content://` URI, not a file.
 * It is readable only through the ContentResolver, and only for as long as
 * the grant lasts, so the bytes have to be copied somewhere we own before
 * anything else can happen to them.
 *
 * SHRINK. A photo straight off a modern phone is eight to twelve megabytes
 * of detail nobody will ever look at — the group wants to see the flat tyre,
 * not read the sidewall serial number. Sending it raw means a minute of
 * uploading on a highway connection and a real chance of never finishing.
 * Sixteen hundred pixels is plenty to show what happened and lands around a
 * few hundred kilobytes.
 */
object ImageFiles {

    /** Longest edge, in pixels, of an image we are willing to upload. */
    private const val MAX_EDGE_PX = 1600

    /**
     * JPEG quality. 80 is the point where further reduction starts to show
     * on a photo of a road, and stops saving much.
     */
    private const val JPEG_QUALITY = 80

    private const val TAG = "ImageFiles"

    /**
     * Reads [uri], downscales it, and writes a JPEG into the cache.
     *
     * Returns null if the image cannot be read at all — a picker returning
     * a URI we have no grant for is common enough to be an expected outcome
     * rather than an exception.
     *
     * Blocking. Call it from Dispatchers.IO.
     */
    fun prepareForUpload(context: Context, uri: Uri, prefix: String): File? {
        // Block body, not an expression body: Kotlin forbids `return` inside
        // an expression-bodied function, and the early exits below are far
        // clearer than nesting the whole thing in elses.
        return try {
            // Two passes. The first reads only the header to learn the size,
            // which is what lets the second pass decode straight into a
            // smaller bitmap — decoding a 12-megapixel image at full size
            // just to shrink it is how an app runs out of memory on a cheap
            // phone.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Could not read the image's dimensions")
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // inSampleSize only halves, so the result is somewhere between
            // the target and twice it. This brings it the rest of the way.
            val scaled = scaleToFit(decoded)

            // Orientation lives in EXIF, not in the pixels. Skipping this is
            // why photos taken in portrait so often arrive on their side.
            val upright = applyExifRotation(context, uri, scaled)

            val file = File(context.cacheDir, "$prefix-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out ->
                upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            upright.recycle()

            file.takeIf { it.exists() && it.length() > 0 }
        } catch (e: OutOfMemoryError) {
            // Caught explicitly because it is an Error, not an Exception, so
            // the clause below would never see it — and a huge photo on a
            // low-memory phone is exactly how it happens.
            Log.e(TAG, "Ran out of memory decoding that image")
            null
        } catch (e: Exception) {
            // A URI we have no grant for, or a file that is not really an
            // image. Both are ordinary outcomes of a system picker.
            Log.e(TAG, "Could not prepare that image", e)
            null
        }
    }

    /** The largest power-of-two shrink that still leaves us above target. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE_PX) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE_PX) return source

        val ratio = MAX_EDGE_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            // A missing or malformed EXIF block is not a reason to lose the
            // photo — it just means we cannot straighten it.
            Log.w(TAG, "No usable EXIF orientation: ${e.message}")
            0f
        }

        if (degrees == 0f) return bitmap

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
