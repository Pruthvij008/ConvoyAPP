package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.auth.DeviceAuthRequest
import com.convoy.mobile.dataModel.auth.LoginRequest
import com.convoy.mobile.dataModel.auth.RegisterRequest
import com.convoy.mobile.dataModel.auth.DeviceAuthResponse
import com.convoy.mobile.dataModel.auth.UserResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthInterface {

    @POST(ApiEndpoints.REGISTER)
    suspend fun register(
        @Body request: RegisterRequest,
    ): DeviceAuthResponse

    @POST(ApiEndpoints.LOGIN)
    suspend fun login(
        @Body request: LoginRequest,
    ): DeviceAuthResponse

    /**
     * Idempotent by design — called on every cold start. The same device id
     * always returns the same user, so there is no separate "log in" and
     * "sign up"; there is only "here is who I am".
     */
    @POST(ApiEndpoints.DEVICE_AUTH)
    suspend fun deviceAuth(
        @Body request: DeviceAuthRequest,
    ): DeviceAuthResponse

    @GET(ApiEndpoints.ME)
    suspend fun getMe(): UserResponse
}
