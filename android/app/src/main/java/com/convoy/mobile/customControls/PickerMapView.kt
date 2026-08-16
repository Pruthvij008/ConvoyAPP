package com.convoy.mobile.customControls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.Constants
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * A map you move under a fixed pin, for choosing a place.
 *
 * Separate from [ConvoyMapView] because the two do opposite things: that one
 * drives the camera to follow vehicles, this one reports wherever the user
 * has dragged to. Sharing a component between them would mean one fighting
 * the other for control of the camera.
 */
@Composable
fun PickerMapView(
    startLat: Double,
    startLng: Double,
    onCentreChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Incremented by the caller when the camera should jump to
     * [startLat]/[startLng] — picking a search result, for instance.
     *
     * A counter rather than the coordinates themselves, because the map
     * reports its own centre back as those coordinates change. Watching the
     * coordinates would recentre the map in response to the user dragging
     * it, which fights the gesture and makes panning impossible.
     */
    recentreKey: Int = 0,
) {
    val context = LocalContext.current
    val isDark = ConvoyTheme.colors.isDark

    remember { MapLibre.getInstance(context) }
    val mapView = remember { MapView(context) }
    val styleApplied = remember { arrayOfNulls<Boolean>(1) }
    val appliedRecentre = remember { intArrayOf(-1) }

    DisposableEffect(Unit) {
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
                // A search result was chosen — fly to it. Guarded by the
                // last key applied so this runs once per pick and not on
                // every recomposition afterwards.
                if (styleApplied[0] != null && appliedRecentre[0] != recentreKey) {
                    appliedRecentre[0] = recentreKey
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(startLat, startLng),
                            Constants.MAP_PLACE_ZOOM,
                        ),
                    )
                }

                // Style is expensive to (re)load, so only when the theme
                // differs from what is already showing.
                if (styleApplied[0] == isDark) return@getMapAsync

                val styleUrl =
                    if (isDark) Constants.MAP_STYLE_NIGHT else Constants.MAP_STYLE_DAY

                map.setStyle(Style.Builder().fromUri(styleUrl)) {
                    styleApplied[0] = isDark
                    appliedRecentre[0] = recentreKey

                    map.uiSettings.apply {
                        isRotateGesturesEnabled = false
                        isTiltGesturesEnabled = false
                        isLogoEnabled = false
                        isAttributionEnabled = true
                    }

                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(startLat, startLng))
                        .zoom(Constants.MAP_DEFAULT_ZOOM)
                        .build()

                    // Reported on idle rather than on every frame, so the
                    // coordinate readout doesn't flicker while dragging.
                    map.addOnCameraIdleListener {
                        val centre = map.cameraPosition.target
                        if (centre != null) onCentreChanged(centre.latitude, centre.longitude)
                    }
                }
            }
        },
    )
}
