package com.convoy.mobile.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.Chip
import com.convoy.mobile.customControls.ChipTone
import com.convoy.mobile.customControls.GhostButton
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.SectionLabel
import com.convoy.mobile.customControls.SurfaceCard
import com.convoy.mobile.customControls.VehicleBadge
import com.convoy.mobile.customControls.TopSnackbar
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.dataModel.vehicle.Participant
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.customControls.safeTop
import com.convoy.mobile.customControls.safeBottom
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.viewModels.LobbyViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The waiting room, and the host's last look before pressing Start.
 *
 * No location is shared in this state. This is the window for joining,
 * picking cars, and curating the trip's markers.
 */
@AndroidEntryPoint
class LobbyActivity : BaseActivity() {

    private val viewModel: LobbyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tripId = intent.getStringExtra(Constants.EXTRA_TRIP_ID)
        if (tripId.isNullOrBlank()) {
            finish()
            return
        }

        // Foreground location is asked for here rather than at the moment
        // Start is pressed — a permission dialog appearing as the convoy
        // pulls away is the worst possible timing.
        if (!hasLocationPermission()) requestLocationPermission()

        // Opened from a running trip's header means "show me the roster",
        // not "take me back to the map the moment you load".
        val alreadyRunning = intent.getBooleanExtra(Constants.EXTRA_TRIP_RUNNING, false)
        viewModel.load(tripId, autoOpenMapOnStart = !alreadyRunning)

        setThemedContent {
            LobbyScreen(
                viewModel = viewModel,
                onStarted = { openMap(tripId) },
                onLeave = { viewModel.leaveTrip { goToMain() } },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(silent = true)
    }

    private fun openMap(tripId: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(Constants.EXTRA_TRIP_ID, tripId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}

@Composable
private fun LobbyScreen(
    viewModel: LobbyViewModel,
    onStarted: () -> Unit,
    onLeave: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    LaunchedEffect(viewModel.tripStarted) {
        if (viewModel.tripStarted) onStarted()
    }

    // The lobby had no way to report a failure at all. Everything it does
    // is a write someone is waiting on — start the trip, admit a member,
    // hand over host, mark yourself ready — and every one of them could be
    // refused by the server with the screen showing no sign of it.
    Box(modifier = Modifier.fillMaxSize()) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .safeTop(),
    ) {
        // ── Header ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = viewModel.trip?.name.orEmpty(),
                    color = colors.text,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Waiting to start",
                    color = colors.muted,
                    fontSize = 12.5.sp,
                )
            }
            Text(
                text = "Leave",
                color = colors.muted,
                fontSize = 14.sp,
                modifier = Modifier.clickableOnce(onClick = onLeave).padding(8.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Chip("● ${viewModel.readyCount} of ${viewModel.total} ready", ChipTone.LIVE)
            Chip("${viewModel.vehicles.size} cars")
            if (viewModel.pendingRequests > 0) {
                Chip("${viewModel.pendingRequests} waiting", ChipTone.WARN)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("Who's coming")

            SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                Column {
                    viewModel.vehicles.forEach { vehicle ->
                        VehicleLobbyRow(
                            vehicle = vehicle,
                            occupants = viewModel.participants.filter {
                                it.vehicleId == vehicle.id
                            },
                            onBoard = { viewModel.boardVehicle(vehicle.id) },
                            canBoard = viewModel.me?.vehicleId != vehicle.id,
                        )
                    }

                    // People with no car cannot be tracked and would appear
                    // nowhere on the map, so they are shown explicitly.
                    viewModel.participants
                        .filter { !it.hasVehicle }
                        .forEach { UnassignedRow(it) }
                }
            }

            Spacer(Modifier.height(20.dp))
        }

        // ── Footer ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .safeBottom()
                .padding(bottom = 20.dp),
        ) {
            viewModel.startBlockedMessage?.let { message ->
                StartBlockedBanner(
                    message = message,
                    onForce = { viewModel.startTrip(force = true) },
                    onDismiss = viewModel::dismissStartBlocked,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (viewModel.amHost) {
                // The host counts toward "x of y ready" like everyone else, so
                // they need the toggle too — otherwise a solo host is stuck
                // looking at "0 of 1 ready" with no way to change it.
                GhostButton(
                    text = if (viewModel.isReady) "You're ready ✓" else "Mark yourself ready",
                    onClick = viewModel::toggleReady,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "Start trip",
                    loading = viewModel.isLoading,
                    onClick = { viewModel.startTrip() },
                )
            } else {
                PrimaryButton(
                    text = if (viewModel.isReady) "You're ready ✓" else "I'm ready",
                    onClick = viewModel::toggleReady,
                )
                Text(
                    text = "The host starts the trip. Location sharing begins then, " +
                        "not before.",
                    color = colors.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

        // Every failure this screen can produce, finally visible.
        TopSnackbar(
            message = viewModel.errorMessage,
            modifier = Modifier.align(Alignment.TopCenter),
            onDismiss = viewModel::dismissError,
        )
    }
}

@Composable
private fun VehicleLobbyRow(
    vehicle: Vehicle,
    occupants: List<Participant>,
    canBoard: Boolean,
    onBoard: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val allReady = occupants.isNotEmpty() && occupants.all { it.isReady }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
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
            )
            Text(
                text = occupants.joinToString(" · ") { it.displayName }
                    .ifBlank { "Nobody in this car yet" },
                color = colors.muted,
                fontSize = 12.5.sp,
            )
        }

        when {
            allReady -> Text("✓", color = colors.route, fontSize = 19.sp)
            canBoard -> Text(
                text = "Join",
                color = colors.route,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickableOnce(onClick = onBoard).padding(6.dp),
            )
            else -> Chip("waiting", ChipTone.NEUTRAL)
        }
    }
}

@Composable
private fun UnassignedRow(participant: Participant) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .background(colors.surface2, RoundedCornerShape(11.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            Text("?", color = colors.muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(participant.displayName, color = colors.muted, fontSize = 15.sp)
            Text(
                text = "Not in a car yet",
                color = colors.amber,
                fontSize = 12.5.sp,
            )
        }

        Chip("waiting", ChipTone.WARN)
    }
}

/**
 * The preflight refused the start and named who is holding it up. It is
 * overridable, because the realistic case is a friend twenty minutes late
 * and the group deciding to set off.
 */
@Composable
private fun StartBlockedBanner(
    message: String,
    onForce: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.amber.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .border(1.dp, colors.amber.copy(alpha = 0.34f), RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚠️", fontSize = 19.sp)
            Text(
                text = message,
                color = colors.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(
                text = "Start anyway",
                color = colors.amber,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickableOnce(onClick = onForce),
            )
            Text(
                text = "Wait",
                color = colors.muted,
                fontSize = 13.5.sp,
                modifier = Modifier.clickableOnce(onClick = onDismiss),
            )
        }
    }
}
