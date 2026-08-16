package com.convoy.mobile.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.utility.PrefsManager
import com.convoy.mobile.utility.ThemeManager
import javax.inject.Inject

/**
 * What every screen in the app shares.
 *
 * Four responsibilities, deliberately kept here so no individual Activity
 * has to remember them: theme resolution, runtime permissions, the session
 * guard, and keeping the screen awake during a trip.
 */
abstract class BaseActivity : ComponentActivity() {

    @Inject lateinit var prefs: PrefsManager
    @Inject lateinit var themeManager: ThemeManager

    /** Recomposes the whole screen when the sun goes down or the user picks a mode. */
    private var isNight by mutableStateOf(true)

    /** Screens reachable before sign-in override this. */
    protected open val requiresSession: Boolean = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        onPermissionsResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (requiresSession && !prefs.isLoggedIn) {
            goToLogin()
            return
        }

        isNight = resolveNight()
    }

    override fun onResume() {
        super.onResume()
        // Re-checked on every resume so crossing sunset while the app was in
        // the background is applied on return, not on next launch.
        isNight = resolveNight()
        applyKeepScreenOn()
    }

    private fun resolveNight(): Boolean = themeManager.isNight().also(::applySystemBarIcons)

    /**
     * The status bar draws its own clock and icons, and Compose cannot reach
     * them. Without this they stay light-on-light in the day theme and the
     * clock is effectively invisible.
     */
    private fun applySystemBarIcons(night: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /**
     * A phone that sleeps mid-drive is a phone nobody can glance at, so the
     * screen is held awake while a trip is running — and only then.
     */
    private fun applyKeepScreenOn() {
        val shouldKeepOn = prefs.keepScreenOn && prefs.activeTripId != null
        if (shouldKeepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Wraps content in the resolved theme. Every screen calls this. */
    protected fun setThemedContent(content: @Composable () -> Unit) {
        setContent {
            ConvoyTheme(
                mode = themeManager.mode,
                isNightBySun = isNight,
                content = content,
            )
        }
    }

    // ── Permissions ─────────────────────────────────────────────
    protected fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Foreground location and notifications only. Background location is
     * requested separately and later — Android requires it to be a second
     * prompt, and Play Store requires a disclosure screen before it.
     */
    protected fun requestLocationPermission() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    protected open fun onPermissionsResult(granted: Map<String, Boolean>) = Unit

    // ── Navigation ──────────────────────────────────────────────
    protected fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    protected fun goToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
