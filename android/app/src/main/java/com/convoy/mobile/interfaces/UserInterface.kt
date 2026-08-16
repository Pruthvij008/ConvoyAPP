package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.auth.UserResponse
import com.convoy.mobile.dataModel.media.ConfirmRequest
import com.convoy.mobile.dataModel.media.SignatureResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST

interface UserInterface {

    /** Permission to write one image into this user's own folder. */
    @POST(ApiEndpoints.AVATAR_SIGNATURE)
    suspend fun getAvatarSignature(): SignatureResponse

    /**
     * Attaches an uploaded image as the profile photo.
     *
     * The server verifies the asset exists under this user's folder before
     * accepting it — without that, any publicId could be pointed at.
     */
    @POST(ApiEndpoints.AVATAR)
    suspend fun setAvatar(@Body request: ConfirmRequest): UserResponse

    @DELETE(ApiEndpoints.AVATAR)
    suspend fun removeAvatar(): UserResponse
}
