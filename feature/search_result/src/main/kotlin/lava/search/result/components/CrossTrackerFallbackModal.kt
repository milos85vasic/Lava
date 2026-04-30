package lava.search.result.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Modal the search screen renders when LavaTrackerSdk emits
 * SearchOutcome.CrossTrackerFallbackProposed (decision 7a-ii: one-tap
 * opt-out). The user can accept (re-run on the proposed tracker) or
 * cancel (surface the original Failure as a Snackbar).
 *
 * Added in SP-3a Phase 4 (Task 4.17). Lives in :feature:search_result
 * because that's where the modal is consumed; :feature:tracker_settings
 * only owns the settings screen.
 */
@Composable
fun CrossTrackerFallbackModal(
    failedTracker: String,
    proposedTracker: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$failedTracker is unavailable") },
        text = {
            Text(
                "All known mirrors of $failedTracker are unreachable. " +
                    "Try the same search on $proposedTracker?",
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("Try $proposedTracker") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
