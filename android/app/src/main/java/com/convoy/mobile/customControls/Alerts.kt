package com.convoy.mobile.customControls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.dataModel.alert.Alert
import com.convoy.mobile.ui.theme.ConvoyTheme

/**
 * A non-critical alert, over the map.
 *
 * Dismissible, because a gap you already know about should not keep
 * shouting. Dismissing is local — the condition stays open on the server
 * and the alert returns if it worsens.
 */
@Composable
fun AlertBanner(
    alert: Alert,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.amber.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .border(1.dp, colors.amber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = alert.glyph, fontSize = 18.sp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = alert.message ?: alert.type,
                color = colors.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 9.dp),
            ) {
                if (onAction != null && actionLabel != null) {
                    Text(
                        text = actionLabel,
                        color = colors.amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickableOnce(onClick = onAction),
                    )
                }
                Text(
                    text = "Dismiss",
                    color = colors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.clickableOnce(haptic = false, onClick = onDismiss),
                )
            }
        }
    }
}

/**
 * The SOS button.
 *
 * Hold-to-arm rather than tap, then a visible countdown that can be
 * cancelled. Nothing leaves the phone until it reaches zero — an accidental
 * press has to be harmless or people will not keep the button on screen.
 */
@Composable
fun SosButton(
    countdown: Int?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConvoyTheme.colors
    val counting = countdown != null

    Box(
        modifier = modifier
            .size(64.dp)
            .background(
                if (counting) colors.red else colors.red.copy(alpha = 0.16f),
                CircleShape,
            )
            .border(
                width = if (counting) 0.dp else 2.dp,
                color = colors.red.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .clickableOnce(debounceMs = 250) { if (counting) onCancel() else onStart() },
        contentAlignment = Alignment.Center,
    ) {
        if (counting) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = countdown.toString(),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(text = "tap to stop", color = Color.White, fontSize = 8.sp)
            }
        } else {
            Text(text = "SOS", color = colors.red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The only screen allowed to take over the phone.
 *
 * The primary action is Navigate, not Acknowledge — someone reading this is
 * deciding whether to turn around, so the button should be the thing they
 * are actually going to do.
 */
@Composable
fun SosOverlay(
    alert: Alert,
    raisedByLabel: String?,
    distanceText: String?,
    canClear: Boolean,
    isSaving: Boolean,
    /**
     * True when YOU are the one who raised this.
     *
     * Without it the screen offered the person in trouble "Navigate to
     * them" and "I'm on my way" — directions to their own position, and a
     * button to tell the convoy they were coming to help themselves. What
     * they actually need to see is that the message got out and who is
     * responding.
     */
    isMine: Boolean = false,
    onNavigate: () -> Unit,
    onCall: (() -> Unit)?,
    onAcknowledge: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val lat = alert.location?.lat
    val lng = alert.location?.lng

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.red.copy(alpha = 0.28f),
                        colors.red.copy(alpha = 0.06f),
                        colors.ground,
                    )
                )
            )
            .safeTop(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(colors.red.copy(alpha = 0.2f), CircleShape)
                    .border(2.dp, colors.red, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🆘", fontSize = 38.sp)
            }

            Text(
                text = when {
                    isMine -> "Your SOS is out"
                    raisedByLabel != null -> "$raisedByLabel needs help"
                    else -> "Someone needs help"
                },
                color = colors.text,
                fontSize = 28.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 22.dp),
            )

            // The one thing the person in trouble needs to know: did anyone
            // hear it. A count beats silence, and silence is what this
            // screen used to give them.
            if (isMine) {
                val coming = alert.acknowledgedBy?.size ?: 0
                Text(
                    text = when (coming) {
                        0 -> "Everyone in the convoy has been alerted. Waiting for a reply…"
                        1 -> "1 person is on their way"
                        else -> "$coming people are on their way"
                    },
                    color = if (coming > 0) colors.route else colors.muted,
                    fontSize = 15.sp,
                    fontWeight = if (coming > 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            distanceText?.let {
                Text(
                    text = it,
                    color = colors.muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            alert.message?.let {
                Text(
                    text = it,
                    color = colors.red,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // The raw coordinates, deliberately. If the app fails, if the
            // data drops, if someone has to read it to a highway patrol —
            // that number still works.
            if (lat != null && lng != null) {
                Text(
                    text = "%.4f, %.4f".format(lat, lng),
                    color = colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeBottom()
                .padding(horizontal = 26.dp, vertical = 26.dp),
        ) {
            if (isMine) {
                // Nothing to navigate to and nobody to tell you are coming.
                // The only action that makes sense from here is standing it
                // down, and it is phrased as the statement it actually is.
                DangerButton(
                    text = "I'm OK — cancel this",
                    enabled = !isSaving,
                    onClick = onClear,
                )
                Text(
                    text = "Cancelling clears the alert on everyone's phone. " +
                        "Only do it once you are actually fine.",
                    color = colors.dim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            } else {
                if (lat != null && lng != null) {
                    DangerButton(text = "Navigate to them", onClick = onNavigate)
                    Spacer(Modifier.height(10.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onCall != null) {
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(text = "Call", onClick = onCall)
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SecondaryButton(text = "I'm on my way", onClick = onAcknowledge)
                    }
                }

                if (canClear) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        text = "Everything's fine — clear this",
                        enabled = !isSaving,
                        onClick = onClear,
                    )
                }

                Text(
                    text = "This stays until someone clears it. Driving on doesn't " +
                        "end an emergency.",
                    color = colors.dim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
        }
    }
}
