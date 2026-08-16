package com.convoy.mobile.network

import com.convoy.mobile.BuildConfig
import com.convoy.mobile.interfaces.AuthInterface
import com.convoy.mobile.interfaces.AlertInterface
import com.convoy.mobile.interfaces.MarkerInterface
import com.convoy.mobile.interfaces.MediaInterface
import com.convoy.mobile.interfaces.UserInterface
import com.convoy.mobile.interfaces.MessageInterface
import com.convoy.mobile.interfaces.PlacesInterface
import com.convoy.mobile.interfaces.TripInterface
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Retrofit, OkHttp and the API interfaces.
 *
 * Timeouts are deliberately short. This app runs on moving vehicles with
 * patchy signal, and a request hanging for 60 seconds is worse than one
 * failing in 15 — the caller can retry, and the UI can say so honestly.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // Bodies in debug only. Release logging would put trip locations
            // and join tokens into logcat, where any app could read them.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // One retry on a dropped connection covers the common case of coming
        // out of a tunnel mid-request.
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit = Retrofit.Builder()
        .baseUrl(ApiEndpoints.BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    @Provides
    @Singleton
    fun provideAuthInterface(retrofit: Retrofit): AuthInterface =
        retrofit.create(AuthInterface::class.java)

    @Provides
    @Singleton
    fun provideTripInterface(retrofit: Retrofit): TripInterface =
        retrofit.create(TripInterface::class.java)

    @Provides
    @Singleton
    fun provideMarkerInterface(retrofit: Retrofit): MarkerInterface =
        retrofit.create(MarkerInterface::class.java)

    @Provides
    @Singleton
    fun provideAlertInterface(retrofit: Retrofit): AlertInterface =
        retrofit.create(AlertInterface::class.java)

    @Provides
    @Singleton
    fun provideMessageInterface(retrofit: Retrofit): MessageInterface =
        retrofit.create(MessageInterface::class.java)

    @Provides
    @Singleton
    fun providePlacesInterface(retrofit: Retrofit): PlacesInterface =
        retrofit.create(PlacesInterface::class.java)

    @Provides
    @Singleton
    fun provideMediaInterface(retrofit: Retrofit): MediaInterface =
        retrofit.create(MediaInterface::class.java)

    @Provides
    @Singleton
    fun provideUserInterface(retrofit: Retrofit): UserInterface =
        retrofit.create(UserInterface::class.java)

    // The Waypoint interface is added here when it is built.
}
