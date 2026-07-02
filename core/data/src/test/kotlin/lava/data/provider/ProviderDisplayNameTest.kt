package lava.data.provider

import lava.network.dto.ProviderDescriptorDto
import lava.tracker.api.TrackerCapability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #4 (2026-06-25 video sweep) — the search-RESULT filter chips (and the
 * onboarding provider subtitles, provider-config rows) rendered RAW lowercased
 * provider ids ("torrentdownloads", "archiveorg", "kinozal", "yts") instead of
 * friendly display names.
 *
 * Root cause: the search-result chip renders `providerDisplayNames[pid] ?: pid`
 * (feature/search_result/SearchResultScreen.kt:377). The display name flows from
 * `MultiSearchEvent.ProviderStart(id, descriptor.displayName)`
 * (core/tracker/client/LavaTrackerSdk.kt:826). For an API-catalogue-backed
 * provider that descriptor is a [lava.tracker.api.RemoteTrackerDescriptor] whose
 * `displayName` is whatever the lava-api-go `/v1/providers` payload supplied —
 * which, for these providers, is the RAW id (or blank). So the chip fell back to
 * the id.
 *
 * The SHARED/CORE fix is [friendlyDisplayName] / [toRemoteDescriptor]: the single
 * catalogue→descriptor adapter humanizes a blank-or-equal-to-id `display_name`,
 * upgrading every downstream consumer at once. (The chip-render site itself lives
 * in feature/search_result and is correct — it already prefers the display name;
 * the gap was the display name being a raw id at the source.)
 *
 * Primary assertion: the produced display name a real user reads on a chip is NOT
 * the raw id.
 */
class ProviderDisplayNameTest {

    // FALSIFIABILITY: in friendlyDisplayName return `serverDisplayName` (or `id`)
    // unconditionally without the humanization branch. Observed: this test FAILS
    // — "raw id MUST be humanized" because the value stays "archiveorg" /
    // "torrentdownloads". Reverted.
    @Test
    fun `blank server display name is humanized from the id`() {
        assertEquals("Archiveorg", friendlyDisplayName(id = "archiveorg", serverDisplayName = ""))
        assertEquals("Yts", friendlyDisplayName(id = "yts", serverDisplayName = ""))
    }

    @Test
    fun `display name equal to the raw id is humanized not echoed back`() {
        // The exact symptom from the video: server sends display_name == id.
        assertEquals(
            "Torrentdownloads",
            friendlyDisplayName(id = "torrentdownloads", serverDisplayName = "torrentdownloads"),
        )
        // Case-insensitive equality also triggers humanization.
        assertEquals("Kinozal", friendlyDisplayName(id = "kinozal", serverDisplayName = "KINOZAL"))
    }

    @Test
    fun `separators in the id become spaced title-cased words`() {
        assertEquals(
            "Torrent Downloads",
            friendlyDisplayName(id = "torrent_downloads", serverDisplayName = ""),
        )
        assertEquals("Archive Org", friendlyDisplayName(id = "archive.org", serverDisplayName = ""))
    }

    @Test
    fun `a genuine friendly server name is preserved`() {
        // When the API DOES send a real friendly name, keep it verbatim.
        assertEquals(
            "Internet Archive",
            friendlyDisplayName(id = "archiveorg", serverDisplayName = "Internet Archive"),
        )
        assertEquals(
            "RuTracker.org",
            friendlyDisplayName(id = "rutracker", serverDisplayName = "RuTracker.org"),
        )
    }

    // End-to-end through the wire DTO → descriptor adapter the catalogue uses.
    // FALSIFIABILITY: pass `displayName = displayName` straight into
    // RemoteTrackerDescriptor.from (the pre-fix shape). Observed: this test FAILS
    // — "descriptor display name MUST NOT be the raw id" because it stays
    // "archiveorg". Reverted.
    @Test
    fun `catalogue DTO with raw display name maps to a humanized descriptor name`() {
        val dto = ProviderDescriptorDto(
            id = "archiveorg",
            displayName = "archiveorg", // server sent the raw id
            kind = "native",
            authType = "NONE",
            encoding = "UTF-8",
        )
        val descriptor = dto.toRemoteDescriptor()
        assertEquals("archiveorg", descriptor.trackerId)
        // PRIMARY: the name a user reads on a chip/subtitle is friendly, not raw.
        assertEquals("Archiveorg", descriptor.displayName)
    }

    @Test
    fun `catalogue DTO omitting display name still parses and humanizes`() {
        // displayName now defaults to "" so a missing field no longer drops the
        // provider on JSON decode — it parses and humanizes from id.
        val dto = ProviderDescriptorDto(
            id = "yts",
            kind = "native",
            authType = "NONE",
            encoding = "UTF-8",
        )
        assertEquals("Yts", dto.toRemoteDescriptor().displayName)
    }

    // §6.E Capability-Honesty (2026-07-02 goapi keystone, device-proven). The goapi
    // catalogue declares auth via `authType` (CAPTCHA_LOGIN/FORM_LOGIN) but omits an
    // AUTH_REQUIRED capability; without deriving it, ApiBackedTrackerClient.
    // getFeature<AuthenticatableTracker>() (gated on TrackerCapability.AUTH_REQUIRED)
    // returned null and sdk.login() short-circuited to null → no session cookie →
    // every /v1/{id}/search 401'd. Primary assertion: a login-typed provider's
    // produced descriptor EXPOSES the login capability.
    //
    // FALSIFIABILITY: in ProviderCatalogRepository.withAuthRequiredWhenAuthTyped,
    // return `this` unconditionally (drop the derivation). Observed: this test
    // FAILS — the capability set stays [SEARCH, BROWSE] with no AUTH_REQUIRED.
    // Reverted.
    @Test
    fun `login-typed provider derives AUTH_REQUIRED capability`() {
        val dto = ProviderDescriptorDto(
            id = "rutracker",
            displayName = "RuTracker.org",
            kind = "native",
            authType = "CAPTCHA_LOGIN",
            encoding = "windows-1251",
            capabilities = listOf("SEARCH", "BROWSE"), // server omits AUTH_REQUIRED
        )
        val caps = dto.toRemoteDescriptor().capabilities
        assertEquals(true, caps.contains(TrackerCapability.AUTH_REQUIRED))
        // original capabilities are preserved alongside the derived one
        assertEquals(true, caps.contains(TrackerCapability.SEARCH))
    }

    // FALSIFIABILITY: make withAuthRequiredWhenAuthTyped add AUTH_REQUIRED for the
    // NONE case too. Observed: this test FAILS — a NONE provider MUST NOT be handed
    // a login feature (it would drive the onboarding cred path + render a spurious
    // login form for an anonymous provider). Reverted.
    @Test
    fun `NONE authType provider does not derive AUTH_REQUIRED`() {
        val dto = ProviderDescriptorDto(
            id = "archiveorg",
            displayName = "Internet Archive",
            kind = "native",
            authType = "NONE",
            encoding = "UTF-8",
            capabilities = listOf("SEARCH"),
        )
        val caps = dto.toRemoteDescriptor().capabilities
        assertEquals(false, caps.contains(TrackerCapability.AUTH_REQUIRED))
    }
}
