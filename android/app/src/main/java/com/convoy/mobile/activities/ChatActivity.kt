package com.convoy.mobile.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import com.convoy.mobile.dataModel.message.SendState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.convoy.mobile.utility.Formatters
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.dataModel.message.Message
import com.convoy.mobile.dataModel.message.QuickMessage
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.viewModels.ChatViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Trip chat.
 *
 * The keyboard is for passengers. The row above it is what drivers use:
 * one-tap phrases covering most of what actually gets said in a convoy,
 * sized for a thumb without looking.
 */
@AndroidEntryPoint
class ChatActivity : BaseActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tripId = intent.getStringExtra(Constants.EXTRA_TRIP_ID)
        if (tripId.isNullOrBlank()) {
            finish()
            return
        }

        viewModel.bind(tripId)

        setThemedContent {
            ChatScreen(viewModel = viewModel, onBack = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        // Senders want to know a "pull over" landed, so receipts are sent
        // as soon as the screen is actually being looked at.
        viewModel.markAllRead()
    }
}

@Composable
private fun ChatScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val colors = ConvoyTheme.colors
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // New messages scroll into view; reading older ones is a deliberate scroll.
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                color = colors.muted,
                fontSize = 22.sp,
                modifier = Modifier.clickableOnce(onClick = onBack).padding(end = 14.dp),
            )
            Column {
                Text(
                    text = "Convoy chat",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Everyone in the trip sees this",
                    color = colors.muted,
                    fontSize = 12.sp,
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.surface2))

        if (viewModel.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💬", fontSize = 34.sp)
                    Text(
                        text = "Nothing said yet",
                        color = colors.muted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = "Tap a phrase below — no typing needed.",
                        color = colors.dim,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(viewModel.messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        isMine = message.senderId != null &&
                            message.senderId == viewModel.myParticipantId,
                        isPlaying = message.mediaUrl != null &&
                            message.mediaUrl == viewModel.playingUrl,
                        onRetry = { viewModel.retry(message) },
                        onPlayVoice = {
                            message.mediaUrl?.let { viewModel.togglePlayback(it) }
                        },
                    )
                }
            }
        }

        if (viewModel.isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(colors.red.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.size(9.dp).background(colors.red, CircleShape))
                Text(
                    text = "Recording  ${"%.1f".format(viewModel.recordingMs / 1000.0)}s",
                    color = colors.red,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Slide away to cancel",
                    color = colors.muted,
                    fontSize = 11.5.sp,
                )
            }
        }

        // ── Quick phrases ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            viewModel.quickMessages.forEach { quick ->
                QuickChip(quick = quick, onClick = { viewModel.sendQuick(quick) })
            }
        }

        // ── Composer ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = viewModel.draft,
                onValueChange = viewModel::onDraftChanged,
                placeholder = { Text("Message the convoy…", color = colors.dim, fontSize = 15.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = colors.text),
                maxLines = 4,
                // Send rather than Done: the action key doing the same thing
                // as the send button is what people expect in a chat, and it
                // saves a reach across the screen while driving.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    viewModel.sendDraft()
                    keyboard?.hide()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.route,
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, colors.border, RoundedCornerShape(22.dp)),
            )

            // Mic when there is nothing typed, send arrow once there is.
            // One button doing the obvious thing beats two competing for a
            // thumb, and the driver never has to look for the right one.
            if (viewModel.draft.isBlank()) {
                val context = LocalContext.current
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (viewModel.isRecording) colors.red else colors.surface2,
                            CircleShape,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.startRecording(context)
                                    // Suspends until the finger lifts or the
                                    // gesture is cancelled, which is what
                                    // makes this hold-to-talk rather than a
                                    // toggle that can be left recording.
                                    val completed = tryAwaitRelease()
                                    if (completed) {
                                        viewModel.stopRecordingAndSend()
                                    } else {
                                        viewModel.cancelRecording()
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (viewModel.isUploadingVoice) "…" else "🎤",
                        fontSize = 19.sp,
                    )
                }
                return@Row
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.route, CircleShape)
                    .clickableOnce { viewModel.sendDraft() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↑",
                    color = if (viewModel.draft.isBlank()) colors.dim else {
                        if (colors.isDark) Color(0xFF032420) else Color.White
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        viewModel.errorMessage?.let {
            Text(
                text = it,
                color = colors.red,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MessageRow(
    message: Message,
    isMine: Boolean,
    isPlaying: Boolean = false,
    onRetry: () -> Unit,
    onPlayVoice: () -> Unit = {},
) {
    val colors = ConvoyTheme.colors

    // A system line ("Pruthvij is on the way") is about the trip, not from a
    // person. Centred and quiet, so it reads as an event rather than as
    // someone talking.
    if (message.isSystem) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.body.orEmpty(),
                color = colors.dim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Someone else's messages carry an initial. Yours do not — you know
        // who you are, and the avatar column would only cost width.
        if (!isMine) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(colors.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message.senderName.take(1).uppercase(),
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 290.dp),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        ) {
            if (!isMine) {
                Text(
                    text = message.senderName,
                    color = colors.dim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }

            val bubbleColor = when {
                message.isCritical -> colors.red
                message.isQuick && isMine -> colors.route
                message.isQuick -> colors.amber.copy(alpha = 0.18f)
                isMine -> colors.route
                else -> colors.surface2
            }
            // Mine are solid, so the two sides read apart at a glance even
            // in sunlight — colour alone is not enough on a phone on a
            // dashboard.
            val textColor = when {
                message.isCritical -> Color.White
                isMine -> if (colors.isDark) Color(0xFF04221E) else Color.White
                message.isQuick -> colors.amber
                else -> colors.text
            }

            Box(
                modifier = Modifier
                    .background(
                        bubbleColor,
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isMine) 18.dp else 5.dp,
                            bottomEnd = if (isMine) 5.dp else 18.dp,
                        ),
                    )
                    .then(
                        when {
                            message.sendState == SendState.FAILED ->
                                Modifier.clickableOnce(onClick = onRetry)
                            message.isVoice -> Modifier.clickableOnce(onClick = onPlayVoice)
                            else -> Modifier
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (message.isVoice) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Text(
                            text = if (isPlaying) "⏸" else "▶",
                            color = textColor,
                            fontSize = 17.sp,
                        )
                        // A fixed bar rather than a real waveform: drawing
                        // an accurate one means decoding the clip, and the
                        // bar's job is only to say "this is audio, this long".
                        Box(
                            modifier = Modifier
                                .width(96.dp)
                                .height(3.dp)
                                .background(
                                    textColor.copy(alpha = if (isPlaying) 0.95f else 0.4f),
                                    RoundedCornerShape(2.dp),
                                )
                        )
                        Text(
                            text = Formatters.duration((message.durationMs ?: 0L) / 1000),
                            color = textColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.isQuick || message.isCritical) {
                            Text(text = if (message.isCritical) "⚠ " else "", fontSize = 13.sp)
                        }
                        Text(
                            text = message.body.orEmpty(),
                            color = textColor,
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (message.isQuick || message.isCritical) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    }
                }
            }

            // The receipt line, on your own messages only. "Read by 4"
            // matters when you have just told everyone to pull over, and is
            // noise under someone else's reply.
            if (isMine) {
                Text(
                    text = when (message.sendState) {
                        SendState.SENDING -> "Sending…"
                        SendState.FAILED -> "Didn't send · tap to retry"
                        SendState.SENT ->
                            if (message.readCount > 0) "Read by ${message.readCount}" else "Sent"
                    },
                    color = if (message.sendState == SendState.FAILED) colors.red else colors.dim,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickChip(quick: QuickMessage, onClick: () -> Unit) {
    val colors = ConvoyTheme.colors
    val critical = quick.severity == "CRITICAL"

    Row(
        modifier = Modifier
            .background(
                if (critical) colors.red.copy(alpha = 0.15f) else colors.surface2,
                RoundedCornerShape(22.dp),
            )
            .clickableOnce(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        quick.icon?.let { Text(text = it, fontSize = 15.sp) }
        Text(
            text = quick.label,
            color = if (critical) colors.red else colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
