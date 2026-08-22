package com.convoy.mobile.network

import android.util.Log
import com.convoy.mobile.utility.PrefsManager
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live layer.
 *
 * One socket, one room per trip. A position update is a single emit that the
 * server fans out to everyone else in that room — N work, not N².
 *
 * The JWT goes in the handshake rather than in each message: the connection
 * is authorised once, before it exists, so an unauthorised phone never gets
 * a socket at all rather than getting one and being filtered afterwards.
 */
@Singleton
class SocketManager @Inject constructor(
    private val prefs: PrefsManager,
    private val gson: Gson,
) {
    private var socket: Socket? = null
    private var tripId: String? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Everything the server pushes. A SharedFlow rather than a StateFlow
     * because these are events, not state — a second "vehicle moved" for the
     * same car is meaningful, not a duplicate to be conflated away.
     */
    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    fun connect(tripId: String) {
        val token = prefs.token
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No token — not connecting.")
            return
        }
        if (socket != null && this.tripId == tripId && socket?.connected() == true) return

        disconnect()
        this.tripId = tripId

        val options = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            // This app runs in moving cars through tunnels and dead zones.
            // The connection WILL drop, repeatedly, on every trip — so
            // reconnection is not optional and the backoff is capped so a
            // long tunnel does not leave the phone waiting minutes.
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(15_000)
            .setAuth(mapOf("token" to token, "tripId" to tripId))
            .build()

        // IO.socket throws on a malformed URI. This is called from the
        // location service's onStartCommand, so an unparseable SOCKET_URL —
        // a bad build config, a typo'd host — would not surface as "cannot
        // connect" but as the tracking service dying the moment a trip
        // starts, taking location sharing with it.
        val created = try {
            IO.socket(ApiEndpoints.SOCKET_URL, options)
        } catch (e: Exception) {
            Log.e(TAG, "Bad socket URL '${ApiEndpoints.SOCKET_URL}': ${e.message}")
            _connected.value = false
            return
        }

        socket = created.apply {
            on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected to trip $tripId")
                _connected.value = true
            }
            on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Disconnected")
                _connected.value = false
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connect error: ${args.firstOrNull()}")
                _connected.value = false
            }

            // A socket only carries what happens AFTER it connects, so the
            // server sends a full snapshot on join and on every reconnect.
            // Without it a phone coming out of a tunnel shows a blank map
            // until each car happens to move again.
            on(EVENT_SNAPSHOT) { args -> emit(SocketEvent.Snapshot(args.json())) }
            on(EVENT_VEHICLE_MOVED) { args -> emit(SocketEvent.VehicleMoved(args.json())) }
            on(EVENT_MARKER_CREATED) { args -> emit(SocketEvent.MarkerCreated(args.json())) }
            on(EVENT_MARKER_CLEARED) { args -> emit(SocketEvent.MarkerCleared(args.json())) }
            on(EVENT_ALERT_RAISED) { args -> emit(SocketEvent.AlertRaised(args.json())) }
            on(EVENT_ALERT_SOS) { args -> emit(SocketEvent.Sos(args.json())) }
            // Without this, an SOS cleared by the person who raised it stays
            // on screen forever on every OTHER phone — a permanent false
            // emergency, which is worse than no alert at all.
            on(EVENT_ALERT_RESOLVED) { args -> emit(SocketEvent.AlertResolved(args.json())) }
            on(EVENT_ALERT_ACKED) { args -> emit(SocketEvent.AlertAcknowledged(args.json())) }
            on(EVENT_MESSAGE_NEW) { args -> emit(SocketEvent.MessageNew(args.json())) }
            on(EVENT_TRIP_ENDED) { args -> emit(SocketEvent.TripEnded(args.json())) }
            on(EVENT_ROUTE_READY) { args -> emit(SocketEvent.RouteReady(args.json())) }

            connect()
        }
    }

    /**
     * Fire-and-forget by design. A position is superseded within seconds, so
     * a dropped one costs nothing — unlike a marker or a message, which are
     * written over REST first and only then broadcast.
     */
    fun sendPosition(
        lat: Double,
        lng: Double,
        speedKmh: Double? = null,
        heading: Double? = null,
        accuracyM: Double? = null,
        batteryPct: Int? = null,
    ) {
        val live = socket ?: return
        if (!live.connected()) return

        val payload = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            speedKmh?.let { put("speedKmh", it) }
            heading?.let { put("heading", it) }
            accuracyM?.let { put("accuracyM", it) }
            batteryPct?.let { put("batteryPct", it) }
        }

        live.emit(EVENT_POSITION_UPDATE, payload)
    }

    /** Pausing is visible to the group — there are no invisible observers. */
    fun setSharing(sharing: Boolean) {
        socket?.emit(EVENT_SHARING_SET, JSONObject().put("sharing", sharing))
    }

    fun requestResync() {
        socket?.emit(EVENT_RESYNC, JSONObject())
    }

    fun disconnect() {
        socket?.apply {
            off()
            disconnect()
            close()
        }
        socket = null
        tripId = null
        _connected.value = false
    }

    private fun emit(event: SocketEvent) {
        // tryEmit returns false when the buffer is full, and the event is
        // then silently gone. Sixty-four deep, that should never happen —
        // but "should never happen" is exactly the kind of loss that would
        // otherwise present as a missed SOS with nothing in the log.
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "Dropped socket event ${event::class.simpleName} — buffer full")
        }
    }

    private fun Array<Any>.json(): JSONObject =
        (firstOrNull() as? JSONObject) ?: JSONObject()

    companion object {
        private const val TAG = "SocketManager"

        // Outgoing
        const val EVENT_POSITION_UPDATE = "position:update"
        const val EVENT_SHARING_SET = "sharing:set"
        const val EVENT_RESYNC = "trip:resync"

        // Incoming
        const val EVENT_SNAPSHOT = "trip:snapshot"
        const val EVENT_VEHICLE_MOVED = "vehicle:moved"
        const val EVENT_MARKER_CREATED = "marker:created"
        const val EVENT_MARKER_CLEARED = "marker:cleared"
        const val EVENT_ALERT_RAISED = "alert:raised"
        const val EVENT_ALERT_SOS = "alert:sos"
        const val EVENT_ALERT_RESOLVED = "alert:resolved"
        const val EVENT_ALERT_ACKED = "alert:acknowledged"
        const val EVENT_MESSAGE_NEW = "message:new"
        const val EVENT_TRIP_ENDED = "trip:ended"
        // Sent when the host starts the trip and the server has the route.
        // Members already sitting in the lobby get the line without a refetch.
        const val EVENT_ROUTE_READY = "route:ready"
    }
}

/** What the server pushes, as typed events rather than raw JSON at call sites. */
sealed class SocketEvent {
    data class Snapshot(val payload: JSONObject) : SocketEvent()
    data class VehicleMoved(val payload: JSONObject) : SocketEvent()
    data class MarkerCreated(val payload: JSONObject) : SocketEvent()
    data class MarkerCleared(val payload: JSONObject) : SocketEvent()
    data class AlertRaised(val payload: JSONObject) : SocketEvent()
    data class Sos(val payload: JSONObject) : SocketEvent()
    data class AlertResolved(val payload: JSONObject) : SocketEvent()
    data class AlertAcknowledged(val payload: JSONObject) : SocketEvent()
    data class MessageNew(val payload: JSONObject) : SocketEvent()
    data class TripEnded(val payload: JSONObject) : SocketEvent()
    data class RouteReady(val payload: JSONObject) : SocketEvent()
}
