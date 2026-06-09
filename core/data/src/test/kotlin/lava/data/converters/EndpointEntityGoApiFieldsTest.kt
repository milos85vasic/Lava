package lava.data.converters

import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LVA-030 (2026-06-09) — Sixth/Third-Law regression coverage for the Room
 * data-converter [Endpoint.toEntity] / [EndpointEntity.toModel] losing the
 * additive [Endpoint.GoApi] fields (`platform`, `storage`, `key`) across a
 * persist/re-hydrate cycle.
 *
 * Forensic anchor (the shipped bug): the preferences-side
 * `lava.securestorage.model.EndpointConverter` (active-endpoint persistence)
 * round-trips `platform`/`storage`/`key` correctly and is fully tested. The
 * Room-side converter in this package — which backs the Connections LIST via
 * `EndpointsRepositoryImpl.add` / `observeAll` — only packed `host:port` into
 * the single `host` column and DROPPED `platform`, `storage`, and `key`.
 *
 * User-visible impact (Sixth-Law clause 3):
 *   - An on-device Lava-API endpoint added through discovery/onboarding carries
 *     a per-instance `key` used by `AuthInterceptor` to send the correct
 *     `Lava-Auth` value. After the endpoint is written to the list and read
 *     back, `key` was null → every request to that endpoint 401s.
 *   - The `platform=android` attribute that renders the distinct "Android
 *     device" label in the list was lost → the row mis-renders as a plain
 *     host/server instance.
 *
 * The fix MUST NOT introduce a Room migration (the `Endpoint` table is still
 * three columns: id/type/host) — it extends the existing `host`-packing scheme
 * the file already uses for `:port`, staying byte-identical for endpoints that
 * carry none of the additive fields (back-compat with every persisted row).
 *
 * FALSIFIABILITY REHEARSAL (per §6.J / Sixth Law clause 2): with the fix
 * reverted (toEntity packs only `host:port`, toModel reads only host/port), the
 * `key`/`platform`/`storage` assertions below fail with
 * `expected:<abc-key-123> but was:<null>`, proving the persist path is
 * load-bearing for the on-device auth + label. Recorded in the commit body.
 */
class EndpointEntityGoApiFieldsTest {

    @Test
    fun `GoApi with key round-trips through Room converter`() {
        val original = Endpoint.GoApi(host = "192.168.0.213", port = 8443, key = "abc-key-123")
        val entity = original.toEntity()
        val parsed = entity.toModel() as Endpoint.GoApi
        assertEquals("abc-key-123", parsed.key)
        assertEquals("192.168.0.213", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals(original, parsed)
    }

    @Test
    fun `GoApi with platform and storage round-trips through Room converter`() {
        val original = Endpoint.GoApi(
            host = "10.0.0.42",
            port = 9443,
            platform = "android",
            storage = "sqlite",
        )
        val entity = original.toEntity()
        val parsed = entity.toModel() as Endpoint.GoApi
        assertEquals("android", parsed.platform)
        assertEquals("sqlite", parsed.storage)
        assertEquals(9443, parsed.port)
        assertEquals(original, parsed)
    }

    @Test
    fun `GoApi with all additive fields round-trips through Room converter`() {
        val original = Endpoint.GoApi(
            host = "10.0.0.42",
            port = 8443,
            platform = "android",
            storage = "sqlite",
            key = "k-with-special=&#chars",
        )
        val parsed = original.toEntity().toModel() as Endpoint.GoApi
        assertEquals(original, parsed)
        assertEquals("k-with-special=&#chars", parsed.key)
    }

    // Back-compat: a plain host/server GoApi (no additive fields) MUST keep
    // packing as the bare `host:port` string so already-persisted rows AND the
    // existing id collision-avoidance semantics are byte-identical.
    @Test
    fun `GoApi with no additive fields stays bare host colon port for back-compat`() {
        val original = Endpoint.GoApi(host = "192.168.0.213", port = 8443)
        val entity = original.toEntity()
        assertEquals("192.168.0.213:8443", entity.host)
        assertEquals("GoApi(192.168.0.213:8443)", entity.id)
        assertEquals(original, entity.toModel())
    }

    // Back-compat: a legacy persisted entity written by the OLD converter
    // (bare `host:port`, no additive segment) MUST still parse with null
    // additive fields.
    @Test
    fun `legacy bare host colon port entity parses with null additive fields`() {
        val legacy = EndpointEntity(
            id = "GoApi(192.168.0.213:8443)",
            type = "GoApi",
            host = "192.168.0.213:8443",
        )
        val parsed = legacy.toModel() as Endpoint.GoApi
        assertEquals("192.168.0.213", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals(null, parsed.platform)
        assertEquals(null, parsed.storage)
        assertEquals(null, parsed.key)
    }

    // The persisted id MUST still distinguish two endpoints at the same
    // host:port that differ only by key (e.g. two on-device instances),
    // otherwise Room's REPLACE collapses them and one key is silently lost.
    @Test
    fun `GoApi id distinguishes endpoints that differ only by key`() {
        val a = Endpoint.GoApi(host = "10.0.0.42", port = 8443, key = "key-A")
        val b = Endpoint.GoApi(host = "10.0.0.42", port = 8443, key = "key-B")
        assertTrue(a.toEntity().id != b.toEntity().id)
    }
}
