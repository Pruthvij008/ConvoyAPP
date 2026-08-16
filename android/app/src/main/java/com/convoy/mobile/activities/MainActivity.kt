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
import com.convoy.mobile.customControls.DangerButton
import com.convoy.mobile.customControls.MarkerPickerSheet
import com.convoy.mobile.customControls.NavigationBar
import com.convoy.mobile.customControls.NavigationFooter
import com.convoy.mobile.customControls.NavTarget
import com.convoy.mobile.customControls.NavigationChoiceSheet
import com.convoy.mobile.customControls.RouteSheet
import com.convoy.mobile.customControls.GhostButton
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.StatusDot
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
        val shouldTrack = trip?.isLive == true && hasLocationPermission()

        if (shouldTrack) {
            LocationTrackingService.start(this, trip!!.id, prefs.activeVehicleId)
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
        markerViewModel.bind(trip.id, trip.markerSet, viewModel.myVehicleId)
    }
    LaunchedEffect(trip.id) { alertViewModel.bind(trip.id) }

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

    // Navigation view — tilted, following, turning with you. Entered
    // deliberately from the directions sheet and left deliberately, because
    // it takes over the whole screen and the camera with it.
    var navigating by remember { mutableStateOf(false) }

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
                val loc = critical.location
                if (loc?.lat != null && loc.lng != null) {
                    navTarget = NavTarget(
                        lat = loc.lat!!,
                        lng = loc.lng!!,
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

        if (theirPos?.lat == null || theirPos.lng == null) {
            // Chasing someone whose position we have lost tells us nothing,
            // and a frozen arrow would be worse than no arrow.
            chaseVehicleId = null
        } else if (myPos?.lat == null || myPos.lng == null) {
            chaseVehicleId = null
        } else {
            val bearing = Geo.bearingDegrees(
                myPos.lat!!, myPos.lng!!, theirPos.lat!!, theirPos.lng!!,
            )
            val metres = Geo.distanceMeters(
                myPos.lat!!, myPos.lng!!, theirPos.lat!!, theirPos.lng!!,
            )
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
                    Navigation.navigateTo(
                        context, theirPos.lat!!, theirPos.lng!!, chased.label,
                    )
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
                val p = vehicle.position
                if (p?.lat != null && p.lng != null && vehicle.id != viewModel.myVehicleId) {
                    navTarget = NavTarget(
                        lat = p.lat!!,
                        lng = p.lng!!,
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
                CircleIconButton("💬", onChat)
                Spacer(Modifier.width(8.dp))
                CircleIconButton("👥", onOpenLobby)
                Spacer(Modifier.width(8.dp))
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
                    actionLabel = alert.location?.lat?.let { "Show me" },
                    onAction = alert.location?.lat?.let {
                        {
                            val loc = alert.location
                            if (loc?.lat != null && loc.lng != null) {
                                Navigation.showOnMap(context, loc.lat!!, loc.lng!!, alert.type)
                            }
                        }
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
                    alertViewModel.startSosCountdown(
                        myVehicle?.position?.lat,
                        myVehicle?.position?.lng,
                    )
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

        if (markerViewModel.pickerOpen) {
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
                onStartCustom = markerViewModel::startCustom,
                onCustomLabelChanged = markerViewModel::onCustomLabelChanged,
                onCustomIconChanged = markerViewModel::onCustomIconChanged,
                onSaveCustom = markerViewModel::saveCustom,
                onCancelCustom = markerViewModel::cancelCustom,
                onNoteChanged = markerViewModel::onNoteChanged,
                onChoose = { option ->
                    markerViewModel.choose(
                        option,
                        myVehicle?.position?.lat,
                        myVehicle?.position?.lng,
                    )
                },
                onConfirmNote = {
                    markerViewModel.confirmNote(
                        myVehicle?.position?.lat,
                        myVehicle?.position?.lng,
                    )
                },
                onDismiss = markerViewModel::closePicker,
            )
        } else {
            ConvoySheet(
                viewModel = viewModel,
                markerViewModel = markerViewModel,
                paused = paused,
                modifier = Modifier.align(Alignment.BottomCenter),
                onNavigateToDestination = {
                    val d = trip.destination
                    if (d?.lat != null && d.lng != null) {
                        navTarget = NavTarget(
                            lat = d.lat!!,
                            lng = d.lng!!,
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
                    val p = vehicle.position
                    if (p?.lat != null && p.lng != null) {
                        navTarget = NavTarget(
                            lat = p.lat!!,
                            lng = p.lng!!,
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
        } // end of the not-navigating branch

        navTarget?.let { target ->
            // Scrim first, so the sheet reads as modal and a stray tap on
            // the map behind it cannot fire something else.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickableOnce(haptic = false) { navTarget = null }
            )

            val myPos = viewModel.vehicles
                .firstOrNull { it.id == viewModel.myVehicleId }?.position
            val metres = if (myPos?.lat != null && myPos.lng != null) {
                Geo.distanceMeters(myPos.lat!!, myPos.lng!!, target.lat, target.lng)
            } else {
                null
            }

            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                NavigationChoiceSheet(
                    target = target,
                    distanceText = metres?.let { Formatters.distance(it) },
                    // Only offered for a vehicle, and never for your own.
                    canOfferHelp = target.vehicleId != null &&
                        target.vehicleId != viewModel.myVehicleId,
                    isBusy = viewModel.isRouting,
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
    paused: Boolean,
    modifier: Modifier = Modifier,
    onNavigateToDestination: () -> Unit,
    onNavigateToVehicle: (com.convoy.mobile.dataModel.vehicle.Vehicle) -> Unit,
) {
    val colors = ConvoyTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val trip = viewModel.trip
    val activeStop = markerViewModel.activeStop
    val hasDestination = trip?.destination?.lat != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(24.dp, sheetShape, clip = false)
            .background(colors.surface, sheetShape)
            .navigationBarsPadding()
            .padding(bottom = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickableOnce(haptic = false) { expanded = !expanded }
                .padding(vertical = 12.dp),
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

        if (expanded) {
            Spacer(Modifier.height(14.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                if (viewModel.amHost) {
                    GhostButton(
                        text = if (paused) "Resume sharing" else "Pause sharing",
                        onClick = viewModel::pauseTrip,
                    )
                    Spacer(Modifier.height(10.dp))
                    DangerButton(text = "End trip", onClick = viewModel::endTrip)
                    Text(
                        text = "Ending stops sharing for everyone, not just you.",
                        color = colors.dim,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                } else {
                    GhostButton(text = "Leave trip", onClick = { viewModel.leaveTrip {} })
                }
            }
        } else {
            Text(
                text = "Pull up for trip controls",
                color = colors.dim,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableOnce(haptic = false) { expanded = true }
                    .padding(top = 12.dp),
            )
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
