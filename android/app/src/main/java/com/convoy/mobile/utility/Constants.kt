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
    // SENDING a position is what costs battery — it wakes the mobile radio.
    // ASKING the GPS for one is nearly free once the chip is already
    // tracking. They are therefore two separate schedules: PING_* is how
    // often the convoy hears from us, SAMPLE_* is how often we look.
    //
    // Collapsing the two is what made your own dot lag fifteen seconds
    // behind Google's, because your own position was only redrawn when it
    // came back from the server.
    const val PING_INTERVAL_MOVING_MS = 15_000L
    const val PING_INTERVAL_TIGHT_MS = 5_000L      // approaching a stop, or a gap opening
    const val PING_INTERVAL_STATIONARY_MS = 150_000L
    const val PING_INTERVAL_SAVER_MS = 45_000L

    /**
     * How often the GPS is read while moving.
     *
     * Two seconds is smooth enough that the dot tracks the road rather than
     * hopping between points on it, and the chip is continuously tracking at
     * this speed regardless — the phone is not powering the radio up and
     * down between fixes, so the marginal cost over a fifteen-second
     * interval is small.
     */
    const val SAMPLE_INTERVAL_MOVING_MS = 2_000L

    /**
     * How often the GPS is read while stopped.
     *
     * Not the 150 s publish cadence, because this is also how quickly the
     * app can notice you have started moving again. At 150 s a car pulling
     * away from a fuel stop stayed frozen on everyone's map for over two
     * minutes.
     */
    const val SAMPLE_INTERVAL_STATIONARY_MS = 20_000L

    /**
     * How long a vehicle must stay slow before sampling drops to the
     * stationary rate.
     *
     * Changing the sampling rate tears down and rebuilds the location
     * request, so it must not follow the speed instantly. In crawling
     * traffic speed sits either side of the stopped threshold for minutes
     * on end, and without this delay the request would be rebuilt every
     * couple of seconds. Speeding back up is immediate — only slowing down
     * waits.
     */
    const val SETTLED_BEFORE_SLOW_SAMPLING_MS = 60_000L

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

    /**
     * Zoom used when the roster asks to be shown a particular car.
     *
     * Closer than the convoy overview, because the question is "where
     * exactly are they" — but not the navigation zoom, which is so tight
     * you lose the surrounding roads and cannot tell where they are
     * relative to you.
     */
    const val MAP_FOCUS_ZOOM = 15.5

    // Chosen search results zoom in further than the default. A result is a
    // specific place, and dropping the camera at city zoom would leave the
    // user unsure the pin actually landed on what they picked.
    const val MAP_PLACE_ZOOM = 16.0

    // Navigation view. Close enough that individual turnings are
    // distinguishable, tilted so the road ahead fills the screen rather
    // than the sky above and the ground behind.
    const val MAP_NAV_ZOOM = 17.2
    const val MAP_NAV_TILT = 55.0
}
