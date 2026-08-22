package com.convoy.mobile.activities

import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.Chip
import com.convoy.mobile.customControls.ChipTone
import com.convoy.mobile.customControls.ConvoyMapView
import com.convoy.mobile.customControls.ActiveStopCard
import com.convoy.mobile.customControls.AlertBanner
import com.convoy.mobile.customControls.SosButton
import com.convoy.mobile.customControls.SosOverlay
import com.convoy.mobile.customControls.MarkerPickerSheet
import com.convoy.mobile.customControls.NavigationBar
import com.convoy.mobile.customControls.NavigationFooter
import com.convoy.mobile.customControls.NavTarget
import com.convoy.mobile.customControls.NavigationChoiceSheet
import com.convoy.mobile.customControls.RouteSheet
import com.convoy.mobile.customControls.TopSnackbar
import com.convoy.mobile.customControls.GhostButton
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.StatusDot
import com.convoy.mobile.customControls.TripControlsButton
import com.convoy.mobile.customControls.TripControlsSheet
import com.convoy.mobile.customControls.VehicleRosterRow
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.dataModel.trip.TripStatus
import com.convoy.mobile.dataModel.vehicle.Freshness
import com.convoy.mobile.service.LocationTrackingService
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.ui.theme.SectionLabelStyle
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.utility.Formatters
import com.convoy.mobile.customControls.ChaseOverlay
import com.convoy.mobile.utility.Geo
import com.convoy.mobile.utility.LocationReadiness
import com.convoy.mobile.utility.Navigation
import com.convoy.mobile.viewModels.MapViewModel
import com.convoy.mobile.viewModels.AlertViewModel
import com.convoy.mobile.viewModels.MarkerViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The hub, and the live trip screen.
 *
 * Two states: no trip running (create or join), and a trip in progress.
 * While a trip runs the MAP IS THE SCREEN — the header and the roster float
 * over it, which is what makes this read as a map app rather than a form
 * with a map embedded in it.
 */
@AndroidEntryPoint
class MainActivity : BaseActivity() {

    private val viewModel: MapViewModel by viewModels()
    private val markerViewModel: MarkerViewModel by viewModels()
    private val alertViewModel: AlertViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTripIfAny()

        setThemedContent {
            MainScreen(
                viewModel = viewModel,
                markerViewModel = markerViewModel,
                alertViewModel = alertViewModel,
                displayName = prefs.displayName.orEmpty(),
                onCreate = { startActivity(Intent(this, CreateTripActivity::class.java)) },
                onJoin = { startActivity(Intent(this, JoinTripActivity::class.java)) },
                onOpenLobby = { tripId -> openLobby(tripId) },
                onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onChat = { tripId ->
                    startActivity(
                        Intent(this, ChatActivity::class.java)
                            .putExtra(Constants.EXTRA_TRIP_ID, tripId)
                    )
                },
                onFinished = { returnToEmptyState() },
                onTripLiveChanged = { syncTrackingService() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTripIfAny()
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.hasTrip) viewModel.refresh(silent = true) else openTripIfAny()
        syncTrackingService()
    }

    /**
     * The trip arrives two ways: passed in after the lobby starts it, or
     * remembered from a previous launch so a cold start resumes straight
     * into the running trip.
     */
    private fun openTripIfAny() {
        val tripId = intent.getStringExtra(Constants.EXTRA_TRIP_ID) ?: prefs.activeTripId
        if (!tripId.isNullOrBlank()) viewModel.load(tripId)
    }

    /**
     * Tracking runs only while the trip is genuinely live, and only if the
     * permission is actually granted — starting a location foreground
     * service without it crashes on Android 14.
     */
    private fun syncTrackingService() {
        val trip = viewModel.trip

        if (trip != null && trip.isLive && hasLocationPermission()) {
            // `trip` is a plain local, so this smart-casts and needs no `!!`.
            LocationTrackingService.start(this, trip.id, prefs.activeVehicleId)
        } else if (trip != null && trip.isFinished) {
            // Only stop on a trip we have loaded and know is over. `null`
            // just means it hasn't arrived yet, and treating that as
            // "finished" kills the service on every cold start.
            LocationTrackingService.stop(this)
        }
    }

    private fun openLobby(tripId: String) {
        startActivity(
            Intent(this, LobbyActivity::class.java)
                .putExtra(Constants.EXTRA_TRIP_ID, tripId)
                .putExtra(Constants.EXTRA_TRIP_RUNNING, viewModel.trip?.isLive == true)
        )
    }

    private fun returnToEmptyState() {
        LocationTrackingService.stop(this)
        prefs.activeTripId = null
        prefs.activeVehicleId = null
        intent.removeExtra(Constants.EXTRA_TRIP_ID)
    }
}

@Composable
private fun MainScreen(
    viewModel: MapViewModel,
    markerViewModel: MarkerViewModel,
    alertViewModel: AlertViewModel,
    displayName: String,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onOpenLobby: (String) -> Unit,
    onSettings: () -> Unit,
    onChat: (String) -> Unit,
    onFinished: () -> Unit,
    onTripLiveChanged: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val trip = viewModel.trip

    LaunchedEffect(viewModel.tripFinished) {
        if (viewModel.tripFinished) onFinished()
    }

    // The trip arrives from the network after this first composes, so
    // tracking is synced when its live state actually changes.
    LaunchedEffect(trip?.isLive, trip?.id) { onTripLiveChanged() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground),
    ) {
        if (trip == null || viewModel.tripFinished) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Convoy",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CircleIconButton("⚙", onSettings)
                }
                Box(modifier = Modifier.weight(1f)) {
                    NoTripScreen(displayName = displayName, onCreate = onCreate, onJoin = onJoin)
                }
            }
        } else {
            ActiveTripScreen(
                viewModel = viewModel,
                markerViewModel = markerViewModel,
                alertViewModel = alertViewModel,
                onOpenLobby = { onOpenLobby(trip.id) },
                onSettings = onSettings,
                onChat = { onChat(trip.id) },
            )
        }
    }
}

@Composable
private fun NoTripScreen(
    displayName: String,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    val colors = ConvoyTheme.colors

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "🗺️", fontSize = 46.sp)

            Text(
                text = if (displayName.isBlank()) "No trip running" else "Hi $displayName",
                color = colors.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp),
            )

            Text(
                text = "Start a trip and share the link, or open one a friend sent you.",
                color = colors.muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 32.dp),
            )

            PrimaryButton(text = "Start a trip", onClick = onCreate)

            GhostButton(
                text = "Join with a code",
                onClick = onJoin,
                modifier = Modifier.padding(top = 10.dp),
            )

            Text(
                text = "Your location is never shared until a trip is running.",
                color = colors.dim,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 26.dp),
            )
        }
    }
}

@Composable
private fun ActiveTripScreen(
    viewModel: MapViewModel,
    markerViewModel: MarkerViewModel,
    alertViewModel: AlertViewModel,
    onOpenLobby: () -> Unit,
    onSettings: () -> Unit,
    onChat: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val context = LocalContext.current
    val trip = viewModel.trip ?: return
    val paused = trip.status == TripStatus.PAUSED
    val myVehicle = viewModel.vehicles.firstOrNull { it.id == viewModel.myVehicleId }

    // The picker is driven by the trip's own curated marker set.
    LaunchedEffect(trip.id, viewModel.myVehicleId) {
        markerViewModel.bind(trip.id, trip.markerSet.orEmpty(), viewModel.myVehicleId)
    }
    LaunchedEffect(trip.id) { alertViewModel.bind(trip.id) }

    // Re-fetch the active stops whenever ANY vehicle's status changes, not
    // just our own. The roster carries a label and an icon; the note and the
    // photo live on the marker, and without this they would only ever appear
    // for a stop that happened to be marked before the screen opened.
    val stopSignature = viewModel.vehicles.joinToString(",") {
        "${it.id}:${it.currentStatus?.markerId.orEmpty()}"
    }
    LaunchedEffect(stopSignature) {
        if (stopSignature.isNotBlank()) markerViewModel.refreshActiveStop(viewModel.myVehicleId)
    }

    // Which car we're chasing, if any. Held by id rather than by the
    // Vehicle itself so the overlay reads live positions every refresh —
    // a captured object would freeze at the moment it was tapped, which is
    // precisely the staleness Chase Mode exists to avoid.
    var chaseVehicleId by remember { mutableStateOf<String?>(null) }

    // Whatever the user has asked to navigate to. EVERY map action sets
    // this — the destination button, tapping a car, an SOS, a stop — so the
    // "in Convoy or in Google Maps?" question is asked identically
    // everywhere instead of each screen deciding for itself.
    var navTarget by remember { mutableStateOf<NavTarget?>(null) }

    // Bumped to ask the map to frame the whole route.
    var fitRouteKey by remember { mutableStateOf(0) }

    // Pause / End / Leave. A sheet rather than a hidden section of the
    // roster, reachable from the header and from the roster both.
    var showTripControls by remember { mutableStateOf(false) }

    // Navigation view — tilted, following, turning with you. Entered
    // deliberately from the directions sheet and left deliberately, because
    // it takes over the whole screen and the camera with it.
    var navigating by remember { mutableStateOf(false) }

    // ── Attaching a photo to a stop ─────────────────────────────
    // Two routes in, because both are real: the flat tyre is in front of
    // you and wants the camera, the wrong turn you took ten minutes ago is
    // already in the gallery.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(markerViewModel::attachPhoto) }

    // Where the camera app will write. Held across the launch because the
    // TakePicture contract reports only success or failure — the URI we
    // handed it is the only way back to the bytes.
    var cameraTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val target = cameraTarget
        if (saved && target != null) markerViewModel.attachPhoto(target)
        cameraTarget = null
    }

    val onPickPhoto: () -> Unit = {
        pickPhoto.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    val onTakePhoto: () -> Unit = {
        val target = newCameraTarget(context)
        if (target != null) {
            cameraTarget = target
            takePhoto.launch(target)
        }
    }

    // A critical alert takes over everything else on screen.
    val critical = alertViewModel.criticalAlert
    if (critical != null) {
        val raisedBy = viewModel.vehicles
            .firstOrNull { it.id == critical.vehicleId }?.label
        SosOverlay(
            alert = critical,
            raisedByLabel = raisedBy,
            distanceText = null,
            canClear = true,
            isSaving = alertViewModel.isSaving,
            onNavigate = {
                critical.location?.latLng()?.let { (alertLat, alertLng) ->
                    navTarget = NavTarget(
                        lat = alertLat,
                        lng = alertLng,
                        label = raisedBy ?: "Emergency",
                        subtitle = critical.message,
                        vehicleId = critical.vehicleId,
                        urgent = true,
                    )
                }
            },
            onCall = null,
            onAcknowledge = { alertViewModel.acknowledge(critical) },
            onClear = { alertViewModel.resolve(critical, "cleared from the app") },
        )
        return
    }

    // Chase Mode. Recomputed from live positions on every refresh, which is
    // why it never goes stale the way a route to a moving car does.
    val chased = chaseVehicleId?.let { id -> viewModel.vehicles.firstOrNull { it.id == id } }
    val me = viewModel.vehicles.firstOrNull { it.id == viewModel.myVehicleId }

    if (chased != null) {
        val theirPos = chased.position
        val myPos = me?.position

        // Resolved once into locals, so the coordinates used below are the
        // same ones that were checked — not a second read of a computed
        // property that the compiler had to be told to trust with `!!`.
        val theirCoords = theirPos?.latLng()
        val myCoords = myPos?.latLng()

        if (theirCoords == null) {
            // Chasing someone whose position we have lost tells us nothing,
            // and a frozen arrow would be worse than no arrow.
            chaseVehicleId = null
        } else if (myCoords == null) {
            chaseVehicleId = null
        } else {
            val (theirLat, theirLng) = theirCoords
            val (myLat, myLng) = myCoords

            val bearing = Geo.bearingDegrees(myLat, myLng, theirLat, theirLng)
            val metres = Geo.distanceMeters(myLat, myLng, theirLat, theirLng)
            // Speed and heading live on lastKnown; position is only the
            // coordinate pair.
            val mine = me.lastKnown
            val theirs = chased.lastKnown
            val stopped = chased.hasActiveStop || (theirs?.speedKmh ?: 0.0) < 3.0

            ChaseOverlay(
                targetName = chased.label,
                bearingDeg = bearing,
                myHeadingDeg = mine?.heading?.toFloat(),
                distanceText = Formatters.distance(metres),
                closingKmh = Geo.closingSpeedKmh(
                    mySpeedKmh = mine?.speedKmh,
                    myHeadingDeg = mine?.heading,
                    targetSpeedKmh = theirs?.speedKmh,
                    targetHeadingDeg = theirs?.heading,
                    bearingToTargetDeg = bearing.toDouble(),
                ),
                targetStopped = stopped,
                onNavigateInstead = {
                    Navigation.navigateTo(context, theirLat, theirLng, chased.label)
                },
                onClose = { chaseVehicleId = null },
            )
            return
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        ConvoyMapView(
            vehicles = viewModel.vehicles,
            destinationLat = trip.destination?.lat,
            destinationLng = trip.destination?.lng,
            destinationLabel = trip.destinationAddress ?: trip.name,
            // Other people's active stops. Your own is already the sheet.
            // Every stop, including your own. Seeing your own marker next
            // to your dot is how you confirm the group can actually see it —
            // without it, marking a stop feels like it went nowhere.
            stops = viewModel.vehicles
                .filter { it.hasActiveStop }
                .mapNotNull { v ->
                    val p = v.position ?: return@mapNotNull null
                    val lat = p.lat ?: return@mapNotNull null
                    val lng = p.lng ?: return@mapNotNull null
                    com.convoy.mobile.customControls.MapStop(
                        lat = lat,
                        lng = lng,
                        icon = v.currentStatus?.icon,
                        label = if (v.id == viewModel.myVehicleId) {
                            "You — ${v.currentStatus?.label.orEmpty()}"
                        } else {
                            "${v.label} — ${v.currentStatus?.label.orEmpty()}"
                        },
                        critical = v.currentStatus?.waitingForGroup == true,
                        // Rendered as a badge on the vehicle dot, not as a
                        // separate pin the dot would cover.
                        standalone = false,
                    )
                },
            routePoints = (viewModel.myRoute ?: trip.routeCache)?.points.orEmpty(),
            fitRouteKey = fitRouteKey,
            navigationMode = navigating,
            myVehicleId = viewModel.myVehicleId,
            // Tapping a car on the map asks the same question as tapping it
            // in the roster — one answer, wherever you tap it.
            onVehicleTapped = { vehicle ->
                val coords = vehicle.position?.latLng()
                if (coords != null && vehicle.id != viewModel.myVehicleId) {
                    navTarget = NavTarget(
                        lat = coords.first,
                        lng = coords.second,
                        label = vehicle.label,
                        subtitle = vehicle.currentStatus?.label ?: "In the convoy",
                        vehicleId = vehicle.id,
                        isMoving = !vehicle.hasActiveStop &&
                            (vehicle.lastKnown?.speedKmh ?: 0.0) >= 3.0,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // While navigating the screen belongs to the road: the roster, the
        // header pills and the action tiles are gone rather than dimmed.
        //
        // Written as if/else, NOT as an early `return@Box`. Returning out of
        // a composable scope corrupts Compose's group bookkeeping and
        // crashes the next recomposition — the same trap that already bit
        // the marker picker.
        if (navigating) {
            NavigationBar(
                destinationLabel = trip.destinationAddress ?: trip.name,
                distanceText = null,
                etaText = null,
                trafficAware = false,
                modifier = Modifier.align(Alignment.TopCenter),
                onMarkStop = markerViewModel::openPicker,
                onOpenChat = { onChat() },
                onExit = { navigating = false },
            )
            NavigationFooter(
                distanceText = viewModel.myRoute?.distanceM
                    ?.let { Formatters.distance(it.toDouble()) },
                etaText = viewModel.myRoute?.durationS?.let { Formatters.duration(it) },
                trafficAware = viewModel.myRoute?.isTrafficAware == true,
                modifier = Modifier.align(Alignment.BottomCenter),
                onExit = { navigating = false },
            )
        } else {

        // ── Floating header ─────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloatingBar {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trip.name,
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusDot(if (paused) colors.amber else colors.route)
                        Text(
                            text = if (paused) "Paused" else "Sharing your location",
                            color = colors.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                // Right next to the state it changes. Pause and End used to
                // be reachable only through a line of dim text below the
                // roster, which on a six-car trip was off the bottom of the
                // sheet entirely.
                TripControlsButton(paused = paused) { showTripControls = true }
                Spacer(Modifier.width(7.dp))
                CircleIconButton("💬", onChat)
                Spacer(Modifier.width(7.dp))
                CircleIconButton("👥", onOpenLobby)
                Spacer(Modifier.width(7.dp))
                CircleIconButton("⚙", onSettings)
            }

            // The one line that answers "are we still together?"
            FloatingBar {
                StatusDot(
                    if (viewModel.vehicles.any { it.freshness == Freshness.LOST }) colors.amber
                    else colors.route
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = viewModel.convoySummary,
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!viewModel.socketConnected) Chip("reconnecting", ChipTone.WARN)
            }

            alertViewModel.banners.take(2).forEach { alert ->
                AlertBanner(
                    alert = alert,
                    onDismiss = { alertViewModel.dismiss(alert) },
                    // Keyed on the same pair the action needs. Keyed on
                    // `lat` alone, an alert carrying only a latitude showed
                    // a "Show me" button that did nothing when tapped.
                    actionLabel = alert.location?.latLng()?.let { "Show me" },
                    // Built from the resolved pair, so the action only
                    // exists when there is actually somewhere to show.
                    onAction = alert.location?.latLng()?.let { (alertLat, alertLng) ->
                        { Navigation.showOnMap(context, alertLat, alertLng, alert.type) }
                    },
                )
            }
        }

        // Always reachable, never in the way of the roster.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp, bottom = 120.dp),
        ) {
            SosButton(
                countdown = alertViewModel.sosCountdown,
                onStart = {
                    // A provider, not a pair of values: this is invoked when
                    // the countdown ends, so it reads the position we have
                    // THEN rather than the one we had ten seconds earlier.
                    //
                    // Our own GPS first. An emergency raised at the position
                    // the server last heard about can be a long way back
                    // down the road from where the car actually is — which,
                    // for the one alert that has to be right, is the one
                    // place it must not be.
                    alertViewModel.startSosCountdown {
                        viewModel.myFix?.let { fix -> fix.lat to fix.lng }
                            ?: viewModel.vehicles
                                .firstOrNull { it.id == viewModel.myVehicleId }
                                ?.position?.latLng()
                    }
                },
                onCancel = alertViewModel::cancelSosCountdown,
            )
        }

        if (viewModel.vehicles.none { it.position != null }) {
            // Re-read on every refresh, because the answer changes the moment
            // the user grants permission or flips location back on.
            val readiness = remember(viewModel.vehicles.size, viewModel.socketConnected) {
                LocationReadiness.of(context)
            }

            FloatingBar(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 44.dp),
            ) {
                Text(readiness.glyph, fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = readiness.message,
                    color = if (readiness == LocationReadiness.WAITING) colors.muted else colors.amber,
                    fontSize = 13.sp,
                )
            }
        }

        } // end of the not-navigating branch

        // Rendered OUTSIDE the branch above. Inside it, the picker simply
        // did not exist while navigating — the button fired and nothing
        // appeared, because the sheet was not in the composition at all.
        // Marking a stop is most needed exactly when you are driving.
        if (markerViewModel.pickerOpen) {
            // Where the stop actually happened. Read from our OWN fix first
            // — the same reason the dot does — so a stop marked at speed
            // lands where the car is, not where the server last heard it was.
            val stopLat = viewModel.myFix?.lat ?: myVehicle?.position?.lat
            val stopLng = viewModel.myFix?.lng ?: myVehicle?.position?.lng

            MarkerPickerSheet(
                favourites = markerViewModel.favourites,
                trouble = markerViewModel.trouble,
                others = markerViewModel.others,
                pendingNoteFor = markerViewModel.pendingNoteFor,
                note = markerViewModel.note,
                isSaving = markerViewModel.isSaving,
                errorMessage = markerViewModel.errorMessage,
                creatingCustom = markerViewModel.creatingCustom,
                customLabel = markerViewModel.customLabel,
                customIcon = markerViewModel.customIcon,
                pendingPhoto = markerViewModel.pendingPhoto,
                isUploadingPhoto = markerViewModel.isUploadingPhoto,
                onPickPhoto = onPickPhoto,
                onTakePhoto = onTakePhoto,
                onRemovePhoto = markerViewModel::removePhoto,
                onStartCustom = markerViewModel::startCustom,
                onCustomLabelChanged = markerViewModel::onCustomLabelChanged,
                onCustomIconChanged = markerViewModel::onCustomIconChanged,
                onSaveCustom = markerViewModel::saveCustom,
                onCancelCustom = markerViewModel::cancelCustom,
                onNoteChanged = markerViewModel::onNoteChanged,
                onChoose = { option ->
                    markerViewModel.choose(context, option, stopLat, stopLng)
                },
                onAddDetail = markerViewModel::addDetail,
                onConfirmNote = {
                    markerViewModel.confirmNote(context, stopLat, stopLng)
                },
                onCancelDetail = markerViewModel::cancelDetail,
                onDismiss = markerViewModel::closePicker,
            )
        } else {
            ConvoySheet(
                viewModel = viewModel,
                markerViewModel = markerViewModel,
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenTripControls = { showTripControls = true },
                onNavigateToDestination = {
                    trip.destination?.latLng()?.let { (destLat, destLng) ->
                        navTarget = NavTarget(
                            lat = destLat,
                            lng = destLng,
                            label = trip.destinationAddress ?: trip.name,
                            subtitle = "Where the trip is headed",
                        )
                    }
                },
                // Tapping a car does NOT immediately throw you out of the
                // app. A stopped friend is a fixed point, so a maps app
                // handles that properly — but a moving one is exactly the
                // case turn-by-turn cannot serve, and Chase Mode can.
                onNavigateToVehicle = { vehicle ->
                    vehicle.position?.latLng()?.let { (carLat, carLng) ->
                        navTarget = NavTarget(
                            lat = carLat,
                            lng = carLng,
                            label = vehicle.label,
                            subtitle = vehicle.currentStatus?.label ?: "In the convoy",
                            vehicleId = vehicle.id,
                            isMoving = !vehicle.hasActiveStop &&
                                (vehicle.lastKnown?.speedKmh ?: 0.0) >= 3.0,
                        )
                    }
                },
            )
        }

        // Above everything, including navigation. A failure the user cannot
        // see is a failure reported to nobody — and two whole ViewModels
        // were reporting into the void here.
        //
        // MapViewModel.errorMessage covers End trip, Pause and Leave: the
        // host tapped "End trip", the server refused, and the app said
        // nothing at all. AlertViewModel.errorMessage was worse — a failed
        // SOS looked exactly like a sent one, which is the single most
        // dangerous silence this app could have.
        TopSnackbar(
            message = viewModel.routeError
                ?: alertViewModel.errorMessage
                ?: viewModel.errorMessage
                ?: markerViewModel.errorMessage,
            modifier = Modifier.align(Alignment.TopCenter),
            onDismiss = {
                viewModel.clearRouteError()
                alertViewModel.dismissError()
                viewModel.dismissError()
                markerViewModel.dismissError()
            },
        )

        // Scrim and sheet animate separately: the backdrop fades, the sheet
        // slides. Popping a modal into existence with no transition is the
        // single thing that most makes an app feel unfinished, and it also
        // costs the user the sense of where the sheet came from.
        AnimatedVisibility(
            visible = showTripControls,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(160)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickableOnce(haptic = false, pressScale = 1f) { showTripControls = false }
            )
        }
        AnimatedVisibility(
            visible = showTripControls,
            // Comes up from the edge it lives on, and leaves the same way.
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) + fadeIn(animationSpec = tween(120)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(180),
            ) + fadeOut(animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
                TripControlsSheet(
                    tripName = trip.name,
                    paused = paused,
                    amHost = viewModel.amHost,
                    isBusy = viewModel.isLoading,
                    onPauseToggle = {
                        viewModel.pauseTrip()
                        showTripControls = false
                    },
                    // Left open on End: the trip finishing is what closes
                    // this screen, and closing the sheet first would show
                    // the map for a moment as though nothing had happened.
                    onEndTrip = viewModel::endTrip,
                    onLeaveTrip = { viewModel.leaveTrip { showTripControls = false } },
                    onDismiss = { showTripControls = false },
                )
        }

        // The sheet has to keep rendering its old contents while it slides
        // away, but navTarget is already null by then. Holding the last
        // non-null target is what lets it animate out instead of vanishing.
        val shownTarget = remember { mutableStateOf<NavTarget?>(null) }
        navTarget?.let { shownTarget.value = it }

        AnimatedVisibility(
            visible = navTarget != null,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(160)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickableOnce(haptic = false, pressScale = 1f) { navTarget = null }
            )
        }

        shownTarget.value?.let { target ->
            val myCoords = viewModel.vehicles
                .firstOrNull { it.id == viewModel.myVehicleId }?.position?.latLng()
            val metres = myCoords?.let { (myLat, myLng) ->
                Geo.distanceMeters(myLat, myLng, target.lat, target.lng)
            }

            // What they sent with their stop, if anything. This is why a
            // photo is worth attaching at all — it lands in front of the
            // person deciding whether to turn round.
            val theirStop = markerViewModel.stopFor(target.vehicleId)

            AnimatedVisibility(
                visible = navTarget != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) + fadeIn(animationSpec = tween(120)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(180),
                ) + fadeOut(animationSpec = tween(140)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                NavigationChoiceSheet(
                    target = target,
                    distanceText = metres?.let { Formatters.distance(it) },
                    // Only offered for a vehicle, and never for your own.
                    canOfferHelp = target.vehicleId != null &&
                        target.vehicleId != viewModel.myVehicleId,
                    isBusy = viewModel.isRouting,
                    stopPhotoUrl = theirStop?.media?.firstOrNull { it.isImage }?.url,
                    stopNote = theirStop?.note,
                    onUseInApp = {
                        viewModel.requestMyRoute(target.lat, target.lng)
                        navigating = true
                        navTarget = null
                    },
                    onUseGoogleMaps = {
                        Navigation.navigateTo(context, target.lat, target.lng, target.label)
                        navTarget = null
                    },
                    onTellThemImComing = {
                        target.vehicleId?.let { viewModel.tellThemImComing(it) }
                        // Routed as well as announced: saying you are coming
                        // and then not being shown the way would be a
                        // strange place to leave someone.
                        viewModel.requestMyRoute(target.lat, target.lng)
                        navigating = true
                        navTarget = null
                    },
                    onDismiss = { navTarget = null },
                )
            }
        }

        // A standing banner while you are on your way to someone, with the
        // way out. A helper whose friend has already fixed the puncture must
        // be able to say so — otherwise the group is left believing help is
        // still coming.
        viewModel.helpingVehicleId?.let { id ->
            val who = viewModel.vehicles.firstOrNull { it.id == id }?.label ?: "them"
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 120.dp, start = 14.dp, end = 14.dp)
                    .fillMaxWidth()
                    .background(colors.route.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "On your way to $who",
                    color = if (colors.isDark) Color(0xFF04221E) else Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Not going",
                    color = if (colors.isDark) Color(0xFF04221E) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickableOnce { viewModel.stopHelping() },
                )
            }
        }

    }
}

/**
 * Somewhere for the camera app to put the photo.
 *
 * It has to be a content:// URI from our FileProvider. Handing another app a
 * file:// path is what FileUriExposedException exists to stop, and the
 * camera cannot write into our cache directory without the temporary grant
 * a provider URI carries.
 */
private fun newCameraTarget(context: android.content.Context): android.net.Uri? = try {
    val dir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
    val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.jpg")
    androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
} catch (e: Exception) {
    android.util.Log.e("MainActivity", "No camera target: ${e.message}")
    null
}

/** The translucent pill the header and summary float in, over the map. */
@Composable
private fun FloatingBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ConvoyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(16.dp), clip = false)
            .background(colors.surface.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CircleIconButton(glyph: String, onClick: () -> Unit) {
    val colors = ConvoyTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(colors.surface2, CircleShape)
            .clickableOnce(haptic = false, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 15.sp)
    }
}

/**
 * The roster, the actions, and the trip controls.
 *
 * Collapsed it shows the convoy and the two things a driver actually does:
 * mark a stop, and get directions. The destructive controls live behind a
 * deliberate pull-up, so ending everyone's trip is never one stray tap away
 * in a moving car.
 */
@Composable
private fun ConvoySheet(
    viewModel: MapViewModel,
    markerViewModel: MarkerViewModel,
    modifier: Modifier = Modifier,
    onOpenTripControls: () -> Unit,
    onNavigateToDestination: () -> Unit,
    onNavigateToVehicle: (com.convoy.mobile.dataModel.vehicle.Vehicle) -> Unit,
) {
    val colors = ConvoyTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val trip = viewModel.trip
    val activeStop = markerViewModel.activeStop
    val hasDestination = trip?.destination?.lat != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(24.dp, sheetShape, clip = false)
            .background(colors.surface, sheetShape)
            // The sheet's height changes constantly — a car joins, someone
            // marks a stop and the roster is replaced by the stop card. Left
            // unanimated the whole sheet jumps, and on a screen the user is
            // glancing at from the road a jump reads as something breaking.
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            )
            .navigationBarsPadding()
            .padding(bottom = 14.dp),
    ) {
        // The grab handle is now decoration only. It used to toggle a
        // hidden section, which meant the trip could be paused via a
        // control whose entire affordance was a 4dp grey bar.
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

        // An active stop takes over the sheet: while you are stopped, the
        // only things that matter are wait-vs-go-ahead and resuming.
        //
        // Written as if/else rather than an early `return@Column`. Returning
        // out of a composable scope corrupts Compose's group bookkeeping and
        // crashes the next recomposition inside Stack.pop.
        if (activeStop != null) {
            ActiveStopCard(
                stop = activeStop,
                elapsed = Formatters.duration(activeStop.durationS ?: 0L),
                isSaving = markerViewModel.isSaving,
                onToggleWaiting = markerViewModel::toggleWaiting,
                onResume = markerViewModel::clearStop,
            )
            Spacer(Modifier.height(14.dp))
        } else {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActionTile(
                glyph = "\u270B",
                label = "Mark a stop",
                highlighted = true,
                modifier = Modifier.weight(1f),
                onClick = markerViewModel::openPicker,
            )
            ActionTile(
                glyph = "\u27A4",
                label = if (hasDestination) "Directions" else "No destination",
                enabled = hasDestination,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToDestination,
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "THE CONVOY",
                style = SectionLabelStyle,
                color = colors.dim,
                modifier = Modifier.weight(1f),
            )
            Chip(viewModel.vehicles.size.toString() + " cars")
        }

        Spacer(Modifier.height(6.dp))

        viewModel.vehicles.forEachIndexed { index, vehicle ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 66.dp)
                        .height(1.dp)
                        .background(colors.surface2)
                )
            }
            val isMe = vehicle.id == viewModel.myVehicleId
            VehicleRosterRow(
                vehicle = vehicle,
                subtitle = viewModel.participants
                    .filter { it.vehicleId == vehicle.id }
                    .joinToString(", ") { it.displayName }
                    .ifBlank { null },
                isYou = isMe,
                distanceText = vehicle.currentStatus?.label,
                etaText = if (vehicle.hasActiveStop) "stopped" else null,
                // Tapping someone else's car offers directions to it — the
                // recovery path when a friend stops or takes a wrong turn.
                onClick = if (!isMe && vehicle.position != null) {
                    { onNavigateToVehicle(vehicle) }
                } else null,
            )
        }

        } // end of the no-active-stop branch

        // The second way in, for anyone whose eyes are already on the
        // roster. A real row with a label and a chevron, not the line of
        // dim grey text that used to sit here saying "Pull up for trip
        // controls" — which described a drag gesture that was never
        // implemented, and sat below a list long enough to push it off the
        // bottom of the sheet on a trip with more than about four cars.
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(colors.surface2, RoundedCornerShape(14.dp))
                .clickableOnce(haptic = false, onClick = onOpenTripControls)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (viewModel.amHost) "Pause or end this trip" else "Leave this trip",
                color = colors.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(text = "›", color = colors.muted, fontSize = 17.sp)
        }
    }
}

/** A large, glanceable action — sized to be hit without looking. */
@Composable
private fun ActionTile(
    glyph: String,
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    val background = when {
        !enabled -> colors.surface2.copy(alpha = 0.5f)
        highlighted -> colors.route.copy(alpha = 0.15f)
        else -> colors.surface2
    }
    val tint = when {
        !enabled -> colors.dim
        highlighted -> colors.route
        else -> colors.text
    }

    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(16.dp))
            .clickableOnce(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = glyph, fontSize = 18.sp)
        Text(
            text = label,
            color = tint,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
