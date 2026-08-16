package com.convoy.mobile.repository

import android.util.Log
import com.convoy.mobile.dataModel.alert.Alert
import com.convoy.mobile.dataModel.alert.ResolveAlertRequest
import com.convoy.mobile.dataModel.alert.SosData
import com.convoy.mobile.dataModel.alert.SosRequest
import com.convoy.mobile.interfaces.AlertInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRepository @Inject constructor(
    private val alertApi: AlertInterface,
) {

    suspend fun listOpen(tripId: String): NetworkResult<List<Alert>> {
        return when (val r = safeApiCall { alertApi.listAlerts(tripId) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.alerts.orEmpty())
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun acknowledge(tripId: String, alertId: String): NetworkResult<Alert> {
        return when (val r = safeApiCall { alertApi.acknowledge(tripId, alertId) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.alert)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun resolve(
        tripId: String,
        alertId: String,
        reason: String? = null,
        cancelled: Boolean = false,
    ): NetworkResult<Alert> {
        Log.d(TAG, "Resolving $alertId (cancelled=$cancelled)")
        val request = ResolveAlertRequest(reason = reason, cancelled = cancelled)
        return when (val r = safeApiCall { alertApi.resolve(tripId, alertId, request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.alert)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun raiseSos(
        tripId: String,
        lat: Double?,
        lng: Double?,
        note: String? = null,
        crash: Boolean = false,
    ): NetworkResult<SosData> {
        Log.w(TAG, "SOS raised for trip $tripId")
        val request = SosRequest(
            type = if (crash) "CRASH" else "SOS",
            lat = lat,
            lng = lng,
            note = note?.trim()?.ifBlank { null },
        )
        return when (val r = safeApiCall { alertApi.raiseSos(tripId, request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    private companion object {
        const val TAG = "AlertRepository"
    }
}
