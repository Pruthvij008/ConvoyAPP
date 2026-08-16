package com.convoy.mobile.interfaces

import com.convoy.mobile.dataModel.place.PlaceReverseResponse
import com.convoy.mobile.dataModel.place.PlaceSearchResponse
import com.convoy.mobile.network.ApiEndpoints
import retrofit2.http.GET
import retrofit2.http.Query

interface PlacesInterface {

    /**
     * `lat`/`lng` are the searcher's own position, sent so results are
     * biased towards them — typing "station" in Pune should find Pune
     * station, not the most famous station on earth.
     */
    @GET(ApiEndpoints.PLACES_SEARCH)
    suspend fun search(
        @Query("q") query: String,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
    ): PlaceSearchResponse

    /** Turns a manually dropped pin into a name someone can recognise. */
    @GET(ApiEndpoints.PLACES_REVERSE)
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
    ): PlaceReverseResponse
}
