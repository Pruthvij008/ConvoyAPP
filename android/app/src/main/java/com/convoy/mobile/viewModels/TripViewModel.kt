package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.trip.Trip
import com.convoy.mobile.dataModel.trip.TripPreview
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.TripRepository
import com.convoy.mobile.utility.PrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Creating a trip, and joining one.
 *
 * Both flows end the same way — with an active trip id — so they share a
 * ViewModel rather than duplicating the vehicle-choice logic twice.
 */
@HiltViewModel
class TripViewModel @Inject constructor(
    private val repository: TripRepository,
    private val prefs: PrefsManager,
) : ViewModel() {

    // ── Create ──────────────────────────────────────────────────
    var tripName by mutableStateOf("")
        private set
    var destination by mutableStateOf("")
        private set

    /**
     * Real coordinates from the map picker. A typed address is not a
     * destination — nothing can be drawn from it and no directions given.
     */
    var destinationLat by mutableStateOf<Double?>(null)
        private set
    var destinationLng by mutableStateOf<Double?>(null)
        private set

    val hasPickedDestination: Boolean get() = destinationLat != null && destinationLng != null
    var vehicleLabel by mutableStateOf("")
        private set
    var vehicleType by mutableStateOf("CAR")
        private set

    // ── Join ────────────────────────────────────────────────────
    var joinCode by mutableStateOf("")
        private set
    var joinPassword by mutableStateOf("")
        private set
    private var joinToken: String? = null

    var preview by mutableStateOf<TripPreview?>(null)
        private set

    /** Cars already in the trip, so a joiner can ride with someone. */
    var previewVehicles by mutableStateOf<List<Vehicle>>(emptyList())
        private set

    // ── Shared ──────────────────────────────────────────────────
    var myTrips by mutableStateOf<List<Trip>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Set when the flow finishes; the Activity watches this and moves on. */
    var createdTripId by mutableStateOf<String?>(null)
        private set
    var joinedTripId by mutableStateOf<String?>(null)
        private set

    /** Shown once, right after creating. Never retrievable again. */
    var joinLink by mutableStateOf<String?>(null)
        private set
    var joinCodeCreated by mutableStateOf<String?>(null)
        private set

    /** Approval was on, so we are waiting for the host to let us in. */
    var awaitingApproval by mutableStateOf(false)
        private set

    val canCreate: Boolean
        get() = tripName.trim().length >= 3 && !isLoading

    val canJoinByCode: Boolean
        get() = joinCode.trim().length >= 4 && !isLoading

    fun onTripNameChanged(value: String) { tripName = value.take(80); errorMessage = null }
    fun onDestinationChanged(value: String) { destination = value.take(120) }

    fun onDestinationPicked(lat: Double, lng: Double, label: String) {
        destinationLat = lat
        destinationLng = lng
        if (label.isNotBlank()) destination = label
    }
    fun onVehicleLabelChanged(value: String) { vehicleLabel = value.take(40) }
    fun onVehicleTypeChanged(value: String) { vehicleType = value }
    fun onJoinPasswordChanged(value: String) { joinPassword = value }

    fun onJoinCodeChanged(value: String) {
        // The code alphabet excludes ambiguous characters, so it is always
        // uppercase and never contains 0/O or 1/I/L.
        joinCode = value.uppercase().filter { it.isLetterOrDigit() }.take(8)
        errorMessage = null
    }

    fun setJoinToken(token: String?) { joinToken = token }

    // ── Actions ─────────────────────────────────────────────────

    fun createTrip() {
        if (!canCreate) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            when (
                val result = repository.createTrip(
                    name = tripName,
                    destinationAddress = destination,
                    destinationLat = destinationLat,
                    destinationLng = destinationLng,
                    vehicleLabel = vehicleLabel.ifBlank { "My car" },
                    vehicleType = vehicleType,
                )
            ) {
                is NetworkResult.Success -> {
                    joinLink = result.data.joinLink
                    joinCodeCreated = result.data.joinCode
                    createdTripId = result.data.trip.id
                    Log.d(TAG, "Trip created: ${result.data.trip.id}")
                }
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    /** Called when a shared link opens the app, or a code is typed. */
    fun loadPreview() {
        val token = joinToken
        val code = joinCode.trim().ifBlank { null }
        if (token == null && code == null) return

        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            when (val result = repository.previewTrip(token, code)) {
                is NetworkResult.Success -> {
                    preview = result.data
                    Log.d(TAG, "Preview: ${result.data.name}")
                }
                is NetworkResult.Error -> {
                    preview = null
                    errorMessage = result.message
                }
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    /**
     * [newVehicleLabel] brings your own car and makes you its tracker;
     * [vehicleId] rides with someone as a passenger.
     */
    fun joinTrip(vehicleId: String? = null, newVehicleLabel: String? = null) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            when (
                val result = repository.joinTrip(
                    token = joinToken,
                    code = joinCode.trim().ifBlank { null },
                    password = joinPassword,
                    vehicleId = vehicleId,
                    newVehicleLabel = newVehicleLabel,
                )
            ) {
                is NetworkResult.Success -> {
                    if (result.data.participant.isPending) {
                        // Approval is on: we are in the queue, not the trip.
                        awaitingApproval = true
                    } else {
                        joinedTripId = result.data.tripId
                    }
                }
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    fun loadMyTrips() {
        viewModelScope.launch {
            when (val result = repository.getMyTrips()) {
                is NetworkResult.Success -> myTrips = result.data
                is NetworkResult.Error -> Log.w(TAG, "Trip list failed: ${result.message}")
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun dismissError() { errorMessage = null }

    private companion object {
        const val TAG = "TripViewModel"
    }
}
