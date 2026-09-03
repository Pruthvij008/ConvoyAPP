package com.convoy.mobile.utility

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.convoy.mobile.R
import com.convoy.mobile.activities.ChatActivity
import com.convoy.mobile.activities.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telling someone something happened while they were not looking.
 *
 * Four notification channels existed and nothing ever posted to three of
 * them. A message arrived and the phone stayed silent; an SOS arrived and
 * the phone stayed silent. The only way to learn anything was to already be
 * looking at the screen — which, in a car, is the one thing the driver
 * should not be doing.
 *
 * Posted from the tracking service rather than a screen, because the
 * service is what holds the socket for the whole trip. A ViewModel only
 * exists while its screen does, so anything relying on one would notify
 * exclusively when the user could already see it.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * The trip whose chat is on screen right now, if any.
     *
     * Notifying someone about a message they are looking at is noise, and
     * the buzz-while-reading is exactly the behaviour that gets an app's
     * notifications switched off.
     */
    @Volatile
    var chatVisibleForTrip: String? = null

    /**
     * POST_NOTIFICATIONS is a runtime permission from Android 13.
     *
     * Posting without it throws nothing and does nothing — the notification
     * is silently dropped — so this is checked to keep the failure
     * diagnosable rather than invisible.
     */
    private val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * A chat message or voice note.
     *
     * Uses one id and an inbox style, so ten messages while you drive
     * through a tunnel become one growing notification rather than ten
     * separate buzzes — which is the difference between useful and a reason
     * to turn notifications off.
     */
    fun message(tripId: String, from: String, body: String) {
        if (!allowed) return
        if (chatVisibleForTrip == tripId) return

        recentMessages += "$from: $body"
        while (recentMessages.size > MAX_INBOX_LINES) recentMessages.removeAt(0)

        val open = PendingIntent.getActivity(
            context,
            REQ_CHAT,
            Intent(context, ChatActivity::class.java)
                .putExtra(Constants.EXTRA_TRIP_ID, tripId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val style = NotificationCompat.InboxStyle()
        recentMessages.forEach { style.addLine(it) }

        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (recentMessages.size == 1) from else "${recentMessages.size} new messages")
            .setContentText(body)
            .setStyle(style)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        runCatching { manager.notify(Constants.NOTIFICATION_ID_MESSAGES, builder.build()) }
    }

    /** Chat has been opened, so the backlog is read. */
    fun clearMessages() {
        recentMessages.clear()
        runCatching { manager.cancel(Constants.NOTIFICATION_ID_MESSAGES) }
    }

    /**
     * An alert from the convoy.
     *
     * `critical` routes it to the channel that bypasses Do Not Disturb and
     * is allowed to interrupt a full-screen app. An SOS that waits politely
     * behind a silenced phone is not an SOS.
     */
    fun alert(tripId: String, alertId: String, title: String, body: String, critical: Boolean) {
        if (!allowed) return

        val open = PendingIntent.getActivity(
            context,
            alertId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(Constants.EXTRA_TRIP_ID, tripId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(
            context,
            if (critical) Constants.CHANNEL_CRITICAL else Constants.CHANNEL_ALERTS,
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setCategory(
                if (critical) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_STATUS
            )
            .setPriority(
                if (critical) NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .apply {
                // Puts an emergency on screen even over another app. Only
                // for critical, because doing it for a low-battery warning
                // is how an app earns "turn these off".
                if (critical) setFullScreenIntent(open, true)
            }

        // Keyed on the alert, so a second gap warning for the same car
        // replaces the first rather than stacking.
        val id = Constants.NOTIFICATION_ID_ALERT_BASE + (alertId.hashCode() and 0xFFF)
        runCatching { manager.notify(id, builder.build()) }
    }

    private companion object {
        const val REQ_CHAT = 41
        const val MAX_INBOX_LINES = 6

        /** The running backlog shown in the expanded message notification. */
        val recentMessages = mutableListOf<String>()
    }
}
