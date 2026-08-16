package com.convoy.mobile.utility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's own position, straight from its own GPS.
 *
 * It exists because of one specific bug: the map drew YOUR dot from the
 * vehicle list, and that list comes back from the server. Your own position
 * was therefore making a round trip — GPS, socket, Redis, broadcast, back
 * down to the phone that produced it — before the phone would draw it.
 *
 * The cost of that trip is not the network latency, it is the PUBLISH
 * CADENCE. Positions go out every fifteen seconds while moving to protect
 * the battery, so your own dot was up to fifteen seconds and several hundred
 * metres behind where you actually were. Next to Google Maps, which draws
 * from the GPS chip directly, it read as broken — and it was.
 *
 * Everyone ELSE's dot still comes over the socket, because there is no other
 * way to know where they are. Only your own short-circuits, and there is no
 * reason it should ever have gone the long way round.
 */
@Singleton
class MyLocation @Inject constructor() {

    /**
     * One fix from this device.
     *
     * [at] is the phone's own clock, which is what makes the age honest:
     * a fix is stale relative to now, not relative to whenever a server
     * happened to write it down.
     */
    data class Fix(
        val lat: Double,
        val lng: Double,
        val speedKmh: Double?,
        val headingDeg: Double?,
        val accuracyM: Double?,
        val at: Long = System.currentTimeMillis(),
    )

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    /** The last fix, for callers that need a value rather than a stream. */
    val current: Fix? get() = _fix.value

    fun update(fix: Fix) { _fix.value = fix }

    /**
     * Forgotten when tracking stops.
     *
     * A position left behind after a trip ends would be drawn on the next
     * trip's map as though it were current — which, having been read
     * straight from the GPS, would look entirely convincing.
     */
    fun clear() { _fix.value = null }
}
