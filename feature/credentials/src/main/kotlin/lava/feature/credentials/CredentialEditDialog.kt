package lava.feature.credentials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import lava.designsystem.component.Button
import lava.designsystem.component.OutlinedTextField
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.theme.AppTheme

/**
 * Bottom sheet dialog for creating or editing provider credentials.
 *
 * Matches the wireframe: label field, credential type selector (Password/Token/API Key),
 * conditional fields per type, Cancel/Save buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialEditDialog(
    state: CredentialDialogState,
    onAction: (CredentialsAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onAction(CredentialsAction.DismissDialog) },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = AppTheme.spaces.large, topEnd = AppTheme.spaces.large),
        containerColor = AppTheme.colors.surface,
        contentColor = AppTheme.colors.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
        ) {
            Text(
                text = if (state.isEditing) {
                    stringResource(R.string.credentials_edit)
                } else {
                    stringResource(R.string.credentials_add)
                },
                style = AppTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(AppTheme.spaces.large))

            OutlinedTextField(
                value = state.label,
                onValueChange = { onAction(CredentialsAction.SetLabel(it)) },
                label = { Text(stringResource(R.string.credentials_label)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(AppTheme.spaces.large))

            Text(
                text = stringResource(R.string.credentials_type),
                style = AppTheme.typography.labelLarge,
            )

            Spacer(modifier = Modifier.height(AppTheme.spaces.small))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spaces.small),
            ) {
                CredentialType.entries.forEach { type ->
                    val selected = state.credentialType == type
                    OutlinedButton(
                        onClick = { onAction(CredentialsAction.SetCredentialType(type)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(AppTheme.spaces.small),
                        contentPadding = PaddingValues(vertical = AppTheme.spaces.small),
                    ) {
                        Text(
                            text = when (type) {
                                CredentialType.PASSWORD -> stringResource(R.string.credentials_password)
                                CredentialType.TOKEN -> stringResource(R.string.credentials_token)
                                CredentialType.API_KEY -> stringResource(R.string.credentials_api_key)
                            },
                            color = if (selected) {
                                AppTheme.colors.primary
                            } else {
                                AppTheme.colors.outline
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppTheme.spaces.large))

            when (state.credentialType) {
                CredentialType.PASSWORD -> {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = { onAction(CredentialsAction.SetUsername(it)) },
                        label = { Text(stringResource(R.string.credentials_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(AppTheme.spaces.medium))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { onAction(CredentialsAction.SetPassword(it)) },
                        label = { Text(stringResource(R.string.credentials_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = KeyboardType.Password,
                        ),
                        singleLine = true,
                    )
                }
                CredentialType.TOKEN -> {
                    OutlinedTextField(
                        value = state.token,
                        onValueChange = { onAction(CredentialsAction.SetToken(it)) },
                        label = { Text(stringResource(R.string.credentials_token_value)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true,
                    )
                }
                CredentialType.API_KEY -> {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = { onAction(CredentialsAction.SetApiKey(it)) },
                        label = { Text(stringResource(R.string.credentials_api_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(AppTheme.spaces.medium))
                    OutlinedTextField(
                        value = state.apiSecret,
                        onValueChange = { onAction(CredentialsAction.SetApiSecret(it)) },
                        label = { Text(stringResource(R.string.credentials_api_secret)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true,
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppTheme.spaces.extraLarge))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spaces.medium),
            ) {
                TextButton(
                    text = stringResource(R.string.credentials_cancel),
                    onClick = { onAction(CredentialsAction.DismissDialog) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    text = stringResource(R.string.credentials_save),
                    onClick = { onAction(CredentialsAction.SubmitDialog(state.providerId)) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(AppTheme.spaces.medium))
        }
    }
}

@Composable
private fun ProtocolRadio(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = AppTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(AppTheme.spaces.zero))
    }
}
