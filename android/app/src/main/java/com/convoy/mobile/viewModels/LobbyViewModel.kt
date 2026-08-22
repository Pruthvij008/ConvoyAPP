package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.trip.LobbyBlockers
import com.convoy.mobile.dataModel.trip.Trip
import com.convoy.mobile.dataModel.trip.TripStatus
import com.convoy.mobile.dataModel.vehicle.Participant
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The waiting room. Nobody's location is shared here — this is the window
 * for joining, picking cars, and curating the trip's markers.
 */
@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val repository: TripRepository,
) : ViewModel() {

    private var tripId: String? = null
    private var pollJob: Job? = null
    private var autoOpenMap: Boolean = true

    var trip by mutableStateOf<Trip?>(null)
        private set
    var me by mutableStateOf<Participant?>(null)
        private set
    var participants by mutableStateOf<List<Participant>>(emptyList())
        private set
    var vehicles by mutableStateOf<List<Vehicle>>(emptyList())
        private set

    var readyCount by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var pendingRequests by mutableStateOf(0)
        private set
    var blockers by mutableStateOf(LobbyBlockers())
        private set
    var canStart by mutableStateOf(false)
        private set

    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** The backend refused the start and named who is holding it up. */
    var startBlockedMessage by mutableStateOf<String?>(null)
        private set

    /** Set when the trip goes ACTIVE; the Activity moves to the map. */
    var tripStarted by mutableStateOf(false)
        private set

    val amHost: Boolean get() = me?.canManageTrip == true
    val isReady: Boolean get() = me?.isReady == true

    /**
     * [autoOpenMapOnStart] is false when the lobby is opened from a trip that
     * is ALREADY running — otherwise the screen bounces straight back out and
     * the roster becomes unreachable for the whole trip.
     */
    fun load(tripId: String, autoOpenMapOnStart: Boolean = true) {
        this.tripId = tripId
        this.autoOpenMap = autoOpenMapOnStart
        tripStarted = false
        refresh()
        startPolling()
    }

    /**
     * The lobby has no socket yet — it is short-lived and low-traffic, and
     * opening a socket before the trip is live would mean connecting to a
     * room that carries nothing. Polling every few seconds is honest here.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (trip?.status == TripStatus.LOBBY || trip?.status == TripStatus.DRAFT) {
                    refresh(silent = true)
                }
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        val id = tripId ?: return

        viewModelScope.launch {
            if (!silent) isLoading = true

            when (val detail = repository.getTrip(id)) {
                is NetworkResult.Success -> {
                    trip = detail.data.trip
                    me = detail.data.me
                    participants = detail.data.participants.orEmpty()
                    vehicles = detail.data.vehicles.orEmpty()

                    // Only navigate when the trip STARTS while we are watching.
                    // If it was already running when this screen opened, the
                    // user came here deliberately to see the roster.
                    if (autoOpenMap && detail.data.trip.status == TripStatus.ACTIVE) {
                        tripStarted = true
                    }
                }
                is NetworkResult.Error -> if (!silent) errorMessage = detail.message
                NetworkResult.Loading -> Unit
            }

            when (val lobby = repository.getLobby(id)) {
                is NetworkResult.Success -> {
                    readyCount = lobby.data.readyCount
                    total = lobby.data.total
                    pendingRequests = lobby.data.pendingRequests
                    blockers = lobby.data.blockers ?: LobbyBlockers()
                    canStart = lobby.data.canStart
                }
                is NetworkResult.Error -> Log.w(TAG, "Lobby fetch failed: ${lobby.message}")
                NetworkResult.Loading -> Unit
            }

            if (!silent) isLoading = false
        }
    }

    fun toggleReady() {
        val id = tripId ?: return
        val next = !(me?.isReady ?: false)

        viewModelScope.launch {
            when (val result = repository.setReady(id, next)) {
                is NetworkResult.Success -> {
                    me = result.data.participant
                    readyCount = result.data.ready
                    total = result.data.total
                }
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun addVehicle(label: String) {
        val id = tripId ?: return
        if (label.isBlank()) return

        viewModelScope.launch {
            when (val result = repository.createVehicle(id, label)) {
                is NetworkResult.Success -> refresh(silent = true)
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun boardVehicle(vehicleId: String) {
        val id = tripId ?: return

        viewModelScope.launch {
            when (val result = repository.boardVehicle(id, vehicleId)) {
                is NetworkResult.Success -> refresh(silent = true)
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Starting is the moment location begins flowing. The backend refuses if
     * anyone has no vehicle and says who; [force] overrides that, because the
     * realistic case is a friend running late and the group setting off.
     */
    fun startTrip(force: Boolean = false) {
        val id = tripId ?: return

        viewModelScope.launch {
            isLoading = true
            startBlockedMessage = null

            when (val result = repository.updateStatus(id, TripStatus.ACTIVE, force)) {
                is NetworkResult.Success -> {
                    trip = result.data
                    // Pressing Start always opens the map, even if this
                    // lobby was opened on an already-running trip.
                    autoOpenMap = true
                    tripStarted = true
                }
                is NetworkResult.Error -> {
                    // 409 means the preflight refused and the message names
                    // whoever is not in a car — worth showing verbatim.
                    if (result.code == 409) {
                        startBlockedMessage = result.message
                    } else {
                        errorMessage = result.message
                    }
                }
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    fun leaveTrip(onDone: () -> Unit) {
        val id = tripId ?: return

        viewModelScope.launch {
            when (repository.leaveTrip(id)) {
                is NetworkResult.Success -> onDone()
                is NetworkResult.Error -> errorMessage = "Could not leave the trip."
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun dismissStartBlocked() { startBlockedMessage = null }
    fun dismissError() { errorMessage = null }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "LobbyViewModel"
        const val POLL_INTERVAL_MS = 5_000L
    }
}
