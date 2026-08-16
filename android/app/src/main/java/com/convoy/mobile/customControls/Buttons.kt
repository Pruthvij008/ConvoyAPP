package com.convoy.mobile.customControls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.ui.theme.ConvoyTheme

/**
 * Every tappable control in the app is at least 56dp tall.
 *
 * That is well above the 48dp Android minimum, because the person tapping
 * is often in a moving vehicle and not looking directly at the screen.
 */
private val ButtonHeight = 56.dp
private val ButtonShape = RoundedCornerShape(16.dp)

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val clickable = enabled && !loading

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .alpha(if (clickable) 1f else 0.45f)
            .background(colors.route, ButtonShape)
            .clickableOnce(enabled = clickable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = if (colors.isDark) Color(0xFF032420) else Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                color = if (colors.isDark) Color(0xFF032420) else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .border(1.dp, colors.border, ButtonShape)
            .clickableOnce(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.muted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DangerButton(
    text: String,
    modifier: Modifier = Modifier,
    // Present for the same reason it is on PrimaryButton: a destructive
    // action fired twice because the first tap gave no feedback is the one
    // kind of double-submit that cannot be taken back.
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .background(colors.red, ButtonShape)
            .clickableOnce(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .background(colors.surface2, ButtonShape)
            .clickableOnce(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        color = ConvoyTheme.colors.red,
        fontSize = 13.5.sp,
        modifier = modifier.padding(top = 10.dp),
    )
}
