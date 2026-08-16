package com.convoy.mobile.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convoy.mobile.customControls.PrimaryButton
import com.convoy.mobile.customControls.clickableOnce
import com.convoy.mobile.ui.theme.ConvoyTheme
import com.convoy.mobile.viewModels.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Sign in, or create an account — one screen, two modes.
 *
 * Username and password only. There is no email, so there is nothing to
 * verify and nothing standing between registering and joining a convoy.
 */
@AndroidEntryPoint
class LoginActivity : BaseActivity() {

    override val requiresSession = false

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent { LoginScreen(viewModel, onDone = { goToMain() }) }
    }
}

@Composable
private fun LoginScreen(viewModel: AuthViewModel, onDone: () -> Unit) {
    val colors = ConvoyTheme.colors
    val register = viewModel.isRegisterMode
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.isAuthenticated) {
        if (viewModel.isAuthenticated) onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
        ) {
            Text(text = "🛣️", fontSize = 44.sp)

            Text(
                text = if (register) "Create your account" else "Welcome back",
                color = colors.text,
                fontSize = 31.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp),
            )

            Text(
                text = if (register) {
                    "Pick a username your friends will recognise. No email, " +
                        "nothing to verify."
                } else {
                    "Sign in to get back to your trips."
                },
                color = colors.muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
            )

            // ── Username ────────────────────────────────────────
            FieldLabel("Username")
            AuthField(
                value = viewModel.username,
                onValueChange = viewModel::onUsernameChanged,
                placeholder = "pruthvij",
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next,
                ),
                isError = viewModel.usernameError != null,
            )
            HintOrError(hint = null, error = viewModel.usernameError)

            // ── Display name (register only) ────────────────────
            if (register) {
                Spacer(Modifier.height(14.dp))
                FieldLabel("Display name")
                AuthField(
                    value = viewModel.displayName,
                    onValueChange = viewModel::onDisplayNameChanged,
                    placeholder = "Pruthvij",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                )
                HintOrError(
                    hint = "What the convoy sees on the map. Optional.",
                    error = null,
                )
            }

            // ── Password ────────────────────────────────────────
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("Password", Modifier.weight(1f))
                Text(
                    text = if (showPassword) "Hide" else "Show",
                    color = colors.route,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickableOnce(haptic = false) { showPassword = !showPassword }
                        .padding(4.dp),
                )
            }
            AuthField(
                value = viewModel.password,
                onValueChange = viewModel::onPasswordChanged,
                placeholder = "At least 8 characters",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                isError = viewModel.passwordError != null,
            )
            HintOrError(
                hint = if (register) "There's no email, so this can't be reset. " +
                    "Pick something you'll remember." else null,
                error = viewModel.passwordError,
            )

            viewModel.errorMessage?.let {
                Text(
                    text = it,
                    color = colors.red,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = if (register) "Create account" else "Sign in",
                enabled = viewModel.canSubmit,
                loading = viewModel.isLoading,
                onClick = viewModel::submit,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (register) "Already have an account?" else "New here?",
                    color = colors.muted,
                    fontSize = 14.sp,
                )
                Text(
                    text = if (register) " Sign in" else " Create one",
                    color = colors.route,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickableOnce(haptic = false) { viewModel.toggleMode() },
                )
            }

            Text(
                text = "Your location is only ever shared while a trip is running.",
                color = colors.dim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ConvoyTheme.colors.muted,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(bottom = 7.dp),
    )
}

@Composable
private fun HintOrError(hint: String?, error: String?) {
    val colors = ConvoyTheme.colors
    val text = error ?: hint ?: return
    Text(
        text = text,
        color = if (error != null) colors.red else colors.dim,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
) {
    val colors = ConvoyTheme.colors

    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, color = colors.text),
        placeholder = { Text(placeholder, color = colors.dim, fontSize = 17.sp) },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = colors.route,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isError) colors.red.copy(alpha = 0.6f) else colors.border,
                RoundedCornerShape(16.dp),
            ),
    )
}
