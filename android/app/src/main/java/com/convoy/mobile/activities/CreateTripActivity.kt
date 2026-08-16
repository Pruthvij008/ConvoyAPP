package com.convoy.mobile.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.ChipTone
import com.convoy.mobile.customControls.ConvoyTextField
import com.convoy.mobile.customControls.ErrorText
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.SecondaryButton
import com.convoy.mobile.customControls.SectionLabel
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.viewModels.TripViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Creating a trip, and the share sheet that follows it.
 *
 * A trip opens for joining the moment it is created — the whole point is
 * to share the link — so there is no separate "publish" step.
 */
@AndroidEntryPoint
class CreateTripActivity : BaseActivity() {

    private val viewModel: TripViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setThemedContent {
            CreateTripScreen(
                viewModel = viewModel,
                onBack = { finish() },
                onShare = { link -> shareLink(link) },
                onOpenLobby = { tripId -> openLobby(tripId) },
                onPickDestination = { pickDestination() },
            )
        }
    }

    private val destinationPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        viewModel.onDestinationPicked(
            data.getDoubleExtra(PickDestinationActivity.EXTRA_LAT, 0.0),
            data.getDoubleExtra(PickDestinationActivity.EXTRA_LNG, 0.0),
            data.getStringExtra(PickDestinationActivity.EXTRA_LABEL).orEmpty(),
        )
    }

    private fun pickDestination() {
        destinationPicker.launch(
            PickDestinationActivity.intent(
                this,
                viewModel.destinationLat,
                viewModel.destinationLng,
            )
        )
    }

    private fun shareLink(link: String) {
        val trip = viewModel.tripName.ifBlank { "a trip" }
        val text = "Join $trip on Convoy: $link"

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Share the trip",
            )
        )
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
private fun CreateTripScreen(
    viewModel: TripViewModel,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    onOpenLobby: (String) -> Unit,
    onPickDestination: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val link = viewModel.joinLink
    val tripId = viewModel.createdTripId

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .statusBarsPadding(),
    ) {
        if (link != null && tripId != null) {
            ShareStep(
                link = link,
                code = viewModel.joinCodeCreated.orEmpty(),
                tripName = viewModel.tripName,
                onShare = { onShare(link) },
                onContinue = { onOpenLobby(tripId) },
            )
        } else {
            FormStep(
                viewModel = viewModel,
                onBack = onBack,
                onPickDestination = onPickDestination,
            )
        }
    }
}

@Composable
private fun FormStep(
    viewModel: TripViewModel,
    onBack: () -> Unit,
    onPickDestination: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        Text(
            text = "←",
            color = colors.muted,
            fontSize = 22.sp,
            modifier = Modifier.clickableOnce(onClick = onBack).padding(vertical = 8.dp),
        )

        Text(
            text = "Start a trip",
            color = colors.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "You'll get a link to share. Anyone who taps it can join.",
            color = colors.muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 26.dp),
        )

        SectionLabel("Name this trip", Modifier.padding(horizontal = 0.dp))
        ConvoyTextField(
            value = viewModel.tripName,
            onValueChange = viewModel::onTripNameChanged,
            placeholder = "Pune to Goa",
        )

        SectionLabel("Destination", Modifier.padding(horizontal = 0.dp))
        DestinationPicker(
            label = viewModel.destination,
            hasPoint = viewModel.hasPickedDestination,
            lat = viewModel.destinationLat,
            lng = viewModel.destinationLng,
            onPick = onPickDestination,
        )

        SectionLabel("Your vehicle", Modifier.padding(horizontal = 0.dp))
        ConvoyTextField(
            value = viewModel.vehicleLabel,
            onValueChange = viewModel::onVehicleLabelChanged,
            placeholder = "Rohit's Thar",
        )

        Spacer(Modifier.height(10.dp))
        VehicleTypeRow(
            selected = viewModel.vehicleType,
            onSelect = viewModel::onVehicleTypeChanged,
        )

        Text(
            text = "Four friends in one car is one dot on the map, not four. " +
                "Passengers join your vehicle instead of creating their own.",
            color = colors.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 14.dp),
        )

        ErrorText(viewModel.errorMessage)

        Spacer(Modifier.height(26.dp))
        PrimaryButton(
            text = "Create and get the link",
            enabled = viewModel.canCreate,
            loading = viewModel.isLoading,
            onClick = viewModel::createTrip,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VehicleTypeRow(selected: String, onSelect: (String) -> Unit) {
    val colors = ConvoyTheme.colors
    val types = listOf("CAR" to "Car", "BIKE" to "Bike", "SUV" to "SUV", "VAN" to "Van")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) colors.route.copy(alpha = 0.16f) else colors.surface2,
                        RoundedCornerShape(12.dp),
                    )
                    .clickableOnce(haptic = false) { onSelect(key) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) colors.route else colors.muted,
                    fontSize = 13.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * The link is shown exactly once. Only its hash is stored on the server, so
 * it cannot be recovered later — rotating the invite issues a fresh one.
 */
@Composable
private fun ShareStep(
    link: String,
    code: String,
    tripName: String,
    onShare: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "🔗", fontSize = 44.sp)

        Text(
            text = tripName.ifBlank { "Trip created" },
            color = colors.text,
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = "Send this to everyone coming. Tapping it opens the trip.",
            color = colors.muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 26.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Or read out this code",
                    color = colors.dim,
                    fontSize = 12.sp,
                )
                Text(
                    text = code,
                    color = colors.route,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        PrimaryButton(text = "Share the link", onClick = onShare)
        Spacer(Modifier.height(10.dp))
        SecondaryButton(text = "Go to the lobby", onClick = onContinue)

        Text(
            text = "The link expires in 24 hours, and you can revoke it any time.",
            color = colors.dim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 22.dp),
        )
    }
}


/**
 * Destination as a tappable card rather than a text field.
 *
 * Shows the coordinates once picked, because that is the difference between
 * a place the app can navigate to and a string nobody can route from.
 */
@Composable
private fun DestinationPicker(
    label: String,
    hasPoint: Boolean,
    lat: Double?,
    lng: Double?,
    onPick: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .clickableOnce(onClick = onPick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = if (hasPoint) "\uD83D\uDCCD" else "\uD83D\uDDFA\uFE0F", fontSize = 22.sp)

        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                text = if (hasPoint) label.ifBlank { "Destination" } else "Pick on the map",
                color = if (hasPoint) colors.text else colors.dim,
                fontSize = 16.sp,
                fontWeight = if (hasPoint) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (hasPoint && lat != null && lng != null) {
                Text(
                    text = "%.5f, %.5f".format(lat, lng),
                    color = colors.dim,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
            } else {
                Text(
                    text = "Needed for directions and ETAs",
                    color = colors.dim,
                    fontSize = 12.sp,
                )
            }
        }

        Text(text = "\u203A", color = colors.muted, fontSize = 22.sp)
    }
}
