package lava.provider.config

import androidx.compose.ui.graphics.Color
import lava.credentials.model.CredentialsEntry
import lava.domain.usecase.ProbeResult
import lava.tracker.api.TrackerDescriptor

data class ProviderConfigState(
    val providerId: String = "",
    val descriptor: TrackerDescriptor? = null,
    val displayName: String = "",
    val color: Color = Color.Gray,
    val syncEnabled: Boolean = false,
    val anonymous: Boolean = false,
    val boundCredential: CredentialsEntry? = null,
    val availableCredentials: List<CredentialsEntry> = emptyList(),
    val descriptorMirrors: List<String> = emptyList(),
    val userMirrors: List<String> = emptyList(),
    val probeResults: Map<String, ProbeResult> = emptyMap(),
    val showAssignSheet: Boolean = false,
    val showCloneDialog: Boolean = false,
)
