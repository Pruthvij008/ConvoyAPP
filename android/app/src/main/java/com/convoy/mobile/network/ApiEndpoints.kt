package com.convoy.mobile.network

import com.convoy.mobile.BuildConfig

/**
 * Every URL in the app, in one place.
 *
 * Retrofit interfaces reference these constants directly rather than
 * repeating path strings, so a route that changes on the server is a
 * one-line edit here.
 */
object ApiEndpoints {

    // Swapped per build type: the emulator reaches the host machine on
    // 10.0.2.2, production points at the real API.
    const val BASE_URL = BuildConfig.BASE_URL + "api/v1/"
    const val SOCKET_URL = BuildConfig.SOCKET_URL

    // ── Auth ────────────────────────────────────────────────────
    // Username + password is the primary sign-in. No email, so there is
    // nothing to verify and no OTP round trip before joining a convoy.
    const val REGISTER = "auth/register"
    const val LOGIN = "auth/login-username"
    /** Guest identity, kept for joining without an account. */
    const val DEVICE_AUTH = "auth/device"
    const val ME = "users/me"

    // Optional profile photo. Signed, uploaded direct to Cloudinary, then
    // verified server-side before it can be attached.
    const val AVATAR_SIGNATURE = "users/me/avatar/signature"
    const val AVATAR = "users/me/avatar"

    // ── Trips ───────────────────────────────────────────────────
    const val TRIPS = "trips"
    const val TRIP_PREVIEW = "trips/preview"
    const val TRIP_JOIN = "trips/join"
    const val TRIP_BY_ID = "trips/{tripId}"
    const val TRIP_STATUS = "trips/{tripId}/status"
    const val TRIP_LOBBY = "trips/{tripId}/lobby"
    const val TRIP_READY = "trips/{tripId}/ready"
    const val TRIP_LEAVE = "trips/{tripId}/leave"
    const val TRIP_TRANSFER_HOST = "trips/{tripId}/transfer-host"
    const val TRIP_ROTATE_INVITE = "trips/{tripId}/invite/rotate"
    const val TRIP_REQUESTS = "trips/{tripId}/requests"
    const val TRIP_REQUEST_DECIDE = "trips/{tripId}/requests/{participantId}"
    const val TRIP_PARTICIPANT = "trips/{tripId}/participants/{participantId}"
    const val TRIP_SHARE = "trips/{tripId}/share"

    // ── Vehicles ────────────────────────────────────────────────
    const val VEHICLES = "trips/{tripId}/vehicles"
    const val VEHICLE_BY_ID = "trips/{tripId}/vehicles/{vehicleId}"
    const val VEHICLE_BOARD = "trips/{tripId}/vehicles/{vehicleId}/board"

    // ── Markers ─────────────────────────────────────────────────
    const val MARKERS = "trips/{tripId}/markers"
    const val MARKER_BY_ID = "trips/{tripId}/markers/{markerId}"
    const val MARKER_CLEAR = "trips/{tripId}/markers/{markerId}/clear"
    const val MARKER_SET = "trips/{tripId}/marker-set"

    // Directions from wherever the caller is right now to the destination.
    // Per-person by nature — everyone in the convoy is somewhere different.
    const val TRIP_MY_ROUTE = "trips/{tripId}/route"

    // Telling the convoy you have peeled off to reach someone.
    const val TRIP_HELP = "trips/{tripId}/help"
    const val MARKER_SET_ENTRY = "trips/{tripId}/marker-set/{key}"
    const val MARKER_CATALOGUE = "markers/catalogue"
    const val MARKER_LIBRARY = "markers/library"
    const val MARKER_LIBRARY_ENTRY = "markers/library/{key}"

    // ── Waypoints ───────────────────────────────────────────────
    const val WAYPOINTS = "trips/{tripId}/waypoints"
    const val WAYPOINT_BY_ID = "trips/{tripId}/waypoints/{waypointId}"
    const val WAYPOINT_VOTE = "trips/{tripId}/waypoints/{waypointId}/vote"
    const val WAYPOINT_ARRIVE = "trips/{tripId}/waypoints/{waypointId}/arrive"
    const val WAYPOINT_REORDER = "trips/{tripId}/waypoints/reorder"

    // ── Messages ────────────────────────────────────────────────
    const val MESSAGES = "trips/{tripId}/messages"
    const val MESSAGES_READ = "trips/{tripId}/messages/read"
    const val QUICK_MESSAGES = "markers/quick-messages"

    // ── Alerts ──────────────────────────────────────────────────
    const val ALERTS = "trips/{tripId}/alerts"
    const val ALERT_ACK = "trips/{tripId}/alerts/{alertId}/ack"
    const val ALERT_RESOLVE = "trips/{tripId}/alerts/{alertId}/resolve"
    const val SOS = "trips/{tripId}/sos"

    // ── Media ───────────────────────────────────────────────────
    const val MEDIA_SIGNATURE = "trips/{tripId}/media/signature"
    const val MEDIA_CONFIRM = "trips/{tripId}/media/confirm"
    const val MEDIA_CONFIG = "markers/media-config"

    // ── Places ──────────────────────────────────────────────────
    // Geocoding is proxied through our server rather than called from the
    // phone: the free providers require an identifying User-Agent and rate
    // limit per application, so one server is compliant where N phones
    // are not.
    const val PLACES_SEARCH = "places/search"
    const val PLACES_REVERSE = "places/reverse"
}
