package com.convoy.mobile.activities

import android.app.Activity
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.GhostButton
import com.convoy.mobile.customControls.PickerMapView
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.dataModel.place.Place
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.viewModels.PlaceSearchViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Choosing where the trip is going.
 *
 * Search first, map second. Typing a place name is how everyone already
 * sets a destination, and asking someone to pan a map until a pin lines up
 * with a beach three hundred kilometres away is not a real option.
 *
 * The map stays underneath for the cases search cannot handle — a spot with
 * no name, a meeting point on a particular corner, a dhaba the group knows
 * but OpenStreetMap does not. Picking a result moves the map; moving the map
 * still works on its own.
 */
@AndroidEntryPoint
class PickDestinationActivity : BaseActivity() {

    private val searchViewModel: PlaceSearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val startLng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)

        // Results near the user rank first — most destinations are closer
        // than the most famous place with the same name.
        searchViewModel.setNear(
            startLat.takeIf { !it.isNaN() },
            startLng.takeIf { !it.isNaN() },
        )

        setThemedContent {
            PickDestinationScreen(
                viewModel = searchViewModel,
                startLat = startLat.takeIf { !it.isNaN() },
                startLng = startLng.takeIf { !it.isNaN() },
                onCancel = { finish() },
                onConfirm = { lat, lng, label ->
                    setResult(
                        Activity.RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_LAT, lat)
                            .putExtra(EXTRA_LNG, lng)
                            .putExtra(EXTRA_LABEL, label),
                    )
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_LAT = "extra_dest_lat"
        const val EXTRA_LNG = "extra_dest_lng"
        const val EXTRA_LABEL = "extra_dest_label"

        fun intent(activity: Activity, nearLat: Double?, nearLng: Double?): Intent =
            Intent(activity, PickDestinationActivity::class.java).apply {
                nearLat?.let { putExtra(EXTRA_LAT, it) }
                nearLng?.let { putExtra(EXTRA_LNG, it) }
            }
    }
}

@Composable
private fun PickDestinationScreen(
    viewModel: PlaceSearchViewModel,
    startLat: Double?,
    startLng: Double?,
    onCancel: () -> Unit,
    onConfirm: (Double, Double, String) -> Unit,
) {
    val colors = ConvoyTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current

    var lat by remember { mutableStateOf(startLat ?: 18.5204) }
    var lng by remember { mutableStateOf(startLng ?: 73.8567) }
    var label by remember { mutableStateOf("") }

    // Bumped when a search result is chosen, to tell the map to recentre.
    // The map otherwise owns the camera, and fighting it every recomposition
    // would make it impossible to pan.
    var recentreKey by remember { mutableStateOf(0) }

    // Whether the user has named this place themselves. Once they have, a
    // reverse-geocode must not overwrite it.
    var labelIsUserSet by remember { mutableStateOf(false) }

    val showResults = viewModel.query.trim().length >= 2

    // A pin dropped by hand still gets a name, so the trip list shows
    // "Anjuna, Bardez" rather than a pair of decimals. Only when the user
    // has not named it themselves.
    LaunchedEffect(lat, lng) {
        if (!labelIsUserSet) {
            viewModel.nameFor(lat, lng) { name ->
                if (!labelIsUserSet && name != null) label = name
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.ground)) {

        PickerMapView(
            startLat = lat,
            startLng = lng,
            recentreKey = recentreKey,
            onCentreChanged = { newLat, newLng ->
                lat = newLat
                lng = newLng
            },
            modifier = Modifier.fillMaxSize(),
        )

        // The pin sits at the centre of the screen. Offset by half its own
        // height so the POINT lands on the centre, not the middle of the icon.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-18).dp),
        ) {
            Text(text = "📍", fontSize = 38.sp)
        }

        // ── Search ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(16.dp), clip = false)
                    .background(colors.surface.copy(alpha = 0.98f), RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    color = colors.muted,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clickableOnce(onClick = onCancel)
                        .padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                )

                TextField(
                    value = viewModel.query,
                    onValueChange = viewModel::onQueryChanged,
                    placeholder = {
                        Text("Search a place or address", color = colors.dim, fontSize = 15.sp)
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = colors.text),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchNow() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = colors.route,
                    ),
                    modifier = Modifier.weight(1f),
                )

                if (viewModel.isSearching) {
                    CircularProgressIndicator(
                        color = colors.route,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                } else if (viewModel.query.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = colors.muted,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickableOnce { viewModel.clear() }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }

            // ── Results ─────────────────────────────────────────
            if (showResults && (viewModel.results.isNotEmpty() || viewModel.searched)) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(14.dp, RoundedCornerShape(16.dp), clip = false)
                        .background(colors.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
                ) {
                    if (viewModel.results.isEmpty()) {
                        Text(
                            text = "Nothing found. Try a nearby landmark, or drop the pin yourself.",
                            color = colors.muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(viewModel.results) { place ->
                                PlaceRow(
                                    place = place,
                                    onClick = {
                                        lat = place.lat
                                        lng = place.lng
                                        label = place.displayLabel
                                        labelIsUserSet = true
                                        recentreKey += 1
                                        viewModel.clear()
                                        keyboard?.hide()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            viewModel.errorMessage?.let {
                Text(
                    text = it,
                    color = colors.red,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
        }

        // ── Confirm sheet ───────────────────────────────────────
        // Hidden while results are showing so it cannot cover them on a
        // short screen.
        if (!showResults || viewModel.results.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), clip = false)
                    .background(
                        colors.surface,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(20.dp),
            ) {
                Text(
                    text = "DESTINATION",
                    color = colors.dim,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label.ifBlank { "Move the map to place the pin" },
                    color = if (label.isBlank()) colors.muted else colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(4.dp))

                // The raw coordinates, shown deliberately. If everything else
                // fails, a number someone can read out still works.
                Text(
                    text = "%.5f, %.5f".format(lat, lng),
                    color = colors.dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )

                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    text = "Set as destination",
                    onClick = { onConfirm(lat, lng, label.ifBlank { "Destination" }) },
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(text = "Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    val colors = ConvoyTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableOnce(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "📍", fontSize = 17.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (!place.description.isNullOrBlank()) {
                Text(
                    text = place.description,
                    color = colors.muted,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
