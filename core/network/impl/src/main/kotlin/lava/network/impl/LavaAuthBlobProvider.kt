package lava.network.impl

/**
 * Source of the encrypted client-auth UUID blob + crypto material.
 *
 * Phase 11 of the Phase-1 plan generates a `LavaAuthGenerated` class that
 * implements this interface (with values pulled from `.env` at build
 * time and AES-GCM-encrypted by the Gradle codegen task). Until that
 * lands, [StubLavaAuthBlobProvider] returns empty bytes — `AuthInterceptor`
 * detects the empty blob and skips header injection, so the auth feature
 * is inert until Phase 11 ships its generator.
 *
 * The constitutional clause in `core/CLAUDE.md` constrains
 * `AuthInterceptor` to be the ONLY consumer of [getBlob]. Reflective
 * access from elsewhere is a constitutional violation.
 */
// `public` (not `internal`) so the build-time-generated
// lava.auth.LavaAuthGenerated class — which lives in the :app
// module's generated source set — can implement it. The interface
// is the contract the build-time codegen output binds against;
// keeping it `internal` would prevent that cross-module
// implementation from compiling.
//
// The constitutional restriction in core/CLAUDE.md (Auth UUID
// memory hygiene) constrains callers, not the visibility: the
// AuthInterceptor is the ONLY production consumer of [getBlob];
// reflective access from elsewhere is a §6.J violation regardless
// of Kotlin visibility.
interface LavaAuthBlobProvider {
    /** AES-256-GCM ciphertext + 16-byte tag. Empty when no real provider is wired. */
    fun getBlob(): ByteArray

    /** 12-byte AES-GCM nonce that decrypts [getBlob]. */
    fun getNonce(): ByteArray

    /** Per-release pepper that participates in HKDF salt derivation. */
    fun getPepper(): ByteArray

    /** HTTP header name to carry the base64-encoded plaintext UUID. */
    fun getFieldName(): String
}
