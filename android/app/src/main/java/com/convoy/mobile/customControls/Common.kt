package com.convoy.mobile.customControls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.dataModel.vehicle.Freshness
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.ui.theme.NumberStyle
import com.convoy.mobile.ui.theme.SectionLabelStyle
import com.convoy.mobile.utility.Formatters

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = SectionLabelStyle,
        color = ConvoyTheme.colors.dim,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

enum class ChipTone { NEUTRAL, LIVE, WARN, CRITICAL, STALE }

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.NEUTRAL, modifier: Modifier = Modifier) {
    val colors = ConvoyTheme.colors
    val (bg, fg) = when (tone) {
        ChipTone.NEUTRAL -> colors.surface2 to colors.muted
        ChipTone.LIVE -> colors.route.copy(alpha = 0.15f) to colors.route
        ChipTone.WARN -> colors.amber.copy(alpha = 0.16f) to colors.amber
        ChipTone.CRITICAL -> colors.red.copy(alpha = 0.17f) to colors.red
        ChipTone.STALE -> colors.muted.copy(alpha = 0.14f) to colors.muted
    }

    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** The coloured square carrying a vehicle's initial. */
@Composable
fun VehicleBadge(
    label: String,
    colorHex: String?,
    modifier: Modifier = Modifier,
    size: Int = 34,
) {
    val colors = ConvoyTheme.colors
    val fill = Formatters.parseColor(colorHex) ?: colors.vehicles.first()

    Box(
        modifier = modifier
            .size(size.dp)
            .background(fill, RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1).uppercase(),
            color = Color.White,
            fontSize = (size * 0.44).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * One line of the convoy roster.
 *
 * Staleness is shown as a visual state, not just a label — a frozen dot
 * must never read as a live one.
 */
@Composable
fun VehicleRosterRow(
    vehicle: Vehicle,
    subtitle: String? = null,
    distanceText: String? = null,
    etaText: String? = null,
    isYou: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickableOnce(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VehicleBadge(label = vehicle.label, colorHex = vehicle.color)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vehicle.label,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                when (vehicle.freshness) {
                    Freshness.LIVE -> Chip("● live", ChipTone.LIVE)
                    Freshness.STALE -> Chip(
                        "◌ ${Formatters.shortAgo(vehicle.lastFixAgeSec)}",
                        ChipTone.STALE,
                    )
                    Freshness.LOST -> Chip("○ no signal", ChipTone.WARN)
                }

                subtitle?.let {
                    Text(text = it, color = colors.muted, fontSize = 12.5.sp, maxLines = 1)
                }
            }
        }

        if (trailing != null) {
            trailing()
        } else if (distanceText != null || etaText != null || isYou) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isYou) "—" else distanceText.orEmpty(),
                    style = NumberStyle,
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isYou) "you" else etaText.orEmpty(),
                    style = NumberStyle,
                    color = colors.dim,
                    fontSize = 11.5.sp,
                )
            }
        }
    }
}

@Composable
fun ConvoyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    // Done rather than Default. A field with no explicit action leaves the
    // keyboard covering half the screen with no obvious way to dismiss it,
    // which on a form with a button underneath means the button cannot be
    // reached. Every field gets a working Done key unless it asks for
    // something else.
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    /** Runs when the user presses the keyboard's action key. */
    onImeAction: (() -> Unit)? = null,
    textSize: Int = 17,
) {
    val colors = ConvoyTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Both are needed: hiding the keyboard without clearing focus leaves the
    // field looking active with a cursor blinking in it, and clearing focus
    // alone does not reliably dismiss the keyboard on every OEM.
    val dismiss = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = LocalTextStyle.current.copy(fontSize = textSize.sp, color = colors.text),
        placeholder = { Text(placeholder, color = colors.dim, fontSize = textSize.sp) },
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke(); dismiss() },
            onGo = { onImeAction?.invoke(); dismiss() },
            onSend = { onImeAction?.invoke(); dismiss() },
            onSearch = { onImeAction?.invoke(); dismiss() },
            // Next moves to the following field rather than dismissing —
            // that is the whole point of a Next key on a form.
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = colors.route,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
    )
}

/** A card that groups rows, matching the sheets in the designs. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = ConvoyTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(7.dp).background(color, CircleShape))
}
