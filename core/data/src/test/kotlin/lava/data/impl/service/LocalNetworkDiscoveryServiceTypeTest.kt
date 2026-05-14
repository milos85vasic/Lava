package lava.data.impl.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the mDNS service-type discrimination introduced
 * 2026-05-14 to support a side-by-side DEV lava-api-go instance.
 *
 * The DEV service type `_lava-api-dev._tcp` shares the prefix
 * `_lava-api` with the production type `_lava-api._tcp`. The
 * `matchesServiceType` helper MUST distinguish them so a watcher
 * subscribed to one does not accept the other; otherwise a debug
 * APK on a LAN with both instances would emit a misclassified
 * `Engine` tag.
 *
 * Falsifiability rehearsal:
 *   1. In LocalNetworkDiscoveryServiceImpl.kt, weaken matchesServiceType
 *      to `found.contains(watched)` (the SP-3.4 forensic-anchor pre-fix
 *      shape).
 *   2. Re-run this test.
 *   3. Expected failures:
 *      - matchesServiceType_distinguishesDevFromProd: returns true for
 *        watched=`_lava-api._tcp` + found=`_lava-api-dev._tcp.local.`
 *      - matchesServiceType_distinguishesProdFromDev: returns true for
 *        watched=`_lava-api-dev._tcp` + found=`_lava-api._tcp.local.`
 *   4. Restore; re-run; both pass.
 */
class LocalNetworkDiscoveryServiceTypeTest {

    @Test
    fun serviceTypesRelease_excludesDevType() {
        assertFalse(
            "release builds must NOT subscribe to _lava-api-dev._tcp",
            lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPES_RELEASE
                .contains(lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO_DEV),
        )
        assertTrue(
            "release builds must subscribe to _lava-api._tcp",
            lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPES_RELEASE
                .contains(lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO),
        )
    }

    @Test
    fun serviceTypesDebug_includesDevTypeInAdditionToProd() {
        assertTrue(
            "debug builds must subscribe to _lava-api-dev._tcp",
            lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPES_DEBUG
                .contains(lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO_DEV),
        )
        assertTrue(
            "debug builds must also subscribe to _lava-api._tcp",
            lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPES_DEBUG
                .contains(lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO),
        )
        assertEquals(
            "debug list must be release set + the dev type — no other change",
            lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPES_RELEASE.size + 1,
            lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPES_DEBUG.size,
        )
    }

    @Test
    fun matchesServiceType_distinguishesDevFromProd() {
        // A listener watching the prod type must REJECT advertisements
        // from the dev type, even though the dev type's literal contains
        // the prod prefix. This is the SP-3.4 forensic-anchor regression
        // protection extended to the new dev type.
        assertFalse(
            "watcher for _lava-api._tcp must reject _lava-api-dev._tcp",
            matchesServiceType("_lava-api._tcp", "_lava-api-dev._tcp.local."),
        )
        assertFalse(
            "watcher for _lava-api._tcp must reject _lava-api-dev._tcp (no trailing dot)",
            matchesServiceType("_lava-api._tcp", "_lava-api-dev._tcp"),
        )
    }

    @Test
    fun matchesServiceType_distinguishesProdFromDev() {
        assertFalse(
            "watcher for _lava-api-dev._tcp must reject _lava-api._tcp",
            matchesServiceType("_lava-api-dev._tcp", "_lava-api._tcp.local."),
        )
        assertFalse(
            "watcher for _lava-api-dev._tcp must reject _lava-api._tcp (no trailing dot)",
            matchesServiceType("_lava-api-dev._tcp", "_lava-api._tcp"),
        )
    }

    @Test
    fun matchesServiceType_acceptsExactDevType() {
        assertTrue(
            "watcher for _lava-api-dev._tcp must accept _lava-api-dev._tcp.local.",
            matchesServiceType("_lava-api-dev._tcp", "_lava-api-dev._tcp.local."),
        )
        assertTrue(
            "watcher for _lava-api-dev._tcp must accept _lava-api-dev._tcp",
            matchesServiceType("_lava-api-dev._tcp", "_lava-api-dev._tcp"),
        )
    }
}
