package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.alert.Alert
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.SocketEvent
import com.convoy.mobile.network.SocketManager
import com.convoy.mobile.repository.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Alerts, and the SOS button.
 *
 * The SOS countdown lives here rather than on the server: it must be
 * cancellable, and a request already in flight cannot be taken back. Ten
 * seconds is the difference between a panic button people trust and one
 * they are afraid to have on screen.
 */
@HiltViewModel
class AlertViewModel @Inject constructor(
    private val repository: AlertRepository,
    private val socketManager: SocketManager,
) : ViewModel() {

    private var tripId: String? = null
    private var countdownJob: Job? = null

    var alerts by mutableStateOf<List<Alert>>(emptyList())
        private set

    /** Locally dismissed, so a banner someone waved away stays away. */
    private var dismissed by mutableStateOf<Set<String>>(emptySet())

    var isSaving by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // ── SOS ─────────────────────────────────────────────────────
    var sosCountdown by mutableStateOf<Int?>(null)
        private set
    var sosSent by mutableStateOf(false)
        private set
    var sosShareUrl by mutableStateOf<String?>(null)
        private set

    val countdownRunning: Boolean get() = sosCountdown != null

    /** The one alert that takes over the screen, if any. */
    val criticalAlert: Alert?
        get() = alerts.firstOrNull { it.isCritical && it.isLive && it.id !in dismissed }

    /** Everything else, as banners over the map. */
    val banners: List<Alert>
        get() = alerts.filter { it.isLive && !it.isCritical && it.id !in dismissed }

    fun bind(tripId: String) {
        if (this.tripId == tripId) return
        this.tripId = tripId
        refresh()
        observeSocket()
    }

    private fun observeSocket() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    // Both arrive as full alert payloads, but refetching keeps
                    // one code path for de-duplication and ordering.
                    is SocketEvent.AlertRaised, is SocketEvent.Sos -> refresh()

                    // Removed locally FIRST, then refetched. Waiting for the
                    // round trip would leave a full-screen emergency on
                    // someone's windscreen for another second after it was
                    // called off, which is exactly when it matters least and
                    // frightens most.
                    is SocketEvent.AlertResolved -> {
                        val resolvedId = event.payload.optString("alertId")
                        if (resolvedId.isNotBlank()) {
                            alerts = alerts.filterNot { it.id == resolvedId }
                        }
                        refresh()
                    }

                    is SocketEvent.AlertAcknowledged -> refresh()
                    else -> Unit
                }
            }
        }
    }

    fun refresh() {
        val id = tripId ?: return
        viewModelScope.launch {
            when (val r = repository.listOpen(id)) {
                is NetworkResult.Success -> alerts = r.data
                is NetworkResult.Error -> Log.w(TAG, "Alert fetch failed: ${r.message}")
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Waving a banner away is local — the condition is still live. */
    fun dismiss(alert: Alert) {
        dismissed = dismissed + alert.id
    }

    fun acknowledge(alert: Alert) {
        val id = tripId ?: return
        viewModelScope.launch {
            when (val r = repository.acknowledge(id, alert.id)) {
                is NetworkResult.Success -> refresh()
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun resolve(alert: Alert, reason: String? = null) {
        val id = tripId ?: return
        viewModelScope.launch {
            isSaving = true
            when (val r = repository.resolve(id, alert.id, reason)) {
                is NetworkResult.Success -> {
                    if (alert.isSos) { sosSent = false; sosShareUrl = null }
                    refresh()
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isSaving = false
        }
    }

    // ── The panic button ────────────────────────────────────────

    /**
     * Starts the cancellable countdown. Nothing leaves the phone until it
     * reaches zero, which is what makes an accidental press harmless.
     */
    fun startSosCountdown(lat: Double?, lng: Double?) {
        if (countdownRunning) return

        countdownJob = viewModelScope.launch {
            for (remaining in SOS_COUNTDOWN_SEC downTo 1) {
                sosCountdown = remaining
                delay(1_000)
            }
            sosCountdown = null
            sendSos(lat, lng)
        }
    }

    fun cancelSosCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        sosCountdown = null
    }

    private fun sendSos(lat: Double?, lng: Double?) {
        val id = tripId ?: return
        viewModelScope.launch {
            isSaving = true
            when (val r = repository.raiseSos(id, lat, lng)) {
                is NetworkResult.Success -> {
                    sosSent = true
                    sosShareUrl = r.data.shareUrl
                    refresh()
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isSaving = false
        }
    }

    fun dismissError() { errorMessage = null }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AlertViewModel"
        /** Long enough to catch a pocket press, short enough to matter. */
        const val SOS_COUNTDOWN_SEC = 10
    }
}
