package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.marker.AddMarkerSetRequest
import com.convoy.mobile.dataModel.marker.CreateMarkerRequest
import com.convoy.mobile.dataModel.marker.MarkerSetResponse
import com.convoy.mobile.dataModel.marker.MarkerListResponse
import com.convoy.mobile.dataModel.marker.MarkerResponse
import com.convoy.mobile.dataModel.marker.UpdateMarkerRequest
import com.convoy.mobile.dataModel.common.SimpleResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MarkerInterface {

    @GET(ApiEndpoints.MARKERS)
    suspend fun listMarkers(
        @Path("tripId") tripId: String,
        @Query("state") state: String? = null,
        @Query("kind") kind: String? = null,
    ): MarkerListResponse

    @POST(ApiEndpoints.MARKERS)
    suspend fun createMarker(
        @Path("tripId") tripId: String,
        @Body request: CreateMarkerRequest,
    ): MarkerResponse

    @PATCH(ApiEndpoints.MARKER_BY_ID)
    suspend fun updateMarker(
        @Path("tripId") tripId: String,
        @Path("markerId") markerId: String,
        @Body request: UpdateMarkerRequest,
    ): MarkerResponse

    /**
     * Resuming the drive. Distinct from delete: a cleared stop is history
     * worth keeping, and the trip recap is built from it.
     */
    @POST(ApiEndpoints.MARKER_CLEAR)
    suspend fun clearMarker(
        @Path("tripId") tripId: String,
        @Path("markerId") markerId: String,
    ): MarkerResponse

    /**
     * Adds a marker the group invented to this trip'''s set.
     *
     * Any member may do this — inventing a stop reason is the whole point
     * of custom markers, and gating it behind the host would mean the one
     * person who needs a washroom marker cannot make one.
     */
    @POST(ApiEndpoints.MARKER_SET)
    suspend fun addToMarkerSet(
        @Path("tripId") tripId: String,
        @Body request: AddMarkerSetRequest,
    ): MarkerSetResponse

    /** Keeps it for next time, on this user'''s own account. */
    @POST(ApiEndpoints.MARKER_LIBRARY)
    suspend fun saveToLibrary(
        @Body request: AddMarkerSetRequest,
    ): MarkerSetResponse
}
