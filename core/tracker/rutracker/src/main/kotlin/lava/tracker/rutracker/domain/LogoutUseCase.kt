package lava.tracker.rutracker.domain

import lava.auth.api.TokenProvider

/**
 * Minimal stub for the rutracker logout flow. RuTracker logout is a
 * server-side action that simply discards the cookie; the auth token store
 * rotation lives at the AuthService layer (which holds the cookie store).
 *
 * Section F may upgrade this to issue an explicit logout HTTP call when the
 * AuthService logout flow is migrated.
 */
class LogoutUseCase(private val tokenProvider: TokenProvider) {
    suspend operator fun invoke() {
        // No-op: cookie discard happens at the AuthService level.
    }
}
