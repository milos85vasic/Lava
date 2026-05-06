package lava.network.impl

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inert default implementation of [LavaAuthBlobProvider] used until
 * Phase 11's build-time codegen ships its real generator.
 *
 * Returns empty bytes for the blob — [AuthInterceptor] short-circuits
 * on empty blob, so the auth feature does not engage. This is the
 * fail-closed default: requests proceed WITHOUT a `Lava-Auth` header
 * (the API will respond 401 in production once Phase 11 lands and the
 * server-side enforcement engages), preserving §6.J posture (no silent
 * fake-success).
 */
@Singleton
internal class StubLavaAuthBlobProvider @Inject constructor() : LavaAuthBlobProvider {
    override fun getBlob(): ByteArray = ByteArray(0)
    override fun getNonce(): ByteArray = ByteArray(0)
    override fun getPepper(): ByteArray = ByteArray(0)
    override fun getFieldName(): String = ""
}
