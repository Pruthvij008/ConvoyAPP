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
                    )
                }
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

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (viewModel.draft.isBlank()) colors.surface2 else colors.route,
                        CircleShape,
                    )
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
private fun MessageRow(message: Message, isMine: Boolean) {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = if (isMine) {
                // Only shown on your own messages: "read by 4" matters when
                // you have just told everyone to pull over, and is noise on
                // someone else's reply.
                if (message.readCount > 0) "You · read by ${message.readCount}" else "You"
            } else {
                message.senderName
            },
            color = colors.dim,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(bottom = 3.dp),
        )

        val bubbleColor = when {
            message.isCritical -> colors.red.copy(alpha = 0.18f)
            message.isQuick -> colors.amber.copy(alpha = 0.15f)
            isMine -> colors.route.copy(alpha = 0.16f)
            else -> colors.surface2
        }
        val textColor = when {
            message.isCritical -> colors.red
            message.isQuick -> colors.amber
            else -> colors.text
        }

        Box(
            modifier = Modifier
                .background(
                    bubbleColor,
                    if (isMine) {
                        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                    } else {
                        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
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
