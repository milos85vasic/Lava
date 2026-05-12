package lava.credentials.manager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import lava.credentials.CredentialsEntryRepository
import lava.credentials.PassphraseManager
import lava.credentials.model.CredentialSecret
import lava.credentials.model.CredentialsEntry
import lava.credentials.session.CredentialsKeyHolder
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CredentialsManagerViewModel @Inject constructor(
    private val repo: CredentialsEntryRepository,
    private val passphrase: PassphraseManager,
    private val keyHolder: CredentialsKeyHolder,
) : ContainerHost<CredentialsManagerState, CredentialsManagerSideEffect>, ViewModel() {

    override val container = container<CredentialsManagerState, CredentialsManagerSideEffect>(
        initialState = CredentialsManagerState(
            needsFirstTimeSetup = !passphrase.isInitialized(),
            unlocked = keyHolder.isUnlocked(),
        ),
        onCreate = {
            if (keyHolder.isUnlocked()) {
                startObservingEntries()
            }
        },
    )

    fun perform(action: CredentialsManagerAction) = intent {
        when (action) {
            is CredentialsManagerAction.FirstTimeSetup -> {
                passphrase.firstTimeSetup(action.passphrase)
                reduce { state.copy(needsFirstTimeSetup = false, unlocked = true, unlockError = null) }
                startObservingEntries()
            }
            is CredentialsManagerAction.Unlock -> {
                if (passphrase.unlock(action.passphrase)) {
                    reduce { state.copy(unlocked = true, unlockError = null) }
                    startObservingEntries()
                } else {
                    reduce { state.copy(unlockError = "Wrong passphrase") }
                }
            }
            CredentialsManagerAction.AddNew -> reduce { state.copy(editing = empty()) }
            is CredentialsManagerAction.Edit -> {
                val existing = repo.get(action.id) ?: return@intent
                reduce { state.copy(editing = existing) }
            }
            is CredentialsManagerAction.Save -> {
                val now = System.currentTimeMillis()
                val current = state.editing ?: empty()
                val saved = current.copy(
                    displayName = action.displayName,
                    secret = action.secret,
                    updatedAtUtc = now,
                )
                repo.upsert(saved)
                reduce { state.copy(editing = null) }
                postSideEffect(CredentialsManagerSideEffect.ShowToast("Saved"))
            }
            is CredentialsManagerAction.Delete -> {
                repo.delete(action.id)
                postSideEffect(CredentialsManagerSideEffect.ShowToast("Deleted"))
            }
            CredentialsManagerAction.DismissEdit -> reduce { state.copy(editing = null) }
        }
    }

    private fun org.orbitmvi.orbit.syntax.simple.SimpleSyntax<CredentialsManagerState, CredentialsManagerSideEffect>.startObservingEntries() {
        viewModelScope.launch {
            repo.observe().collect { entries -> reduce { state.copy(entries = entries) } }
        }
    }

    private fun empty() = CredentialsEntry(
        id = UUID.randomUUID().toString(),
        displayName = "",
        secret = CredentialSecret.UsernamePassword("", ""),
        createdAtUtc = System.currentTimeMillis(),
        updatedAtUtc = System.currentTimeMillis(),
    )
}
