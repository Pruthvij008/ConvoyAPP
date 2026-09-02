package com.convoy.mobile.customControls

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Keeping content out from under the hardware.
 *
 * The app draws edge to edge — `setDecorFitsSystemWindows(window, false)` in
 * BaseActivity — which is right for a map app: tiles should run under the
 * status bar rather than stopping at a grey stripe. The cost is that every
 * screen becomes responsible for its own insets, and anything that forgets
 * ends up underneath something.
 *
 * These use `safeDrawing` rather than `statusBars` / `navigationBars`,
 * because those two are not the whole story:
 *
 *   DISPLAY CUTOUT. A punch-hole or notch is not always inside the status
 *   bar inset, and in landscape it is not in it at all — it moves to the
 *   side. Padding for status bars alone puts text under the camera on
 *   exactly the phones that have one.
 *
 *   GESTURE NAVIGATION. The home pill sits in the navigation bar inset, and
 *   a button placed under it is not merely ugly — the system swallows the
 *   touch, so the control silently stops working.
 *
 *   HORIZONTAL. Curved-edge and landscape cutouts intrude from the sides,
 *   which neither vertical inset covers.
 *
 * safeDrawing is the union of all of that, plus the keyboard.
 */

/** Top edge: status bar, notch, and any side intrusion. */
@Composable
fun Modifier.safeTop(): Modifier = this.windowInsetsPadding(
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
)

/** Bottom edge: navigation bar or gesture pill, and any side intrusion. */
@Composable
fun Modifier.safeBottom(): Modifier = this.windowInsetsPadding(
    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
)

/**
 * Every edge.
 *
 * For a full screen that owns its whole surface — a form, a list, a
 * settings page. NOT for the map, which is meant to bleed to the edges with
 * only the floating controls inset.
 */
@Composable
fun Modifier.safeAll(): Modifier = this.windowInsetsPadding(WindowInsets.safeDrawing)
