package com.convoy.mobile.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
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

// One instance. Building a Gson per failed request is pure waste on exactly
// the path that is already having a bad time.
private val errorGson = Gson()

private const val TAG = "safeApiCall"

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
    } catch (e: CancellationException) {
        // MUST be rethrown, and must be caught BEFORE the generic clause.
        //
        // Cancellation is not a failure — it is how a coroutine is told to
        // stop, and it is thrown constantly here: every time a ViewModel is
        // cleared, a screen is left, or a refresh races the trip ending.
        // Swallowing it did two bad things at once. It broke structured
        // concurrency, because the scope never learned the child had
        // finished cancelling; and it turned an ordinary teardown into
        // `NetworkResult.Error("Job was cancelled")`, which is a sentence a
        // user could actually end up reading on the map screen.
        throw e
    } catch (e: HttpException) {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val parsed = raw?.let {
            runCatching { errorGson.fromJson(it, ApiErrorBody::class.java) }.getOrNull()
        }
        NetworkResult.Error(
            message = parsed?.message ?: defaultMessageFor(e.code()),
            code = e.code(),
        )
    } catch (e: IOException) {
        // No connectivity. Expected constantly in this app — tunnels, ghats,
        // dead zones — so it is phrased as a state, not a failure.
        NetworkResult.Error("No connection. This will retry on its own.")
    } catch (e: JsonParseException) {
        // The body was not the JSON we expected. The common cause is not a
        // bug but a hosting one: a sleeping free-tier service answers the
        // first request with an HTML holding page, and Gson chokes on the
        // "<". Retrying genuinely does fix it, so the message says so.
        Log.e(TAG, "Malformed response body", e)
        NetworkResult.Error("The server is waking up. Try that again in a moment.")
    } catch (e: NullPointerException) {
        // A field the app requires was missing from the response.
        //
        // Gson builds objects by unsafe allocation and never runs Kotlin
        // constructors, so a field declared non-null that the server omits
        // stays null and detonates at first use — often far from here, deep
        // in a composable. Catching it at the boundary turns a crash into a
        // message, and logs the shape problem for us.
        Log.e(TAG, "Response was missing a required field", e)
        NetworkResult.Error("The server sent something we couldn't read.")
    } catch (e: Exception) {
        // Deliberately NOT surfacing e.message: it is written for a
        // developer, and things like "Unable to resolve host \"…\"" or a
        // Gson type path help nobody holding a phone.
        Log.e(TAG, "Unexpected failure in a network call", e)
        NetworkResult.Error("Something went wrong. Try again.")
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
