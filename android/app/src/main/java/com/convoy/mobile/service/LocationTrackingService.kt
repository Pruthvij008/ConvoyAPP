package com.convoy.mobile.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.convoy.mobile.R
import com.convoy.mobile.activities.MainActivity
import com.convoy.mobile.network.SocketManager
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.utility.MyLocation
import com.convoy.mobile.utility.PrefsManager
import com.convoy.mobile.utility.ThemeManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The only thing in the app that reads GPS.
 *
 * It lives in a foreground service because an Activity dies the moment the
 * user switches apps, and tracking that stops when someone opens WhatsApp
 * is worse than no tracking at all.
 *
 * Battery is the constraint that shapes everything here. Continuous GPS
 * flattens a phone in about four hours and people uninstall, so the cadence
 * adapts: fast while moving, a bare heartbeat while parked, and slower
 * again once the battery is low.
 */
@AndroidEntryPoint
class LocationTrackingService : LifecycleService() {

    @Inject lateinit var prefs: PrefsManager
    @Inject lateinit var socketManager: SocketManager
    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var myLocation: MyLocation

    private lateinit var fusedClient: FusedLocationProviderClient

    /** How often positions go out over the socket. The battery-expensive one. */
    private var publishIntervalMs = Constants.PING_INTERVAL_MOVING_MS

    /** How often we ASK the GPS. Deliberately not the same number — see below. */
    private var sampleIntervalMs = Constants.SAMPLE_INTERVAL_MOVING_MS

    /** When we last sent a position, so publishing can be throttled on its own. */
    private var lastPublishedAt = 0L

    /**
     * When the vehicle was last seen genuinely moving.
     *
     * Sampling drops to the slow rate only after this has been quiet for a
     * while, and goes back to fast the instant anything moves. Without that
     * asymmetry, crawling traffic — where speed sits either side of the
     * stopped threshold for minutes at a time — would tear the location
     * request down and rebuild it every couple of seconds.
     */
    private var lastMovingAt = System.currentTimeMillis()

    private var tripId: String? = null
    private var vehicleId: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            publish(location)
            adaptCadence(location.speed * 3.6)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            Constants.ACTION_STOP_TRACKING -> {
                stopTracking()
                return START_NOT_STICKY
            }
        }

        // A restart after process death arrives with a null intent, so the
        // trip is recovered from storage rather than the extras.
        tripId = intent?.getStringExtra(Constants.EXTRA_TRIP_ID) ?: prefs.activeTripId
        vehicleId = intent?.getStringExtra(Constants.EXTRA_VEHICLE_ID) ?: prefs.activeVehicleId

        val trip = tripId
        if (trip.isNullOrBlank()) {
            Log.w(TAG, "No trip to track — stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(Constants.NOTIFICATION_ID_TRACKING, buildNotification())
        socketManager.connect(trip)
        primeFirstFix()
        requestUpdates(sampleIntervalMs)

        // STICKY so Android restarts this if it kills the process mid-trip.
        return START_STICKY
    }

    /**
     * Gets a position on the map immediately instead of waiting up to a full
     * interval for the next scheduled fix.
     *
     * Uses the cached last-known position first (instant, free), then asks
     * for one high-accuracy fix. Staring at an empty map for fifteen seconds
     * after pressing Start reads as broken even though it is working.
     */
    private fun primeFirstFix() {
        try {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                // The CACHED fix, which is free but can be hours old — it is
                // whatever the last app to ask for a position happened to
                // get. Worth showing while a real fix is acquired, worthless
                // and actively misleading if it predates this journey, so it
                // is only accepted while it is plausibly still true.
                val ageMs = location?.let { System.currentTimeMillis() - it.time }
                if (location != null && ageMs != null && ageMs <= MAX_CACHED_FIX_AGE_MS) {
                    publish(location, force = true)
                } else if (location != null) {
                    Log.d(TAG, "Ignored a cached fix ${(ageMs ?: 0) / 1000}s old")
                }
            }

            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        publish(location, force = true)
                    } else {
                        // High accuracy means "use the GPS radio". A wifi-only
                        // tablet has no such radio, so this returns null and
                        // waiting longer will never help — the map would sit
                        // on "waiting for a first position" forever.
                        Log.w(TAG, "No high-accuracy fix — retrying on wifi/cell")
                        primeCoarseFix()
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "First fix failed: ${it.message} — retrying coarse")
                    primeCoarseFix()
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "No location permission for first fix")
        }
    }

    /**
     * The fallback for devices with no GPS chip.
     *
     * Wifi/cell positioning is accurate to a few hundred metres rather than
     * a few, which is useless for drawing a car on a road but perfectly
     * adequate for a passenger's tablet showing where the convoy is. A rough
     * position beats no position.
     */
    private fun primeCoarseFix() {
        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        publish(location, force = true)
                    } else {
                        Log.w(TAG, "No position available from any provider on this device")
                    }
                }
                .addOnFailureListener { Log.w(TAG, "Coarse fix failed: ${it.message}") }
        } catch (e: SecurityException) {
            Log.e(TAG, "No location permission for coarse fix")
        }
    }

    /**
     * The worst fix we are willing to broadcast, in metres.
     *
     * A position accurate to half a kilometre is not a position — it puts a
     * car on the wrong road entirely, and the convoy would rather see the
     * last good fix marked stale than a confident wrong one. Anything above
     * this is dropped and the next fix is waited for.
     */
    private val worstUsableAccuracyM = 100f

    /**
     * One place that turns an Android Location into a position — shown here,
     * and sometimes sent to everyone else.
     *
     * Those are two different questions with two different costs, and
     * treating them as one is what made your own dot lag. Drawing your own
     * position is free: the fix is already in this process. Sending it wakes
     * the mobile radio, which is the single most expensive thing this app
     * does. So the local update happens on EVERY fix, and the send is
     * throttled to the cadence the battery rules chose.
     */
    private fun publish(location: android.location.Location, force: Boolean = false) {
        // hasAccuracy() is checked first: a fix with no accuracy figure at
        // all cannot be judged, and is trusted rather than discarded.
        if (location.hasAccuracy() && location.accuracy > worstUsableAccuracyM) {
            Log.w(TAG, "Dropped a ${location.accuracy.toInt()}m fix — too vague to place a car")
            return
        }

        // Logged so accuracy can be confirmed on a real drive rather than
        // guessed at. A good open-sky GPS fix reads single digits; anything
        // in the tens means the phone is still falling back to wifi or cell.
        Log.d(TAG, "Fix accuracy: ${if (location.hasAccuracy()) "${location.accuracy.toInt()}m" else "unknown"}")

        val speedKmh = location.speed * 3.6
        themeManager.updateLocation(location.latitude, location.longitude)

        // Immediately, always. This is the whole fix for the lagging dot:
        // the map reads this and never waits for the server to hand our own
        // position back to us.
        myLocation.update(
            MyLocation.Fix(
                lat = location.latitude,
                lng = location.longitude,
                speedKmh = speedKmh,
                headingDeg = location.bearing.toDouble().takeIf { location.hasBearing() },
                accuracyM = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
            )
        )

        val now = System.currentTimeMillis()
        // `force` is for the first fix of a trip. The convoy is waiting to
        // see you appear at all, and making them wait out a throttle window
        // for that is the opposite of what the throttle is for.
        if (!force && now - lastPublishedAt < publishIntervalMs) return
        lastPublishedAt = now

        socketManager.sendPosition(
            lat = location.latitude,
            lng = location.longitude,
            speedKmh = speedKmh,
            heading = location.bearing.toDouble(),
            accuracyM = location.accuracy.toDouble(),
            batteryPct = batteryPercent(),
        )
        Log.d(TAG, "Position sent: ${location.latitude}, ${location.longitude}")
    }

    /**
     * The cadence rules, in one place.
     *
     * Two cadences, not one. SENDING is what costs battery — it wakes the
     * mobile radio — so a parked car still drops to a heartbeat every few
     * minutes. SAMPLING is nearly free once the GPS chip is already tracking,
     * so it stays fast enough to draw a moving dot.
     *
     * Keeping them equal had a second consequence nobody had noticed: a car
     * stopped at a long light dropped to the 150-second stationary cadence,
     * and since that was also the sampling rate, the next fix — and therefore
     * any chance of noticing the car had moved off — was up to two and a half
     * minutes away. The dot sat frozen at the junction long after the car had
     * gone. Sampling on its own schedule is what fixes that.
     */
    private fun adaptCadence(speedKmh: Double) {
        val battery = batteryPercent() ?: 100
        val now = System.currentTimeMillis()

        val moving = speedKmh > Constants.STOPPED_SPEED_KMH
        if (moving) lastMovingAt = now

        // Publishing follows the instantaneous speed, as it always has: it
        // is a cheap decision made every fix, and the cost of getting it
        // briefly wrong is one extra ping.
        val targetPublish = when {
            battery <= Constants.LOW_BATTERY_FORCE_SAVER_PCT -> Constants.PING_INTERVAL_SAVER_MS
            moving -> Constants.PING_INTERVAL_MOVING_MS
            else -> Constants.PING_INTERVAL_STATIONARY_MS
        }

        // Sampling needs hysteresis, because changing it tears down and
        // rebuilds the location request. Slow down only after a sustained
        // stop; speed up the instant anything moves.
        val settled = now - lastMovingAt >= Constants.SETTLED_BEFORE_SLOW_SAMPLING_MS
        val targetSample = when {
            // On a dying battery we stop being clever. The dot being smooth
            // matters less than the phone still being alive at the far end.
            battery <= Constants.LOW_BATTERY_FORCE_SAVER_PCT -> Constants.PING_INTERVAL_SAVER_MS
            settled -> Constants.SAMPLE_INTERVAL_STATIONARY_MS
            else -> Constants.SAMPLE_INTERVAL_MOVING_MS
        }

        publishIntervalMs = targetPublish

        if (targetSample != sampleIntervalMs) {
            Log.d(
                TAG,
                "Sampling ${sampleIntervalMs}ms → ${targetSample}ms, publishing every " +
                    "${targetPublish}ms (speed=${speedKmh.toInt()} battery=$battery)",
            )
            sampleIntervalMs = targetSample
            requestUpdates(targetSample)
        }
    }

    private fun requestUpdates(intervalMs: Long) {
        val request = LocationRequest.Builder(intervalMs)
            // HIGH_ACCURACY means "use the GPS radio". BALANCED does not —
            // it uses wifi and cell towers, which on mobile data with no
            // wifi in range is tower triangulation, accurate to HUNDREDS of
            // metres. That is useless for showing which car is where, and
            // it is why positions were landing ~100 m from the truth.
            //
            // The battery cost is real but it is paid on OUR terms: the
            // interval already stretches to a heartbeat when parked, so GPS
            // is only running hard while the convoy is actually moving.
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            // Batching holds fixes back and hands them over in a burst, which
            // saves wakeups and is exactly wrong for a dot someone is
            // watching — it arrives late and then jumps. Allowed only at the
            // slow sampling rates, where nobody is watching a live dot
            // anyway and the saved wakeups are the entire point.
            .setMaxUpdateDelayMillis(
                if (intervalMs <= Constants.SAMPLE_INTERVAL_MOVING_MS) 0L else intervalMs * 2
            )
            // Deliberately NOT set to a displacement filter.
            //
            // A GPS fix arrives poor and improves over the following seconds
            // as more satellites lock. Those improvements happen at almost
            // the same coordinates, so a 10 m displacement filter throws
            // them away and leaves the first, worst fix on screen. Accuracy
            // is filtered instead, in publish(), which keeps the good fixes
            // and discards the vague ones.
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            fusedClient.removeLocationUpdates(callback)
            fusedClient.requestLocationUpdates(request, callback, mainLooper)
        } catch (e: SecurityException) {
            // Permission revoked mid-trip. Stop cleanly rather than crash.
            Log.e(TAG, "Location permission lost: ${e.message}")
            stopTracking()
        }
    }

    private fun batteryPercent(): Int? {
        val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }

    /**
     * Persistent and non-dismissible: the user must always be able to see
     * that their location is being shared, and stop it from here.
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(Constants.EXTRA_TRIP_ID, tripId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LocationTrackingService::class.java).apply {
                action = Constants.ACTION_STOP_TRACKING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, Constants.CHANNEL_TRACKING)
            .setContentTitle("Sharing your location")
            .setContentText("Your convoy can see where you are.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .setOngoing(true)
            .setSilent(true) // a permanent statement of fact, not news
            .build()
    }

    private fun stopTracking() {
        Log.d(TAG, "Stopping tracking")
        runCatching { fusedClient.removeLocationUpdates(callback) }
        // Otherwise the last fix of this trip is still sitting in the bus
        // when the next one opens, and it would be drawn as current.
        myLocation.clear()
        socketManager.disconnect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { fusedClient.removeLocationUpdates(callback) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocationService"

        /**
         * How old the free cached fix may be before it is ignored.
         *
         * Two minutes is roughly "since you got in the car". Older than that
         * and it is likely to be your driveway, drawn confidently on a map
         * of a road you are already an hour down.
         */
        private const val MAX_CACHED_FIX_AGE_MS = 120_000L

        fun start(context: Context, tripId: String, vehicleId: String?) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = Constants.ACTION_START_TRACKING
                putExtra(Constants.EXTRA_TRIP_ID, tripId)
                putExtra(Constants.EXTRA_VEHICLE_ID, vehicleId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationTrackingService::class.java).apply {
                    action = Constants.ACTION_STOP_TRACKING
                }
            )
        }
    }
}
