package lava.network.dto

import lava.network.serialization.JsonFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-Bluff: primary assertion is on the PARSED VALUES a real consumer reads
 * (id / kind / indexer / capabilities / authType / baseUrls / supportsAnonymous),
 * not "decode did not throw". Uses a captured `GET /v1/providers` payload with
 * one native and one jackett entry — the exact shape the API serves.
 */
class ProviderDescriptorDtoTest {

    private val json = JsonFactory.create()

    private val capturedPayload = """
        {
          "providers": [
            {
              "id": "rutracker",
              "displayName": "RuTracker.org",
              "kind": "native",
              "capabilities": ["SEARCH","BROWSE","FORUM","TOPIC","COMMENTS","FAVORITES","TORRENT_DOWNLOAD","MAGNET_LINK","AUTH_REQUIRED","CAPTCHA_LOGIN"],
              "authType": "CAPTCHA_LOGIN",
              "encoding": "Windows-1251",
              "baseUrls": ["https://rutracker.org","https://rutracker.net"],
              "supportsAnonymous": false
            },
            {
              "id": "1337x",
              "displayName": "1337x",
              "kind": "jackett",
              "indexer": "1337x",
              "capabilities": ["SEARCH","MAGNET_LINK","TORRENT_DOWNLOAD"],
              "authType": "NONE",
              "encoding": "UTF-8",
              "baseUrls": [],
              "supportsAnonymous": true
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesNativeAndJackettEntries() {
        val response = json.decodeFromString(ProvidersResponseDto.serializer(), capturedPayload)

        assertEquals(2, response.providers.size)

        val native = response.providers[0]
        assertEquals("rutracker", native.id)
        assertEquals("RuTracker.org", native.displayName)
        assertEquals("native", native.kind)
        assertNull(native.indexer)
        assertEquals("CAPTCHA_LOGIN", native.authType)
        assertEquals("Windows-1251", native.encoding)
        assertTrue(native.capabilities.contains("SEARCH"))
        assertTrue(native.capabilities.contains("CAPTCHA_LOGIN"))
        assertEquals(listOf("https://rutracker.org", "https://rutracker.net"), native.baseUrls)
        assertFalse(native.supportsAnonymous)

        val jackett = response.providers[1]
        assertEquals("1337x", jackett.id)
        assertEquals("jackett", jackett.kind)
        assertEquals("1337x", jackett.indexer)
        assertEquals("NONE", jackett.authType)
        assertEquals(listOf("SEARCH", "MAGNET_LINK", "TORRENT_DOWNLOAD"), jackett.capabilities)
        assertTrue(jackett.baseUrls.isEmpty())
        assertTrue(jackett.supportsAnonymous)
    }

    @Test
    fun toleratesUnknownTopLevelKeys() {
        // The server may add fields the client predates; ignoreUnknownKeys must hold.
        val withExtra = """
            { "providers": [
              { "id":"x","displayName":"X","kind":"native","capabilities":[],"authType":"NONE","encoding":"UTF-8","baseUrls":[],"supportsAnonymous":true,"futureField":42 }
            ], "serverVersion": "2.3.25" }
        """.trimIndent()

        val response = json.decodeFromString(ProvidersResponseDto.serializer(), withExtra)
        assertEquals(1, response.providers.size)
        assertEquals("x", response.providers[0].id)
    }
}
