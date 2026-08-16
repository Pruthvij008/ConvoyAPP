package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.common.SimpleResponse
import com.convoy.mobile.dataModel.trip.CreateTripRequest
import com.convoy.mobile.dataModel.trip.CreateTripResponse
import com.convoy.mobile.dataModel.trip.InviteResponse
import com.convoy.mobile.dataModel.trip.JoinTripRequest
import com.convoy.mobile.dataModel.trip.JoinTripResponse
import com.convoy.mobile.dataModel.trip.LobbyResponse
import com.convoy.mobile.dataModel.trip.PreviewRequest
import com.convoy.mobile.dataModel.trip.PreviewResponse
import com.convoy.mobile.dataModel.trip.ReadyRequest
import com.convoy.mobile.dataModel.trip.ReadyResponse
import com.convoy.mobile.dataModel.trip.StatusRequest
import com.convoy.mobile.dataModel.trip.TripDetailResponse
import com.convoy.mobile.dataModel.trip.TripListResponse
import com.convoy.mobile.dataModel.trip.TripResponse
import com.convoy.mobile.dataModel.trip.VehicleInput
import com.convoy.mobile.dataModel.vehicle.VehicleListResponse
import com.convoy.mobile.dataModel.vehicle.VehicleResponse
import com.convoy.mobile.dataModel.trip.MyRouteRequest
import com.convoy.mobile.dataModel.trip.MyRouteResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface TripInterface {

    // ── Trips ───────────────────────────────────────────────────
    @POST(ApiEndpoints.TRIPS)
    suspend fun createTrip(
        @Body request: CreateTripRequest,
    ): CreateTripResponse

    @GET(ApiEndpoints.TRIPS)
    suspend fun getMyTrips(): TripListResponse

    @GET(ApiEndpoints.TRIP_BY_ID)
    suspend fun getTrip(
        @Path("tripId") tripId: String,
    ): TripDetailResponse

    // ── Joining ─────────────────────────────────────────────────
    /** Shows the trip before committing to it. Carries no location. */
    @POST(ApiEndpoints.TRIP_PREVIEW)
    suspend fun previewTrip(
        @Body request: PreviewRequest,
    ): PreviewResponse

    @POST(ApiEndpoints.TRIP_JOIN)
    suspend fun joinTrip(
        @Body request: JoinTripRequest,
    ): JoinTripResponse

    @POST(ApiEndpoints.TRIP_LEAVE)
    suspend fun leaveTrip(
        @Path("tripId") tripId: String,
    ): SimpleResponse

    // ── Lifecycle ───────────────────────────────────────────────
    @PATCH(ApiEndpoints.TRIP_STATUS)
    suspend fun updateStatus(
        @Path("tripId") tripId: String,
        @Body request: StatusRequest,
    ): TripResponse

    // ── Lobby ───────────────────────────────────────────────────
    @GET(ApiEndpoints.TRIP_LOBBY)
    suspend fun getLobby(
        @Path("tripId") tripId: String,
    ): LobbyResponse

    @POST(ApiEndpoints.TRIP_READY)
    suspend fun setReady(
        @Path("tripId") tripId: String,
        @Body request: ReadyRequest,
    ): ReadyResponse

    // ── Invite ──────────────────────────────────────────────────
    /** Kills every previously shared link at once. */
    @POST(ApiEndpoints.TRIP_ROTATE_INVITE)
    suspend fun rotateInvite(
        @Path("tripId") tripId: String,
    ): InviteResponse

    // ── Vehicles ────────────────────────────────────────────────
    @GET(ApiEndpoints.VEHICLES)
    suspend fun getVehicles(
        @Path("tripId") tripId: String,
    ): VehicleListResponse

    @POST(ApiEndpoints.VEHICLES)
    suspend fun createVehicle(
        @Path("tripId") tripId: String,
        @Body request: VehicleInput,
    ): VehicleResponse

    /** Join an existing car as a passenger, or move between cars mid-trip. */
    @POST(ApiEndpoints.VEHICLE_BOARD)
    suspend fun boardVehicle(
        @Path("tripId") tripId: String,
        @Path("vehicleId") vehicleId: String,
    ): VehicleResponse

    /**
     * Directions from the caller's current position to the destination.
     *
     * POST because it carries live coordinates, and a position has no
     * business sitting in a URL that ends up in access logs.
     */
    @POST(ApiEndpoints.TRIP_MY_ROUTE)
    suspend fun getMyRoute(
        @Path("tripId") tripId: String,
        @Body request: MyRouteRequest,
    ): MyRouteResponse
}
