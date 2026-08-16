package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.alert.AlertListResponse
import com.convoy.mobile.dataModel.alert.AlertResponse
import com.convoy.mobile.dataModel.alert.ResolveAlertRequest
import com.convoy.mobile.dataModel.alert.SosRequest
import com.convoy.mobile.dataModel.alert.SosResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface AlertInterface {

    @GET(ApiEndpoints.ALERTS)
    suspend fun listAlerts(
        @Path("tripId") tripId: String,
    ): AlertListResponse

    /** "I've seen this" — not "this is over". The condition stays live. */
    @POST(ApiEndpoints.ALERT_ACK)
    suspend fun acknowledge(
        @Path("tripId") tripId: String,
        @Path("alertId") alertId: String,
    ): AlertResponse

    @POST(ApiEndpoints.ALERT_RESOLVE)
    suspend fun resolve(
        @Path("tripId") tripId: String,
        @Path("alertId") alertId: String,
        @Body request: ResolveAlertRequest,
    ): AlertResponse

    /**
     * The countdown happens on the device. By the time this is called the
     * user has confirmed, so the server raises it immediately rather than
     * waiting for the next sweep.
     */
    @POST(ApiEndpoints.SOS)
    suspend fun raiseSos(
        @Path("tripId") tripId: String,
        @Body request: SosRequest,
    ): SosResponse
}
