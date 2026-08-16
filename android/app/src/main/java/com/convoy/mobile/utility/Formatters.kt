package com.convoy.mobile.utility

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Every number the user reads.
 *
 * Kept in one place because the same distance appears on the map, in the
 * roster and in an alert, and it must read identically in all three — a
 * gap described as "6.2 km" in one place and "6200 m" in another looks
 * like two different problems.
 */
object Formatters {

    /** Metres to the shortest honest string: "820 m", "6.2 km", "112 km". */
    fun distance(meters: Double?): String {
        if (meters == null) return "—"
        return when {
            meters < 1000 -> "${meters.roundToInt()} m"
            meters < 10_000 -> "%.1f km".format(meters / 1000)
            else -> "${(meters / 1000).roundToInt()} km"
        }
    }

    fun speed(kmh: Double?): String =
        if (kmh == null) "—" else "${kmh.roundToInt()} km/h"

    fun battery(pct: Int?): String = if (pct == null) "—" else "$pct%"

    /** "2 min behind", "1 hr 20 min behind". */
    fun minutesBehind(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return ""
        return if (minutes < 60) {
            "$minutes min behind"
        } else {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "$h hr behind" else "$h hr $m min behind"
        }
    }

    /** Compact staleness for a roster chip: "40s ago", "4m ago", "2h ago". */
    fun shortAgo(seconds: Int?): String {
        if (seconds == null) return "no fix"
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    /** Elapsed time for a stop timer: "6 min", "1:24". */
    fun duration(seconds: Long?): String {
        if (seconds == null) return "—"
        val mins = seconds / 60
        return when {
            mins < 60 -> "$mins min"
            else -> "%d:%02d".format(mins / 60, mins % 60)
        }
    }

    /**
     * A hex string from the server into a Compose colour.
     *
     * Vehicle colours are assigned by the backend so every device draws the
     * same car the same colour — returns null rather than guessing when the
     * string is malformed.
     */
    fun parseColor(hex: String?): Color? {
        val cleaned = hex?.trim()?.removePrefix("#") ?: return null
        if (cleaned.length != 6 && cleaned.length != 8) return null
        return runCatching {
            val value = cleaned.toLong(16)
            if (cleaned.length == 6) Color(value or 0xFF000000L) else Color(value)
        }.getOrNull()
    }
}
