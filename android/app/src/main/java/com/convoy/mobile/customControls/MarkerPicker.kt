package com.convoy.mobile.customControls

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.convoy.mobile.dataModel.marker.Marker
import com.convoy.mobile.dataModel.marker.MarkerOption
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.ui.theme.SectionLabelStyle
import com.convoy.mobile.viewModels.MarkerViewModel

/**
 * "Why have you stopped?"
 *
 * Six big buttons, not a grid of twenty. The trip's curated set decides
 * what appears, and the four favourites come first — if everything is a
 * favourite, nothing is.
 *
 * Every target here is deliberately oversized: the person tapping is in a
 * vehicle and is not looking directly at the screen.
 */
@Composable
fun MarkerPickerSheet(
    favourites: List<MarkerOption>,
    trouble: List<MarkerOption>,
    others: List<MarkerOption>,
    pendingNoteFor: MarkerOption?,
    note: String,
    isSaving: Boolean,
    errorMessage: String?,
    creatingCustom: Boolean,
    customLabel: String,
    customIcon: String,
    /** The photo waiting to go with this stop, if one has been picked. */
    pendingPhoto: Uri? = null,
    isUploadingPhoto: Boolean = false,
    onPickPhoto: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onRemovePhoto: () -> Unit = {},
    onNoteChanged: (String) -> Unit,
    onChoose: (MarkerOption) -> Unit,
    /** Long-press: open the detail step instead of saving immediately. */
    onAddDetail: (MarkerOption) -> Unit = {},
    onConfirmNote: () -> Unit,
    onCancelDetail: () -> Unit = {},
    onStartCustom: () -> Unit,
    onCustomLabelChanged: (String) -> Unit,
    onCustomIconChanged: (String) -> Unit,
    onSaveCustom: () -> Unit,
    onCancelCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, sheetShape)
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

        // Written as a when/else chain rather than an early `return@Column`.
        // Returning out of a composable scope corrupts Compose's group
        // bookkeeping and crashes the next recomposition inside Stack.pop.
        when {
            pendingNoteFor != null -> NoteStep(
                option = pendingNoteFor,
                note = note,
                isSaving = isSaving,
                errorMessage = errorMessage,
                pendingPhoto = pendingPhoto,
                isUploadingPhoto = isUploadingPhoto,
                onPickPhoto = onPickPhoto,
                onTakePhoto = onTakePhoto,
                onRemovePhoto = onRemovePhoto,
                onNoteChanged = onNoteChanged,
                onConfirm = onConfirmNote,
                // Back to the grid, not out of the sheet entirely. Landing
                // on the map after backing out of one marker means starting
                // the whole thing again to pick a different one.
                onBack = onCancelDetail,
            )

            creatingCustom -> CreateMarkerStep(
                label = customLabel,
                icon = customIcon,
                isSaving = isSaving,
                errorMessage = errorMessage,
                onLabelChanged = onCustomLabelChanged,
                onIconChanged = onCustomIconChanged,
                onSave = onSaveCustom,
                onBack = onCancelCustom,
            )

            else -> {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "Why have you stopped?",
                        color = colors.text,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "The group sees this straight away. Hold to add a photo.",
                        color = colors.muted,
                        fontSize = 13.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (favourites.isNotEmpty()) {
                        GroupLabel("Most used")
                        MarkerGrid(favourites, onChoose, onAddDetail = onAddDetail)
                    }

                    if (trouble.isNotEmpty()) {
                        GroupLabel("Trouble")
                        MarkerGrid(trouble, onChoose, danger = true, onAddDetail = onAddDetail)
                    }

                    if (others.isNotEmpty()) {
                        GroupLabel("Something else")
                        MarkerGrid(others, onChoose, onAddDetail = onAddDetail)
                    }

                    // Last, because it is the rarest action — but present
                    // on every trip, because no catalogue we ship will
                    // cover what a particular group actually stops for.
                    GroupLabel("Not listed?")
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        CreateMarkerTile(onClick = onStartCustom)
                    }

                    errorMessage?.let {
                        Text(
                            text = it,
                            color = colors.red,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                        GhostButton(text = "Cancel", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/** Dashed-looking "add" affordance, visually distinct from a real marker. */
@Composable
private fun CreateMarkerTile(onClick: () -> Unit) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .clickableOnce(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "➕", fontSize = 22.sp)
        Column {
            Text(
                text = "Make your own",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Washroom, chai, temple — whatever you stop for",
                color = colors.muted,
                fontSize = 12.5.sp,
            )
        }
    }
}

/**
 * Naming a new marker.
 *
 * A name and an icon, and nothing else. Severity and wait-for-group are
 * asked for on the stop itself, and putting them here would turn a
 * ten-second action into a form.
 */
@Composable
private fun CreateMarkerStep(
    label: String,
    icon: String,
    isSaving: Boolean,
    errorMessage: String?,
    onLabelChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "Make your own stop",
            color = colors.text,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Everyone on this trip gets it, and it's saved for your next one.",
            color = colors.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(18.dp))

        ConvoyTextField(
            value = label,
            onValueChange = onLabelChanged,
            placeholder = "Name it — e.g. Washroom",
            textSize = 16,
            // Done closes the keyboard so the icon grid and the Create
            // button below it are reachable without a back press.
            onImeAction = {},
        )

        Spacer(Modifier.height(18.dp))

        Text(text = "PICK AN ICON", style = SectionLabelStyle, color = colors.dim)
        Spacer(Modifier.height(10.dp))

        // A short curated palette, not the system emoji keyboard — that is
        // a scrolling grid of thousands, which is the wrong thing to hand
        // someone who is stopped by the roadside.
        MarkerViewModel.CUSTOM_ICONS.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { candidate ->
                    val selected = candidate == icon
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) colors.route.copy(alpha = 0.18f) else colors.surface2,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                if (selected) 1.5.dp else 1.dp,
                                if (selected) colors.route else colors.surface2,
                                RoundedCornerShape(12.dp),
                            )
                            .clickableOnce { onIconChanged(candidate) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = candidate, fontSize = 20.sp)
                    }
                }
            }
        }

        errorMessage?.let {
            Text(
                text = it,
                color = colors.red,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            text = if (isSaving) "Creating…" else "Create $icon ${label.ifBlank { "" }}".trim(),
            enabled = !isSaving && label.isNotBlank(),
            onClick = onSave,
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = SectionLabelStyle,
        color = ConvoyTheme.colors.dim,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp),
    )
}

/** Two per row, so each stays a comfortable thumb target. */
@Composable
private fun MarkerGrid(
    options: List<MarkerOption>,
    onChoose: (MarkerOption) -> Unit,
    danger: Boolean = false,
    onAddDetail: (MarkerOption) -> Unit = {},
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                row.forEach { option ->
                    MarkerButton(
                        option = option,
                        danger = danger || option.severity == "CRITICAL",
                        modifier = Modifier.weight(1f),
                        onClick = { onChoose(option) },
                        onLongClick = { onAddDetail(option) },
                    )
                }
                // Keeps a lone odd button half-width instead of stretching it.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Tap saves the stop; hold opens the detail step.
 *
 * A tap has to stay instant — the common case is a driver marking fuel with
 * one thumb — so the photo lives behind a hold rather than adding a second
 * screen to every stop. The hint above the grid is what makes the hold
 * discoverable, since a long-press nobody is told about does not exist.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkerButton(
    option: MarkerOption,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val colors = ConvoyTheme.colors
    val haptics = LocalHapticFeedback.current
    val background = if (danger) colors.red.copy(alpha = 0.13f) else colors.surface2
    val borderColor = if (danger) colors.red.copy(alpha = 0.32f) else colors.surface2

    // combinedClickable is used instead of clickableOnce because that helper
    // has no long-press form — but the debounce it provides is not optional
    // here. On a bumpy road one intended tap regularly registers as two, and
    // on this particular control a double-fire means two stops.
    val lastFired = remember { longArrayOf(0L) }
    fun once(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastFired[0] < 600L) return
        lastFired[0] = now
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
    }

    Column(
        modifier = modifier
            .background(background, RoundedCornerShape(18.dp))
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = { once(onClick) },
                onLongClick = { once(onLongClick) },
            )
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(text = option.icon ?: "📍", fontSize = 28.sp)
        Text(
            text = option.label,
            color = if (danger) colors.red else colors.text,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * Some markers demand a word before they can be saved. "Other" with no
 * explanation tells the convoy nothing, and a medical stop with no detail
 * is worse than none at all.
 */
@Composable
private fun NoteStep(
    option: MarkerOption,
    note: String,
    isSaving: Boolean,
    errorMessage: String?,
    pendingPhoto: Uri?,
    isUploadingPhoto: Boolean,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onNoteChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = option.icon ?: "📍", fontSize = 30.sp)
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = option.label,
                    color = colors.text,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (option.requiresNote) {
                        "Tell the group what's happening."
                    } else {
                        "Add a photo or a word, or just send it."
                    },
                    color = colors.muted,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        ConvoyTextField(
            value = note,
            onValueChange = onNoteChanged,
            placeholder = "e.g. tyre blown, need a hand",
            textSize = 16,
        )

        Spacer(Modifier.height(14.dp))
        StopPhotoRow(
            photo = pendingPhoto,
            isUploading = isUploadingPhoto,
            onPick = onPickPhoto,
            onTake = onTakePhoto,
            onRemove = onRemovePhoto,
        )

        errorMessage?.let {
            Text(
                text = it,
                color = colors.red,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (isUploadingPhoto) "Sending the photo…" else "Tell the convoy",
            loading = isSaving,
            onClick = onConfirm,
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(text = "Back", onClick = onBack)
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * The photo control: two ways in, one way out.
 *
 * Camera and gallery are both offered because both are real. The tyre is in
 * front of you and wants the camera; the photo of the wrong turn you took
 * ten minutes ago is already in the gallery.
 *
 * Once a photo is attached it is shown at a decent size rather than as a
 * chip. A thumbnail the size of a fingernail cannot be checked, and sending
 * the group a blurred photo of the road surface helps nobody.
 */
@Composable
private fun StopPhotoRow(
    photo: Uri?,
    isUploading: Boolean,
    onPick: () -> Unit,
    onTake: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    // if/else rather than an early `return`, to match the rule this file
    // already follows twice over: leaving a composable scope early is how
    // the picker crashed on its next recomposition.
    if (photo == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhotoAction("📷", "Take a photo", Modifier.weight(1f), onTake)
            PhotoAction("🖼️", "Choose one", Modifier.weight(1f), onPick)
        }
    } else {
        Column {
            AsyncImage(
                model = photo,
                contentDescription = "The photo attached to this stop",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    // Clip BEFORE the background, or the square background
                    // paints outside the rounded corners of the image.
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface2),
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isUploading) "Sending…" else "Goes with the stop",
                    color = colors.muted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!isUploading) {
                    Text(
                        text = "Remove",
                        color = colors.red,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickableOnce(haptic = false, onClick = onRemove),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoAction(
    glyph: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    Row(
        modifier = modifier
            .background(colors.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickableOnce(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(text = glyph, fontSize = 17.sp)
        Text(
            text = label,
            color = colors.text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Shown in place of the roster while this vehicle has an active stop.
 *
 * The wait/go-ahead toggle is the whole point: "I've stopped for fuel"
 * tells the convoy nothing about whether to pull over.
 */
@Composable
fun ActiveStopCard(
    stop: Marker,
    elapsed: String,
    onToggleWaiting: () -> Unit,
    onResume: () -> Unit,
    isSaving: Boolean,
) {
    val colors = ConvoyTheme.colors

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        if (stop.isCritical) colors.red.copy(alpha = 0.16f)
                        else colors.route.copy(alpha = 0.14f),
                        RoundedCornerShape(17.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stop.icon ?: "📍", fontSize = 25.sp)
            }

            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    text = "You're at a ${stop.label.lowercase()} stop",
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$elapsed · everyone can see this",
                    color = colors.muted,
                    fontSize = 12.5.sp,
                )
            }
        }

        stop.note?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "“$it”",
                color = colors.muted,
                fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // Shown back to you, not just sent. Without this there is no way to
        // tell whether the photo actually reached the group or quietly
        // failed on the way — and "did they see it?" is the whole question.
        stop.media?.firstOrNull { it.isImage }?.url?.let { url ->
            Spacer(Modifier.height(12.dp))
            AsyncImage(
                model = url,
                contentDescription = "The photo you sent with this stop",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }

        Spacer(Modifier.height(16.dp))

        // A segmented control, because this is a choice between two states
        // rather than a switch that can be left ambiguous.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2, RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegmentOption(
                text = "Wait for me",
                selected = stop.waitingForGroup,
                modifier = Modifier.weight(1f),
                onClick = { if (!stop.waitingForGroup) onToggleWaiting() },
            )
            SegmentOption(
                text = "Go ahead",
                selected = !stop.waitingForGroup,
                modifier = Modifier.weight(1f),
                onClick = { if (stop.waitingForGroup) onToggleWaiting() },
            )
        }

        Spacer(Modifier.height(14.dp))
        PrimaryButton(text = "I'm moving again", loading = isSaving, onClick = onResume)
    }
}

@Composable
private fun SegmentOption(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    Box(
        modifier = modifier
            .background(
                if (selected) colors.route else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(13.dp),
            )
            .clickableOnce(haptic = false, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = when {
                selected && colors.isDark -> androidx.compose.ui.graphics.Color(0xFF032420)
                selected -> androidx.compose.ui.graphics.Color.White
                else -> colors.muted
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
