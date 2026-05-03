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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = if (state.isEditing) "Edit Credential" else "Create Credential",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = state.label,
                onValueChange = { onAction(CredentialsAction.SetLabel(it)) },
                label = { Text("Label / Name") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Credential Type",
                style = MaterialTheme.typography.labelLarge,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CredentialType.entries.forEach { type ->
                    val selected = state.credentialType == type
                    OutlinedButton(
                        onClick = { onAction(CredentialsAction.SetCredentialType(type)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text(
                            text = when (type) {
                                CredentialType.PASSWORD -> "Password"
                                CredentialType.TOKEN -> "Token"
                                CredentialType.API_KEY -> "API Key"
                            },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (state.credentialType) {
                CredentialType.PASSWORD -> {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = { onAction(CredentialsAction.SetUsername(it)) },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { onAction(CredentialsAction.SetPassword(it)) },
                        label = { Text("Password") },
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
                        label = { Text("Token Value") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true,
                    )
                }
                CredentialType.API_KEY -> {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = { onAction(CredentialsAction.SetApiKey(it)) },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.apiSecret,
                        onValueChange = { onAction(CredentialsAction.SetApiSecret(it)) },
                        label = { Text("Secret (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(CredentialsAction.DismissDialog) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onAction(CredentialsAction.SubmitDialog(state.providerId)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
