package lava.onboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import lava.designsystem.color.ProviderColors
import lava.designsystem.component.Button
import lava.designsystem.component.CircularProgressIndicator
import lava.designsystem.component.Icon
import lava.designsystem.component.OutlinedTextField
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.onboarding.ProviderConfigState
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerDescriptor

@Composable
fun ConfigureStep(
    provider: TrackerDescriptor,
    config: ProviderConfigState,
    isRunning: Boolean,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onToggleAnonymous: (Boolean) -> Unit,
    onTestAndContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = ProviderColors.forProvider(provider.trackerId)
    val isAnonymous = provider.authType == AuthType.NONE

    Surface(
        modifier = modifier.fillMaxSize(),
        color = AppTheme.colors.background,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Configure ${provider.displayName}",
                style = AppTheme.typography.headlineSmall,
                color = color,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isAnonymous) "This provider does not require credentials." else "Enter your credentials for this provider.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (!isAnonymous && provider.supportsAnonymous) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Use anonymous access",
                            style = AppTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Skip entering credentials",
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.useAnonymous,
                        onCheckedChange = onToggleAnonymous,
                        modifier = Modifier.testTag("anonymous_switch"),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(if (isAnonymous) 0.dp else 8.dp))

            if (!isAnonymous && !config.useAnonymous) {
                OutlinedTextField(
                    value = config.username,
                    onValueChange = onUsernameChanged,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                var passwordVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = config.password,
                    onValueChange = onPasswordChanged,
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    // Standard password UX: mask characters by default; the
                    // trailing eye icon reveals them while held visible.
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Box(
                            modifier = Modifier
                                .testTag(PasswordVisibilityToggleTestTag)
                                .clickable { passwordVisible = !passwordVisible },
                        ) {
                            Icon(
                                icon = if (passwordVisible) {
                                    LavaIcons.PasswordVisible
                                } else {
                                    LavaIcons.PasswordHidden
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(24.dp))
            }

            if (config.error != null) {
                Text(
                    text = config.error,
                    color = AppTheme.colors.error,
                    style = AppTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = onTestAndContinue,
                enabled = !isRunning && (isAnonymous || config.useAnonymous || config.username.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.onPrimary,
                    )
                } else {
                    Text(if (isAnonymous || config.useAnonymous) "Continue" else "Test & Continue")
                }
            }
        }
    }
}

/**
 * Test tag for the password show/hide (eye) toggle on the provider Configure
 * step. Lets Compose UI Challenge tests tap it and assert the entered password
 * flips between masked and plain text.
 */
const val PasswordVisibilityToggleTestTag = "password_visibility_toggle"
