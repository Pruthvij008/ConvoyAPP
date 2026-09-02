package com.convoy.mobile.customControls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * The route, shown inside Convoy.
 *
 * Tapping "Directions" used to eject you straight into Google Maps, which
 * meant losing sight of the convoy at the exact moment you wanted it. The
 * route is already computed and drawn on our own map, so the default is now
 * to SHOW it here.
 *
 * Handing off to a maps app is still offered, because turn-by-turn voice
 * guidance is genuinely better there and we are not rebuilding it. But it is
 * the second option, chosen deliberately, rather than the only one.
 */
@Composable
fun RouteSheet(
    destinationLabel: String,
    /** Total route distance, already formatted. */
    distanceText: String?,
    /** Driving time, already formatted. */
    durationText: String?,
    /** True when the estimate came from Google and accounts for traffic. */
    trafficAware: Boolean,
    hasRoute: Boolean,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onShowOnMap: () -> Unit,
    onOpenMapsApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, sheetShape)
            .safeBottom()
            .padding(bottom = 20.dp),
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

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(text = "HEADING TO", style = SectionLabelStyle, color = colors.dim)
            Spacer(Modifier.height(6.dp))
            Text(
                text = destinationLabel,
                color = colors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (isLoading) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Working out your route…",
                    color = colors.muted,
                    fontSize = 13.5.sp,
                )
                Spacer(Modifier.height(18.dp))
                GhostButton(text = "Close", onClick = onDismiss)
            } else if (errorMessage != null) {
                Spacer(Modifier.height(14.dp))
                Text(text = errorMessage, color = colors.amber, fontSize = 13.5.sp)
                Spacer(Modifier.height(18.dp))
                // Google Maps still works without a position of our own —
                // it has its own. So the handoff stays available here.
                PrimaryButton(text = "Open in Maps instead", onClick = onOpenMapsApp)
                Spacer(Modifier.height(10.dp))
                GhostButton(text = "Close", onClick = onDismiss)
            } else if (hasRoute) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    distanceText?.let { Stat(label = "DISTANCE", value = it) }
                    durationText?.let {
                        Stat(
                            label = if (trafficAware) "WITH TRAFFIC" else "DRIVING TIME",
                            value = it,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    // Worth saying plainly: an estimate that ignores traffic
                    // can be hours out on a long drive, and a convoy planning
                    // a chai stop around it deserves to know which it is.
                    text = if (trafficAware) {
                        "Live traffic included in this estimate."
                    } else {
                        "Free-flow estimate — real traffic will make this longer."
                    },
                    color = colors.dim,
                    fontSize = 11.5.sp,
                )

                Spacer(Modifier.height(18.dp))
                PrimaryButton(text = "Show the route", onClick = onShowOnMap)
                Spacer(Modifier.height(10.dp))
                GhostButton(text = "Open in Maps for voice guidance", onClick = onOpenMapsApp)
            } else {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "No route drawn yet — it's worked out when the trip starts.",
                    color = colors.muted,
                    fontSize = 13.5.sp,
                )
                Spacer(Modifier.height(18.dp))
                PrimaryButton(text = "Open in Maps", onClick = onOpenMapsApp)
            }

            Spacer(Modifier.height(10.dp))
            GhostButton(text = "Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val colors = ConvoyTheme.colors
    Column {
        Text(text = label, style = SectionLabelStyle, color = colors.dim)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
