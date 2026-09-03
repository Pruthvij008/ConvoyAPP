package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.trip.Trip
import com.convoy.mobile.dataModel.trip.TripStatus
import com.convoy.mobile.dataModel.vehicle.Freshness
import com.convoy.mobile.dataModel.vehicle.Participant
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.dataModel.trip.RouteCache
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.SocketEvent
import com.convoy.mobile.network.SocketManager
import com.convoy.mobile.repository.TripRepository
import com.convoy.mobile.utility.MyLocation
import com.convoy.mobile.utility.PrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The live trip screen.
 *
 * Owns the convoy's state while a trip is running. The map and the socket
 * layer attach here next; today it drives the roster and trip controls,
 * refreshing over REST.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: TripRepository,
    private val socketManager: SocketManager,
    private val prefs: PrefsManager,
    private val myLocation: MyLocation,
) : ViewModel() {

    private var tripId: String? = null
    private var refreshJob: Job? = null

    /**
     * Where THIS phone is, according to this phone.
     *
     * Everyone else's position has to come over the socket; ours does not,
     * and routing it through the server cost us up to a full publish
     * interval of lag on the one dot the user is looking at hardest.
     */
    var myFix by mutableStateOf<MyLocation.Fix?>(null)
        private set

    /** Reset on any success — only a sustained refusal ends the trip. */
    private var consecutiveRejections = 0

    /** Live positions arriving over the socket, keyed by vehicle id. */
    private val livePositions = mutableMapOf<String, LivePosition>()

    var socketConnected by mutableStateOf(false)
        private set

    // ── Directions ──────────────────────────────────────────────
    // The route from MY position to the destination, fetched when the user
    // asks for it. Distinct from Trip.routeCache, which is the shared
    // origin-to-destination line everyone sees.
    var myRoute by mutableStateOf<RouteCache?>(null)
        private set
    var isRouting by mutableStateOf(false)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set

    var trip by mutableStateOf<Trip?>(null)
        private set
    var me by mutableStateOf<Participant?>(null)
        private set
    var participants by mutableStateOf<List<Participant>>(emptyList())
        private set
    var vehicles by mutableStateOf<List<Vehicle>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Set when the trip finishes, so the Activity returns to the empty state. */
    var tripFinished by mutableStateOf(false)
        private set

    val hasTrip: Boolean get() = trip != null
    val amHost: Boolean get() = me?.canManageTrip == true

    /** My own vehicle, so the roster can mark it "you" rather than a distance. */
    val myVehicleId: String? get() = me?.vehicleId

    /** "3 of 4 · all moving" — the strip across the top of the map. */
    val convoySummary: String
        get() {
            val total = vehicles.size
            if (total == 0) return "No vehicles yet"
            val live = vehicles.count { it.freshness == Freshness.LIVE }
            val moving = vehicles.count { !it.isStopped && it.freshness == Freshness.LIVE }
            return when {
                live == 0 -> "$total cars · waiting for a signal"
                moving == live -> "$live of $total · all moving"
                moving == 0 -> "$live of $total · all stopped"
                else -> "$live of $total · $moving moving"
            }
        }

    fun load(tripId: String) {
        // MainActivity is singleTask, so this ViewModel outlives any single
        // trip. Without resetting here, a `tripFinished` left true by an
        // earlier trip pins the screen to the empty state forever — and the
        // Activity's finished-handler keeps wiping the saved trip id.
        if (this.tripId != tripId) {
            trip = null
            me = null
            participants = emptyList()
            vehicles = emptyList()
        }
        tripFinished = false
        errorMessage = null

        this.tripId = tripId
        refresh()
        startPolling()
        observeSocket()
    }

    /**
     * The socket carries positions; REST carries everything slower-moving.
     * A vehicle:moved event updates just that car rather than refetching the
     * whole trip, which is the entire reason the socket exists.
     */
    private fun observeSocket() {
        viewModelScope.launch {
            socketManager.connected.collect { socketConnected = it }
        }
        // Our own GPS, arriving every couple of seconds while moving rather
        // than every fifteen. This is what makes our dot keep up with the
        // car instead of trailing it.
        viewModelScope.launch {
            myLocation.fix.collect { fix ->
                myFix = fix
                mergeLivePositions()
            }
        }
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.VehicleMoved -> applyMovement(event.payload)
                    is SocketEvent.Snapshot -> applySnapshot(event.payload)
                    is SocketEvent.TripEnded -> {
                        tripFinished = true
                        refreshJob?.cancel()
                    }
                    // Markers and alerts change the roster's status text, and
                    // are rare enough that a refresh is cheaper than parsing.
                    is SocketEvent.MarkerCreated,
                    is SocketEvent.MarkerCleared,
                    is SocketEvent.AlertRaised -> refresh(silent = true)
                    // Sent when the host starts the trip. Anyone already
                    // sitting in the lobby gets the line drawn without a
                    // refetch.
                    is SocketEvent.RouteReady -> refresh(silent = true)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Directions from where I am to where the trip is going.
     *
     * Needs a position to route FROM, which is why this reports a clear
     * refusal rather than silently doing nothing when there is no fix yet.
     */
    /**
     * Routes from where I am to [toLat]/[toLng], or to the trip destination
     * when no target is given.
     *
     * One function for every navigable thing — destination, a friend with a
     * puncture, an SOS, a waypoint — because they are all the same question.
     */
    fun requestMyRoute(toLat: Double? = null, toLng: Double? = null) {
        val id = trip?.id ?: return

        // Our own GPS first. Routing from the server's idea of where we are
        // meant directions could begin up to a publish interval back down
        // the road — far enough, at highway speed, to start the route with a
        // turn we have already gone past.
        val mine = myFix
        val position = vehicles.firstOrNull { it.id == myVehicleId }?.position

        val lat = mine?.lat ?: position?.lat
        val lng = mine?.lng ?: position?.lng
        if (lat == null || lng == null) {
            routeError = "Waiting for your position — directions need to know where you are."
            return
        }

        viewModelScope.launch {
            isRouting = true
            routeError = null
            when (val r = repository.getMyRoute(id, lat, lng, toLat, toLng)) {
                is NetworkResult.Success -> myRoute = r.data
                is NetworkResult.Error -> routeError = r.message
                NetworkResult.Loading -> Unit
            }
            isRouting = false
        }
    }

    // ── Going to help someone ───────────────────────────────────
    /** The vehicle I have told the group I am heading to, if any. */
    var helpingVehicleId by mutableStateOf<String?>(null)
        private set

    fun tellThemImComing(vehicleId: String) {
        val id = trip?.id ?: return
        viewModelScope.launch {
            when (val r = repository.startHelping(id, vehicleId)) {
                is NetworkResult.Success -> helpingVehicleId = vehicleId
                is NetworkResult.Error -> routeError = r.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * No longer going — the puncture got fixed, or you arrived.
     *
     * Clears the drawn route too: a line to a car you are no longer heading
     * for is worse than no line, because it silently contradicts what the
     * group has just been told.
     */
    fun stopHelping() {
        val id = trip?.id ?: return
        viewModelScope.launch {
            when (val r = repository.stopHelping(id)) {
                is NetworkResult.Success -> {
                    helpingVehicleId = null
                    myRoute = null
                }
                is NetworkResult.Error -> routeError = r.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearRouteError() { routeError = null }

    private fun applyMovement(payload: org.json.JSONObject) {
        val vehicleId = payload.optString("vehicleId").ifBlank { return }
        livePositions[vehicleId] = LivePosition(
            lat = payload.optDouble("lat"),
            lng = payload.optDouble("lng"),
            speedKmh = payload.optDouble("speedKmh").takeIf { !it.isNaN() },
            heading = payload.optDouble("heading").takeIf { !it.isNaN() },
            accuracyM = payload.optDouble("accuracyM").takeIf { !it.isNaN() },
            batteryPct = payload.optInt("batteryPct", -1).takeIf { it >= 0 },
            receivedAt = System.currentTimeMillis(),
        )
        mergeLivePositions()
    }

    private fun applySnapshot(payload: org.json.JSONObject) {
        val array = payload.optJSONArray("vehicles") ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val position = item.optJSONObject("position") ?: continue
            val vehicleId = item.optString("vehicleId")
            if (vehicleId.isBlank()) continue
            livePositions[vehicleId] = LivePosition(
                lat = position.optDouble("lat"),
                lng = position.optDouble("lng"),
                speedKmh = position.optDouble("speedKmh").takeIf { !it.isNaN() },
                heading = position.optDouble("heading").takeIf { !it.isNaN() },
                accuracyM = position.optDouble("accuracyM").takeIf { !it.isNaN() },
                batteryPct = position.optInt("batteryPct", -1).takeIf { it >= 0 },
                receivedAt = System.currentTimeMillis(),
            )
        }
        mergeLivePositions()
    }

    /**
     * Overlays live positions onto the REST-loaded vehicles.
     *
     * Two sources, in order of authority. Everyone else's position arrives
     * over the socket. OUR OWN comes from this phone's GPS and wins over
     * whatever the socket says about us, because the socket's version of us
     * is the same fix after a round trip through the server — older by
     * definition, and never newer.
     *
     * lastFixAgeSec is recomputed from when WE learned the position rather
     * than trusting a stored value, so a dot that stops arriving visibly
     * ages instead of sitting there looking live.
     */
    private fun mergeLivePositions() {
        val mine = myFix
        if (livePositions.isEmpty() && mine == null) return
        val now = System.currentTimeMillis()
        val myId = myVehicleId

        vehicles = vehicles.map { vehicle ->
            if (mine != null && myId != null && vehicle.id == myId) {
                return@map vehicle.copy(
                    lastKnown = com.convoy.mobile.dataModel.vehicle.LastKnown(
                        point = com.convoy.mobile.dataModel.common.GeoPoint.of(mine.lat, mine.lng),
                        heading = mine.headingDeg,
                        speedKmh = mine.speedKmh,
                        accuracyM = mine.accuracyM,
                        // Ours alone is not carried locally — it is the one
                        // number the phone cannot read about itself here —
                        // so the socket's last word on it is kept.
                        batteryPct = livePositions[vehicle.id]?.batteryPct
                            ?: vehicle.lastKnown?.batteryPct,
                    ),
                    lastFixAgeSec = ((now - mine.at) / 1000).toInt(),
                )
            }

            val live = livePositions[vehicle.id] ?: return@map vehicle
            vehicle.copy(
                lastKnown = com.convoy.mobile.dataModel.vehicle.LastKnown(
                    point = com.convoy.mobile.dataModel.common.GeoPoint.of(live.lat, live.lng),
                    heading = live.heading,
                    speedKmh = live.speedKmh,
                    // Was dropped on every socket update, which quietly wiped
                    // the "±12 m" the map shows when a dot is tapped — the
                    // one readout that tells a real GPS lock from a guess.
                    accuracyM = live.accuracyM,
                    batteryPct = live.batteryPct,
                ),
                lastFixAgeSec = ((now - live.receivedAt) / 1000).toInt(),
            )
        }
    }

    data class LivePosition(
        val lat: Double,
        val lng: Double,
        val speedKmh: Double?,
        val heading: Double?,
        val accuracyM: Double?,
        val batteryPct: Int?,
        val receivedAt: Long,
    )

    /**
     * Interim refresh loop. Positions move to the socket next — polling a
     * live convoy would be exactly the battery mistake the socket exists to
     * avoid — but the roster still needs to be right in the meantime.
     */
    private fun startPolling() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                if (trip?.isFinished != true) refresh(silent = true)
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        val id = tripId ?: return

        viewModelScope.launch {
            if (!silent) isLoading = true

            when (val result = repository.getTrip(id)) {
                is NetworkResult.Success -> {
                    consecutiveRejections = 0
                    trip = result.data.trip
                    me = result.data.me
                    // Persisted so the tracking service can tell our own
                    // chat messages from everyone else's without a screen
                    // being open to ask.
                    prefs.activeParticipantId = result.data.me.id
                    participants = result.data.participants.orEmpty()
                    vehicles = result.data.vehicles.orEmpty()
                    // REST just replaced the list, so re-overlay anything the
                    // socket has told us since — otherwise a live dot jumps
                    // back to its last persisted position every 8 seconds.
                    mergeLivePositions()

                    if (result.data.trip.isFinished) {
                        tripFinished = true
                        refreshJob?.cancel()
                    }
                }
                is NetworkResult.Error -> {
                    // Only give up on the trip after REPEATED refusals. A
                    // single 403/404 is often a request that raced a token
                    // refresh or a momentary drop, and throwing the user out
                    // of a running trip for that is far worse than waiting.
                    if (result.code == 403 || result.code == 404) {
                        consecutiveRejections += 1
                        if (consecutiveRejections >= MAX_REJECTIONS) {
                            prefs.activeTripId = null
                            tripFinished = true
                        }
                    } else if (!silent) {
                        errorMessage = result.message
                    }
                }
                NetworkResult.Loading -> Unit
            }

            if (!silent) isLoading = false
        }
    }

    /**
     * Ending stops sharing for EVERYONE, enforced on the server. It does not
     * depend on any phone still being awake to hear about it.
     */
    fun endTrip() {
        val id = tripId ?: return

        viewModelScope.launch {
            isLoading = true
            when (val result = repository.updateStatus(id, TripStatus.ENDED)) {
                is NetworkResult.Success -> {
                    trip = result.data
                    tripFinished = true
                    refreshJob?.cancel()
                }
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    fun pauseTrip() {
        val id = tripId ?: return
        val next = if (trip?.status == TripStatus.PAUSED) TripStatus.ACTIVE else TripStatus.PAUSED

        viewModelScope.launch {
            when (val result = repository.updateStatus(id, next)) {
                is NetworkResult.Success -> trip = result.data
                is NetworkResult.Error -> errorMessage = result.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun leaveTrip(onDone: () -> Unit) {
        val id = tripId ?: return

        viewModelScope.launch {
            when (repository.leaveTrip(id)) {
                is NetworkResult.Success -> {
                    tripFinished = true
                    onDone()
                }
                is NetworkResult.Error -> errorMessage = "Could not leave the trip."
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun dismissError() { errorMessage = null }

    override fun onCleared() {
        refreshJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MapViewModel"
        const val REFRESH_INTERVAL_MS = 8_000L
        const val MAX_REJECTIONS = 3
    }
}
