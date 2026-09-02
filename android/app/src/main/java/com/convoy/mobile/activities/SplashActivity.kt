package com.convoy.mobile.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.safeAll
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.viewModels.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Decides where the user lands, then gets out of the way.
 *
 * Device auth is idempotent, so a returning user is silently re-authorised
 * here and never sees a sign-in screen again — the same device id always
 * resolves to the same person.
 */
@AndroidEntryPoint
class SplashActivity : BaseActivity() {

    override val requiresSession = false

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setThemedContent { SplashScreen() }

        viewModel.restoreSession { loggedIn ->
            if (loggedIn) goToMain() else goToLogin()
        }
    }
}

@Composable
private fun SplashScreen() {
    val colors = ConvoyTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground)
            .safeAll(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "🛣️", fontSize = 52.sp)
            Text(
                text = "Convoy",
                color = colors.text,
                fontSize = 26.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
            CircularProgressIndicator(
                color = colors.route,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .size(22.dp),
            )
        }
    }
}
