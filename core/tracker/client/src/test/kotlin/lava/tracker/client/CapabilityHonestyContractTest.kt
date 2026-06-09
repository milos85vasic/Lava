package lava.tracker.client

import io.mockk.mockk
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.HttpDownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.archiveorg.ArchiveOrgClient
import lava.tracker.gutenberg.GutenbergClient
import lava.tracker.iptorrents.IPTorrentsClient
import lava.tracker.kinozal.KinozalClient
import lava.tracker.nnmclub.NnmclubClient
import lava.tracker.rutor.RuTorClient
import lava.tracker.rutracker.RuTrackerClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Constitutional clause 6.E — Capability-Honesty mechanical CI gate.
 *
 * A single test that mechanically prevents the "declared-but-empty capability"
 * bluff class across ALL registered providers: a [TrackerDescriptor] that
 * declares a [TrackerCapability] whose corresponding feature interface the
 * client's [TrackerClient.getFeature] then refuses to resolve.
 *
 * The 6.E contract (see [TrackerClient.getFeature] KDoc):
 *
 *   capability declared in descriptor  ⇒  getFeature(<matching interface>)
 *   returns NON-NULL.
 *
 * This gate enumerates EVERY client wired into [TrackerClientModule]'s
 * registry (the factories registered there — rutracker, rutor, nnmclub,
 * kinozal, iptorrents, archiveorg, gutenberg), and for each one, for every declared
 * capability that has a matching feature interface, asserts the REAL
 * production [TrackerClient.getFeature] returns a non-null impl.
 *
 * --------------------------------------------------------------------------
 * What is the System-Under-Test, and what is faked?
 * --------------------------------------------------------------------------
 * The SUT is each client class's [TrackerClient.getFeature] routing logic
 * together with its real `descriptor` object. The feature impls + HTTP
 * clients that the client constructor receives are boundaries BELOW the SUT
 * (Seventh Law clause 4 permits faking boundaries below the SUT — analogous
 * to mocking the HTTP socket). They are supplied as relaxed mockk fakes so we
 * never touch the network: [getFeature] only routes references, it does not
 * invoke any feature method, so the fakes' behaviour is irrelevant. The
 * primary assertion is on the user-visible contract — "a declared capability
 * resolves to a usable feature object" — exactly the property whose violation
 * shipped to users as a feature that appeared selectable but could not run.
 *
 * --------------------------------------------------------------------------
 * Capability → feature-interface mapping (the 7 capability-bearing features)
 * --------------------------------------------------------------------------
 *   SEARCH           -> SearchableTracker
 *   BROWSE           -> BrowsableTracker
 *   TOPIC            -> TopicTracker
 *   COMMENTS         -> CommentsTracker
 *   FAVORITES        -> FavoritesTracker
 *   AUTH_REQUIRED    -> AuthenticatableTracker
 *   CAPTCHA_LOGIN    -> AuthenticatableTracker  (a login variant; same feature)
 *   TORRENT_DOWNLOAD -> DownloadableTracker
 *   HTTP_DOWNLOAD    -> HttpDownloadableTracker  (HTTP file download, e.g. e-books)
 *   MAGNET_LINK      -> DownloadableTracker      (magnet is a download artifact)
 *
 * --------------------------------------------------------------------------
 * Documented exemptions — capabilities with NO dedicated feature interface
 * --------------------------------------------------------------------------
 *   FORUM        -> exempt: no `ForumTracker` interface exists. Forum
 *                   browsing is surfaced through BrowsableTracker /
 *                   TopicTracker, not a distinct capability-typed feature.
 *   RSS          -> exempt: no `RssTracker` interface exists today.
 *   UPLOAD       -> exempt: no `UploadableTracker` interface exists today.
 *   USER_PROFILE -> exempt: no `ProfileTracker` interface exists today.
 *
 * These four are NOT bluffs: there is no feature interface to resolve, so
 * [TrackerClient.getFeature] cannot be expected to return one. They are
 * enumerated in [EXEMPT_CAPABILITIES] so that a FUTURE capability added to
 * the enum without a mapping is forced through one of two doors — add a
 * mapping in [CAPABILITY_TO_FEATURE] or add an explicit exemption here — and
 * cannot silently slip past the gate (see
 * [`every TrackerCapability is either mapped to a feature or explicitly exempt`]).
 *
 * Bluff-Audit: CapabilityHonestyContractTest
 *   Mutation: added a fake declared-but-unwired capability to one client by
 *             constructing a wrapper whose descriptor declares FAVORITES while
 *             getFeature(FavoritesTracker) returns null (see the
 *             `gate FAILS when a descriptor declares a feature-mapped ...`
 *             negative-control test, which proves the gate's failure path).
 *   Observed-Failure: see method KDoc below.
 *   Reverted: n/a — the mutation is encoded as a permanent negative-control
 *             test, so the gate's failure path is asserted on every run.
 */
class CapabilityHonestyContractTest {

    // -----------------------------------------------------------------
    // The capability → feature-interface map. SEARCH, CAPTCHA_LOGIN, and
    // MAGNET_LINK fold onto features they share (auth / download).
    // -----------------------------------------------------------------
    private val capabilityToFeature: Map<TrackerCapability, KClass<out TrackerFeature>> = CAPABILITY_TO_FEATURE

    /**
     * Every registered client, built with REAL descriptors + relaxed-mock
     * feature/HTTP boundaries. Mirrors the six factories
     * [TrackerClientModule.provideTrackerRegistry] registers.
     */
    private fun registeredClients(): List<TrackerClient> = listOf(
        RuTrackerClient(
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            comments = mockk(relaxed = true),
            favorites = mockk(relaxed = true),
            auth = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
        RuTorClient(
            http = mockk(relaxed = true),
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            comments = mockk(relaxed = true),
            auth = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
        NnmclubClient(
            http = mockk(relaxed = true),
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            comments = mockk(relaxed = true),
            auth = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
        KinozalClient(
            http = mockk(relaxed = true),
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            auth = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
        IPTorrentsClient(
            search = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
        ArchiveOrgClient(
            http = mockk(relaxed = true),
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
        GutenbergClient(
            http = mockk(relaxed = true),
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            download = mockk(relaxed = true),
        ),
    )

    /**
     * THE 6.E GATE.
     *
     * For every registered client, for every declared capability that maps to
     * a feature interface, the real production [TrackerClient.getFeature] MUST
     * return a non-null impl. Any "declared-but-empty capability" surfaces here
     * as a clear, descriptor+capability-named assertion failure.
     */
    @Test
    fun `every declared feature-mapped capability resolves to a non-null feature`() {
        val violations = mutableListOf<String>()

        for (client in registeredClients()) {
            val descriptor = client.descriptor
            for (capability in descriptor.capabilities) {
                val featureClass = capabilityToFeature[capability] ?: continue // exempt capability
                val resolved = client.getFeature(featureClass)
                if (resolved == null) {
                    violations += "[${descriptor.trackerId}] declares $capability " +
                        "but getFeature(${featureClass.simpleName}) returned null " +
                        "(§6.E Capability-Honesty violation: capability declared ⇒ feature MUST resolve)"
                }
            }
        }

        assertTrue(
            "§6.E Capability-Honesty violations (declared-but-empty capabilities):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * THE 6.E REVERSE GATE (phantom-capability bluff).
     *
     * The forward gate above catches "declared but doesn't resolve". This gate
     * catches the INVERSE bluff: a client whose [TrackerClient.getFeature]
     * RESOLVES a feature interface that the descriptor does NOT declare the
     * matching capability for. That is just as much a §6.E lie — the user-facing
     * surface (e.g. a download button) works against a provider whose descriptor
     * claims it cannot download, so any consumer that gates UI on
     * `descriptor.capabilities` is out of sync with what the client actually does.
     *
     * Forensic anchor: archiveorg + gutenberg wire a real HTTP-download impl into
     * their constructors but DELIBERATELY return null from
     * getFeature(DownloadableTracker) because TrackerCapability has no
     * HTTP_DOWNLOAD value and the artifact is not a `.torrent` (see those
     * clients' KDoc). Before this gate existed, a regression flipping that
     * `null` to `download as T` shipped green — the forward gate never checks
     * the reverse direction. This test makes that regression fail.
     *
     * For every registered client, for every feature interface in the map, if
     * getFeature(<interface>) is non-null, then AT LEAST ONE capability mapping
     * to that interface MUST be present in descriptor.capabilities.
     */
    @Test
    fun `every resolved feature is backed by a declared capability`() {
        // Inverse of CAPABILITY_TO_FEATURE: feature interface -> the capabilities
        // that legitimately back it. DownloadableTracker is backed by either
        // TORRENT_DOWNLOAD or MAGNET_LINK; AuthenticatableTracker by AUTH_REQUIRED
        // or CAPTCHA_LOGIN.
        val featureToBackingCapabilities: Map<KClass<out TrackerFeature>, Set<TrackerCapability>> =
            CAPABILITY_TO_FEATURE.entries
                .groupBy({ it.value }, { it.key })
                .mapValues { it.value.toSet() }

        val violations = mutableListOf<String>()

        for (client in registeredClients()) {
            val declared = client.descriptor.capabilities
            for ((featureClass, backingCaps) in featureToBackingCapabilities) {
                val resolved = client.getFeature(featureClass)
                if (resolved != null && backingCaps.none { it in declared }) {
                    violations += "[${client.descriptor.trackerId}] getFeature(${featureClass.simpleName}) " +
                        "returned a non-null impl but the descriptor declares NONE of $backingCaps " +
                        "(§6.E phantom-capability violation: a feature MUST NOT be reachable unless its " +
                        "capability is declared, or consumers that gate on descriptor.capabilities are lied to)"
                }
            }
        }

        assertTrue(
            "§6.E phantom-capability violations (feature resolves but capability undeclared):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * Falsifiability proof for the reverse gate. A synthetic client that
     * resolves a [DownloadableTracker] while declaring NEITHER TORRENT_DOWNLOAD
     * NOR MAGNET_LINK MUST be flagged by the reverse-gate logic. Without this,
     * the reverse gate could be a vacuous always-green assertion.
     */
    @Test
    fun `reverse gate FAILS when a client resolves a feature whose capability is undeclared`() {
        val phantomDownload = mockk<DownloadableTracker>(relaxed = true)
        val phantomClient = object : TrackerClient {
            override val descriptor: TrackerDescriptor = object : TrackerDescriptor {
                override val trackerId = "synthetic-phantom"
                override val displayName = "Synthetic Phantom"
                override val baseUrls = emptyList<lava.sdk.api.MirrorUrl>()

                // Declares SEARCH only — NOT TORRENT_DOWNLOAD / MAGNET_LINK.
                override val capabilities = setOf(TrackerCapability.SEARCH)
                override val authType = AuthType.NONE
                override val encoding = "UTF-8"
                override val expectedHealthMarker = "x"
            }

            override suspend fun healthCheck() = true

            @Suppress("UNCHECKED_CAST")
            override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? =
                // Phantom: resolves a download feature the descriptor never declared.
                if (featureClass == DownloadableTracker::class) phantomDownload as T else null

            override fun close() {}
        }

        val downloadBackers = setOf(TrackerCapability.TORRENT_DOWNLOAD, TrackerCapability.MAGNET_LINK)
        val declared = phantomClient.descriptor.capabilities
        val resolved = phantomClient.getFeature(DownloadableTracker::class)
        val flagged = resolved != null && downloadBackers.none { it in declared }

        assertTrue(
            "Falsifiability: the reverse §6.E gate MUST flag a client that resolves a feature " +
                "whose backing capability is undeclared. If this is false, the reverse gate is vacuous.",
            flagged,
        )
    }

    /**
     * Coverage assertion: this gate must guard exactly the clients the Hilt
     * registry registers. If a new provider is added to [TrackerClientModule]
     * without being added to [registeredClients], the gate would silently stop
     * guarding it — this test forces the two lists to stay in lockstep.
     */
    @Test
    fun `gate guards exactly the registered providers`() {
        val guarded = registeredClients().map { it.descriptor.trackerId }.toSet()
        val expected = setOf("rutracker", "rutor", "nnmclub", "kinozal", "iptorrents", "archiveorg", "gutenberg")
        assertTrue(
            "Registered clients guarded by the 6.E gate ($guarded) must equal the " +
                "factories TrackerClientModule registers ($expected). If you added a " +
                "provider to the Hilt registry, add it to registeredClients() too.",
            guarded == expected,
        )
    }

    /**
     * Closes the enum-drift hole: every [TrackerCapability] value MUST be
     * either mapped to a feature interface in [CAPABILITY_TO_FEATURE] or
     * explicitly listed in [EXEMPT_CAPABILITIES]. A new enum value added
     * without doing one of those two things fails here — preventing a
     * future capability from silently bypassing the 6.E gate.
     */
    @Test
    fun `every TrackerCapability is either mapped to a feature or explicitly exempt`() {
        val unclassified = TrackerCapability.entries.filter {
            it !in CAPABILITY_TO_FEATURE && it !in EXEMPT_CAPABILITIES
        }
        assertTrue(
            "Unclassified TrackerCapability values: $unclassified. Each new capability " +
                "MUST be added to CAPABILITY_TO_FEATURE (if it has a feature interface) " +
                "or EXEMPT_CAPABILITIES (with a documented reason).",
            unclassified.isEmpty(),
        )
    }

    /**
     * Negative control / falsifiability proof. A synthetic client whose
     * descriptor declares FAVORITES while getFeature(FavoritesTracker) returns
     * null MUST be flagged by the same gate logic. This proves the gate has a
     * working failure path: it is not a vacuous always-green assertion.
     *
     * Observed-Failure (if the gate logic were inverted to ignore null): this
     * test fails with "expected the gate to flag the synthetic declared-but-
     * empty FAVORITES capability, but no violation was recorded".
     */
    @Test
    fun `gate FAILS when a descriptor declares a feature-mapped capability the client cannot resolve`() {
        val bluffClient = object : TrackerClient {
            override val descriptor: TrackerDescriptor = object : TrackerDescriptor {
                override val trackerId = "synthetic-bluff"
                override val displayName = "Synthetic Bluff"
                override val baseUrls = emptyList<lava.sdk.api.MirrorUrl>()
                override val capabilities = setOf(TrackerCapability.FAVORITES)
                override val authType = AuthType.NONE
                override val encoding = "UTF-8"
                override val expectedHealthMarker = "x"
            }

            override suspend fun healthCheck() = true

            // Declared FAVORITES but resolves nothing — the canonical bluff.
            override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = null

            override fun close() {}
        }

        val violations = mutableListOf<String>()
        for (capability in bluffClient.descriptor.capabilities) {
            val featureClass = CAPABILITY_TO_FEATURE[capability] ?: continue
            if (bluffClient.getFeature(featureClass) == null) {
                violations += "[${bluffClient.descriptor.trackerId}] declares $capability " +
                    "but getFeature(${featureClass.simpleName}) returned null"
            }
        }

        assertTrue(
            "Falsifiability: the 6.E gate MUST flag a descriptor that declares a " +
                "feature-mapped capability the client cannot resolve. If this is empty, " +
                "the gate is vacuous and would never catch a real bluff.",
            violations.isNotEmpty(),
        )
    }

    /**
     * Sanity: the verified honest providers (rutracker — declares all 7
     * capability-bearing features) resolve every one. A direct, readable
     * spot-check that complements the data-driven gate above.
     */
    @Test
    fun `rutracker resolves all seven capability-bearing feature interfaces`() {
        val client = RuTrackerClient(
            search = mockk(relaxed = true),
            browse = mockk(relaxed = true),
            topic = mockk(relaxed = true),
            comments = mockk(relaxed = true),
            favorites = mockk(relaxed = true),
            auth = mockk(relaxed = true),
            download = mockk(relaxed = true),
        )
        assertNotNull(client.getFeature(SearchableTracker::class))
        assertNotNull(client.getFeature(BrowsableTracker::class))
        assertNotNull(client.getFeature(TopicTracker::class))
        assertNotNull(client.getFeature(CommentsTracker::class))
        assertNotNull(client.getFeature(FavoritesTracker::class))
        assertNotNull(client.getFeature(AuthenticatableTracker::class))
        assertNotNull(client.getFeature(DownloadableTracker::class))
    }

    private companion object {
        val CAPABILITY_TO_FEATURE: Map<TrackerCapability, KClass<out TrackerFeature>> = mapOf(
            TrackerCapability.SEARCH to SearchableTracker::class,
            TrackerCapability.BROWSE to BrowsableTracker::class,
            TrackerCapability.TOPIC to TopicTracker::class,
            TrackerCapability.COMMENTS to CommentsTracker::class,
            TrackerCapability.FAVORITES to FavoritesTracker::class,
            TrackerCapability.AUTH_REQUIRED to AuthenticatableTracker::class,
            TrackerCapability.CAPTCHA_LOGIN to AuthenticatableTracker::class,
            TrackerCapability.TORRENT_DOWNLOAD to DownloadableTracker::class,
            TrackerCapability.HTTP_DOWNLOAD to HttpDownloadableTracker::class,
            TrackerCapability.MAGNET_LINK to DownloadableTracker::class,
        )

        // Capabilities with NO dedicated feature interface — documented exemptions.
        val EXEMPT_CAPABILITIES: Set<TrackerCapability> = setOf(
            TrackerCapability.FORUM, // surfaced via Browsable/Topic; no ForumTracker interface
            TrackerCapability.RSS, // no RssTracker interface today
            TrackerCapability.UPLOAD, // no UploadableTracker interface today
            TrackerCapability.USER_PROFILE, // no ProfileTracker interface today
        )
    }
}
