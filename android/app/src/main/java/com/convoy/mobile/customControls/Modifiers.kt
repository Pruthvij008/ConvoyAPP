package com.convoy.mobile.customControls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * A click that cannot fire twice.
 *
 * On a bumpy road a single intended tap regularly registers as two, and in
 * this app a double-fire means two markers, two join requests, or two SOS
 * alerts. Every control in the app uses this rather than plain `clickable`.
 */
@Composable
fun Modifier.clickableOnce(
    enabled: Boolean = true,
    debounceMs: Long = 600L,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val hapticFeedback = LocalHapticFeedback.current
    val lastClick = remember { longArrayOf(0L) }
    val interaction = remember { MutableInteractionSource() }

    this.clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastClick[0] >= debounceMs) {
            lastClick[0] = now
            // Confirms the tap landed without needing to look at the screen.
            if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
    }
}
