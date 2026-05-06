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
internal interface LavaAuthBlobProvider {
    /** AES-256-GCM ciphertext + 16-byte tag. Empty when no real provider is wired. */
    fun getBlob(): ByteArray

    /** 12-byte AES-GCM nonce that decrypts [getBlob]. */
    fun getNonce(): ByteArray

    /** Per-release pepper that participates in HKDF salt derivation. */
    fun getPepper(): ByteArray

    /** HTTP header name to carry the base64-encoded plaintext UUID. */
    fun getFieldName(): String
}
