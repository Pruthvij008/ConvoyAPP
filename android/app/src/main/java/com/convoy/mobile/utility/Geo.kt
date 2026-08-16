package com.convoy.mobile.utility

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geo maths done on the device.
 *
 * Every one of these is free and instant. Asking a routing API "how far is
 * Rohit" would be a network call per vehicle per refresh — the exact cost
 * blowup the plan warns about (§4.7) — for numbers a phone can work out in
 * microseconds.
 *
 * These are straight-line ("as the crow flies") figures. That is honest for
 * "which way is he and is the gap growing", and deliberately NOT what the
 * backend's gap alerts use: those measure distance along the route, because
 * on a mountain road a car 500 m away straight-line can be 20 minutes back.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Metres between two points, via the haversine formula. */
    fun distanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Initial compass bearing from point 1 to point 2, in degrees clockwise
     * from true north.
     *
     * "Initial" matters: on a long line the bearing changes as you travel,
     * because meridians converge. Over the distances a convoy cares about
     * the difference is negligible, and recomputing every few seconds from
     * the current position makes it irrelevant anyway.
     */
    fun bearingDegrees(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lng2 - lng1)

        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)

        // atan2 returns -180..180; compass bearings are 0..360.
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /**
     * How fast the gap is closing, in km/h. Positive means you are gaining.
     *
     * This is the number that actually answers "will I catch them?", and it
     * is not the same as the difference in speeds. Two cars both doing
     * 80 km/h are closing at zero if they are going the same way — and at
     * 160 if they are heading towards each other. So the component of the
     * relative velocity ALONG the line between them is what matters.
     *
     * Returns null when either speed is unknown, so the UI can say "working
     * it out" rather than confidently showing a fabricated zero.
     */
    fun closingSpeedKmh(
        mySpeedKmh: Double?,
        myHeadingDeg: Double?,
        targetSpeedKmh: Double?,
        targetHeadingDeg: Double?,
        bearingToTargetDeg: Double,
    ): Double? {
        if (mySpeedKmh == null || myHeadingDeg == null) return null

        // My speed projected onto the line towards them: driving straight at
        // them counts fully, driving at right angles counts for nothing.
        val myComponent = mySpeedKmh * cos(Math.toRadians(myHeadingDeg - bearingToTargetDeg))

        // Their speed along that same line, which is opening the gap when
        // they are travelling away from me.
        val theirComponent = if (targetSpeedKmh != null && targetHeadingDeg != null) {
            targetSpeedKmh * cos(Math.toRadians(targetHeadingDeg - bearingToTargetDeg))
        } else {
            // A stationary target is the common case here — someone pulled
            // over waiting. Treating unknown as stopped is the right guess:
            // it makes the number depend only on how fast I am approaching.
            0.0
        }

        return myComponent - theirComponent
    }

    /**
     * Smallest angle between two compass bearings, -180..180.
     *
     * Needed because 350° and 10° are twenty degrees apart, not three
     * hundred and forty — a subtraction alone gets this wrong every time
     * the arrow crosses north.
     */
    fun angleDelta(from: Float, to: Float): Float {
        var delta = (to - from + 540f) % 360f - 180f
        if (abs(delta) < 0.0001f) delta = 0f
        return delta
    }
}
