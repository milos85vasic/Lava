package lava.api.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the mDNS TXT-record wire contract the discovery side
 * (`core/data` `LocalNetworkDiscoveryServiceImpl`) parses.
 *
 * Bluff-Audit (recorded in commit body): dropping the `platform` entry from
 * buildTxtRecords makes `txt records contain the discovery contract` fail with
 * "expected android but was null"; swapping serviceTypeFor(GO) to return the
 * dev type makes `service type matches build variant` fail.
 */
class MdnsAdvertiserTxtTest {

    @Test
    fun `txt records contain the discovery contract`() {
        val txt = buildTxtRecords(AdvertisedEngine.GO, version = "2.3.22")

        // PRIMARY: the exact attributes a peer's NsdManager resolve reads.
        assertEquals("go", txt[ApiTxtKeys.ENGINE])
        assertEquals("android", txt[ApiTxtKeys.PLATFORM])
        assertEquals("sqlite", txt[ApiTxtKeys.STORAGE])
        assertEquals("2.3.22", txt[ApiTxtKeys.VERSION])
    }

    @Test
    fun `dev build advertises go-dev engine`() {
        val txt = buildTxtRecords(AdvertisedEngine.GO_DEV, version = "2.3.22")
        assertEquals("go-dev", txt[ApiTxtKeys.ENGINE])
    }

    @Test
    fun `version is the supplied embed version not a hardcoded literal`() {
        // §6.R: the advertised version tracks the running embed's reported
        // version, so different inputs produce different outputs.
        val a = buildTxtRecords(AdvertisedEngine.GO, version = "1.0.0")
        val b = buildTxtRecords(AdvertisedEngine.GO, version = "9.9.9")
        assertEquals("1.0.0", a[ApiTxtKeys.VERSION])
        assertEquals("9.9.9", b[ApiTxtKeys.VERSION])
    }

    @Test
    fun `service type matches build variant`() {
        assertEquals("_lava-api._tcp", serviceTypeFor(AdvertisedEngine.GO))
        assertEquals("_lava-api-dev._tcp", serviceTypeFor(AdvertisedEngine.GO_DEV))
    }

    @Test
    fun `service types match the discovery catalog literals`() {
        // These literals are the cross-process contract with
        // DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO / SERVICE_TYPE_GO_DEV in
        // core/data. A drift here silently breaks LAN discovery.
        assertTrue(ApiServiceTypes.GO == "_lava-api._tcp")
        assertTrue(ApiServiceTypes.GO_DEV == "_lava-api-dev._tcp")
    }
}
