package com.convoy.mobile.repository

import android.util.Log
import com.convoy.mobile.dataModel.marker.CreateMarkerRequest
import com.convoy.mobile.dataModel.marker.AddMarkerSetRequest
import com.convoy.mobile.dataModel.marker.Marker
import com.convoy.mobile.dataModel.media.UploadedMedia
import com.convoy.mobile.dataModel.trip.TripMarker
import com.convoy.mobile.dataModel.marker.UpdateMarkerRequest
import com.convoy.mobile.interfaces.MarkerInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkerRepository @Inject constructor(
    private val markerApi: MarkerInterface,
) {

    suspend fun listActive(tripId: String): NetworkResult<List<Marker>> {
        return when (val r = safeApiCall { markerApi.listMarkers(tripId, state = "ACTIVE") }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.markers.orEmpty())
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun listAll(tripId: String): NetworkResult<List<Marker>> {
        return when (val r = safeApiCall { markerApi.listMarkers(tripId) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.markers.orEmpty())
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * The vehicle is never sent — the server takes it from the caller's
     * participant record, so nobody can mark a stop on someone else's car.
     */
    suspend fun createStop(
        tripId: String,
        markerKey: String,
        lat: Double?,
        lng: Double?,
        note: String? = null,
        waitingForGroup: Boolean? = null,
        media: List<UploadedMedia>? = null,
    ): NetworkResult<Marker> {
        Log.d(TAG, "Creating $markerKey stop in $tripId")
        val request = CreateMarkerRequest(
            markerKey = markerKey,
            kind = "STATUS",
            lat = lat,
            lng = lng,
            note = note?.trim()?.ifBlank { null },
            waitingForGroup = waitingForGroup,
            media = media?.takeIf { it.isNotEmpty() },
        )

        return when (val r = safeApiCall { markerApi.createMarker(tripId, request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.marker)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /** A pin on the map rather than a status on a car. */
    suspend fun createPlace(
        tripId: String,
        markerKey: String,
        lat: Double,
        lng: Double,
        note: String? = null,
    ): NetworkResult<Marker> {
        val request = CreateMarkerRequest(
            markerKey = markerKey,
            kind = "PLACE",
            lat = lat,
            lng = lng,
            note = note?.trim()?.ifBlank { null },
        )
        return when (val r = safeApiCall { markerApi.createMarker(tripId, request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.marker)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun setWaiting(
        tripId: String,
        markerId: String,
        waiting: Boolean,
    ): NetworkResult<Marker> {
        val request = UpdateMarkerRequest(waitingForGroup = waiting)
        return when (val r = safeApiCall { markerApi.updateMarker(tripId, markerId, request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.marker)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun clear(tripId: String, markerId: String): NetworkResult<Marker> {
        Log.d(TAG, "Clearing stop $markerId")
        return when (val r = safeApiCall { markerApi.clearMarker(tripId, markerId) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.marker)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Creates a marker the group invented — "Washroom", "Chai", whatever
     * this crew actually stops for.
     *
     * Two writes, deliberately in this order. The trip'''s set is what makes
     * the marker appear in everyone'''s picker, so it goes first and its
     * failure is the one reported. Saving to the personal library is a
     * convenience for next time; if it fails the marker still works for
     * this trip, and telling the user their washroom marker failed when it
     * is visibly right there would be a lie.
     */
    suspend fun createCustomMarker(
        tripId: String,
        label: String,
        icon: String,
        color: String?,
    ): NetworkResult<List<TripMarker>> {
        val request = AddMarkerSetRequest(
            // Namespaced and time-suffixed so two people inventing
            // "Washroom" on the same trip do not collide on one key and
            // silently overwrite each other'''s icon.
            key = "custom_" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                .take(20) + "_" + (System.currentTimeMillis() % 100000),
            label = label.trim(),
            icon = icon,
            color = color,
        )

        val result = when (val r = safeApiCall { markerApi.addToMarkerSet(tripId, request) }) {
            is NetworkResult.Success ->
                NetworkResult.Success(r.data.data.markerSet.orEmpty())
            is NetworkResult.Error -> return r
            NetworkResult.Loading -> NetworkResult.Loading
        }

        // try/catch rather than runCatching, which catches Throwable and
        // would therefore swallow CancellationException — quietly breaking
        // the caller's cancellation on a call that is explicitly allowed to
        // fail.
        try {
            markerApi.saveToLibrary(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Marker saved to trip but not to library: ${e.message}")
        }

        return result
    }

    private companion object {
        const val TAG = "MarkerRepository"
    }
}
