package lava.data.converters

import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SP-3.3 (2026-04-29) Sixth-Law tests for [Endpoint.toEntity] and
 * [EndpointEntity.toModel] — the load-bearing converters between the
 * Room row shape and the typed [Endpoint] sealed interface. Bugs here
 * surface as "Connections list shows duplicate / missing / broken
 * entries", which is exactly what the user reported on 2026-04-29.
 *
 * Sixth-Law clauses 1, 3:
 *   1. The same converters are invoked from
 *      `EndpointsRepositoryImpl.add` / `observeAll` in production.
 *   3. The primary assertion is on the persisted entity (what Room
 *      writes to disk) and the typed model (what the UI renders), both
 *      of which are user-visible — the user sees a row in the list.
 *
 * Falsifiability rehearsals recorded inline per test as `// MUTATION`.
 */
class EndpointEntityConverterTest {

    // CHALLENGE — Round-trip Rutracker. Persisted form has a stable id
    // ("Rutracker") so duplicate inserts collapse via Room's
    // OnConflictStrategy.REPLACE; the user can never end up with two
    // "Main" rows by re-seeding.
    //
    // MUTATION: returning `id = host` instead of "Rutracker" fails the
    // first assertion (id="rutracker.org" not "Rutracker").
    @Test
    fun `Rutracker round-trips through entity converter`() {
        val entity = Endpoint.Rutracker.toEntity()
        assertEquals("Rutracker", entity.id)
        assertEquals("Rutracker", entity.type)
        assertEquals("rutracker.org", entity.host)
        assertEquals(Endpoint.Rutracker, entity.toModel())
    }

    // CHALLENGE — Mirror with bare host round-trips. The id encodes
    // the host so two distinct mirrors don't collide on insert.
    //
    // MUTATION: hard-coding id="Mirror" makes two Mirrors at different
    // hosts collide in Room and one silently wins.
    @Test
    fun `Mirror with bare host round-trips`() {
        val original = Endpoint.Mirror("192.168.0.213")
        val entity = original.toEntity()
        assertEquals("Mirror(192.168.0.213)", entity.id)
        assertEquals("Mirror", entity.type)
        assertEquals("192.168.0.213", entity.host)
        assertEquals(original, entity.toModel())
    }

    // CHALLENGE — Mirror with embedded port survives a round-trip.
    // SP-3.3 leaves this readable for back-compat (legacy rows from
    // pre-SP-3 LAN discovery). The MIGRATION_5_6 path deletes such
    // rows so they should not exist in steady state — but if one is
    // re-added by hand it must still parse to a usable Mirror.
    //
    // MUTATION: stripping the colon-delimited port at conversion time
    // would change the persisted shape, the test catches it via the
    // entity.host assertion.
    @Test
    fun `Mirror with embedded port survives round-trip`() {
        val original = Endpoint.Mirror("192.168.0.213:9090")
        val entity = original.toEntity()
        assertEquals("Mirror(192.168.0.213:9090)", entity.id)
        assertEquals("192.168.0.213:9090", entity.host)
        assertEquals(original, entity.toModel())
    }

    // CHALLENGE — GoApi qualifies its id with `:port` so a host with
    // two different ports doesn't collapse into one row.
    //
    // MUTATION: dropping `:port` from the id produces collisions when
    // a user has both 192.168.0.213:8443 and 192.168.0.213:9443
    // configured.
    @Test
    fun `GoApi round-trips with port encoded in entity host`() {
        val original = Endpoint.GoApi("192.168.0.213", 8443)
        val entity = original.toEntity()
        assertEquals("GoApi(192.168.0.213:8443)", entity.id)
        assertEquals("GoApi", entity.type)
        assertEquals("192.168.0.213:8443", entity.host)
        assertEquals(original, entity.toModel())
    }

    // CHALLENGE — non-default GoApi port survives.
    @Test
    fun `GoApi non-default port round-trips`() {
        val original = Endpoint.GoApi("10.0.0.42", 9443)
        val entity = original.toEntity()
        assertEquals("GoApi(10.0.0.42:9443)", entity.id)
        assertEquals("10.0.0.42:9443", entity.host)
        assertEquals(original, entity.toModel())
    }

    // CHALLENGE — pre-SP-3.2 rows where `type='Proxy'` MUST migrate
    // forward on read to `Endpoint.Rutracker`. Returning null silently
    // would leak a no-longer-renderable entry into the Connections
    // list. This is the read-side complement of MIGRATION_5_6 (which
    // deletes such rows on next app launch).
    //
    // MUTATION: returning `null` for `type=Proxy` fails the assertion.
    @Test
    fun `legacy Proxy entity migrates forward to Rutracker on read`() {
        val legacy = EndpointEntity(id = "Proxy", type = "Proxy", host = "lava-app.tech")
        assertEquals(Endpoint.Rutracker, legacy.toModel())
    }

    // CHALLENGE — unknown type is mapped to null so the
    // `mapNotNull(EndpointEntity::toModel)` filter in
    // EndpointsRepositoryImpl.observeAll silently drops it (rather
    // than crashing with an exhaustive-when failure).
    //
    // MUTATION: throwing IllegalStateException for unknown type would
    // crash the Connections screen on any future shape we don't know
    // about; the test catches that by asserting null.
    @Test
    fun `unknown type maps to null`() {
        val unknown = EndpointEntity(id = "Whatever", type = "Whatever", host = "x.example")
        assertNull(unknown.toModel())
    }
}
