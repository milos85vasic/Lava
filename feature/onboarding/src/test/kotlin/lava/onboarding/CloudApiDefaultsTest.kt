package lava.onboarding

import lava.models.settings.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-bluff unit tests for [CloudApiDefaults] — the parser that turns a raw, user-typed
 * (or config-supplied) Cloud API address into an [Endpoint.GoApi], and derives the default
 * endpoint list shown to the user during onboarding.
 *
 * Per §6.J / §6.AB the primary assertion of every case below is on the **user-visible parse
 * outcome** — the concrete `Endpoint.GoApi(host, port)` a user would end up connecting to, or
 * `null` when the input must be rejected so the user is not silently pointed at a broken host.
 *
 * ## FALSIFIABILITY REHEARSAL
 *
 * Deliberate break (Mutation): make `CloudApiDefaults.parse` always return `null`
 *   fun parse(raw: String): Endpoint.GoApi? = null
 *
 * Observed failure: every "valid input" test fails with a clear assertion message, e.g.
 *   `parsesFullHttpsUrlWithPort`:
 *     java.lang.AssertionError:
 *       expected:<GoApi(host=lava.app, port=7777)> but was:<null>
 *   and `defaultsFromSingleEntryProducesOneGoApi`:
 *     java.lang.AssertionError: expected:<1> but was:<0>
 *
 * Reverted: yes — restoring the real parse implementation makes all cases pass again.
 *
 * A second non-crashing rehearsal (proves discrimination, not just null-vs-non-null):
 *   make `parse` ignore the explicit port and always use the default —
 *     return Endpoint.GoApi(host)   // drops the parsed port
 *   Observed failure: `parsesFullHttpsUrlWithPort` fails with
 *     expected:<GoApi(host=lava.app, port=7777)> but was:<GoApi(host=lava.app, port=8443)>
 *   while the bare-host case still passes — confirming the port assertions are load-bearing.
 */
class CloudApiDefaultsTest {

    // CHALLENGE: full https URL with explicit port -> stripped scheme, host + port preserved
    @Test
    fun parsesFullHttpsUrlWithPort() {
        val result = CloudApiDefaults.parse("https://lava.app:7777")
        assertEquals(Endpoint.GoApi("lava.app", 7777), result)
    }

    // CHALLENGE: host:port with no scheme -> same GoApi as the https form
    @Test
    fun parsesHostAndPortWithoutScheme() {
        val result = CloudApiDefaults.parse("lava.app:7777")
        assertEquals(Endpoint.GoApi("lava.app", 7777), result)
    }

    // CHALLENGE: http scheme is stripped (case-insensitive), low port preserved
    @Test
    fun parsesHttpUrlWithPort() {
        val result = CloudApiDefaults.parse("http://h:80")
        assertEquals(Endpoint.GoApi("h", 80), result)
    }

    // CHALLENGE: bare host (no port) -> GoApi on the default port 8443
    @Test
    fun parsesBareHostWithDefaultPort() {
        val result = CloudApiDefaults.parse("lava.app")
        assertEquals(Endpoint.GoApi("lava.app", Endpoint.GoApi.DEFAULT_PORT), result)
    }

    // CHALLENGE: surrounding whitespace is trimmed before parsing
    @Test
    fun parsesTrimsSurroundingWhitespace() {
        val result = CloudApiDefaults.parse("  lava.app:7777  ")
        assertEquals(Endpoint.GoApi("lava.app", 7777), result)
    }

    // CHALLENGE: blank input is rejected
    @Test
    fun parsesBlankInputReturnsNull() {
        assertNull(CloudApiDefaults.parse(""))
    }

    // CHALLENGE: a path component makes the address invalid
    @Test
    fun parsesUrlWithPathReturnsNull() {
        assertNull(CloudApiDefaults.parse("https://lava.app/path"))
    }

    // CHALLENGE: non-numeric port is rejected
    @Test
    fun parsesNonNumericPortReturnsNull() {
        assertNull(CloudApiDefaults.parse("lava.app:notaport"))
    }

    // CHALLENGE: port 0 is out of the valid 1..65535 range
    @Test
    fun parsesZeroPortReturnsNull() {
        assertNull(CloudApiDefaults.parse("lava.app:0"))
    }

    // CHALLENGE: port above 65535 is out of range
    @Test
    fun parsesPortAboveRangeReturnsNull() {
        assertNull(CloudApiDefaults.parse("lava.app:70000"))
    }

    // CHALLENGE: unknown scheme is rejected
    @Test
    fun parsesUnknownSchemeReturnsNull() {
        assertNull(CloudApiDefaults.parse("ftp://lava.app:1"))
    }

    // CHALLENGE: defaultsFrom a single configured entry yields exactly that GoApi
    @Test
    fun defaultsFromSingleEntryProducesOneGoApi() {
        val result = CloudApiDefaults.defaultsFrom("https://lava.app:7777")
        assertEquals(1, result.size)
        assertEquals(Endpoint.GoApi("lava.app", 7777), result[0])
    }

    // CHALLENGE: defaultsFrom blank input yields an empty list
    @Test
    fun defaultsFromBlankProducesEmptyList() {
        val result = CloudApiDefaults.defaultsFrom("")
        assertTrue("expected empty list but was $result", result.isEmpty())
    }

    // CHALLENGE: defaultsFrom a comma-separated pair yields two GoApi entries
    @Test
    fun defaultsFromCommaSeparatedPairProducesTwo() {
        val result = CloudApiDefaults.defaultsFrom("https://a:1,https://b:2")
        assertEquals(2, result.size)
    }
}
