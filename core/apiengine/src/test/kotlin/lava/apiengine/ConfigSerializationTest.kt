package lava.apiengine

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cheap JVM regression test for the Phase E on-device serialization defect.
 *
 * The on-device `NativeApiEngine.start()` calls
 * `json.encodeToString(config.toDto())` on the `@Serializable ConfigDto`, and
 * `status()` decodes a `@Serializable StatusDto`. Before the
 * `lava.kotlin.serialization` compiler plugin was applied to `:core:apiengine`,
 * NO serializers were generated, so both calls threw at runtime
 * (`Serializer for class 'ConfigDto' is not found ...`) and the landing screen
 * never reached "Running" (Phase E Challenge02/03/04 timed out).
 *
 * This test exercises the EXACT production serializer path — the production
 * `Json` instance (`NativeApiEngine.defaultJson`), the production `toDto()`
 * mapper, and `StatusDto` + `toApiStatus()`. It FAILS (serializer-not-found)
 * when the plugin is absent and PASSES once the plugin is applied — that IS
 * the §6.T.1 reproduction of the on-device defect at the JVM level.
 */
class ConfigSerializationTest {

    private val json = NativeApiEngine.defaultJson

    @Test
    fun configDto_serializesWithAllKeysTheGoEmbedReads() {
        val config =
            ApiConfig(
                bindAddr = "0.0.0.0",
                port = 8443,
                sqlitePath = "/data/data/app/files/lava.db",
                authSharedKey = "base64-uuid-blob",
                authFieldName = "Lava-Auth",
            )

        // This is the production call path that threw before the plugin landed.
        val wire = json.encodeToString(config.toDto())

        // The Go embed (internal/mobile.startConfig) reads these exact JSON keys.
        assertTrue("missing bindAddr: $wire", wire.contains("\"bindAddr\":\"0.0.0.0\""))
        assertTrue("missing port: $wire", wire.contains("\"port\":8443"))
        assertTrue(
            "missing sqlitePath: $wire",
            wire.contains("\"sqlitePath\":\"/data/data/app/files/lava.db\""),
        )
        assertTrue(
            "missing authSharedKey: $wire",
            wire.contains("\"authSharedKey\":\"base64-uuid-blob\""),
        )
        assertTrue(
            "missing authFieldName: $wire",
            wire.contains("\"authFieldName\":\"Lava-Auth\""),
        )
    }

    @Test
    fun configDto_nullAuthSharedKey_serializesAsEmptyStringForGenerateContract() {
        // The Go side treats authSharedKey == "" as "generate one"; null must
        // serialize to "" (matching NativeApiEngine.toDto's `.orEmpty()`).
        val config = ApiConfig(sqlitePath = "/tmp/lava.db", authSharedKey = null)

        val wire = json.encodeToString(config.toDto())

        assertTrue("null key must serialize as \"\": $wire", wire.contains("\"authSharedKey\":\"\""))
    }

    @Test
    fun statusDto_runningJson_parsesIntoApiStatus() {
        // Mirrors the JSON `internal/mobile.Status()` returns while running;
        // this is the production decode path used by NativeApiEngine.status().
        val raw =
            """
            {
              "state": "running",
              "scheme": "https",
              "bindAddr": "0.0.0.0",
              "port": 8443,
              "requestCount": 7,
              "backend": "sqlite",
              "version": "2.3.22",
              "authEnabled": true,
              "authFieldName": "Lava-Auth",
              "authKey": "live-key"
            }
            """.trimIndent()

        val status = json.decodeFromString<StatusDto>(raw).toApiStatus()

        assertEquals("running", status.state)
        assertEquals("0.0.0.0", status.bindAddr)
        assertEquals(8443, status.port)
        assertEquals(7L, status.requestCount)
        assertEquals("sqlite", status.backend)
        assertEquals("2.3.22", status.version)
        assertEquals("https", status.scheme)
        assertTrue(status.authEnabled)
        assertEquals("Lava-Auth", status.authFieldName)
        assertEquals("live-key", status.authKey)
    }

    @Test
    fun statusDto_stoppedJson_defaultsToStoppedZeroState() {
        // The Go side omits optional fields when stopped (omitempty); the
        // @Serializable defaults must fill the stopped-state zero values.
        val status = json.decodeFromString<StatusDto>("""{"state":"stopped"}""").toApiStatus()

        assertEquals("stopped", status.state)
        assertEquals("", status.bindAddr)
        assertEquals(0, status.port)
        assertEquals(0L, status.requestCount)
        assertFalse(status.authEnabled)
        assertEquals(null, status.authKey)
    }
}
