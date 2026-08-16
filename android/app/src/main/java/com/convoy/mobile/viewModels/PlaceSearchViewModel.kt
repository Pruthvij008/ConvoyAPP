package com.convoy.mobile.viewModels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.place.Place
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.PlacesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Searching for a destination by name.
 *
 * Nobody is going to set a destination by dragging a map to the right spot.
 * They type where they're going, the same way they would in any maps app,
 * and the pin follows.
 */
@HiltViewModel
class PlaceSearchViewModel @Inject constructor(
    private val repository: PlacesRepository,
) : ViewModel() {

    var query by mutableStateOf("")
        private set

    var results by mutableStateOf<List<Place>>(emptyList())
        private set

    var isSearching by mutableStateOf(false)
        private set

    /** Distinct from an empty result list — "nothing found" only after a real search. */
    var searched by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Where the user is, so results near them rank first. */
    private var nearLat: Double? = null
    private var nearLng: Double? = null

    /**
     * The in-flight debounce.
     *
     * Held so each keystroke can cancel the previous one. Without this,
     * typing "goa" would fire three searches and the answer for "g" could
     * land last and overwrite the answer for "goa".
     */
    private var searchJob: Job? = null

    fun setNear(lat: Double?, lng: Double?) {
        nearLat = lat
        nearLng = lng
    }

    fun onQueryChanged(value: String) {
        query = value
        errorMessage = null
        searchJob?.cancel()

        if (value.trim().length < 2) {
            results = emptyList()
            searched = false
            isSearching = false
            return
        }

        searchJob = viewModelScope.launch {
            // Long enough to skip the letters of a word being typed, short
            // enough that the list feels like it is keeping up.
            delay(DEBOUNCE_MS)
            runSearch(value.trim())
        }
    }

    /** The keyboard's search key — searches now rather than waiting out the debounce. */
    fun searchNow() {
        val q = query.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q) }
    }

    private suspend fun runSearch(q: String) {
        isSearching = true
        when (val r = repository.search(q, nearLat, nearLng)) {
            is NetworkResult.Success -> {
                results = r.data
                searched = true
            }
            is NetworkResult.Error -> {
                errorMessage = r.message
                results = emptyList()
            }
            NetworkResult.Loading -> Unit
        }
        isSearching = false
    }

    fun clear() {
        searchJob?.cancel()
        query = ""
        results = emptyList()
        searched = false
        isSearching = false
        errorMessage = null
    }

    /**
     * Names a coordinate the user placed by hand.
     *
     * Best-effort by design: if the lookup fails the caller keeps whatever
     * the user typed, and a nameless destination still navigates correctly.
     */
    fun nameFor(lat: Double, lng: Double, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            when (val r = repository.reverse(lat, lng)) {
                is NetworkResult.Success -> onResult(r.data?.displayLabel)
                is NetworkResult.Error -> onResult(null)
                NetworkResult.Loading -> Unit
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}
