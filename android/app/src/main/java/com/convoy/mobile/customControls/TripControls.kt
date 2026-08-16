package com.convoy.mobile.customControls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.ui.theme.SectionLabelStyle

/**
 * Pause, End, Leave — the three things you can do to the trip itself.
 *
 * These used to live behind a line of dim grey text reading "Pull up for
 * trip controls", printed underneath the roster. Two things were wrong with
 * that. It was the least visible text on a screen dominated by a map, so
 * people did not find it; and it sat BELOW the list of cars, so on a trip
 * with five or six it was off the bottom of the sheet entirely and could
 * not be found even by someone looking for it.
 *
 * Now it is a labelled control in the header, where the trip's status is
 * already shown — the natural place to look for "and how do I change it?".
 *
 * The protection that the hidden pull-up was really providing — not ending
 * everyone's trip with one stray tap in a moving car — is kept, but moved
 * to where it belongs: an explicit confirmation on the destructive action
 * itself, rather than obscurity in front of all of them.
 */
@Composable
fun TripControlsSheet(
    tripName: String,
    paused: Boolean,
    amHost: Boolean,
    isBusy: Boolean,
    onPauseToggle: () -> Unit,
    onEndTrip: () -> Unit,
    onLeaveTrip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    // Reset every time the sheet opens, so a confirmation left armed on a
    // previous visit cannot be completed by a single tap on this one.
    var confirmingEnd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, sheetShape)
            .navigationBarsPadding()
            .padding(bottom = 22.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 4.dp)
                    .background(colors.border, RoundedCornerShape(2.dp))
            )
        }

        Column(modifier = Modifier.padding(horizontal = 22.dp)) {
            Text(text = "THIS TRIP", style = SectionLabelStyle, color = colors.dim)
            Spacer(Modifier.height(7.dp))
            Text(
                text = tripName,
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                StatusDot(if (paused) colors.amber else colors.route)
                Text(
                    text = if (paused) {
                        "Paused — nobody's location is updating"
                    } else {
                        "Live — everyone can see where you are"
                    },
                    color = colors.muted,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(22.dp))

            if (amHost) {
                // Pause is the reversible one, so it goes first and stays
                // one tap. It is also the answer to most of the reasons
                // someone reaches for "End trip" — a long lunch, a hotel
                // for the night — and offering it first means fewer trips
                // ended by accident and restarted five minutes later.
                if (paused) {
                    PrimaryButton(
                        text = "Resume sharing",
                        enabled = !isBusy,
                        onClick = onPauseToggle,
                    )
                } else {
                    SecondaryButton(
                        text = "Pause sharing",
                        enabled = !isBusy,
                        onClick = onPauseToggle,
                    )
                }
                Text(
                    text = if (paused) {
                        "Positions start updating again for everyone."
                    } else {
                        "Stops location updates for everyone. The trip stays open."
                    },
                    color = colors.dim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surface2))
                Spacer(Modifier.height(20.dp))

                if (!confirmingEnd) {
                    DangerButton(
                        text = "End trip",
                        enabled = !isBusy,
                        onClick = { confirmingEnd = true },
                    )
                    Text(
                        text = "Ending stops sharing for everyone, not just you. " +
                            "It can't be undone.",
                        color = colors.dim,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                } else {
                    // The second tap says what it does, in full. "Are you
                    // sure?" answered by "Yes" is a question nobody reads;
                    // a button labelled with the consequence is one they do.
                    Text(
                        text = "End the trip for everyone?",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Every phone stops sharing. Nobody can rejoin.",
                        color = colors.muted,
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    DangerButton(
                        text = "Yes, end it for everyone",
                        enabled = !isBusy,
                        onClick = onEndTrip,
                    )
                    Spacer(Modifier.height(10.dp))
                    GhostButton(text = "Keep the trip running", onClick = { confirmingEnd = false })
                }
            } else {
                // A member can only remove themselves, which is not
                // destructive to anybody else and needs no ceremony.
                SecondaryButton(
                    text = "Leave this trip",
                    enabled = !isBusy,
                    onClick = onLeaveTrip,
                )
                Text(
                    text = "You stop sharing and drop off the map. " +
                        "The trip carries on without you.",
                    color = colors.dim,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            GhostButton(text = "Close", onClick = onDismiss)
        }
    }
}

/**
 * The header's way in.
 *
 * Labelled rather than a bare glyph. A "⋯" in a corner is a menu nobody
 * opens; "Trip" next to the pause state is a thing people press when they
 * want to change the pause state.
 */
@Composable
fun TripControlsButton(paused: Boolean, onClick: () -> Unit) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .background(
                if (paused) colors.amber.copy(alpha = 0.16f) else colors.surface2,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (paused) colors.amber.copy(alpha = 0.4f) else colors.border,
                RoundedCornerShape(12.dp),
            )
            .clickableOnce(haptic = false, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = if (paused) "▶" else "⏸", fontSize = 12.sp, color = colors.text)
        Text(
            text = if (paused) "Paused" else "Trip",
            color = if (paused) colors.amber else colors.text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
