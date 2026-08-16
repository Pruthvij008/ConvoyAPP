package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.media.ConfirmRequest
import com.convoy.mobile.dataModel.media.ConfirmResponse
import com.convoy.mobile.dataModel.media.SignatureRequest
import com.convoy.mobile.dataModel.media.SignatureResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface MediaInterface {

    /** Permission to write one file into this trip's folder, and nowhere else. */
    @POST(ApiEndpoints.MEDIA_SIGNATURE)
    suspend fun getSignature(
        @Path("tripId") tripId: String,
        @Body request: SignatureRequest,
    ): SignatureResponse

    /**
     * The security boundary. A publicId reported by a client is a claim
     * until the server checks the asset exists and sits in the right folder.
     */
    @POST(ApiEndpoints.MEDIA_CONFIRM)
    suspend fun confirm(
        @Path("tripId") tripId: String,
        @Body request: ConfirmRequest,
    ): ConfirmResponse
}
