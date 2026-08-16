package com.convoy.mobile.utility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * Directions.
 *
 * Convoy does NOT rebuild turn-by-turn navigation. It hands the destination
 * to whatever navigation app is already on the phone — free, familiar, and
 * better than anything we would write. The app's job is knowing *where* to
 * send you; Google Maps' job is getting you there.
 */
object Navigation {

    /**
     * Starts navigation to a coordinate.
     *
     * `google.navigation:` launches turn-by-turn directly. If no app handles
     * it — an unusual phone, or a work profile — this falls back to a plain
     * geo: URI, and finally tells the user rather than failing silently.
     */
    fun navigateTo(
        context: Context,
        lat: Double,
        lng: Double,
        label: String? = null,
    ) {
        val encoded = Uri.encode(label ?: "Destination")

        // Tried in order of how good the result is, each falling through to
        // the next. Every one of these is ATTEMPTED rather than probed with
        // resolveActivity: on Android 11+ that check answers "can I SEE this
        // app", not "does it exist", so it returns null for a perfectly
        // installed Google Maps unless the package is declared in <queries>.
        // Trying and catching is honest about what actually happened.
        val candidates = listOf(
            // Turn-by-turn straight away, no confirmation screen.
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d"))
                .setPackage(GOOGLE_MAPS),
            // Google Maps directions screen, in case navigation is unavailable.
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving"),
            ).setPackage(GOOGLE_MAPS),
            // Any maps app at all: Waze, OsmAnd, MapmyIndia, Samsung's own.
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($encoded)")),
            // Last resort — the browser will take it, and that still works.
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving"),
            ),
        )

        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Nothing on this phone handles that particular form. Next.
            } catch (e: Exception) {
                Log.w(TAG, "Directions attempt failed: ${e.message}")
            }
        }

        Toast.makeText(
            context,
            "Couldn't open a maps app for directions.",
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Opens a place for viewing rather than driving — used for a stop
     * someone else marked, where you may just want to see where it is.
     */
    fun showOnMap(context: Context, lat: Double, lng: Double, label: String?) {
        val encoded = Uri.encode(label ?: "Convoy")
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($encoded)"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            navigateTo(context, lat, lng, label)
        }
    }

    /** Calling a convoy member from an alert or the roster. */
    fun dial(context: Context, phone: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "No dialler: ${e.message}")
        }
    }

    private const val GOOGLE_MAPS = "com.google.android.apps.maps"
    private const val TAG = "Navigation"
}
