package com.convoy.mobile.customControls

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * A click that cannot fire twice, and that visibly answers the finger.
 *
 * DEBOUNCE. On a bumpy road a single intended tap regularly registers as
 * two, and in this app a double-fire means two markers, two join requests,
 * or two SOS alerts. Every control in the app uses this rather than plain
 * `clickable`.
 *
 * PRESS RESPONSE. This used to pass `indication = null` and nothing else,
 * which meant no control anywhere in the app reacted to being touched — no
 * ripple, no highlight, no movement. Combined with the debounce, a tap that
 * landed during the quiet window looked identical to a tap that missed, and
 * the whole app felt dead under the thumb.
 *
 * The answer is a spring-loaded squeeze rather than Material's ripple. A
 * ripple is a rectangle-bound wash that looks wrong on the round pills,
 * floating bars and map overlays this app is built from; a scale reads
 * correctly on any shape, and it is legible in peripheral vision, which
 * matters when the person tapping is driving.
 */
@Composable
fun Modifier.clickableOnce(
    enabled: Boolean = true,
    debounceMs: Long = 600L,
    haptic: Boolean = true,
    /**
     * How far the control shrinks while held.
     *
     * Smaller controls can take a deeper squeeze; a full-width sheet or a
     * floating bar wants far less, because scaling a large surface by the
     * same ratio moves its edges a long way and reads as a glitch. Callers
     * that wrap a big surface should pass something nearer 1f.
     */
    pressScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = composed {
    val hapticFeedback = LocalHapticFeedback.current
    val lastClick = remember { longArrayOf(0L) }
    val interaction = remember { MutableInteractionSource() }

    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        // Stiff and lightly bouncy: it has to settle well inside the time a
        // thumb is actually down, or the control is still moving after the
        // action has already happened.
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "press-scale",
    )

    this
        .scale(scale)
        .clickable(
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
