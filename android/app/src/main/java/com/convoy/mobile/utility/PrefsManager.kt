package com.convoy.mobile.utility

import android.content.Context
import android.content.SharedPreferences
import com.convoy.mobile.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local storage for the few things that survive between launches.
 *
 * The device id is the whole identity — there is no account behind it — so
 * it is generated once and never regenerated. Clearing app data creates a
 * new user, which is why rejoining a trip goes through the original link.
 */
@Singleton
class PrefsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Crypto-random, generated on first launch, and the only credential the
     * app has. Deliberately NOT the Android ID: that is shared across apps
     * and survives uninstalls, which would make it a tracking identifier.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    /**
     * The user's own photo, cached so the header and roster can show it
     * without refetching the user on every screen. Null when they have not
     * set one, which is the normal case.
     */
    var photoUrl: String?
        get() = prefs.getString(KEY_PHOTO, null)
        set(value) = prefs.edit().putString(KEY_PHOTO, value).apply()

    /** The trip currently being tracked, so a cold start can resume it. */
    var activeTripId: String?
        get() = prefs.getString(KEY_ACTIVE_TRIP, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_TRIP, value).apply()

    var activeVehicleId: String?
        get() = prefs.getString(KEY_ACTIVE_VEHICLE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_VEHICLE, value).apply()

    /**
     * Our participant id in the active trip.
     *
     * Needed outside any screen: the tracking service decides whether an
     * incoming chat message deserves a notification, and the only way to
     * know it is not our own message echoing back is to compare sender ids.
     * A name comparison would misfire the moment two friends share one.
     */
    var activeParticipantId: String?
        get() = prefs.getString(KEY_ACTIVE_PARTICIPANT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PARTICIPANT, value).apply()

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.AUTO.name)!!)
        }.getOrDefault(ThemeMode.AUTO)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    /** Clears the session but keeps the device id — the identity survives. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_ACTIVE_TRIP)
            .remove(KEY_ACTIVE_VEHICLE)
            .remove(KEY_ACTIVE_PARTICIPANT)
            .apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "jwt_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_NAME = "display_name"
        const val KEY_PHOTO = "photo_url"
        const val KEY_USERNAME = "username"
        const val KEY_ACTIVE_TRIP = "active_trip_id"
        const val KEY_ACTIVE_VEHICLE = "active_vehicle_id"
        const val KEY_ACTIVE_PARTICIPANT = "active_participant_id"
        const val KEY_THEME = "theme_mode"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
