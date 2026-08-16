package com.convoy.mobile.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.Chip
import com.convoy.mobile.customControls.ChipTone
import com.convoy.mobile.customControls.ConvoyTextField
import com.convoy.mobile.customControls.ErrorText
import com.convoy.mobile.customControls.GhostButton
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.SurfaceCard
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.viewModels.TripViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Where a shared link lands, and where a spoken code is typed.
 *
 * Tapping a WhatsApp link and hitting a blank "enter your name" screen is
 * hostile, so the trip is previewed first — name, host, member count —
 * before anyone commits. The preview deliberately carries no location.
 */
@AndroidEntryPoint
class JoinTripActivity : BaseActivity() {

    private val viewModel: TripViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readInvite(intent)

        setThemedContent {
            JoinTripScreen(
                viewModel = viewModel,
                onBack = { finish() },
                onJoined = { tripId -> openLobby(tripId) },
            )
        }
    }

    /** singleTask, so a second link arrives here rather than stacking. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readInvite(intent)
    }

    /**
     * Accepts both link shapes: the verified https App Link, and the custom
     * convoy:// scheme used where App Links cannot be verified (local
     * development, or a messaging app that mangles the URL).
     */
    private fun readInvite(intent: Intent?) {
        val data = intent?.data
        val token = when {
            data == null -> intent?.getStringExtra(Constants.EXTRA_JOIN_TOKEN)
            data.scheme == "convoy" -> data.getQueryParameter("token")
                ?: data.lastPathSegment
            else -> data.lastPathSegment
        }

        if (!token.isNullOrBlank()) {
            viewModel.setJoinToken(token)
            viewModel.loadPreview()
        }

        intent?.getStringExtra(Constants.EXTRA_JOIN_CODE)?.let {
            viewModel.onJoinCodeChanged(it)
            viewModel.loadPreview()
        }
    }

    private fun openLobby(tripId: String) {
        startActivity(
            Intent(this, LobbyActivity::class.java)
                .putExtra(Constants.EXTRA_TRIP_ID, tripId)
        )
        finish()
    }
}

@Composable
private fun JoinTripScreen(
    viewModel: TripViewModel,
    onBack: () -> Unit,
    onJoined: (String) -> Unit,
) {
    val colors = ConvoyTheme.colors
    val preview = viewModel.preview

    LaunchedEffect(viewModel.joinedTripId) {
        viewModel.joinedTripId?.let(onJoined)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = "←",
            color = colors.muted,
            fontSize = 22.sp,
            modifier = Modifier
                .clickableOnce(onClick = onBack)
                .padding(vertical = 12.dp),
        )

        when {
            viewModel.awaitingApproval -> AwaitingApproval()
            preview != null -> PreviewStep(viewModel, preview.hostName, onJoined)
            else -> CodeStep(viewModel)
        }
    }
}

@Composable
private fun CodeStep(viewModel: TripViewModel) {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "🔑", fontSize = 40.sp)

        Text(
            text = "Got a code?",
            color = colors.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = "Six characters, no zeros or ones — they're left out so a " +
                "code read out loud can't be mistyped.",
            color = colors.muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 26.dp),
        )

        ConvoyTextField(
            value = viewModel.joinCode,
            onValueChange = viewModel::onJoinCodeChanged,
            placeholder = "ABC123",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textSize = 22,
        )

        ErrorText(viewModel.errorMessage)

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = "Find the trip",
            enabled = viewModel.canJoinByCode,
            loading = viewModel.isLoading,
            onClick = viewModel::loadPreview,
        )
    }
}

@Composable
private fun PreviewStep(
    viewModel: TripViewModel,
    hostName: String?,
    onJoined: (String) -> Unit,
) {
    val colors = ConvoyTheme.colors
    val preview = viewModel.preview ?: return
    var newVehicle by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "YOU'VE BEEN INVITED",
            color = colors.route,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
        )

        Text(
            text = preview.name,
            color = colors.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp),
        )

        Text(
            text = buildString {
                hostName?.let { append("Hosted by $it") }
                preview.destinationAddress?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }.ifBlank { "Ready to join" },
            color = colors.muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 14.dp, bottom = 22.dp),
        ) {
            Chip("${preview.memberCount} members")
            Chip("${preview.vehicleCount} cars")
            if (preview.requiresApproval) Chip("host approves", ChipTone.WARN)
            if (preview.notOpenYet) Chip("not open yet", ChipTone.WARN)
        }

        if (preview.requiresPassword) {
            ConvoyTextField(
                value = viewModel.joinPassword,
                onValueChange = viewModel::onJoinPasswordChanged,
                placeholder = "Trip password",
                textSize = 16,
            )
            Spacer(Modifier.height(14.dp))
        }

        ConvoyTextField(
            value = newVehicle,
            onValueChange = { newVehicle = it.take(40) },
            placeholder = "Name your car — e.g. Priya's Swift",
            textSize = 16,
        )

        ErrorText(viewModel.errorMessage)

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = "Join with my own car",
            enabled = !viewModel.isLoading && !preview.notOpenYet,
            loading = viewModel.isLoading,
            onClick = { viewModel.joinTrip(newVehicleLabel = newVehicle.ifBlank { "My car" }) },
        )

        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = "I'm riding with someone",
            enabled = !viewModel.isLoading && !preview.notOpenYet,
            // Joining with no vehicle puts you in the trip as a passenger;
            // the lobby is where you pick whose car you're in.
            onClick = { viewModel.joinTrip() },
        )

        if (preview.notOpenYet) {
            Text(
                text = "The host hasn't opened this trip yet. You'll be able to " +
                    "join once they do.",
                color = colors.amber,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun AwaitingApproval() {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = colors.route, strokeWidth = 2.dp)

        Text(
            text = "Waiting for the host",
            color = colors.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "They've been asked to let you in. This screen will move on " +
                "by itself once they do.",
            color = colors.muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
