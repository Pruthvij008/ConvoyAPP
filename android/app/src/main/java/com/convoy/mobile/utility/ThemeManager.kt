package com.convoy.mobile.utility

import com.convoy.mobile.ui.theme.ThemeMode
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Decides whether the app is currently in its night or day skin.
 *
 * AUTO follows the real sunset where the user is, not a fixed hour, because
 * "dark after 6pm" is wrong for most of the year — 7pm is pitch dark in
 * December and broad daylight in June. The device already reports its
 * location for the trip, so the correct answer costs nothing.
 */
@Singleton
class ThemeManager @Inject constructor(
    private val prefs: PrefsManager,
) {
    /** Last known position, updated by the location service. */
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    fun updateLocation(lat: Double, lng: Double) {
        lastLat = lat
        lastLng = lng
    }

    var mode: ThemeMode
        get() = prefs.themeMode
        set(value) { prefs.themeMode = value }

    /**
     * True when the sun is down. Falls back to a plain hour check when no
     * position is known yet — on first launch, before any permission is
     * granted, an approximate answer beats no answer.
     */
    fun isNight(now: Calendar = Calendar.getInstance()): Boolean {
        val lat = lastLat
        val lng = lastLng
        if (lat == null || lng == null) {
            val hour = now.get(Calendar.HOUR_OF_DAY)
            return hour < 6 || hour >= 19
        }

        val sunrise = sunEventMinutes(lat, lng, now, sunrise = true)
        val sunset = sunEventMinutes(lat, lng, now, sunrise = false)
        if (sunrise == null || sunset == null) return true // polar day/night

        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return minutesNow < sunrise || minutesNow >= sunset
    }

    /** For the settings screen: "sunset in Pune today is 6:52 pm". */
    fun sunsetLabel(now: Calendar = Calendar.getInstance()): String? {
        val lat = lastLat ?: return null
        val lng = lastLng ?: return null
        val minutes = sunEventMinutes(lat, lng, now, sunrise = false) ?: return null
        return formatMinutes(minutes)
    }

    fun sunriseLabel(now: Calendar = Calendar.getInstance()): String? {
        val lat = lastLat ?: return null
        val lng = lastLng ?: return null
        val minutes = sunEventMinutes(lat, lng, now, sunrise = true) ?: return null
        return formatMinutes(minutes)
    }

    private fun formatMinutes(totalMinutes: Int): String {
        val h24 = (totalMinutes / 60) % 24
        val m = totalMinutes % 60
        val suffix = if (h24 < 12) "am" else "pm"
        val h12 = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        return "%d:%02d %s".format(h12, m, suffix)
    }

    /**
     * Sunrise/sunset as minutes past local midnight, using the standard
     * NOAA approximation. Accurate to a couple of minutes, which is far
     * more precision than a theme switch needs.
     */
    private fun sunEventMinutes(
        lat: Double,
        lng: Double,
        cal: Calendar,
        sunrise: Boolean,
    ): Int? {
        val zenith = 90.833 // includes atmospheric refraction
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        val lngHour = lng / 15.0
        val t = dayOfYear + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0

        val meanAnomaly = (0.9856 * t) - 3.289
        var trueLong = meanAnomaly +
            (1.916 * sin(meanAnomaly.rad)) +
            (0.020 * sin(2 * meanAnomaly.rad)) + 282.634
        trueLong = trueLong.mod360()

        var rightAsc = Math.toDegrees(Math.atan(0.91764 * tan(trueLong.rad))).mod360()
        // Right ascension has to land in the same quadrant as the longitude.
        val lQuadrant = Math.floor(trueLong / 90.0) * 90.0
        val raQuadrant = Math.floor(rightAsc / 90.0) * 90.0
        rightAsc = (rightAsc + (lQuadrant - raQuadrant)) / 15.0

        val sinDec = 0.39782 * sin(trueLong.rad)
        val cosDec = cos(Math.asin(sinDec))

        val cosH = (cos(zenith.rad) - (sinDec * sin(lat.rad))) / (cosDec * cos(lat.rad))
        // Outside [-1, 1] the sun never rises or never sets at this latitude.
        if (cosH > 1 || cosH < -1) return null

        val h = if (sunrise) {
            (360.0 - Math.toDegrees(acos(cosH))) / 15.0
        } else {
            Math.toDegrees(acos(cosH)) / 15.0
        }

        val meanTime = h + rightAsc - (0.06571 * t) - 6.622
        val utc = (meanTime - lngHour).mod24()

        val offsetMinutes = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000
        val local = (utc * 60).toInt() + offsetMinutes
        return ((local % 1440) + 1440) % 1440
    }

    private val Double.rad: Double get() = Math.toRadians(this)
    private fun Double.mod360(): Double = ((this % 360) + 360) % 360
    private fun Double.mod24(): Double = ((this % 24) + 24) % 24
}
