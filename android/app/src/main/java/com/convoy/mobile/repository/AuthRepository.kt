package com.convoy.mobile.repository

import android.util.Log
import com.convoy.mobile.dataModel.auth.DeviceAuthRequest
import com.convoy.mobile.dataModel.auth.LoginRequest
import com.convoy.mobile.dataModel.auth.RegisterRequest
import com.convoy.mobile.dataModel.auth.DeviceAuthResponse
import com.convoy.mobile.dataModel.auth.User
import com.convoy.mobile.interfaces.AuthInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import com.convoy.mobile.utility.PrefsManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthInterface,
    private val prefs: PrefsManager,
) {

    /** Creates an account. The token comes back immediately — there is no
     *  email to verify, so nothing stands between registering and joining. */
    suspend fun register(
        username: String,
        displayName: String,
        password: String,
    ): NetworkResult<User> {
        val request = RegisterRequest(
            username = username.trim().lowercase(),
            name = displayName.trim().ifBlank { username.trim() },
            password = password,
            passwordConfirm = password,
            deviceId = prefs.deviceId,
        )
        Log.d(TAG, "Register calling — ${request.username}")

        return when (val result = safeApiCall { authApi.register(request) }) {
            is NetworkResult.Success -> {
                persist(result.data)
                NetworkResult.Success(result.data.data.user)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun login(username: String, password: String): NetworkResult<User> {
        val request = LoginRequest(username.trim().lowercase(), password)
        Log.d(TAG, "Login calling — ${request.username}")

        return when (val result = safeApiCall { authApi.login(request) }) {
            is NetworkResult.Success -> {
                persist(result.data)
                NetworkResult.Success(result.data.data.user)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Signs in with the device key. On success the token is persisted here
     * rather than in the ViewModel, so every later request — REST and socket
     * alike — picks it up without anyone having to remember to save it.
     */
    suspend fun deviceAuth(name: String): NetworkResult<User> {
        val request = DeviceAuthRequest(
            deviceId = prefs.deviceId,
            name = name.trim(),
        )
        Log.d(TAG, "Device auth calling — name: ${request.name}")

        return when (val result = safeApiCall { authApi.deviceAuth(request) }) {
            is NetworkResult.Success -> {
                persist(result.data)
                NetworkResult.Success(result.data.data.user)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun getMe(): NetworkResult<User> {
        Log.d(TAG, "Fetching current user")
        return when (val result = safeApiCall { authApi.getMe() }) {
            is NetworkResult.Success ->
                result.data.data?.user?.let { NetworkResult.Success(it) }
                    ?: NetworkResult.Error("Couldn't load your account.")
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Validates a stored token on launch. A real account cannot silently
     * re-authenticate the way a device key can — there is no password
     * stored — so this just asks the server whether the token still works.
     */
    suspend fun refreshSession(): NetworkResult<User> {
        if (!prefs.isLoggedIn) return NetworkResult.Error("Not signed in yet.")
        return getMe()
    }

    private fun persist(response: DeviceAuthResponse) {
        prefs.token = response.token
        prefs.userId = response.data.user.id
        prefs.displayName = response.data.user.name
        prefs.username = response.data.user.username
    }

    fun signOut() = prefs.clearSession()

    private companion object {
        const val TAG = "AuthRepository"
    }
}
