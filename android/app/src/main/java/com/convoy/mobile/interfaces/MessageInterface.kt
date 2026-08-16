package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.common.SimpleResponse
import com.convoy.mobile.dataModel.message.MarkReadRequest
import com.convoy.mobile.dataModel.message.MessageListResponse
import com.convoy.mobile.dataModel.message.MessageResponse
import com.convoy.mobile.dataModel.message.QuickMessageResponse
import com.convoy.mobile.dataModel.message.SendMessageRequest
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MessageInterface {

    @GET(ApiEndpoints.MESSAGES)
    suspend fun listMessages(
        @Path("tripId") tripId: String,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50,
    ): MessageListResponse

    /**
     * The reliable path. The socket is the low-latency one people normally
     * use; this exists for a phone whose socket has dropped — which, in a
     * car, is often.
     */
    @POST(ApiEndpoints.MESSAGES)
    suspend fun sendMessage(
        @Path("tripId") tripId: String,
        @Body request: SendMessageRequest,
    ): MessageResponse

    @POST(ApiEndpoints.MESSAGES_READ)
    suspend fun markRead(
        @Path("tripId") tripId: String,
        @Body request: MarkReadRequest,
    ): SimpleResponse

    /** Served rather than hardcoded, so the app can't drift from the server. */
    @GET(ApiEndpoints.QUICK_MESSAGES)
    suspend fun getQuickMessages(): QuickMessageResponse
}
