package com.convoy.mobile.repository

import android.util.Log
import com.convoy.mobile.dataModel.media.ConfirmRequest
import com.convoy.mobile.dataModel.media.SignatureRequest
import com.convoy.mobile.dataModel.media.UploadedMedia
import com.convoy.mobile.interfaces.MediaInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three-step upload.
 *
 *   1. ask our server to SIGN a folder-scoped upload
 *   2. send the bytes DIRECTLY to Cloudinary
 *   3. ask our server to VERIFY what landed
 *
 * Step 2 skipping our server is the point: a voice note on a highway
 * connection has enough to contend with without an extra hop, and audio
 * bytes have no business consuming our bandwidth. Step 3 is the security
 * boundary — the publicId a client reports is a claim until the server
 * confirms the asset exists in the right folder.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val mediaApi: MediaInterface,
) {

    // Its own client, with a long write timeout. The shared one is tuned for
    // small JSON calls, and a clip over a weak signal takes much longer than
    // any API request should be allowed to.
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads a file and returns the verified media object.
     *
     * `resourceType` is "video" for audio — Cloudinary routes audio through
     * its video pipeline. Surprising, but it is their API, not our choice.
     */
    suspend fun upload(
        tripId: String,
        file: File,
        resourceType: String = "video",
    ): NetworkResult<UploadedMedia> {

        val signature = when (
            val r = safeApiCall { mediaApi.getSignature(tripId, SignatureRequest(resourceType)) }
        ) {
            is NetworkResult.Success -> r.data.data
            is NetworkResult.Error -> return r
            NetworkResult.Loading -> return NetworkResult.Loading
        }

        if (signature.maxBytes > 0 && file.length() > signature.maxBytes) {
            return NetworkResult.Error("That clip is too long to send.")
        }

        // Dispatchers.IO is not a nicety here, it is the whole reason uploads
        // failed. This is called from viewModelScope, which runs on the main
        // thread, and OkHttp's execute() BLOCKS. Android answers a blocking
        // network call on the main thread with NetworkOnMainThreadException —
        // caught below and reported as "check your signal", which sent
        // everyone looking at their bars for a bug that was never about the
        // network.
        val publicId = try {
            withContext(Dispatchers.IO) {
                uploadDirect(signature.uploadUrl, signature, file)
            }
        } catch (e: CancellationException) {
            // Never swallowed: the screen was left or the ViewModel cleared,
            // and turning that into a user-facing upload error is both wrong
            // and a break in structured concurrency.
            throw e
        } catch (e: IOException) {
            // The one case that genuinely IS the signal.
            Log.e(TAG, "Direct upload failed: ${e.message}")
            return NetworkResult.Error("Couldn't upload that — check your signal.")
        } catch (e: Exception) {
            // Anything else is our bug, not the user's connection. Saying
            // "check your signal" for a programming error is how the real
            // cause stayed hidden.
            Log.e(TAG, "Direct upload failed", e)
            return NetworkResult.Error("Couldn't send that voice note. Try again.")
        } ?: return NetworkResult.Error("The upload didn't complete.")

        return when (
            val r = safeApiCall { mediaApi.confirm(tripId, ConfirmRequest(publicId, resourceType)) }
        ) {
            is NetworkResult.Success ->
                r.data.data.media?.let { NetworkResult.Success(it) }
                    ?: NetworkResult.Error("The upload couldn't be verified.")
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /** Straight to Cloudinary, using the signed parameters. Returns publicId. */
    private fun uploadDirect(
        url: String,
        signature: com.convoy.mobile.dataModel.media.UploadSignature,
        file: File,
    ): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_key", signature.apiKey)
            .addFormDataPart("timestamp", signature.timestamp.toString())
            .addFormDataPart("signature", signature.signature)
            // Must match what the server signed, byte for byte, or
            // Cloudinary rejects it — which is exactly the protection that
            // stops a client writing into another trip's folder.
            .addFormDataPart("folder", signature.folder)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaTypeOrNull()),
            )
            .build()

        uploadClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                Log.e(TAG, "Cloudinary ${res.code}: ${text.take(200)}")
                return null
            }
            return JSONObject(text).optString("public_id").ifBlank { null }
        }
    }

    private companion object {
        const val TAG = "MediaRepository"
    }
}
