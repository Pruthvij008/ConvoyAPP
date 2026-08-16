package com.convoy.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Convoy's own token set, carried alongside Material3's colour scheme.
 *
 * Material3 has no slot for "the route line", "a stale vehicle" or "the
 * muted line under a roster row", and forcing those into primary/secondary
 * would lose the meaning. Components read these tokens instead, which is
 * also what lets the whole UI re-theme by swapping about fifteen values.
 */
data class ConvoyColors(
    val ground: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val route: Color,
    val amber: Color,
    val red: Color,
    val vehicles: List<Color>,
    val isDark: Boolean,
)

private val NightColors = ConvoyColors(
    ground = NightGround, surface = NightSurface, surface2 = NightSurface2,
    border = NightBorder, text = NightText, muted = NightMuted, dim = NightDim,
    route = NightRoute, amber = NightAmber, red = NightRed,
    vehicles = VehicleNight, isDark = true,
)

private val DayColors = ConvoyColors(
    ground = DayGround, surface = DaySurface, surface2 = DaySurface2,
    border = DayBorder, text = DayText, muted = DayMuted, dim = DayDim,
    route = DayRoute, amber = DayAmber, red = DayRed,
    vehicles = VehicleDay, isDark = false,
)

val LocalConvoyColors = staticCompositionLocalOf { NightColors }

/** `ConvoyTheme.colors.route` reads better at call sites than a local. */
object ConvoyTheme {
    val colors: ConvoyColors
        @Composable @ReadOnlyComposable get() = LocalConvoyColors.current
}

/**
 * What the user picked in Settings. AUTO is the default and follows the
 * actual sunset where they are, not a fixed hour — 7pm is pitch dark in
 * December and broad daylight in June.
 */
enum class ThemeMode { AUTO, LIGHT, DARK, SYSTEM }

@Composable
fun ConvoyTheme(
    mode: ThemeMode = ThemeMode.AUTO,
    /** Resolved by ThemeManager from the device's location and the date. */
    isNightBySun: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()

    val dark = when (mode) {
        ThemeMode.AUTO -> isNightBySun
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val convoy = if (dark) NightColors else DayColors

    // Material3 still needs a scheme for its own components (ripples,
    // text fields, dialogs), so it is mapped from the same tokens rather
    // than being a second, competing palette.
    val material = if (dark) {
        darkColorScheme(
            primary = convoy.route,
            onPrimary = Color(0xFF032420),
            background = convoy.ground,
            onBackground = convoy.text,
            surface = convoy.surface,
            onSurface = convoy.text,
            surfaceVariant = convoy.surface2,
            onSurfaceVariant = convoy.muted,
            outline = convoy.border,
            error = convoy.red,
        )
    } else {
        lightColorScheme(
            primary = convoy.route,
            onPrimary = Color.White,
            background = convoy.ground,
            onBackground = convoy.text,
            surface = convoy.surface,
            onSurface = convoy.text,
            surfaceVariant = convoy.surface2,
            onSurfaceVariant = convoy.muted,
            outline = convoy.border,
            error = convoy.red,
        )
    }

    CompositionLocalProvider(LocalConvoyColors provides convoy) {
        MaterialTheme(
            colorScheme = material,
            typography = ConvoyTypography,
            content = content,
        )
    }
}
