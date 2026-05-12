package lava.credentials.manager

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialType
import lava.credentials.model.CredentialsEntry
import lava.credentials.model.type
import lava.designsystem.component.AppBar
import lava.designsystem.component.BackButton
import lava.designsystem.component.Body
import lava.designsystem.component.BodyLarge
import lava.designsystem.component.BodySmall
import lava.designsystem.component.Dialog
import lava.designsystem.component.Icon
import lava.designsystem.component.IconButton
import lava.designsystem.component.OutlinedTextField
import lava.designsystem.component.Scaffold
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.component.TextButton
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.navigation.viewModel
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

@Composable
fun CredentialsManagerScreen(
    onBack: () -> Unit = {},
    viewModel: CredentialsManagerViewModel = viewModel(),
) {
    val context = LocalContext.current
    viewModel.collectSideEffect { effect ->
        when (effect) {
            is CredentialsManagerSideEffect.ShowToast ->
                Toast.makeText(context, effect.msg, Toast.LENGTH_SHORT).show()
        }
    }
    val state by viewModel.collectAsState()
    CredentialsManagerScreen(state, viewModel::perform, onBack)
}

@Composable
private fun CredentialsManagerScreen(
    state: CredentialsManagerState,
    onAction: (CredentialsManagerAction) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = { appBarState ->
        AppBar(
            navigationIcon = { BackButton(onClick = onBack) },
            title = { Text("Credentials") },
            appBarState = appBarState,
        )
    },
    floatingActionButton = {
        if (state.unlocked) {
            FloatingActionButton(
                onClick = { onAction(CredentialsManagerAction.AddNew) },
                containerColor = AppTheme.colors.primaryContainer,
                contentColor = AppTheme.colors.onPrimaryContainer,
            ) {
                Icon(icon = LavaIcons.Add, contentDescription = "Add credential")
            }
        }
    },
) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        when {
            state.needsFirstTimeSetup -> FirstTimeSetupDialog(
                onCreate = { pw -> onAction(CredentialsManagerAction.FirstTimeSetup(pw)) },
            )
            !state.unlocked -> UnlockDialog(
                error = state.unlockError,
                onUnlock = { pw -> onAction(CredentialsManagerAction.Unlock(pw)) },
            )
            else -> EntriesList(
                entries = state.entries,
                onEdit = { id -> onAction(CredentialsManagerAction.Edit(id)) },
                onDelete = { id -> onAction(CredentialsManagerAction.Delete(id)) },
            )
        }

        state.editing?.let { editing ->
            EditDialog(
                entry = editing,
                onSave = { name, secret ->
                    onAction(CredentialsManagerAction.Save(name, secret))
                },
                onCancel = { onAction(CredentialsManagerAction.DismissEdit) },
            )
        }
    }
}

@Composable
private fun FirstTimeSetupDialog(onCreate: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = pw.isNotEmpty() && confirm.isNotEmpty() && pw != confirm
    Dialog(
        title = { Text("Create passphrase") },
        text = {
            Column {
                BodySmall(
                    text = "Choose a passphrase to encrypt your credentials. You will need it to unlock this screen.",
                    color = AppTheme.colors.outline,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppTheme.spaces.medium),
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppTheme.spaces.small),
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch,
                    singleLine = true,
                )
                if (mismatch) {
                    BodySmall(
                        modifier = Modifier.padding(top = AppTheme.spaces.small),
                        text = "Passphrases do not match",
                        color = AppTheme.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = "Create",
                enabled = pw.isNotEmpty() && pw == confirm,
                onClick = { onCreate(pw) },
            )
        },
        onDismissRequest = {},
    )
}

@Composable
private fun UnlockDialog(error: String?, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Dialog(
        title = { Text("Unlock credentials") },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    singleLine = true,
                )
                if (error != null) {
                    BodySmall(
                        modifier = Modifier.padding(top = AppTheme.spaces.small),
                        text = error,
                        color = AppTheme.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = "Unlock",
                enabled = pw.isNotEmpty(),
                onClick = { onUnlock(pw) },
            )
        },
        onDismissRequest = {},
    )
}

@Composable
private fun EntriesList(
    entries: List<CredentialsEntry>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon = LavaIcons.Password,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AppTheme.colors.outline,
            )
            BodyLarge(
                modifier = Modifier.padding(top = AppTheme.spaces.medium),
                text = "No credentials yet",
                color = AppTheme.colors.onSurface,
            )
            BodySmall(
                modifier = Modifier.padding(top = AppTheme.spaces.small),
                text = "Tap + to add one",
                color = AppTheme.colors.outline,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppTheme.spaces.medium),
    ) {
        items(entries, key = CredentialsEntry::id) { entry ->
            EntryRow(entry = entry, onClick = { onEdit(entry.id) }, onDelete = { onDelete(entry.id) })
        }
    }
}

@Composable
private fun EntryRow(
    entry: CredentialsEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spaces.small),
        onClick = onClick,
        shape = AppTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppTheme.spaces.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BodyLarge(text = entry.displayName.ifEmpty { "(unnamed)" })
                BodySmall(text = entry.type.label(), color = AppTheme.colors.outline)
            }
            IconButton(
                icon = LavaIcons.Delete,
                contentDescription = "Delete",
                tint = AppTheme.colors.error,
                onClick = { confirmDelete = true },
            )
        }
    }
    if (confirmDelete) {
        Dialog(
            title = { Text("Delete?") },
            text = { Text("Delete \"${entry.displayName.ifEmpty { "this entry" }}\"?") },
            confirmButton = {
                TextButton(
                    text = "Delete",
                    color = AppTheme.colors.error,
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                )
            },
            dismissButton = {
                TextButton(text = "Cancel", onClick = { confirmDelete = false })
            },
            onDismissRequest = { confirmDelete = false },
        )
    }
}

@Composable
private fun EditDialog(
    entry: CredentialsEntry,
    onSave: (String, CredentialSecret) -> Unit,
    onCancel: () -> Unit,
) {
    var displayName by remember(entry.id) { mutableStateOf(entry.displayName) }
    var type by remember(entry.id) { mutableStateOf(entry.type) }
    var username by remember(entry.id) {
        mutableStateOf((entry.secret as? CredentialSecret.UsernamePassword)?.username.orEmpty())
    }
    var password by remember(entry.id) {
        mutableStateOf((entry.secret as? CredentialSecret.UsernamePassword)?.password.orEmpty())
    }
    var apiKey by remember(entry.id) {
        mutableStateOf((entry.secret as? CredentialSecret.ApiKey)?.key.orEmpty())
    }
    var bearer by remember(entry.id) {
        mutableStateOf((entry.secret as? CredentialSecret.BearerToken)?.token.orEmpty())
    }
    var cookie by remember(entry.id) {
        mutableStateOf((entry.secret as? CredentialSecret.CookieSession)?.cookie.orEmpty())
    }

    Dialog(
        title = { Text(if (entry.displayName.isEmpty()) "Add credential" else "Edit credential") },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
                Column(modifier = Modifier.padding(top = AppTheme.spaces.small)) {
                    CredentialType.entries.forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = type == t, onClick = { type = t })
                            Body(text = t.label())
                        }
                    }
                }
                when (type) {
                    CredentialType.USERNAME_PASSWORD -> {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppTheme.spaces.small),
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppTheme.spaces.small),
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                    CredentialType.API_KEY -> OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppTheme.spaces.small),
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    CredentialType.BEARER_TOKEN -> OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppTheme.spaces.small),
                        value = bearer,
                        onValueChange = { bearer = it },
                        label = { Text("Bearer Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    CredentialType.COOKIE_SESSION -> OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppTheme.spaces.small),
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text("Cookie") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = "Save",
                onClick = {
                    val secret: CredentialSecret = when (type) {
                        CredentialType.USERNAME_PASSWORD -> CredentialSecret.UsernamePassword(username, password)
                        CredentialType.API_KEY -> CredentialSecret.ApiKey(apiKey)
                        CredentialType.BEARER_TOKEN -> CredentialSecret.BearerToken(bearer)
                        CredentialType.COOKIE_SESSION -> CredentialSecret.CookieSession(cookie)
                    }
                    onSave(displayName, secret)
                },
            )
        },
        dismissButton = { TextButton(text = "Cancel", onClick = onCancel) },
        onDismissRequest = onCancel,
    )
}

private fun CredentialType.label(): String = when (this) {
    CredentialType.USERNAME_PASSWORD -> "Username / Password"
    CredentialType.API_KEY -> "API Key"
    CredentialType.BEARER_TOKEN -> "Bearer Token"
    CredentialType.COOKIE_SESSION -> "Cookie"
}
