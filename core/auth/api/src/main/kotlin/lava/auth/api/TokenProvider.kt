package lava.auth.api

interface TokenProvider {
    suspend fun getToken(): String
    suspend fun refreshToken(): Boolean

    /**
     * Persist a provider-obtained session token so a subsequent [getToken]
     * returns it.
     *
     * LAYER 2 fix (2026-07-02, token-store mismatch): the multi-tracker SDK
     * login path (`RuTrackerAuth.login` -> `LoginUseCase`) obtains the rutracker
     * session cookie but historically never wrote it to the store [getToken]
     * reads (only the legacy single-tracker `AuthServiceImpl.login` did).
     * `RuTrackerSearch` reads that same store; an empty token makes
     * `WithTokenVerificationUseCase` throw `Unauthorized` BEFORE any
     * `tracker.php` request, so a genuinely-logged-in SDK session could not
     * search. This seam lets the SDK login path bridge the obtained token into
     * the store `getToken()` reads.
     *
     * The body defaults to a no-op so this stays an additive interface change.
     * PRODUCTION implementations (`AuthServiceImpl`) MUST override it to actually
     * persist, and behaviorally-equivalent test fakes that exercise the
     * login -> search seam MUST override it too (§6.J Third Law — fakes must be
     * behaviorally equivalent).
     */
    suspend fun persistProviderToken(token: String) {
        // Default no-op; see KDoc. Production AuthServiceImpl overrides this.
    }
}
