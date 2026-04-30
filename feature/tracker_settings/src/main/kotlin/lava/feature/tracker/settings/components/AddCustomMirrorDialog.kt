package lava.feature.tracker.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lava.sdk.api.Protocol

/**
 * Dialog the user opens via the "Add custom mirror" button. Validates
 * the URL roughly (must start with http:// or https://) and emits the
 * tuple (url, priority, protocol) on confirm. Added in SP-3a Phase 4
 * (Task 4.15).
 */
@Composable
fun AddCustomMirrorDialog(
    targetTrackerId: String,
    onConfirm: (url: String, priority: Int, protocol: Protocol) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(50) }
    var protocol by remember { mutableStateOf(Protocol.HTTPS) }
    val isValid = url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add mirror for $targetTrackerId") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://my-mirror.example/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Priority: $priority", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = priority.toFloat(),
                    onValueChange = { priority = it.toInt() },
                    valueRange = 0f..100f,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Protocol", style = MaterialTheme.typography.labelMedium)
                Row {
                    ProtocolRadio(label = "HTTPS", selected = protocol == Protocol.HTTPS) { protocol = Protocol.HTTPS }
                    ProtocolRadio(label = "HTTP", selected = protocol == Protocol.HTTP) { protocol = Protocol.HTTP }
                    ProtocolRadio(label = "HTTP3", selected = protocol == Protocol.HTTP3) { protocol = Protocol.HTTP3 }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(url, priority, protocol) },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProtocolRadio(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(0.dp))
    }
}
