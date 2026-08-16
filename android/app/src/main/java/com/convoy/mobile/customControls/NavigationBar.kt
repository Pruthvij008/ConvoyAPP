package com.convoy.mobile.customControls

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.ui.theme.ConvoyTheme

/**
 * The navigation chrome.
 *
 * Two bars and nothing else. While driving, every pixel that is not the
 * road ahead or the next number you need is a pixel competing for a glance
 * you cannot spare — so the roster, the header pills and the action tiles
 * are gone entirely rather than merely dimmed.
 *
 * The numbers are deliberately large. These are read at 80 km/h in
 * peripheral vision, not studied.
 */
@Composable
fun NavigationBar(
    destinationLabel: String,
    distanceText: String?,
    etaText: String?,
    trafficAware: Boolean,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {

        // ── Top: where you're going ─────────────────────────────
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(18.dp), clip = false)
                .background(colors.surface, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(colors.route.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🧭", fontSize = 17.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Heading to",
                    color = colors.dim,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = destinationLabel,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The bottom navigation bar: distance, time, and the way out.
 *
 * Separated from the top bar so it can sit against the bottom edge, where
 * a thumb reaches without the hand leaving the wheel.
 */
@Composable
fun NavigationFooter(
    distanceText: String?,
    etaText: String?,
    trafficAware: Boolean,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), clip = false)
            .background(
                colors.surface,
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = etaText ?: "—",
                    color = colors.text,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = distanceText ?: "",
                    color = colors.muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                // Which estimate this is matters: free-flow can be hours
                // out on a long drive, and a convoy planning a stop around
                // it deserves to know.
                text = if (trafficAware) "With live traffic" else "Free-flow estimate",
                color = colors.dim,
                fontSize = 11.sp,
            )
        }

        Box(
            modifier = Modifier
                .background(colors.red.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                .clickableOnce(onClick = onExit)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "Exit",
                color = colors.red,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
