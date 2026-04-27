package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import lava.auth.api.AuthService
import lava.models.auth.AuthState
import javax.inject.Inject

interface ObserveAuthStateUseCase : () -> Flow<AuthState>

class ObserveAuthStateUseCaseImpl @Inject constructor(
    private val authService: AuthService,
) : ObserveAuthStateUseCase {
    override operator fun invoke(): Flow<AuthState> {
        return authService.observeAuthState()
            .distinctUntilChanged()
    }
}
