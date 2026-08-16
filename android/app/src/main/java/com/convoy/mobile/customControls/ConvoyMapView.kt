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

                if (loadedStyleIsDark[0] == isDark && map.style?.isFullyLoaded == true) {
                    // Style is already right — just move the dots. This is the
                    // path taken on every position update.
                    drawAll(
                        view.context, map, vehicles, palette, isDark,
                        destinationLat, destinationLng, destinationLabel, stops,
                        routePoints, colors.route.toArgb(),
                    )
                    frameConvoy(map, vehicles, followVehicleId, destinationLat, destinationLng)
                    return@getMapAsync
                }

                val styleUrl =
                    if (isDark) Constants.MAP_STYLE_NIGHT else Constants.MAP_STYLE_DAY

                map.setStyle(Style.Builder().fromUri(styleUrl)) {
                    loadedStyleIsDark[0] = isDark
                    map.uiSettings.apply {
                        isRotateGesturesEnabled = false   // a rotated map is disorienting at speed
                        isTiltGesturesEnabled = false
                        isAttributionEnabled = true        // OSM requires attribution
                        isLogoEnabled = false
                        setCompassMargins(0, 0, 24, 0)
                    }

                    drawAll(
                        view.context, map, vehicles, palette, isDark,
                        destinationLat, destinationLng, destinationLabel, stops,
                        routePoints, colors.route.toArgb(),
                    )
                    frameConvoy(map, vehicles, followVehicleId, destinationLat, destinationLng)
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
) {
    map.clear()
    val icons = IconFactory.getInstance(context)

    // The route goes down first so every pin and every vehicle dot draws on
    // top of it. A line painted over a car would hide the one thing on this
    // screen the user is actually looking for.
    if (routePoints.size >= 2) {
        map.addPolyline(
            PolylineOptions()
                .addAll(routePoints.map { LatLng(it.first, it.second) })
                .color(routeColor)
                .alpha(0.75f)
                .width(5f)
        )
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

    stops.forEach { stop ->
        map.addMarker(
            MarkerOptions()
                .position(LatLng(stop.lat, stop.lng))
                .title(stop.label)
                .snippet(if (stop.critical) "Needs help" else "Stopped here")
                .icon(icons.fromBitmap(glyphBitmap(stop.icon ?: "\uD83D\uDCCD", isDark, stop.critical)))
        )
    }

    drawVehicles(context, map, vehicles, palette, isDark)
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

        val icon = icons.fromBitmap(vehicleBitmap(color, vehicle.freshness, isDark))

        map.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lng))
                .title(vehicle.label)
                .snippet(
                    when (vehicle.freshness) {
                        Freshness.LIVE -> Formatters.speed(vehicle.lastKnown?.speedKmh)
                        Freshness.STALE -> Formatters.shortAgo(vehicle.lastFixAgeSec)
                        Freshness.LOST -> "No signal"
                    }
                )
                .icon(icon)
        )
    }
}

/** A filled dot with a soft halo, or a hollow ring once the fix is lost. */
private fun vehicleBitmap(color: Int, freshness: Freshness, isDark: Boolean): Bitmap {
    val size = 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = if (isDark) 0xFF0E1514.toInt() else 0xFFFFFFFF.toInt()

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
) {
    val points = vehicles.mapNotNull { vehicle ->
        val p = vehicle.position ?: return@mapNotNull null
        val lat = p.lat ?: return@mapNotNull null
        val lng = p.lng ?: return@mapNotNull null
        vehicle.id to LatLng(lat, lng)
    }
    if (points.isEmpty()) return

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
}
