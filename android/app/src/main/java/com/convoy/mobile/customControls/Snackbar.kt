package com.convoy.mobile.customControls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.ui.theme.ConvoyTheme
import kotlinx.coroutines.delay

/**
 * A message from the top of the screen.
 *
 * Errors used to appear at the bottom, below the sheets — which on a map
 * screen means below the fold, so a failure was reported somewhere the user
 * could not see it. On a phone held in one hand the top is also the only
 * region never covered by the keyboard, a bottom sheet, or a thumb.
 *
 * It dismisses itself. An error that needs tapping away is one more thing to
 * do while driving, and the message has done its job the moment it is read.
 */
@Composable
fun TopSnackbar(
    message: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = true,
    /** How long it stays. Long enough to read twice at a glance. */
    durationMs: Long = 5000,
    onDismiss: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    // Keyed on the message so a NEW error restarts the clock rather than
    // inheriting the remainder of the previous one's.
    LaunchedEffect(message) {
        if (message != null) {
            delay(durationMs)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .safeTop()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(16.dp), clip = false)
                .background(
                    if (isError) colors.red else colors.route,
                    RoundedCornerShape(16.dp),
                )
                .clickableOnce(haptic = false, onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(text = if (isError) "!" else "✓", color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = message.orEmpty(),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
