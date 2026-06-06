package lava.securestorage

import lava.securestorage.model.EndpointConverter
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [EndpointConverter.fromJson]'s defensive branches — the paths a
 * corrupted or forward-incompatible persisted record takes. These are the
 * branches that decide whether a user with a damaged preferences row crashes on
 * launch or degrades gracefully to "no endpoint" (which the UI recovers from by
 * seeding a default). The happy/round-trip paths live in [EndpointConverterTest];
 * this file pins the failure-handling contract that `runCatching { … }.getOrNull()`
 * + the `else -> null` arm provide.
 *
 * Anti-Bluff posture (§6.J): the SUT is the production [EndpointConverter]. The
 * primary assertion is on the user-recoverable null outcome.
 *
 * Bluff-Audit recorded in the commit body.
 */
class EndpointConverterMalformedTest {

    @Test
    fun `fromJson returns null on syntactically invalid json`() {
        assertNull(with(EndpointConverter) { fromJson("this is not json") })
    }

    @Test
    fun `fromJson returns null on empty input`() {
        assertNull(with(EndpointConverter) { fromJson("") })
    }

    @Test
    fun `fromJson returns null when the type discriminator is absent`() {
        // No "type" key → getString throws → runCatching swallows → null.
        assertNull(with(EndpointConverter) { fromJson("""{"host":"example.com"}""") })
    }

    @Test
    fun `fromJson returns null for an unrecognised type discriminator`() {
        // The else-arm: a future/unknown endpoint type must not crash an older
        // client; it degrades to null so the default-seeding path recovers.
        assertNull(with(EndpointConverter) { fromJson("""{"type":"QuantumLink"}""") })
    }

    @Test
    fun `fromJson returns null when a Mirror record is missing its host`() {
        // type=Mirror but no "host" → getString(HostKey) throws → null.
        assertNull(with(EndpointConverter) { fromJson("""{"type":"Mirror"}""") })
    }

    @Test
    fun `fromJson returns null when a GoApi record is missing its host`() {
        // type=GoApi but no "host" → getString(HostKey) throws → null.
        assertNull(with(EndpointConverter) { fromJson("""{"type":"GoApi","port":8443}""") })
    }
}
