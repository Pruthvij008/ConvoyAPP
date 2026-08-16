package com.convoy.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The two palettes from the screen designs.
 *
 * Daylight is not an inversion of night. The relationship between roads and
 * land actually flips — at night roads are lighter than the ground, in
 * daylight they are white on grey-green — and both accents change value,
 * because the night teal is bright enough to glow on black and fails
 * completely on white.
 */

// ── Night ────────────────────────────────────────────────────────
val NightGround = Color(0xFF0A0F0E)   // app ground, blue-green near-black
val NightSurface = Color(0xFF131A19)  // sheets and cards
val NightSurface2 = Color(0xFF1D2725) // pressed states, dividers
val NightBorder = Color(0xFF2A3735)
val NightText = Color(0xFFE8F0ED)
val NightMuted = Color(0xFF8A9B96)
val NightDim = Color(0xFF5F706B)
val NightRoute = Color(0xFF35D6BC)    // route line + every primary action
val NightAmber = Color(0xFFF0A63C)
val NightRed = Color(0xFFFF5A4D)

// ── Day ──────────────────────────────────────────────────────────
val DayGround = Color(0xFFF4F7F3)
val DaySurface = Color(0xFFFFFFFF)
val DaySurface2 = Color(0xFFE7EDE8)
val DayBorder = Color(0xFFD2DCD5)
val DayText = Color(0xFF10201B)
val DayMuted = Color(0xFF5A6E68)
val DayDim = Color(0xFF8B9C96)
val DayRoute = Color(0xFF0B8E7A)      // same hue, darkened to survive glare
val DayAmber = Color(0xFFA96A10)
val DayRed = Color(0xFFC8362A)

/**
 * Vehicle colours — the accent system, mirroring VEHICLE_PALETTE in the
 * backend so the colour the server assigns is the colour the app draws.
 * Assigned round-robin, so two cars in one convoy are never the same.
 */
val VehicleNight = listOf(
    Color(0xFF4C8DFF), Color(0xFFFF6B6B), Color(0xFF3ECF8E), Color(0xFFF5A623),
    Color(0xFFA78BFA), Color(0xFF22D3EE), Color(0xFFF472B6), Color(0xFF9BE24A),
)

val VehicleDay = listOf(
    Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF15A05F), Color(0xFFC77C08),
    Color(0xFF7C3AED), Color(0xFF0891B2), Color(0xFFDB2777), Color(0xFF4D7C0F),
)
