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

    private lateinit var fusedClient: FusedLocationProviderClient
    private var currentIntervalMs = Constants.PING_INTERVAL_MOVING_MS
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
        requestUpdates(currentIntervalMs)

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
                location?.let { publish(it) }
            }

            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        publish(location)
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
                        publish(location)
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

    /** One place that turns an Android Location into an outgoing position. */
    private fun publish(location: android.location.Location) {
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
     * A parked car does not need GPS at all — the radio costs more battery
     * than the GPS chip, so a stationary vehicle sends a heartbeat every few
     * minutes instead of a position every fifteen seconds.
     */
    private fun adaptCadence(speedKmh: Double) {
        val battery = batteryPercent() ?: 100
        val target = when {
            battery <= Constants.LOW_BATTERY_FORCE_SAVER_PCT -> Constants.PING_INTERVAL_SAVER_MS
            speedKmh <= Constants.STOPPED_SPEED_KMH -> Constants.PING_INTERVAL_STATIONARY_MS
            else -> Constants.PING_INTERVAL_MOVING_MS
        }

        if (target != currentIntervalMs) {
            Log.d(TAG, "Cadence ${currentIntervalMs}ms → ${target}ms (speed=${speedKmh.toInt()} battery=$battery)")
            currentIntervalMs = target
            requestUpdates(target)
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
            .setMaxUpdateDelayMillis(intervalMs * 2) // lets Android batch wakeups
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
