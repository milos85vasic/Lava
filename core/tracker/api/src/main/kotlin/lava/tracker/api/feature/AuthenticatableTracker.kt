package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult

interface AuthenticatableTracker : TrackerFeature {
    suspend fun login(req: LoginRequest): LoginResult

    suspend fun logout()

    suspend fun checkAuth(): AuthState
}
