package com.convoy.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.convoy.mobile.utility.Constants
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ConvoyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /**
     * Three channels, because the app has three genuinely different levels
     * of urgency and users should be able to silence the quiet one without
     * losing the loud one.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // The persistent "you are sharing location" notice. Silent by design
        // — it is a permanent, non-dismissible statement of fact, not news.
        val tracking = NotificationChannel(
            Constants.CHANNEL_TRACKING,
            "Trip tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while your location is being shared with a convoy."
            setShowBadge(false)
        }

        // Gaps, off-route, stalled, low battery.
        val alerts = NotificationChannel(
            Constants.CHANNEL_ALERTS,
            "Convoy alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Someone falling behind, going off route, or running low on battery."
        }

        // SOS, crash, breakdown. Allowed to interrupt everything, because
        // missing one of these is the failure that actually matters.
        val critical = NotificationChannel(
            Constants.CHANNEL_CRITICAL,
            "Emergencies",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "SOS and breakdowns from your convoy."
            enableVibration(true)
            setBypassDnd(true)
        }

        // Chat and voice notes. Its own channel so someone who wants the
        // emergencies but not the chatter can silence one without the other
        // — which on a long drive is a real preference, not a hypothetical.
        val messages = NotificationChannel(
            Constants.CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Chat messages and voice notes from your convoy."
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(tracking, alerts, critical, messages))
    }
}
