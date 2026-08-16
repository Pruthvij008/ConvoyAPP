package com.convoy.mobile.customControls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.ui.theme.ConvoyTheme

/**
 * Chase Mode — catching up to someone who is still moving.
 *
 * This exists because it is the one navigation case a maps app cannot
 * serve. Google Maps needs a FIXED destination; a friend driving ahead of
 * you is not one. Route to where they were and you are navigating to an
 * empty stretch of road the moment they move.
 *
 * So this deliberately isn't turn-by-turn. It is the instrument a rally
 * navigator would use: which way they are, how far, and — the number that
 * actually answers the question — whether you are gaining or losing.
 *
 * Never stale, because it recomputes from live positions rather than from a
 * route planned once.
 */
@Composable
fun ChaseOverlay(
    targetName: String,
    /** Compass bearing to the target, degrees clockwise from north. */
    bearingDeg: Float,
    /** Your own heading, so the arrow can point relative to the windscreen. */
    myHeadingDeg: Float?,
    distanceText: String,
    /** Positive = closing, negative = falling further behind, null = unknown. */
    closingKmh: Double?,
    targetStopped: Boolean,
    onNavigateInstead: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    // When we know which way the car is pointing, the arrow is drawn
    // relative to that — "it's over to your right" — which is the only
    // frame a driver can act on. Without a heading we fall back to true
    // north and say so, rather than silently showing a bearing that looks
    // like a direction but isn't.
    val relative = if (myHeadingDeg != null) bearingDeg - myHeadingDeg else bearingDeg

    // Smoothed, because raw GPS bearing jitters several degrees at a
    // standstill and a twitching arrow reads as a broken instrument.
    val animatedAngle by animateFloatAsState(
        targetValue = relative,
        animationSpec = tween(durationMillis = 400),
        label = "chase-bearing",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "✕",
                color = colors.muted,
                fontSize = 20.sp,
                modifier = Modifier.clickableOnce(onClick = onClose),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "CHASING",
                color = colors.dim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(20.dp))
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = targetName,
            color = colors.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (targetStopped) "Stopped — they're not moving" else "Still moving",
            color = if (targetStopped) colors.route else colors.muted,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(top = 5.dp),
        )

        Spacer(Modifier.height(36.dp))

        Box(
            modifier = Modifier
                .size(232.dp)
                .background(colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Read outside the Canvas: a DrawScope is not a composable
            // scope, so the theme cannot be reached from inside it.
            val arrowColor = colors.route
            Canvas(modifier = Modifier.size(150.dp)) {
                rotate(degrees = animatedAngle) {
                    val w = size.width
                    val h = size.height
                    // A simple kite: nose at the top, notched tail, so which
                    // end is "forward" is unambiguous at a glance.
                    val path = Path().apply {
                        moveTo(w / 2f, 0f)
                        lineTo(w * 0.82f, h)
                        lineTo(w / 2f, h * 0.76f)
                        lineTo(w * 0.18f, h)
                        close()
                    }
                    drawPath(path = path, color = arrowColor)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = distanceText,
            color = colors.text,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )

        // The number that actually answers "am I catching them?". Distance
        // alone does not — 3 km could be closing fast or opening up, and
        // those call for opposite decisions.
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                targetStopped -> "They're waiting — distance is closing"
                closingKmh == null -> "Working out whether you're gaining…"
                closingKmh > 2 -> "Gaining · ${closingKmh.toInt()} km/h faster"
                closingKmh < -2 -> "Falling behind · ${(-closingKmh).toInt()} km/h slower"
                else -> "Holding the same gap"
            },
            color = when {
                targetStopped -> colors.route
                closingKmh == null -> colors.muted
                closingKmh > 2 -> colors.route
                closingKmh < -2 -> colors.amber
                else -> colors.muted
            },
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )

        if (myHeadingDeg == null) {
            Text(
                text = "Arrow points north-relative until you're moving.",
                color = colors.dim,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // Once they've stopped, a real maps app is strictly better — the
        // target is fixed, so turn-by-turn works properly. Offering it only
        // then is the honest version of this screen.
        if (targetStopped) {
            PrimaryButton(text = "Get directions to them", onClick = onNavigateInstead)
            Spacer(Modifier.height(10.dp))
        }
        GhostButton(text = "Back to the map", onClick = onClose)
    }
}
