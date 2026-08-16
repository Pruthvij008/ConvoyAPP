package com.convoy.mobile.utility

/**
 * Values shared across the app. The staleness and cadence numbers mirror the
 * backend's config so both ends agree on what "live" means — if they drift,
 * the server and the app disagree about whether a dot is trustworthy.
 */
object Constants {

    const val PREFS_NAME = "convoy_prefs"

    // ── Staleness (mirrors backend config.location) ─────────────
    /** A fix newer than this is drawn solid. */
    const val STALE_AFTER_SEC = 30
    /** Past this the dot greys out entirely and is labelled last-known. */
    const val LOST_AFTER_SEC = 180

    // ── Location cadence ────────────────────────────────────────
    // The radio costs more battery than the GPS chip, so a parked car sends
    // a heartbeat rather than positions, and a highway cruise does not need
    // a fix every second.
    const val PING_INTERVAL_MOVING_MS = 15_000L
    const val PING_INTERVAL_TIGHT_MS = 5_000L      // approaching a stop, or a gap opening
    const val PING_INTERVAL_STATIONARY_MS = 150_000L
    const val PING_INTERVAL_SAVER_MS = 45_000L
    /** Below this the app forces Saver mode and tells the group. */
    const val LOW_BATTERY_FORCE_SAVER_PCT = 20

    /** Below this speed a vehicle counts as stopped (GPS jitter, not motion). */
    const val STOPPED_SPEED_KMH = 5.0

    /** Stationary this long and the app offers the "why have you stopped?" sheet. */
    const val AUTO_STOP_PROMPT_SEC = 180

    // ── Socket ──────────────────────────────────────────────────
    const val SOCKET_RECONNECT_DELAY_MS = 1_000L
    const val SOCKET_RECONNECT_MAX_DELAY_MS = 15_000L

    // ── Notifications ───────────────────────────────────────────
    const val CHANNEL_TRACKING = "convoy_tracking"
    const val CHANNEL_ALERTS = "convoy_alerts"
    const val CHANNEL_CRITICAL = "convoy_critical"
    const val NOTIFICATION_ID_TRACKING = 1001

    // ── Service actions ─────────────────────────────────────────
    const val ACTION_START_TRACKING = "com.convoy.mobile.START_TRACKING"
    const val ACTION_STOP_TRACKING = "com.convoy.mobile.STOP_TRACKING"
    const val ACTION_PAUSE_SHARING = "com.convoy.mobile.PAUSE_SHARING"
    const val EXTRA_TRIP_ID = "extra_trip_id"
    const val EXTRA_VEHICLE_ID = "extra_vehicle_id"

    // ── Intent extras between activities ────────────────────────
    const val EXTRA_JOIN_TOKEN = "extra_join_token"
    const val EXTRA_JOIN_CODE = "extra_join_code"
    /** Lobby opened from a trip that is already running. */
    const val EXTRA_TRIP_RUNNING = "extra_trip_running"

    // ── Map ─────────────────────────────────────────────────────
    // OpenFreeMap: free vector tiles, no API key, no account, no per-request
    // billing. Vector rather than raster because a dark map has to be
    // RENDERED dark — darkening raster tiles with a brightness filter leaves
    // cached tiles at the wrong brightness and produces visible black
    // patches, which is exactly what happened.
    const val MAP_STYLE_DAY = "https://tiles.openfreemap.org/styles/positron"
    const val MAP_STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"
    const val MAP_DEFAULT_ZOOM = 13.5

    // Chosen search results zoom in further than the default. A result is a
    // specific place, and dropping the camera at city zoom would leave the
    // user unsure the pin actually landed on what they picked.
    const val MAP_PLACE_ZOOM = 16.0
}
