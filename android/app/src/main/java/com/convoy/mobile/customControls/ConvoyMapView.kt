package com.convoy.mobile.customControls

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The live map.
 *
 * MapLibre with OpenFreeMap's vector styles — no API key, no account, no
 * per-request billing.
 *
 * Everything is drawn as GeoJSON SOURCES feeding style LAYERS, not as
 * annotations. That rewrite was not housekeeping. The annotation version
 * called map.clear() and then rebuilt every polyline, every pin and every
 * vehicle bitmap on EVERY position update — and once positions started
 * arriving every two seconds instead of every fifteen, that meant tearing
 * down and reallocating the entire map seven times more often. It is why
 * the map stuttered.
 *
 * With sources, a position update is one setGeoJson call: the same layers
 * keep rendering, nothing is allocated, and the GPU does the work it is
 * there to do. Bitmaps are built only when a vehicle's APPEARANCE changes —
 * its colour, its staleness, the badge it carries — which on a normal drive
 * is almost never.
 *
 * Layers are also explicit about anchoring, which annotations were not: an
 * icon anchors at its CENTRE here, so a dot sits on the coordinate it
 * describes rather than floating above it.
 */
@Composable
fun ConvoyMapView(
    vehicles: List<Vehicle>,
    modifier: Modifier = Modifier,
    followVehicleId: String? = null,
    destinationLat: Double? = null,
    destinationLng: Double? = null,
    destinationLabel: String? = null,
    /** Other people's active stops. */
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
     */
    navigationMode: Boolean = false,
    /** Whose dot the navigation camera follows. */
    myVehicleId: String? = null,
    /**
     * Bumped to hand the camera back to navigation after the user has
     * dragged it away — what the "re-centre" button does.
     */
    recentreKey: Int = 0,
    /**
     * "Show me where they are" — the roster tapping a car.
     *
     * A one-shot look rather than [followVehicleId], which locks the camera
     * on and keeps re-centring. Bumping [focusKey] is what triggers it, so
     * asking for the same car twice works twice.
     */
    focusVehicleId: String? = null,
    focusKey: Int = 0,
    onVehicleTapped: (Vehicle) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = ConvoyTheme.colors
    val isDark = colors.isDark

    // MapLibre must be initialised once before any MapView is created.
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }
    val loadedStyleIsDark = remember { arrayOfNulls<Boolean>(1) }
    val layersReady = remember { booleanArrayOf(false) }

    // Which icon bitmaps the current style already holds. A style reload
    // discards them, so this is cleared alongside it.
    val registeredIcons = remember { mutableSetOf<String>() }

    // The vehicle list as the tap handler should see it. The handler is
    // registered once, so without this it would close over a stale list.
    val latestVehicles = remember { mutableListOf<Vehicle>() }
    latestVehicles.clear()
    latestVehicles.addAll(vehicles)

    val appliedFitRoute = remember { intArrayOf(0) }
    val hasAutoFramed = remember { booleanArrayOf(false) }
    val userMovedCamera = remember { booleanArrayOf(false) }
    val appliedRecentre = remember { intArrayOf(0) }
    val appliedFocus = remember { intArrayOf(0) }

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
                val styleMatches =
                    loadedStyleIsDark[0] == isDark && map.style?.isFullyLoaded == true

                if (styleMatches && layersReady[0]) {
                    // The hot path, taken on every position update. No
                    // clearing and no allocation — just new data into the
                    // sources that are already rendering.
                    map.style?.let { style ->
                        pushData(
                            style, vehicles, stops, routePoints,
                            destinationLat, destinationLng, isDark,
                            colors.vehicles.map { it.toArgb() }, registeredIcons,
                        )
                    }
                    aimCamera(
                        map, vehicles, routePoints, followVehicleId, myVehicleId,
                        destinationLat, destinationLng, navigationMode,
                        fitRouteKey, recentreKey, focusVehicleId, focusKey,
                        appliedFitRoute, appliedRecentre, appliedFocus,
                        hasAutoFramed, userMovedCamera,
                    )
                    return@getMapAsync
                }

                val styleUrl =
                    if (isDark) Constants.MAP_STYLE_NIGHT else Constants.MAP_STYLE_DAY

                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    loadedStyleIsDark[0] = isDark
                    // A fresh style holds none of our images, sources or
                    // layers. Forgetting this is how icons silently vanish
                    // after a day/night switch.
                    registeredIcons.clear()
                    layersReady[0] = false

                    map.uiSettings.apply {
                        // Rotate and tilt belong to the user. Turning the map
                        // to match the road ahead is a perfectly sensible
                        // thing to do, and removing the gesture just makes
                        // the map feel broken.
                        isRotateGesturesEnabled = true
                        isTiltGesturesEnabled = true

                        // The compass is what makes rotation safe to offer:
                        // once turned there must be an obvious way back to
                        // north. MapLibre shows it only while rotated.
                        isCompassEnabled = true
                        setCompassMargins(0, 340, 44, 0)

                        isAttributionEnabled = true        // OSM requires attribution
                        isLogoEnabled = false
                    }

                    installLayers(style, colors.route.toArgb(), isDark)
                    layersReady[0] = true

                    pushData(
                        style, vehicles, stops, routePoints,
                        destinationLat, destinationLng, isDark,
                        colors.vehicles.map { it.toArgb() }, registeredIcons,
                    )

                    // "Moved by a gesture", specifically — the constant
                    // REASON_DEVELOPER_ANIMATION would fire on our own
                    // follow updates and instantly disable following.
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            userMovedCamera[0] = true
                        }
                    }

                    // Registered once, against the list above, rather than
                    // re-registered on every update — adding a listener per
                    // frame leaks them by the hundred.
                    map.addOnMapClickListener { latLng ->
                        val screen = map.projection.toScreenLocation(latLng)
                        // A generous box, because a fingertip is not a pixel
                        // and this is used in a moving vehicle.
                        val box = RectF(
                            screen.x - 28f, screen.y - 28f,
                            screen.x + 28f, screen.y + 28f,
                        )
                        val hit = map.queryRenderedFeatures(box, LYR_VEHICLES)
                            .firstOrNull()
                            ?.getStringProperty(PROP_ID)
                        val vehicle = hit?.let { id -> latestVehicles.firstOrNull { it.id == id } }
                        if (vehicle != null) {
                            onVehicleTapped(vehicle)
                            true
                        } else {
                            false
                        }
                    }

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

// ── Source and layer identifiers ─────────────────────────────────
private const val SRC_ROUTE = "convoy-route-src"
private const val SRC_PINS = "convoy-pins-src"
private const val SRC_VEHICLES = "convoy-vehicles-src"

private const val LYR_ROUTE_CASING = "convoy-route-casing"
private const val LYR_ROUTE = "convoy-route-line"
private const val LYR_PINS = "convoy-pins"
private const val LYR_CONES = "convoy-vehicle-cones"
private const val LYR_VEHICLES = "convoy-vehicles"

private const val PROP_ID = "vehicleId"
private const val PROP_ICON = "icon"
private const val PROP_CONE = "cone"
private const val PROP_BEARING = "bearing"
private const val PROP_MOVING = "moving"

/**
 * Creates the sources and layers once, empty.
 *
 * Everything afterwards is a data update into these, which is what makes a
 * position change cost almost nothing.
 */
private fun installLayers(style: Style, routeColor: Int, isDark: Boolean) {
    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addSource(GeoJsonSource(SRC_PINS))
    style.addSource(GeoJsonSource(SRC_VEHICLES))

    // Width scales with zoom rather than sitting at one fixed value. A line
    // wide enough to follow at driving zoom is a fat smear over a city
    // overview, and one thin enough to read on the overview disappears under
    // the car. Interpolating is what lets a single line be right at both.
    val casingWidth = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(8, 3.5f),
        Expression.stop(13, 7.5f),
        Expression.stop(17, 15f),
        Expression.stop(20, 22f),
    )
    val lineWidth = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(8, 2f),
        Expression.stop(13, 4.5f),
        Expression.stop(17, 10f),
        Expression.stop(20, 15f),
    )

    // Two passes: a dark casing underneath, the route on top. This is how
    // every serious map draws a route, and the reason is practical rather
    // than decorative — a single flat line disappears over roads of a
    // similar colour, which is exactly where you most need to see it.
    style.addLayer(
        LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(if (isDark) 0xFF04211D.toInt() else 0xFF0B3B33.toInt()),
            PropertyFactory.lineWidth(casingWidth),
            PropertyFactory.lineOpacity(0.55f),
            // Round joins and caps are what make a route follow a bend
            // smoothly instead of showing a mitred spike at every vertex.
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
    )
    style.addLayer(
        LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(routeColor),
            PropertyFactory.lineWidth(lineWidth),
            PropertyFactory.lineOpacity(0.95f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
    )

    // Direction arrows used to be scattered along the line as dozens of
    // separate markers. They are gone deliberately: they fought the route
    // for attention and cluttered every bend, and a line drawn from where
    // you are to where you are going was never ambiguous about direction.

    style.addLayer(
        SymbolLayer(LYR_PINS, SRC_PINS).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
    )

    // The heading cone sits BENEATH the dot and is the only thing that
    // rotates, so a vehicle's status badge stays upright while the cone
    // swings round to the direction of travel.
    val cones = SymbolLayer(LYR_CONES, SRC_VEHICLES).withProperties(
        PropertyFactory.iconImage(Expression.get(PROP_CONE)),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
        // Rotate with the MAP, not the screen: the cone keeps pointing along
        // the road even when the map itself has been turned.
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
    )
    // A parked car's reported bearing is meaningless noise, and a beam
    // swinging around a stationary dot reads as a fault.
    cones.setFilter(Expression.eq(Expression.get(PROP_MOVING), Expression.literal(true)))
    style.addLayer(cones)

    style.addLayer(
        SymbolLayer(LYR_VEHICLES, SRC_VEHICLES).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            // CENTRE, explicitly. This is the anchoring the annotation API
            // left implicit, and a dot anchored anywhere else floats off the
            // road it is meant to be sitting on.
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
    )
}

/** Feeds current positions into the existing layers. The hot path. */
private fun pushData(
    style: Style,
    vehicles: List<Vehicle>,
    stops: List<MapStop>,
    routePoints: List<Pair<Double, Double>>,
    destinationLat: Double?,
    destinationLng: Double?,
    isDark: Boolean,
    palette: List<Int>,
    registeredIcons: MutableSet<String>,
) {
    // ── Route ────────────────────────────────────────────────────
    style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(
        if (routePoints.size >= 2) {
            LineString.fromLngLats(routePoints.map { Point.fromLngLat(it.second, it.first) })
        } else {
            // An empty line rather than null: null leaves the previous one
            // on screen, so a cleared route would never actually disappear.
            LineString.fromLngLats(emptyList<Point>())
        }
    )

    // ── Destination and place pins ───────────────────────────────
    val pinFeatures = mutableListOf<Feature>()

    if (destinationLat != null && destinationLng != null) {
        val key = glyphIconKey(FLAG_GLYPH, false, isDark)
        ensureIcon(style, registeredIcons, key) { glyphBitmap(FLAG_GLYPH, isDark, false) }
        pinFeatures += Feature.fromGeometry(
            Point.fromLngLat(destinationLng, destinationLat)
        ).apply { addStringProperty(PROP_ICON, key) }
    }

    // A vehicle's own status rides on its dot (see vehicleBitmap), because a
    // pin at the vehicle's exact coordinates is painted over by the dot.
    // Only standalone place stops belong here.
    stops.filter { it.standalone }.forEach { stop ->
        val glyph = stop.icon ?: PIN_GLYPH
        val key = glyphIconKey(glyph, stop.critical, isDark)
        ensureIcon(style, registeredIcons, key) { glyphBitmap(glyph, isDark, stop.critical) }
        pinFeatures += Feature.fromGeometry(
            Point.fromLngLat(stop.lng, stop.lat)
        ).apply { addStringProperty(PROP_ICON, key) }
    }

    style.getSourceAs<GeoJsonSource>(SRC_PINS)
        ?.setGeoJson(FeatureCollection.fromFeatures(pinFeatures))

    // ── Vehicles ─────────────────────────────────────────────────
    val vehicleFeatures = mutableListOf<Feature>()

    vehicles.forEach { vehicle ->
        val position = vehicle.position ?: return@forEach
        val lat = position.lat ?: return@forEach
        val lng = position.lng ?: return@forEach

        // The server assigns each vehicle a colour so every phone draws the
        // same car the same way. The palette fallback is keyed on the
        // vehicle id, not the list index — index order changes as vehicles
        // come and go, which would make dots swap colours mid-trip.
        val color = Formatters.parseColor(vehicle.color)?.toArgb()
            ?: palette[Math.floorMod(vehicle.id.hashCode(), palette.size)]

        val status = vehicle.currentStatus
        val moving = (vehicle.lastKnown?.speedKmh ?: 0.0) >= 3.0
        val bearing = vehicle.lastKnown?.heading?.toFloat() ?: 0f

        // Keyed on APPEARANCE, not on position. A car that has merely moved
        // reuses the bitmap it already has, which is what keeps a position
        // update free of allocation.
        val iconKey = vehicleIconKey(color, vehicle.freshness, status?.icon, isDark)
        val coneKey = CONE_PREFIX + color
        ensureIcon(style, registeredIcons, iconKey) {
            vehicleBitmap(color, vehicle.freshness, isDark, status?.icon)
        }
        ensureIcon(style, registeredIcons, coneKey) { coneBitmap(color) }

        vehicleFeatures += Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
            addStringProperty(PROP_ID, vehicle.id)
            addStringProperty(PROP_ICON, iconKey)
            addStringProperty(PROP_CONE, coneKey)
            addNumberProperty(PROP_BEARING, bearing)
            addBooleanProperty(PROP_MOVING, moving)
        }
    }

    style.getSourceAs<GeoJsonSource>(SRC_VEHICLES)
        ?.setGeoJson(FeatureCollection.fromFeatures(vehicleFeatures))
}

private const val CONE_PREFIX = "convoy-cone-"
private const val FLAG_GLYPH = "🏁"
private const val PIN_GLYPH = "📍"

private fun vehicleIconKey(color: Int, freshness: Freshness, glyph: String?, isDark: Boolean) =
    "veh-$color-${freshness.name}-${glyph ?: "none"}-${if (isDark) "d" else "l"}"

private fun glyphIconKey(glyph: String, critical: Boolean, isDark: Boolean) =
    "pin-${glyph.hashCode()}-$critical-${if (isDark) "d" else "l"}"

/** Registers an icon with the style once, and only once. */
private inline fun ensureIcon(
    style: Style,
    registered: MutableSet<String>,
    key: String,
    build: () -> Bitmap,
) {
    if (registered.add(key)) style.addImage(key, build())
}

/**
 * The direction cone, drawn pointing UP and rotated by the layer.
 *
 * Its own image rather than part of the dot, so that rotating it does not
 * also spin the status badge — a badge that turns with the road is
 * unreadable and looks broken.
 */
private fun coneBitmap(color: Int): Bitmap {
    val size = 130
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centre = size / 2f

    val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = android.graphics.LinearGradient(
            centre, centre - 40f, centre, centre,
            // Fades out at the far end, so it reads as a direction rather
            // than as a solid object lying on the road.
            (color and 0x00FFFFFF) or 0x00000000,
            (color and 0x00FFFFFF) or 0x99000000.toInt(),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    val path = android.graphics.Path().apply {
        moveTo(centre, centre)
        lineTo(centre - 20f, centre - 40f)
        // A curved far edge rather than a flat one — a triangle looks like
        // an arrow pointing AT something, a cone like a field of view.
        quadTo(centre, centre - 52f, centre + 20f, centre - 40f)
        close()
    }
    canvas.drawPath(path, cone)
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
    paint.color =
        if (critical) 0xFFFF5A4D.toInt()
        else if (isDark) 0xFF2A3735.toInt()
        else 0xFFD2DCD5.toInt()
    canvas.drawCircle(centre, centre, 30f, paint)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(glyph, centre, centre + 12f, text)
    return bitmap
}

/**
 * A filled dot with a soft halo, or a hollow ring once the fix is lost.
 *
 * Staleness is drawn, not just labelled: a frozen dot must never look live.
 */
private fun vehicleBitmap(
    color: Int,
    freshness: Freshness,
    isDark: Boolean,
    statusGlyph: String? = null,
): Bitmap {
    // One size whether or not it carries a badge, so the dot never shifts
    // position when a status appears.
    val size = 130
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

    // The status badge: a small disc up and to the right, carrying the
    // marker's own emoji. Offset rather than centred so the car's actual
    // position stays readable underneath it.
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

/** Every camera decision, in the order they take precedence. */
private fun aimCamera(
    map: MapLibreMap,
    vehicles: List<Vehicle>,
    routePoints: List<Pair<Double, Double>>,
    followVehicleId: String?,
    myVehicleId: String?,
    destinationLat: Double?,
    destinationLng: Double?,
    navigationMode: Boolean,
    fitRouteKey: Int,
    recentreKey: Int,
    focusVehicleId: String?,
    focusKey: Int,
    appliedFitRoute: IntArray,
    appliedRecentre: IntArray,
    appliedFocus: IntArray,
    hasAutoFramed: BooleanArray,
    userMovedCamera: BooleanArray,
) {
    // "Show me where they are" wins over everything, including navigation:
    // it is an explicit request made a moment ago, and anything that
    // overrode it would make the roster feel broken.
    if (appliedFocus[0] != focusKey) {
        appliedFocus[0] = focusKey
        if (focusKey > 0 && focusVehicleId != null) {
            val target = vehicles.firstOrNull { it.id == focusVehicleId }?.position?.latLng()
            if (target != null) {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(target.first, target.second),
                        Constants.MAP_FOCUS_ZOOM,
                    ),
                    650,
                )
                // The camera is now deliberately somewhere the user chose,
                // so navigation must not immediately drag it back.
                userMovedCamera[0] = true
                hasAutoFramed[0] = true
                return
            }
        }
    }

    if (navigationMode) {
        // Navigation owns the camera outright: it re-aims on every position
        // update so the view keeps up with the car. But only while the user
        // has not taken the camera — re-centring on every update means any
        // attempt to look ahead is undone within a second, which reads as
        // broken rather than helpful.
        if (appliedRecentre[0] != recentreKey) {
            appliedRecentre[0] = recentreKey
            userMovedCamera[0] = false
        }
        if (!userMovedCamera[0]) followForNavigation(map, vehicles, myVehicleId)
        return
    }

    // An explicit "show me the route" always wins.
    if (appliedFitRoute[0] != fitRouteKey) {
        appliedFitRoute[0] = fitRouteKey
        if (fitRouteKey > 0 && routePoints.size >= 2) {
            frameRoute(map, routePoints)
            hasAutoFramed[0] = true
            return
        }
    }

    // Auto-framing happens on first load and then STOPS. Re-framing on every
    // update fights the user for the camera: pan away to look at the road
    // ahead and it snaps back within seconds. Follow mode is the one case
    // that should keep tracking.
    if (!hasAutoFramed[0] || followVehicleId != null) {
        if (frameConvoy(map, vehicles, followVehicleId, destinationLat, destinationLng)) {
            hasAutoFramed[0] = true
        }
    }
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
    // Nothing to frame yet. Reported so the caller knows the camera has NOT
    // been positioned and should try again on the next update.
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
 * Deliberately separate from [frameConvoy]: that one keeps the cars in view,
 * which is the right default while driving. This answers a different
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
 * Four things together are what make a map read as "navigation" rather than
 * "a map that happens to be zoomed in":
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
