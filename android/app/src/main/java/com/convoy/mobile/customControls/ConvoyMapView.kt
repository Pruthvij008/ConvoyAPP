package com.convoy.mobile.customControls

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.convoy.mobile.dataModel.vehicle.Freshness
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.Constants
import com.convoy.mobile.utility.Formatters
import com.convoy.mobile.utility.Geo
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * The live map.
 *
 * MapLibre with our own style JSON — no API key, no account, no per-request
 * billing. The day and night styles are two files in res/raw rather than
 * two sets of artwork: the night one darkens the same tiles through
 * MapLibre's raster paint properties.
 *
 * The vehicle dots are drawn as markers rather than a symbol layer because
 * a convoy is a handful of points, not thousands, and markers let each dot
 * carry its own colour and staleness without a data-driven style.
 */
@Composable
fun ConvoyMapView(
    vehicles: List<Vehicle>,
    modifier: Modifier = Modifier,
    followVehicleId: String? = null,
    destinationLat: Double? = null,
    destinationLng: Double? = null,
    destinationLabel: String? = null,
    /** Other people's active stops, as [lat, lng, icon, label]. */
    stops: List<MapStop> = emptyList(),
    /**
     * The trip's route as [lat, lng] pairs, computed once by the server.
     *
     * Drawn so the group can see the path without leaving Convoy — the
     * whole point being that you should not have to jump to another app to
     * know where you are going.
     */
    routePoints: List<Pair<Double, Double>> = emptyList(),
    /**
     * Incremented when the caller wants the camera to frame the WHOLE route
     * rather than the convoy — what "Show the route" does.
     *
     * A counter rather than a boolean, so asking twice works twice.
     */
    fitRouteKey: Int = 0,
    /**
     * Navigation view: tilted, zoomed in, following you and turning with
     * you — what every driver already recognises from a maps app.
     *
     * Deliberately a MODE rather than the default. A tilted, rotating map
     * is right while you are driving a route and wrong while you are
     * looking at how the convoy is spread out.
     */
    navigationMode: Boolean = false,
    /** Whose dot the navigation camera follows. */
    myVehicleId: String? = null,
    onVehicleTapped: (Vehicle) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = ConvoyTheme.colors
    val isDark = colors.isDark

    // MapLibre must be initialised once before any MapView is created.
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }
    val mapRef = remember { arrayOfNulls<MapLibreMap>(1) }
    // Which theme's style is currently loaded. setStyle is expensive and
    // reloads every tile, so it must happen when the THEME changes — not on
    // every recomposition, which leaves half the tiles rendered mid-reload.
    val loadedStyleIsDark = remember { arrayOfNulls<Boolean>(1) }
    val appliedFitRoute = remember { intArrayOf(0) }
    // Whether the camera has been positioned at least once.
    //
    // Auto-framing must happen on first load and then STOP. Re-framing on
    // every position update fights the user for control of the camera: pan
    // away to look at the road ahead and it snaps back within seconds, and
    // any deliberate camera move (like framing the route) is undone by the
    // next refresh. Follow mode is the one case that should keep tracking.
    val hasAutoFramed = remember { booleanArrayOf(false) }
    // Which vehicle each drawn marker belongs to. Rebuilt on every draw,
    // because MapLibre assigns fresh marker ids after a clear().
    val markerOwners = remember { mutableMapOf<Long, String>() }

    // MapView is a plain Android view with its own lifecycle, and it leaks
    // badly if those callbacks are skipped.
    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.getMapAsync { map ->
                mapRef[0] = map
                val palette = colors.vehicles.map { it.toArgb() }

                // Returning true suppresses MapLibre's built-in info window,
                // which is an unstyled white box we have no control over.
                // The tap is handed to the app so it can show a real sheet.
                map.setOnMarkerClickListener { marker ->
                    val vehicleId = markerOwners[marker.id]
                    val vehicle = vehicleId?.let { id -> vehicles.firstOrNull { it.id == id } }
                    if (vehicle != null) {
                        onVehicleTapped(vehicle)
                        true
                    } else {
                        // Not a vehicle (the destination flag, a place pin) —
                        // let the default behaviour handle it.
                        false
                    }
                }

                if (loadedStyleIsDark[0] == isDark && map.style?.isFullyLoaded == true) {
                    // Style is already right — just move the dots. This is the
                    // path taken on every position update.
                    drawAll(
                        view.context, map, vehicles, palette, isDark,
                        destinationLat, destinationLng, destinationLabel, stops,
                        routePoints, colors.route.toArgb(), markerOwners,
                        navigationMode,
                    )

                    // Navigation owns the camera outright: it re-aims on
                    // every position update so the view keeps up with the
                    // car, which is the entire point of the mode.
                    if (navigationMode) {
                        followForNavigation(map, vehicles, myVehicleId)
                        return@getMapAsync
                    }

                    // An explicit "show me the route" always wins.
                    if (appliedFitRoute[0] != fitRouteKey) {
                        appliedFitRoute[0] = fitRouteKey
                        if (fitRouteKey > 0 && routePoints.size >= 2) {
                            frameRoute(map, routePoints)
                            hasAutoFramed[0] = true
                            return@getMapAsync
                        }
                    }
                    if (!hasAutoFramed[0] || followVehicleId != null) {
                        if (frameConvoy(map, vehicles, followVehicleId, destinationLat, destinationLng)) {
                            hasAutoFramed[0] = true
                        }
                    }
                    return@getMapAsync
                }

                val styleUrl =
                    if (isDark) Constants.MAP_STYLE_NIGHT else Constants.MAP_STYLE_DAY

                map.setStyle(Style.Builder().fromUri(styleUrl)) {
                    loadedStyleIsDark[0] = isDark
                    map.uiSettings.apply {
                        // Rotate and tilt belong to the user. They were off
                        // on the theory that a spinning map is disorienting
                        // at speed — but turning the map to match the road
                        // ahead is a perfectly sensible thing for a
                        // passenger to do, and removing the gesture just
                        // makes the map feel broken.
                        isRotateGesturesEnabled = true
                        isTiltGesturesEnabled = true

                        // The compass is what makes rotation safe to offer:
                        // once the map has been turned there must be an
                        // obvious way back to north. MapLibre shows it only
                        // while the map is actually rotated.
                        isCompassEnabled = true
                        setCompassMargins(0, 340, 44, 0)

                        isAttributionEnabled = true        // OSM requires attribution
                        isLogoEnabled = false
                    }

                    drawAll(
                        view.context, map, vehicles, palette, isDark,
                        destinationLat, destinationLng, destinationLabel, stops,
                        routePoints, colors.route.toArgb(), markerOwners,
                        navigationMode,
                    )
                    // First frame after a style load: position the camera
                    // regardless, since the map has just been rebuilt.
                    if (frameConvoy(map, vehicles, followVehicleId, destinationLat, destinationLng)) {
                        hasAutoFramed[0] = true
                    }
                }
            }
        },
    )
}

/** A stop someone else has marked, drawn on the map. */
data class MapStop(
    val lat: Double,
    val lng: Double,
    val icon: String?,
    val label: String,
    val critical: Boolean = false,
    /** False for a vehicle's own status, which rides on the dot instead. */
    val standalone: Boolean = true,
)

/**
 * Everything on the map, in one pass.
 *
 * A single `clear()` followed by every layer, because clearing inside each
 * draw function would wipe whatever the previous one just added.
 */
private fun drawAll(
    context: android.content.Context,
    map: MapLibreMap,
    vehicles: List<Vehicle>,
    palette: List<Int>,
    isDark: Boolean,
    destinationLat: Double?,
    destinationLng: Double?,
    destinationLabel: String?,
    stops: List<MapStop>,
    routePoints: List<Pair<Double, Double>>,
    routeColor: Int,
    markerOwners: MutableMap<Long, String>,
    navigationMode: Boolean = false,
) {
    map.clear()
    markerOwners.clear()
    val icons = IconFactory.getInstance(context)

    // The route goes down first so every pin and every vehicle dot draws on
    // top of it. A line painted over a car would hide the one thing on this
    // screen the user is actually looking for.
    if (routePoints.size >= 2) {
        val line = routePoints.map { LatLng(it.first, it.second) }

        // Two passes: a dark casing underneath, then the route on top. This
        // is how every serious map draws a route, and the reason is
        // practical rather than decorative — a single flat line disappears
        // over roads of a similar colour, which is exactly where you most
        // need to see it.
        // Fatter while navigating. At a driving zoom the line is what you
        // steer by, and a width that reads fine on an overview is far too
        // thin to follow at a glance.
        val casingWidth = if (navigationMode) 15f else 9f
        val lineWidth = if (navigationMode) 10f else 5.5f

        map.addPolyline(
            PolylineOptions()
                .addAll(line)
                .color(if (isDark) 0xFF04211D.toInt() else 0xFF0B3B33.toInt())
                .alpha(0.6f)
                .width(casingWidth)
        )
        map.addPolyline(
            PolylineOptions()
                .addAll(line)
                .color(routeColor)
                .alpha(0.97f)
                .width(lineWidth)
        )

        drawRouteArrows(context, map, routePoints, navigationMode)
    }

    // Destination first, so a vehicle dot always draws on top of it.
    if (destinationLat != null && destinationLng != null) {
        map.addMarker(
            MarkerOptions()
                .position(LatLng(destinationLat, destinationLng))
                .title(destinationLabel ?: "Destination")
                .snippet("Where you're headed")
                .icon(icons.fromBitmap(glyphBitmap("\uD83C\uDFC1", isDark, false)))
        )
    }

    // Vehicle status markers are drawn as a badge ON the dot (see
    // drawVehicles), because a pin at the vehicle's exact coordinates gets
    // painted over by the dot itself. Only PLACE stops belong here.
    stops.filter { it.standalone }.forEach { stop ->
        map.addMarker(
            MarkerOptions()
                .position(LatLng(stop.lat, stop.lng))
                .title(stop.label)
                .snippet(if (stop.critical) "Needs help" else "Stopped here")
                .icon(icons.fromBitmap(glyphBitmap(stop.icon ?: "\uD83D\uDCCD", isDark, stop.critical)))
        )
    }

    drawVehicles(context, map, vehicles, palette, isDark, markerOwners)
}

/**
 * Direction arrows along the route.
 *
 * Without them a line is ambiguous — it says where the road goes but not
 * which way along it you are meant to travel, which matters at exactly the
 * moment a junction offers two ways onto the same road.
 *
 * Spaced by INDEX rather than by distance: the server already simplified
 * the route to evenly-ish spaced points, and measuring true distance for
 * every segment would cost far more than it improves the spacing.
 */
private fun drawRouteArrows(
    context: android.content.Context,
    map: MapLibreMap,
    routePoints: List<Pair<Double, Double>>,
    navigationMode: Boolean = false,
) {
    if (routePoints.size < 8) return

    val icons = IconFactory.getInstance(context)
    // More of them while navigating, because the line fills the screen and
    // widely spaced arrows leave long stretches with no direction cue.
    val target = if (navigationMode) 26 else 12
    val step = (routePoints.size / target).coerceAtLeast(2)

    var i = step
    while (i < routePoints.size - 1) {
        val (lat, lng) = routePoints[i]
        val (nextLat, nextLng) = routePoints[i + 1]

        val bearing = Geo.bearingDegrees(lat, lng, nextLat, nextLng)

        map.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lng))
                .icon(icons.fromBitmap(arrowBitmap(bearing, navigationMode)))
        )
        i += step
    }
}

/**
 * A single chevron, rotated to point along the route.
 *
 * Always white and always ON the line, the way navigation apps draw them —
 * the arrow is a marking painted on the road, not a separate object
 * floating beside it.
 */
private fun arrowBitmap(bearingDeg: Float, navigationMode: Boolean): Bitmap {
    val size = if (navigationMode) 44 else 34
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f

    canvas.save()
    // The chevron is drawn pointing up, then the whole canvas is turned to
    // the compass bearing — far simpler than computing rotated vertices.
    canvas.rotate(bearingDeg, centre, centre)

    val scale = if (navigationMode) 1.3f else 1f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    val path = android.graphics.Path().apply {
        moveTo(centre - 6f * scale, centre + 4f * scale)
        lineTo(centre, centre - 4f * scale)
        lineTo(centre + 6f * scale, centre + 4f * scale)
    }
    canvas.drawPath(path, paint)
    canvas.restore()

    return bitmap
}

/** A pin carrying an emoji — used for stops and the destination. */
private fun glyphBitmap(glyph: String, isDark: Boolean, critical: Boolean): Bitmap {
    val size = 92
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // A filled disc so the emoji stays readable over any map colour.
    paint.color = if (isDark) 0xFF131A19.toInt() else 0xFFFFFFFF.toInt()
    canvas.drawCircle(centre, centre, 30f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 4f
    paint.color = if (critical) 0xFFFF5A4D.toInt() else if (isDark) 0xFF2A3735.toInt() else 0xFFD2DCD5.toInt()
    canvas.drawCircle(centre, centre, 30f, paint)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(glyph, centre, centre + 12f, text)
    return bitmap
}

/**
 * One marker per vehicle, coloured from the palette the server assigned.
 *
 * Staleness is drawn, not just labelled: a faded ring for a stale fix and a
 * hollow dot once it is lost. A frozen dot must never look live.
 */
private fun drawVehicles(
    context: android.content.Context,
    map: MapLibreMap,
    vehicles: List<Vehicle>,
    palette: List<Int>,
    isDark: Boolean,
    markerOwners: MutableMap<Long, String>,
) {
    val icons = IconFactory.getInstance(context)

    vehicles.forEachIndexed { index, vehicle ->
        val position = vehicle.position ?: return@forEachIndexed
        val lat = position.lat ?: return@forEachIndexed
        val lng = position.lng ?: return@forEachIndexed

        // The server assigns each vehicle a colour so every phone draws the
        // same car the same way. The palette fallback is keyed on the
        // vehicle id, not the list index — index order changes as vehicles
        // come and go, which would make dots swap colours mid-trip.
        val color = Formatters.parseColor(vehicle.color)?.toArgb()
            ?: palette[Math.floorMod(vehicle.id.hashCode(), palette.size)]

        // A stopped vehicle carries its reason as a badge on the dot.
        //
        // Drawn INTO the vehicle's own bitmap rather than as a separate pin,
        // because a status marker sits at the vehicle's exact coordinates —
        // a second marker there would be painted over by the dot and never
        // seen. The badge sits above and to the right, clear of the dot.
        val status = vehicle.currentStatus
        // The cone is only drawn when the car is actually MOVING. A parked
        // car's reported bearing is meaningless noise, and a beam swinging
        // around a stationary dot reads as a fault.
        val heading = vehicle.lastKnown
            ?.takeIf { (it.speedKmh ?: 0.0) >= 3.0 }
            ?.heading
            ?.toFloat()
        val icon = icons.fromBitmap(
            vehicleBitmap(color, vehicle.freshness, isDark, status?.icon, heading)
        )

        val drawn = map.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lng))
                // Tapping the dot answers "why has he stopped?" — the whole
                // reason the marker exists.
                .title(
                    if (status != null) "${vehicle.label} — ${status.label.orEmpty()}"
                    else vehicle.label
                )
                .snippet(
                    when {
                        status != null && status.waitingForGroup == true ->
                            "Waiting for the group"
                        status != null -> "Stopped · go ahead"
                        // The accuracy is shown, not just the speed. It is
                        // the only way to tell a real GPS lock from a cell
                        // tower guess — single digits mean satellites,
                        // tens mean the phone is still falling back.
                        vehicle.freshness == Freshness.LIVE -> listOfNotNull(
                            Formatters.speed(vehicle.lastKnown?.speedKmh),
                            vehicle.lastKnown?.accuracyM?.let { "±${it.toInt()} m" },
                        ).joinToString(" · ")
                        vehicle.freshness == Freshness.STALE ->
                            Formatters.shortAgo(vehicle.lastFixAgeSec)
                        else -> "No signal"
                    }
                )
                .icon(icon)
        )
        markerOwners[drawn.id] = vehicle.id
    }
}

/** A filled dot with a soft halo, or a hollow ring once the fix is lost. */
private fun vehicleBitmap(
    color: Int,
    freshness: Freshness,
    isDark: Boolean,
    statusGlyph: String? = null,
    /** Compass bearing, or null when stopped. Drives the direction cone. */
    headingDeg: Float? = null,
): Bitmap {
    // Wider than the dot needs, to leave room for a status badge without
    // the glyph being clipped at the edge of the bitmap.
    // Room for whichever extras this dot carries: a status badge up-right,
    // a heading cone above. Sized once so the dot never shifts position.
    val size = if (statusGlyph != null || headingDeg != null) 130 else 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // The DOT stays centred on the vehicle's real position even when the
    // bitmap is enlarged for a badge — otherwise adding a badge would shift
    // the car itself on the map.
    val centre = size / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = if (isDark) 0xFF0E1514.toInt() else 0xFFFFFFFF.toInt()

    // The direction cone, drawn FIRST so the dot sits on top of its narrow
    // end. Same idea as the beam Google Maps puts on your own location: it
    // answers "which way am I pointing", which a plain dot cannot.
    if (headingDeg != null) {
        canvas.save()
        canvas.rotate(headingDeg, centre, centre)

        val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                centre, centre - 40f, centre, centre,
                // Fades out at the far end, so it reads as a direction
                // rather than as a solid object on the road.
                (color and 0x00FFFFFF) or 0x00000000,
                (color and 0x00FFFFFF) or 0x99000000.toInt(),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        val path = android.graphics.Path().apply {
            moveTo(centre, centre)
            lineTo(centre - 20f, centre - 40f)
            // A curved far edge rather than a flat one — a triangle looks
            // like an arrow pointing AT something, a cone like a field of view.
            quadTo(centre, centre - 52f, centre + 20f, centre - 40f)
            close()
        }
        canvas.drawPath(path, cone)
        canvas.restore()
    }

    // Halo — wider and fainter as the fix ages.
    paint.color = color
    paint.alpha = when (freshness) {
        Freshness.LIVE -> 56
        Freshness.STALE -> 40
        Freshness.LOST -> 28
    }
    canvas.drawCircle(centre, centre, if (freshness == Freshness.LIVE) 34f else 30f, paint)

    // Outline, so a dot separates from whatever is beneath it.
    paint.alpha = 255
    paint.color = outline
    canvas.drawCircle(centre, centre, 22f, paint)

    when (freshness) {
        Freshness.LOST -> {
            // Hollow: present on the map, but plainly not reporting.
            paint.color = color
            paint.alpha = 150
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            canvas.drawCircle(centre, centre, 17f, paint)
        }
        else -> {
            paint.color = color
            paint.alpha = if (freshness == Freshness.STALE) 140 else 255
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centre, centre, 18f, paint)
        }
    }

    // The status badge: a small disc up and to the right of the dot,
    // carrying the marker's own emoji. Offset rather than centred so the
    // car's actual position stays readable underneath it.
    if (statusGlyph != null) {
        val badgeX = centre + 27f
        val badgeY = centre - 27f

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = if (isDark) 0xFF131A19.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawCircle(badgeX, badgeY, 24f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = color
        canvas.drawCircle(badgeX, badgeY, 24f, paint)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 27f
            textAlign = Paint.Align.CENTER
        }
        // Nudged down by roughly a third of the text size, because drawText
        // places the BASELINE at y, not the visual centre of the glyph.
        canvas.drawText(statusGlyph, badgeX, badgeY + 10f, glyphPaint)
    }

    return bitmap
}

/**
 * Frames the whole convoy, or follows one vehicle.
 *
 * Fitting every car in view is the default because the question this map
 * answers most often is "where is everyone", not "where am I".
 */
private fun frameConvoy(
    map: MapLibreMap,
    vehicles: List<Vehicle>,
    followVehicleId: String?,
    destinationLat: Double? = null,
    destinationLng: Double? = null,
): Boolean {
    val points = vehicles.mapNotNull { vehicle ->
        val p = vehicle.position ?: return@mapNotNull null
        val lat = p.lat ?: return@mapNotNull null
        val lng = p.lng ?: return@mapNotNull null
        vehicle.id to LatLng(lat, lng)
    }
    // Nothing to frame yet. Reported so the caller knows the camera has
    // NOT been positioned and should try again on the next update.
    if (points.isEmpty()) return false

    val followed = followVehicleId?.let { id -> points.firstOrNull { it.first == id }?.second }

    when {
        followed != null -> map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(followed, Constants.MAP_DEFAULT_ZOOM)
        )
        points.size == 1 -> map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(points.first().second, Constants.MAP_DEFAULT_ZOOM)
        )
        else -> {
            val bounds = LatLngBounds.Builder()
                .includes(points.map { it.second })
                .build()
            // Generous padding so a dot at the edge is not half under the
            // convoy strip or the bottom sheet.
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140))
        }
    }
    return true
}


/**
 * Frames the entire route.
 *
 * Deliberately separate from [frameConvoy]: that one keeps the cars in
 * view, which is the right default while driving. This answers a different
 * question — "what does the whole journey look like?" — and answering it
 * means zooming out past the convoy.
 */
private fun frameRoute(map: MapLibreMap, routePoints: List<Pair<Double, Double>>) {
    val builder = LatLngBounds.Builder()
    routePoints.forEach { builder.include(LatLng(it.first, it.second)) }

    // A route that doubles back can produce degenerate bounds, and
    // LatLngBounds throws rather than returning something usable.
    runCatching {
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 90), 700)
    }
}


/**
 * The navigation camera.
 *
 * Four things together are what make a map read as "navigation" rather
 * than "a map that happens to be zoomed in":
 *
 *   TILT     — the road ahead occupies most of the screen instead of the
 *              sky above you and the ground behind you
 *   BEARING  — the map turns so your direction of travel is always up,
 *              which removes the mental rotation of reading a north-up map
 *              while steering
 *   ZOOM     — close enough that individual turnings are distinguishable
 *   OFFSET   — you sit low on the screen, because what is behind you does
 *              not matter and what is ahead does
 *
 * Bearing is held at the last known heading when stopped: GPS bearing is
 * meaningless at a standstill, and letting it through spins the whole map
 * around a parked car.
 */
private fun followForNavigation(
    map: MapLibreMap,
    vehicles: List<Vehicle>,
    myVehicleId: String?,
) {
    val me = vehicles.firstOrNull { it.id == myVehicleId } ?: return
    val position = me.position ?: return
    val lat = position.lat ?: return
    val lng = position.lng ?: return

    val moving = (me.lastKnown?.speedKmh ?: 0.0) >= 3.0
    val bearing = if (moving) me.lastKnown?.heading ?: 0.0 else map.cameraPosition.bearing

    map.animateCamera(
        CameraUpdateFactory.newCameraPosition(
            org.maplibre.android.camera.CameraPosition.Builder()
                .target(LatLng(lat, lng))
                .zoom(Constants.MAP_NAV_ZOOM)
                .tilt(Constants.MAP_NAV_TILT)
                .bearing(bearing)
                .build()
        ),
        // Matched to the position cadence so the camera glides between
        // fixes instead of snapping and then sitting still.
        1200,
    )
}
