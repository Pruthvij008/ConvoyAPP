package com.convoy.mobile.activities

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.convoy.mobile.viewModels.ProfileViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.GhostButton
import com.convoy.mobile.customControls.SectionLabel
import com.convoy.mobile.customControls.SurfaceCard
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * Appearance, sharing state, and the account.
 *
 * The sharing section states the guarantee in the user's words and then
 * shows the evidence — an app that asks for background location has to
 * earn it, and a toggle reading "off" is not proof.
 */
@AndroidEntryPoint
class SettingsActivity : BaseActivity() {

    private val profileViewModel: ProfileViewModel by viewModels()

    /**
     * The system photo picker.
     *
     * PickVisualMedia rather than a storage permission: it hands back one
     * image the user chose and nothing else, so the app never asks to read
     * their whole gallery for a single avatar.
     */
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) profileViewModel.pickedPhoto(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setThemedContent {
            SettingsScreen(
                photoUrl = profileViewModel.photoUrl,
                isUploadingPhoto = profileViewModel.isUploading,
                photoError = profileViewModel.errorMessage,
                onPickPhoto = {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemovePhoto = profileViewModel::removePhoto,
                displayName = prefs.displayName.orEmpty(),
                username = prefs.username.orEmpty(),
                hasActiveTrip = prefs.activeTripId != null,
                currentMode = themeManager.mode,
                sunsetLabel = themeManager.sunsetLabel(),
                keepScreenOn = prefs.keepScreenOn,
                onModeChange = { mode ->
                    themeManager.mode = mode
                    recreate() // re-resolve the whole theme immediately
                },
                onKeepScreenOnChange = { prefs.keepScreenOn = it },
                onBack = { finish() },
                onSignOut = {
                    prefs.clearSession()
                    goToLogin()
                },
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    displayName: String,
    username: String,
    hasActiveTrip: Boolean,
    currentMode: ThemeMode,
    sunsetLabel: String?,
    keepScreenOn: Boolean,
    photoUrl: String?,
    isUploadingPhoto: Boolean,
    photoError: String?,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onModeChange: (ThemeMode) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = ConvoyTheme.colors
    var screenOn by remember { mutableStateOf(keepScreenOn) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                color = colors.muted,
                fontSize = 22.sp,
                modifier = Modifier.clickableOnce(onClick = onBack).padding(end = 14.dp),
            )
            Text(
                text = "Settings",
                color = colors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ── Account ─────────────────────────────────────────────
        SectionLabel("You")
        SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Optional, and never nagged about. The initial is a
                    // perfectly good identity in a six-car roster; a photo
                    // is just quicker to recognise at a glance.
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(colors.surface2, CircleShape)
                            .clickableOnce { onPickPhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (photoUrl != null) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = "Your photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape),
                            )
                        } else {
                            Text(
                                text = displayName.take(1).uppercase().ifBlank { "?" },
                                color = colors.muted,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        if (isUploadingPhoto) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column {
                        Text(
                            text = if (photoUrl != null) "Change photo" else "Add a photo",
                            color = colors.route,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickableOnce { onPickPhoto() },
                        )
                        Text(
                            text = "Optional — helps the group spot you",
                            color = colors.dim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (photoUrl != null) {
                            Text(
                                text = "Remove",
                                color = colors.red,
                                fontSize = 12.5.sp,
                                modifier = Modifier
                                    .clickableOnce { onRemovePhoto() }
                                    .padding(top = 6.dp),
                            )
                        }
                    }
                }

                // The ViewModel has been reporting upload failures all along
                // and this screen threw them away, so a photo that did not
                // upload simply never appeared and never said why. That was
                // worst while the upload was broken outright: the spinner
                // stopped, nothing changed, and the app stayed silent.
                photoError?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        color = colors.red,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = displayName.ifBlank { "Not signed in" },
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (username.isNotBlank()) {
                    Text(
                        text = "@$username",
                        color = colors.route,
                        fontSize = 13.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = "Signed in on this device. Your trips follow the " +
                        "account, so they survive a new phone.",
                    color = colors.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ── Theme ───────────────────────────────────────────────
        SectionLabel("Appearance")
        SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column {
                ThemeRow("Auto", "Light until sunset, then dark",
                    ThemeMode.AUTO, currentMode, onModeChange)
                ThemeRow("Always light", null, ThemeMode.LIGHT, currentMode, onModeChange)
                ThemeRow("Always dark", null, ThemeMode.DARK, currentMode, onModeChange)
                ThemeRow("Match my phone", "Follow the Android setting",
                    ThemeMode.SYSTEM, currentMode, onModeChange)
            }
        }

        Text(
            text = sunsetLabel?.let {
                "Auto uses the real sunset where you are — today that's $it."
            } ?: "Auto switches at sunset. Once a trip has run, it'll use the " +
                "real sunset for your location.",
            color = colors.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        // ── On the map ──────────────────────────────────────────
        SectionLabel("On the map")
        SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep the screen on", color = colors.text, fontSize = 15.sp)
                    Text(
                        text = "While a trip is running",
                        color = colors.muted,
                        fontSize = 12.5.sp,
                    )
                }
                Toggle(checked = screenOn) {
                    screenOn = !screenOn
                    onKeepScreenOnChange(screenOn)
                }
            }
        }

        // ── Sharing ─────────────────────────────────────────────
        SectionLabel("Location sharing")
        SurfaceCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (hasActiveTrip) colors.route else colors.dim,
                                RoundedCornerShape(5.dp),
                            )
                    )
                    Text(
                        text = if (hasActiveTrip) "Sharing — a trip is running"
                        else "You're not sharing anything",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    text = "Your location leaves this phone only while a trip is " +
                        "running and you're in it. A trip with no activity for 12 " +
                        "hours ends itself.",
                    color = colors.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        // ── Sign out ────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            if (confirmSignOut) {
                Text(
                    text = if (hasActiveTrip) {
                        "You're in a running trip. Signing out stops your phone " +
                            "reporting, and the convoy will see you go quiet."
                    } else {
                        "You'll need your username and password to sign back in."
                    },
                    color = colors.amber,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = "Cancel",
                        onClick = { confirmSignOut = false },
                        modifier = Modifier.weight(1f),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        GhostButton(text = "Sign out", onClick = onSignOut)
                    }
                }
            } else {
                GhostButton(text = "Sign out", onClick = { confirmSignOut = true })
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ThemeRow(
    title: String,
    subtitle: String?,
    mode: ThemeMode,
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = ConvoyTheme.colors
    val active = mode == current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableOnce(haptic = false) { onSelect(mode) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 15.sp)
            subtitle?.let { Text(it, color = colors.muted, fontSize = 12.5.sp) }
        }
        if (active) Text("✓", color = colors.route, fontSize = 18.sp)
    }
}

@Composable
private fun Toggle(checked: Boolean, onToggle: () -> Unit) {
    val colors = ConvoyTheme.colors

    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 27.dp)
            .background(
                if (checked) colors.route else colors.border,
                RoundedCornerShape(14.dp),
            )
            .clickableOnce(haptic = false, onClick = onToggle),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(21.dp)
                .background(Color.White, RoundedCornerShape(11.dp))
        )
    }
}
