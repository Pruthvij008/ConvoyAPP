package com.convoy.mobile.utility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Why there is no position yet.
 *
 * "Waiting for a first position" is the right message for exactly one of
 * these cases and a lie for the rest. A device with no GPS chip will never
 * stop waiting, and telling someone to be patient while nothing can possibly
 * happen is the worst kind of unhelpful.
 */
enum class LocationReadiness {
    /** Permission never granted, or revoked. Fixable in the app. */
    NO_PERMISSION,

    /** Location switched off system-wide. Fixable in Settings. */
    SERVICES_OFF,

    /** No GPS radio and no network provider — nothing can ever report. */
    NO_PROVIDER,

    /** Everything is in order; a fix is genuinely on its way. */
    WAITING;

    /** What to actually show the user. */
    val message: String
        get() = when (this) {
            NO_PERMISSION -> "Convoy needs location permission to put you on the map"
            SERVICES_OFF -> "Location is switched off on this device"
            NO_PROVIDER -> "This device can't report a position — you'll still see everyone else"
            WAITING -> "Waiting for a first position"
        }

    /** The glyph beside it — a satellite is wrong when the problem isn't the sky. */
    val glyph: String
        get() = when (this) {
            NO_PERMISSION -> "🔒"
            SERVICES_OFF -> "⚙"
            NO_PROVIDER -> "📡"
            WAITING -> "🛰️"
        }

    /** Whether the user can do anything about it. */
    val isActionable: Boolean
        get() = this == NO_PERMISSION || this == SERVICES_OFF

    companion object {
        fun of(context: Context): LocationReadiness {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

            if (!granted) return NO_PERMISSION

            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return WAITING

            val gps = runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)

            // Wifi/cell positioning. This is what a tablet with no GPS chip
            // uses, and it is accurate enough to know which city you are in
            // even if not which lane.
            val network = runCatching {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)

            return when {
                gps || network -> WAITING
                // Providers exist but all are switched off — a system toggle,
                // distinct from hardware that was never there.
                manager.allProviders.any { it != LocationManager.PASSIVE_PROVIDER } -> SERVICES_OFF
                else -> NO_PROVIDER
            }
        }
    }
}
