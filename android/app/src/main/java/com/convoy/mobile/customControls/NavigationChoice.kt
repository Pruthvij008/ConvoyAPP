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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.ui.theme.SectionLabelStyle

/**
 * Somewhere the user can be navigated to.
 *
 * Every navigable thing on the map collapses to this: the trip destination,
 * a friend with a puncture, an SOS, a waypoint, a place marker. Having one
 * type means the "how do you want to get there?" question is asked the same
 * way everywhere instead of being reinvented per screen.
 */
data class NavTarget(
    val lat: Double,
    val lng: Double,
    val label: String,
    /** What this is, phrased for the sheet's subtitle. */
    val subtitle: String? = null,
    /** The vehicle being navigated to, when there is one. Drives "I'm on the way". */
    val vehicleId: String? = null,
    /** True for an emergency, which changes the wording and the emphasis. */
    val urgent: Boolean = false,
    /** A moving car cannot be a fixed destination — see the note in the sheet. */
    val isMoving: Boolean = false,
)

/**
 * "How do you want to get there?"
 *
 * Asked for EVERY navigation action rather than only the destination
 * button, so the answer is never a surprise. Convoy's own route keeps the
 * convoy on screen; Google gives turn-by-turn voice. Neither is right for
 * every situation, which is exactly why the user is asked instead of the
 * app deciding.
 */
@Composable
fun NavigationChoiceSheet(
    target: NavTarget,
    distanceText: String?,
    /** Shown only when the target is a vehicle you could go and help. */
    canOfferHelp: Boolean,
    isBusy: Boolean,
    onUseInApp: () -> Unit,
    onUseGoogleMaps: () -> Unit,
    onTellThemImComing: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

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
            Text(
                text = if (target.urgent) "EMERGENCY" else "GO TO",
                style = SectionLabelStyle,
                color = if (target.urgent) colors.red else colors.dim,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = target.label,
                color = colors.text,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 27.sp,
            )

            val subtitle = listOfNotNull(target.subtitle, distanceText).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(text = subtitle, color = colors.muted, fontSize = 13.5.sp)
            }

            // Said plainly rather than discovered halfway down the road: a
            // maps app takes a FIXED destination, so routing to a car that
            // is still driving sends you to where it used to be.
            if (target.isMoving) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.amber.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("!", color = colors.amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "They're still moving. Google Maps will route you to where " +
                            "they are now, not where they'll be — Convoy keeps updating.",
                        color = colors.muted,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            NavOption(
                glyph = "🧭",
                title = "Show it in Convoy",
                detail = "Keeps everyone on the map. Updates as they move.",
                highlighted = true,
                enabled = !isBusy,
                onClick = onUseInApp,
            )
            Spacer(Modifier.height(10.dp))
            NavOption(
                glyph = "➤",
                title = "Open Google Maps",
                detail = "Turn-by-turn with voice. You'll leave Convoy.",
                highlighted = false,
                enabled = !isBusy,
                onClick = onUseGoogleMaps,
            )

            // Telling them help is coming matters as much as the route: a
            // stranded driver watching a dot approach with no message does
            // not know it is coming for them.
            if (canOfferHelp) {
                Spacer(Modifier.height(10.dp))
                NavOption(
                    glyph = "🤝",
                    title = "Tell them I'm on the way",
                    detail = "Posts to the group so they know help is coming.",
                    highlighted = false,
                    enabled = !isBusy,
                    onClick = onTellThemImComing,
                )
            }

            Spacer(Modifier.height(14.dp))
            GhostButton(text = "Cancel", onClick = onDismiss)
        }
    }
}

@Composable
private fun NavOption(
    glyph: String,
    title: String,
    detail: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val background = if (highlighted) colors.route.copy(alpha = 0.14f) else colors.surface2
    val borderColor = if (highlighted) colors.route.copy(alpha = 0.45f) else colors.border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickableOnce(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    if (highlighted) colors.route.copy(alpha = 0.18f) else colors.surface,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = glyph, fontSize = 17.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (highlighted) colors.route else colors.text,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = detail, color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
