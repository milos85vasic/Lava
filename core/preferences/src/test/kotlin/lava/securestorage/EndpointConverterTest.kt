package lava.securestorage

import lava.models.settings.Endpoint
import lava.securestorage.model.EndpointConverter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointConverterTest {

    @Test
    fun `convert Proxy to json `() {
        assertEquals(
            "{\"type\":\"Proxy\"}",
            with(EndpointConverter) { Endpoint.Proxy.toJson() },
        )
    }

    @Test
    fun `convert Rutracker to json `() {
        assertEquals(
            "{\"type\":\"Rutracker\"}",
            with(EndpointConverter) { Endpoint.Rutracker.toJson() },
        )
    }

    @Test
    fun `convert Mirror to json `() {
        assertEquals(
            "{\"host\":\"example.com\",\"type\":\"Mirror\"}",
            with(EndpointConverter) { Endpoint.Mirror("example.com").toJson() },
        )
    }

    @Test
    fun `parse Proxy from json`() {
        assertEquals(
            Endpoint.Proxy,
            with(EndpointConverter) { fromJson("{\"type\":\"Proxy\"}") },
        )
    }

    @Test
    fun `parse Rutracker from json`() {
        assertEquals(
            Endpoint.Rutracker,
            with(EndpointConverter) { fromJson("{\"type\":\"Rutracker\"}") },
        )
    }

    @Test
    fun `parse Mirror from json`() {
        assertEquals(
            Endpoint.Mirror("example.com"),
            with(EndpointConverter) { fromJson("{\"host\":\"example.com\",\"type\":\"Mirror\"}") },
        )
    }

    // SP-3 Sixth-Law Challenge tests for the Endpoint.GoApi variant.
    // Primary assertion is on user-visible persisted state (the JSON
    // string written into preferences AND the Endpoint instance read
    // back). Falsifiability rehearsal recorded in the SP-3 commit body.

    // CHALLENGE — Endpoint.GoApi serialises to a JSON document with
    // type=GoApi + the host + the port. Asserts on semantic content
    // not literal byte-equality because JSONObject's key order on the
    // host JVM is implementation-defined (HashMap-backed); on Android
    // it is insertion-order (LinkedHashMap-backed). A literal string
    // assertion would couple the test to the test JVM's iteration
    // order — see the rehearsal in the SP-3 commit body where this
    // same test was initially written as a literal-string assertion
    // and FAILED on the host JVM with "expected …host…type…port… but
    // was …port…host…type…", proving the brittleness. The semantic
    // form here is robust and still falsifiable against real defects
    // (drop the port → Test fails: parsed.has(\"port\") is false).
    @Test
    fun `convert GoApi to json includes host and port`() {
        val json = JSONObject(with(EndpointConverter) { Endpoint.GoApi("192.168.1.100", 8443).toJson() })
        assertEquals("GoApi", json.getString("type"))
        assertEquals("192.168.1.100", json.getString("host"))
        assertEquals(8443, json.getInt("port"))
    }

    // CHALLENGE — non-default port survives serialization.
    @Test
    fun `convert GoApi with non-default port to json preserves port`() {
        val json = JSONObject(with(EndpointConverter) { Endpoint.GoApi("192.168.1.100", 12345).toJson() })
        assertEquals("GoApi", json.getString("type"))
        assertEquals("192.168.1.100", json.getString("host"))
        assertEquals(12345, json.getInt("port"))
    }

    // CHALLENGE — round-trip parse preserves the GoApi variant + port.
    @Test
    fun `parse GoApi from json with explicit port`() {
        assertEquals(
            Endpoint.GoApi("192.168.1.100", 12345),
            with(EndpointConverter) {
                fromJson("{\"host\":\"192.168.1.100\",\"port\":12345,\"type\":\"GoApi\"}")
            },
        )
    }

    // CHALLENGE — back-compat: an older-version JSON record without a
    // `port` field still deserializes (falls back to spec-default 8443).
    @Test
    fun `parse GoApi from json without port falls back to default`() {
        assertEquals(
            Endpoint.GoApi("192.168.1.100", Endpoint.GoApi.DEFAULT_PORT),
            with(EndpointConverter) {
                fromJson("{\"host\":\"192.168.1.100\",\"type\":\"GoApi\"}")
            },
        )
    }

    // CHALLENGE — full round-trip on a non-default port. This is the
    // load-bearing test: a user picks an api-go endpoint at port 9443,
    // the app persists it, the next launch re-hydrates the same
    // endpoint and routes traffic to the right port. Anything less
    // and the user sees connection refused on the next launch.
    @Test
    fun `roundtrip GoApi survives toJson then fromJson with port preserved`() {
        val original = Endpoint.GoApi("10.0.0.42", 9443)
        val json = with(EndpointConverter) { original.toJson() }
        val parsed = with(EndpointConverter) { fromJson(json) }
        assertEquals(original, parsed)
        // Stronger: explicit port equality (data-class equals would
        // still pass if we accidentally normalised both sides to the
        // same wrong value, e.g. both 8443).
        assertEquals(9443, (parsed as Endpoint.GoApi).port)
    }
}
