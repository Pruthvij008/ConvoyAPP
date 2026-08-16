package com.convoy.mobile.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.HttpException
import java.io.IOException

/**
 * What a repository hands back to a ViewModel.
 *
 * Retrofit throws on a non-2xx response, which would otherwise mean a
 * try/catch in every ViewModel method. Wrapping it here means the ViewModel
 * gets one value to branch on, and the error already carries a message
 * written for the user rather than a stack trace.
 */
sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val code: Int? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()

    val isSuccess get() = this is Success
}

/** The backend's error shape: { status: "fail", message: "..." }. */
private data class ApiErrorBody(
    @SerializedName("status") val status: String?,
    @SerializedName("message") val message: String?,
)

/**
 * Runs a network call and converts anything thrown into a readable Error.
 *
 * The messages the backend sends are already user-facing ("Sneha isn't in a
 * vehicle yet", "That invite is not valid"), so they are surfaced as-is
 * rather than replaced with something generic.
 */
suspend fun <T> safeApiCall(block: suspend () -> T): NetworkResult<T> {
    return try {
        NetworkResult.Success(block())
    } catch (e: HttpException) {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val parsed = raw?.let {
            runCatching { Gson().fromJson(it, ApiErrorBody::class.java) }.getOrNull()
        }
        NetworkResult.Error(
            message = parsed?.message ?: defaultMessageFor(e.code()),
            code = e.code(),
        )
    } catch (e: IOException) {
        // No connectivity. Expected constantly in this app — tunnels, ghats,
        // dead zones — so it is phrased as a state, not a failure.
        NetworkResult.Error("No connection. This will retry on its own.")
    } catch (e: Exception) {
        NetworkResult.Error(e.message ?: "Something went wrong.")
    }
}

private fun defaultMessageFor(code: Int): String = when (code) {
    401 -> "Your session expired. Open the app again."
    403 -> "You don't have access to that."
    404 -> "That doesn't exist any more."
    409 -> "That can't be done right now."
    410 -> "That has already finished."
    429 -> "Too many attempts. Wait a moment."
    in 500..599 -> "The server is having trouble. Try again shortly."
    else -> "Something went wrong."
}
