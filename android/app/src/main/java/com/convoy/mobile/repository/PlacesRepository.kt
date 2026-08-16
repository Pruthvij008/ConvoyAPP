package com.convoy.mobile.repository

import com.convoy.mobile.dataModel.place.Place
import com.convoy.mobile.interfaces.PlacesInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlacesRepository @Inject constructor(
    private val placesApi: PlacesInterface,
) {

    suspend fun search(
        query: String,
        nearLat: Double? = null,
        nearLng: Double? = null,
    ): NetworkResult<List<Place>> {
        return when (val r = safeApiCall { placesApi.search(query, nearLat, nearLng) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.results.orEmpty())
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun reverse(lat: Double, lng: Double): NetworkResult<Place?> {
        return when (val r = safeApiCall { placesApi.reverse(lat, lng) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.place)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }
}
