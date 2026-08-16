package com.convoy.mobile.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.convoy.mobile.dataModel.auth.User
import com.convoy.mobile.dataModel.media.ConfirmRequest
import com.convoy.mobile.interfaces.UserInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional profile photo.
 *
 * Entirely optional by design — a convoy reads perfectly well with initials
 * on a coloured disc. But a real face makes a roster of six cars far quicker
 * to scan, which is worth something at a glance while driving.
 *
 * Same three steps as every other upload: sign, upload direct to Cloudinary,
 * verify. The verify step is what stops a client attaching somebody else's
 * image by claiming its publicId.
 */
@Singleton
class AvatarRepository @Inject constructor(
    private val userApi: UserInterface,
) {

    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun upload(context: Context, uri: Uri): NetworkResult<User> {
        // The picker hands back a content:// URI, which is not a file we can
        // read directly. Copying to our own cache is the only reliable way
        // to get bytes across every OEM's picker implementation.
        //
        // On Dispatchers.IO because this is called from viewModelScope — the
        // main thread — and reading a picked file there is a StrictMode
        // violation on the way to being a dropped frame.
        val file = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            ?: return NetworkResult.Error("Couldn't read that image.")

        val signature = when (val r = safeApiCall { userApi.getAvatarSignature() }) {
            is NetworkResult.Success -> r.data.data
            is NetworkResult.Error -> { file.delete(); return r }
            NetworkResult.Loading -> { file.delete(); return NetworkResult.Loading }
        }

        if (signature.maxBytes > 0 && file.length() > signature.maxBytes) {
            file.delete()
            return NetworkResult.Error("That photo is too large.")
        }

        // Same reason as MediaRepository: execute() blocks, viewModelScope is
        // the main thread, and Android throws NetworkOnMainThreadException for
        // that combination — which this catch reported as a signal problem.
        val publicId = try {
            withContext(Dispatchers.IO) {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("api_key", signature.apiKey)
                    .addFormDataPart("timestamp", signature.timestamp.toString())
                    .addFormDataPart("signature", signature.signature)
                    .addFormDataPart("folder", signature.folder)
                    .addFormDataPart(
                        "file",
                        file.name,
                        file.asRequestBody("image/*".toMediaTypeOrNull()),
                    )
                    .build()

                uploadClient.newCall(Request.Builder().url(signature.uploadUrl).post(body).build())
                    .execute().use { res ->
                        val text = res.body?.string().orEmpty()
                        if (!res.isSuccessful) {
                            Log.e(TAG, "Cloudinary ${res.code}: ${text.take(180)}")
                            null
                        } else {
                            JSONObject(text).optString("public_id").ifBlank { null }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Avatar upload failed", e)
            null
        } finally {
            file.delete()
        } ?: return NetworkResult.Error("Couldn't upload that photo — check your signal.")

        return when (
            val r = safeApiCall { userApi.setAvatar(ConfirmRequest(publicId, "image")) }
        ) {
            is NetworkResult.Success ->
                r.data.data?.user?.let { NetworkResult.Success(it) }
                    ?: NetworkResult.Error("The photo couldn't be saved.")
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun remove(): NetworkResult<User> =
        when (val r = safeApiCall { userApi.removeAvatar() }) {
            is NetworkResult.Success ->
                r.data.data?.user?.let { NetworkResult.Success(it) }
                    ?: NetworkResult.Error("Couldn't remove the photo.")
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }

    private fun copyToCache(context: Context, uri: Uri): File? = try {
        val file = File(context.cacheDir, "avatar-${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.takeIf { it.exists() && it.length() > 0 }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read picked image: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "AvatarRepository"
    }
}
