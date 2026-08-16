package com.convoy.mobile.viewModels

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.AvatarRepository
import com.convoy.mobile.utility.PrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The profile photo, which is optional and always has been.
 *
 * Nothing in the app requires one — a roster reads fine with initials on a
 * coloured disc. This exists because a face is quicker to recognise than a
 * letter when you are glancing at six cars.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: AvatarRepository,
    private val prefs: PrefsManager,
) : ViewModel() {

    /** Null means no photo — the initial is shown instead. */
    var photoUrl by mutableStateOf(prefs.photoUrl)
        private set

    var isUploading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun pickedPhoto(context: Context, uri: Uri) {
        viewModelScope.launch {
            isUploading = true
            errorMessage = null

            when (val r = repository.upload(context, uri)) {
                is NetworkResult.Success -> {
                    // Cached locally so the roster and header can show it
                    // without refetching the user on every screen.
                    photoUrl = r.data.photo?.takeIf { it != DEFAULT_PHOTO }
                    prefs.photoUrl = photoUrl
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isUploading = false
        }
    }

    fun removePhoto() {
        viewModelScope.launch {
            isUploading = true
            when (val r = repository.remove()) {
                is NetworkResult.Success -> {
                    photoUrl = null
                    prefs.photoUrl = null
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isUploading = false
        }
    }

    fun dismissError() { errorMessage = null }

    private companion object {
        /** What the server stores when there is no real photo. */
        const val DEFAULT_PHOTO = "default.jpg"
    }
}
