package com.convoy.mobile.network

import com.convoy.mobile.utility.PrefsManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the JWT to every request.
 *
 * The token comes from device auth, which is the same token the socket
 * handshake uses — one credential for both transports, so a session that
 * works for REST also works for live location.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val prefs: PrefsManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // The auth endpoint is what issues the token, so it must not require
        // one — otherwise a fresh install could never sign in.
        if (original.url.encodedPath.endsWith(ApiEndpoints.DEVICE_AUTH)) {
            return chain.proceed(original)
        }

        val token = prefs.token
        val request = if (token.isNullOrBlank()) {
            original
        } else {
            original.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        }

        return chain.proceed(request)
    }
}
