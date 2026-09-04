package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.auth.User
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.AuthRepository
import com.convoy.mobile.utility.PrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
    private val prefs: PrefsManager,
) : ViewModel() {

    /**
     * One screen, two modes, so nobody hunts for a separate signup page.
     *
     * Starts on REGISTER for anyone who has never signed in on this device,
     * and on sign-in only for someone returning. Defaulting to sign-in meant
     * a first-time user was greeted with "Welcome back" — which, next to a
     * pre-filled-looking username field, read as though the app had arrived
     * with somebody else's account already in it.
     *
     * `username` rather than the token: the token is cleared on sign-out,
     * but someone who has signed in before still wants the sign-in form.
     */
    var isRegisterMode by mutableStateOf(prefs.username.isNullOrBlank())
        private set

    var username by mutableStateOf("")
        private set
    var displayName by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    var user by mutableStateOf<User?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isAuthenticated by mutableStateOf(false)
        private set

    /** Mirrors the server's rule, so the failure is prevented not reported. */
    val usernameError: String?
        get() {
            val u = username.trim()
            return when {
                u.isEmpty() -> null
                u.length < 3 -> "At least 3 characters"
                u.length > 20 -> "20 characters or fewer"
                !u.matches(Regex("^[a-zA-Z0-9_]+$")) -> "Letters, numbers and _ only"
                else -> null
            }
        }

    val passwordError: String?
        get() = if (password.isNotEmpty() && password.length < 8) {
            "At least 8 characters"
        } else null

    val canSubmit: Boolean
        get() = username.trim().length >= 3 &&
            password.length >= 8 &&
            usernameError == null &&
            !isLoading

    fun toggleMode() {
        isRegisterMode = !isRegisterMode
        errorMessage = null
    }

    fun onUsernameChanged(value: String) {
        // Spaces are the commonest mistake and are never valid, so they are
        // stripped as you type rather than rejected on submit.
        username = value.trim().take(20)
        errorMessage = null
    }

    fun onDisplayNameChanged(value: String) { displayName = value.take(40) }

    fun onPasswordChanged(value: String) {
        password = value.take(64)
        errorMessage = null
    }

    fun submit() {
        if (!canSubmit) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            val result = if (isRegisterMode) {
                repository.register(username, displayName, password)
            } else {
                repository.login(username, password)
            }

            when (result) {
                is NetworkResult.Success -> {
                    user = result.data
                    isAuthenticated = true
                    password = "" // never keep it in memory after use
                    Log.d(TAG, "Signed in as ${result.data.username}")
                }
                is NetworkResult.Error -> {
                    errorMessage = result.message
                    // A taken username is a signup problem, not a typo — say
                    // so by offering the other mode rather than just failing.
                    if (result.code == 409 && isRegisterMode) {
                        errorMessage = "${result.message} Try signing in instead."
                    }
                }
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    /**
     * Called from the splash screen. A stored token is checked against the
     * server; anything other than a clear rejection leaves the user signed
     * in, because being thrown to a login screen for a dropped connection
     * is worse than a brief stale session.
     */
    fun restoreSession(onDone: (loggedIn: Boolean) -> Unit) {
        viewModelScope.launch {
            if (!prefs.isLoggedIn) {
                onDone(false)
                return@launch
            }

            when (val result = repository.refreshSession()) {
                is NetworkResult.Success -> {
                    user = result.data
                    isAuthenticated = true
                    onDone(true)
                }
                is NetworkResult.Error -> {
                    val rejected = result.code == 401 || result.code == 403
                    if (rejected) repository.signOut()
                    Log.w(TAG, "Session check failed: ${result.message}")
                    onDone(!rejected)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun dismissError() { errorMessage = null }

    private companion object {
        const val TAG = "AuthViewModel"
    }
}
