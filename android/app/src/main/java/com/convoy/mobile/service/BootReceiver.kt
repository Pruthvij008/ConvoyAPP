package com.convoy.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.convoy.mobile.utility.PrefsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Resumes tracking after the phone reboots mid-trip.
 *
 * A convoy does not stop because someone's phone restarted on a long drive,
 * and silently dropping off the map is exactly the failure the whole
 * staleness design exists to make visible.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: PrefsManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val tripId = prefs.activeTripId
        if (tripId.isNullOrBlank()) return

        Log.d("BootReceiver", "Resuming tracking for trip $tripId after reboot")
        LocationTrackingService.start(context, tripId, prefs.activeVehicleId)
    }
}
