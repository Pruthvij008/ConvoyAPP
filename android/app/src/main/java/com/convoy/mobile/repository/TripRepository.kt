package com.convoy.mobile.repository

import android.util.Log
import com.convoy.mobile.dataModel.trip.CreateTripData
import com.convoy.mobile.dataModel.trip.CreateTripRequest
import com.convoy.mobile.dataModel.trip.InviteData
import com.convoy.mobile.dataModel.trip.JoinTripData
import com.convoy.mobile.dataModel.trip.JoinTripRequest
import com.convoy.mobile.dataModel.trip.LobbyData
import com.convoy.mobile.dataModel.trip.PreviewRequest
import com.convoy.mobile.dataModel.trip.ReadyData
import com.convoy.mobile.dataModel.trip.ReadyRequest
import com.convoy.mobile.dataModel.trip.StatusRequest
import com.convoy.mobile.dataModel.trip.Trip
import com.convoy.mobile.dataModel.trip.TripDetailData
import com.convoy.mobile.dataModel.trip.TripPreview
import com.convoy.mobile.dataModel.trip.VehicleInput
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.interfaces.TripInterface
import com.convoy.mobile.dataModel.trip.MyRouteRequest
import com.convoy.mobile.dataModel.trip.RouteCache
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import com.convoy.mobile.utility.PrefsManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    private val tripApi: TripInterface,
    private val prefs: PrefsManager,
) {

    suspend fun createTrip(
        name: String,
        destinationAddress: String?,
        destinationLat: Double? = null,
        destinationLng: Double? = null,
        vehicleLabel: String?,
        vehicleType: String = "CAR",
    ): NetworkResult<CreateTripData> {
        val request = CreateTripRequest(
            name = name.trim(),
            destinationAddress = destinationAddress?.trim()?.ifBlank { null },
            // GeoPoint.of takes lat/lng in human order and stores the
            // [lng, lat] the server expects — never build one by hand.
            destination = if (destinationLat != null && destinationLng != null) {
                com.convoy.mobile.dataModel.common.GeoPoint.of(destinationLat, destinationLng)
            } else null,
            deviceId = prefs.deviceId,
            vehicle = vehicleLabel?.trim()?.ifBlank { null }?.let {
                VehicleInput(label = it, type = vehicleType)
            },
        )
        Log.d(TAG, "Create trip calling — ${request.name}")

        return when (val result = safeApiCall { tripApi.createTrip(request) }) {
            is NetworkResult.Success -> {
                // Remembered so a cold start can resume straight into the trip.
                prefs.activeTripId = result.data.data.trip.id
                prefs.activeVehicleId = result.data.data.vehicle?.id
                NetworkResult.Success(result.data.data)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun getMyTrips(): NetworkResult<List<Trip>> {
        Log.d(TAG, "Fetching my trips")
        return when (val result = safeApiCall { tripApi.getMyTrips() }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data.trips)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun getTrip(tripId: String): NetworkResult<TripDetailData> {
        Log.d(TAG, "Fetching trip $tripId")
        return when (val result = safeApiCall { tripApi.getTrip(tripId) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Joining ─────────────────────────────────────────────────

    /**
     * Either a link token or a spoken code. The backend answers the same way
     * for a wrong code and a missing trip, so this cannot be used to
     * enumerate valid codes.
     */
    suspend fun previewTrip(token: String?, code: String?): NetworkResult<TripPreview> {
        Log.d(TAG, "Preview calling — token: ${token != null}, code: $code")
        val request = PreviewRequest(token = token, code = code?.uppercase()?.trim())

        return when (val result = safeApiCall { tripApi.previewTrip(request) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data.trip)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun joinTrip(
        token: String?,
        code: String?,
        password: String? = null,
        vehicleId: String? = null,
        newVehicleLabel: String? = null,
    ): NetworkResult<JoinTripData> {
        val request = JoinTripRequest(
            token = token,
            code = code?.uppercase()?.trim(),
            password = password?.ifBlank { null },
            deviceId = prefs.deviceId,
            vehicleId = vehicleId,
            vehicle = newVehicleLabel?.trim()?.ifBlank { null }?.let { VehicleInput(label = it) },
        )
        Log.d(TAG, "Join trip calling")

        return when (val result = safeApiCall { tripApi.joinTrip(request) }) {
            is NetworkResult.Success -> {
                prefs.activeTripId = result.data.data.tripId
                prefs.activeVehicleId = result.data.data.participant.vehicleId
                NetworkResult.Success(result.data.data)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun leaveTrip(tripId: String): NetworkResult<Unit> {
        Log.d(TAG, "Leaving trip $tripId")
        return when (val result = safeApiCall { tripApi.leaveTrip(tripId) }) {
            is NetworkResult.Success -> {
                clearActiveTrip(tripId)
                NetworkResult.Success(Unit)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────

    /**
     * Starting is the moment location begins flowing, so the backend runs a
     * preflight and can refuse with a message naming whoever is not in a
     * vehicle. [force] is how the host overrides that.
     */
    suspend fun updateStatus(
        tripId: String,
        status: String,
        force: Boolean = false,
    ): NetworkResult<Trip> {
        Log.d(TAG, "Trip $tripId → $status (force=$force)")
        val request = StatusRequest(status = status, force = force)

        return when (val result = safeApiCall { tripApi.updateStatus(tripId, request) }) {
            is NetworkResult.Success -> {
                val trip = result.data.data.trip
                if (trip.isFinished) clearActiveTrip(tripId)
                NetworkResult.Success(trip)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Lobby ───────────────────────────────────────────────────

    suspend fun getLobby(tripId: String): NetworkResult<LobbyData> {
        return when (val result = safeApiCall { tripApi.getLobby(tripId) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun setReady(tripId: String, ready: Boolean): NetworkResult<ReadyData> {
        Log.d(TAG, "Ready=$ready for trip $tripId")
        return when (val result = safeApiCall { tripApi.setReady(tripId, ReadyRequest(ready)) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun rotateInvite(tripId: String): NetworkResult<InviteData> {
        Log.d(TAG, "Rotating invite for $tripId")
        return when (val result = safeApiCall { tripApi.rotateInvite(tripId) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Vehicles ────────────────────────────────────────────────

    suspend fun getVehicles(tripId: String): NetworkResult<List<Vehicle>> {
        return when (val result = safeApiCall { tripApi.getVehicles(tripId) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.data.vehicles)
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun createVehicle(
        tripId: String,
        label: String,
        type: String = "CAR",
    ): NetworkResult<Vehicle> {
        Log.d(TAG, "Creating vehicle '$label' in $tripId")
        val request = VehicleInput(label = label.trim(), type = type)

        return when (val result = safeApiCall { tripApi.createVehicle(tripId, request) }) {
            is NetworkResult.Success -> {
                // Creating a vehicle makes you its tracker — the phone that
                // actually broadcasts.
                prefs.activeVehicleId = result.data.data.vehicle.id
                NetworkResult.Success(result.data.data.vehicle)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun boardVehicle(tripId: String, vehicleId: String): NetworkResult<Vehicle> {
        Log.d(TAG, "Boarding vehicle $vehicleId")
        return when (val result = safeApiCall { tripApi.boardVehicle(tripId, vehicleId) }) {
            is NetworkResult.Success -> {
                prefs.activeVehicleId = vehicleId
                NetworkResult.Success(result.data.data.vehicle)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /** Only forgets the trip if it is the one we were tracking. */
    private fun clearActiveTrip(tripId: String) {
        if (prefs.activeTripId == tripId) {
            prefs.activeTripId = null
            prefs.activeVehicleId = null
        }
    }

    /**
     * Directions from where the caller is now to the trip destination.
     *
     * Fetched on demand rather than on every position update: the line
     * barely changes between pings, and redrawing it every fifteen seconds
     * would burn a day's routing quota in minutes.
     */
    suspend fun getMyRoute(
        tripId: String,
        lat: Double,
        lng: Double,
    ): NetworkResult<RouteCache> {
        Log.d(TAG, "Routing from $lat,$lng")
        return when (val r = safeApiCall { tripApi.getMyRoute(tripId, MyRouteRequest(lat, lng)) }) {
            is NetworkResult.Success -> {
                val route = r.data.data.route
                if (route == null || route.points.size < 2) {
                    NetworkResult.Error("No route came back for this destination.")
                } else {
                    NetworkResult.Success(route)
                }
            }
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    private companion object {
        const val TAG = "TripRepository"
    }
}
