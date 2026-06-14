/*
 * Challenge Test C43 — Settings → Server list de-duplicates the chosen
 * online server (operator defect 2026-06-14; fix @1d0294e5).
 *
 * Operator-reported defect: "When we open Settings → Server list, the chosen
 * online server appears TWICE in the list." The user onboarded with an
 * online/cloud API endpoint; that single endpoint was rendered duplicated in
 * the Settings Server list.
 *
 * ROOT CAUSE (core/data/.../converters/Endpoint.kt:62-80): the Room PRIMARY
 * KEY id for an [Endpoint.GoApi] is `GoApi(${packHost()})`, and `packHost()`
 * appends the additive `key`/`platform`/`storage` fields after a `#` sentinel.
 * So the SAME physical server (same host:port) persisted via two paths that
 * differ ONLY in those additive fields gets TWO distinct primary keys →
 * `EndpointDao.insert(OnConflictStrategy.REPLACE)` de-dups only on a matching
 * id → TWO Room rows → [EndpointsRepositoryImpl.observeAll] emitted the same
 * server TWICE → `ConnectionsViewModel.observeConnections` rendered the same
 * host:port twice in the list `ConnectionsList` iterates over.
 *
 * The two real production paths (see OnboardingViewModel):
 *   - cloud "Add server" flow (onAddCloudApi → CloudApiDefaults.parse) writes
 *     a BARE GoApi (key=null, platform=null, storage=null)
 *   - on-device / mDNS-discovered flow (onOnDeviceApiReturned /
 *     startApiDiscovery) writes a GoApi carrying a per-instance KEY and/or the
 *     platform+storage TXT attributes
 * Both call endpointsRepository.add() with the SAME host:port → two list rows.
 *
 * FIX (@1d0294e5): EndpointsRepositoryImpl.observeAll() now applies
 * `.distinctBy(::serverIdentity)` where `serverIdentity(GoApi) =
 * "GoApi(host:port)"` — the transport-defining identity, EXCLUDING the
 * additive fields that bloat the Room id. One list row per actual server.
 *
 * WHY THIS IS HONEST UNDER §6.J / §6.AB (real data path, no bluff):
 * - The Room database is a REAL on-device [lava.database.AppDatabase] — the
 *   SAME @Database the app ships (version 12, real EndpointEntity table). Only
 *   in-memory (no disk file) so the test is hermetic; the schema, converters,
 *   DAO SQL, and OnConflictStrategy.REPLACE are byte-identical to production.
 * - The repository is the REAL production [EndpointsRepositoryImpl] wired to
 *   that real DAO — no fake, no stub. `add()` packs via the REAL
 *   `Endpoint.toEntity()` converter, so the two-distinct-primary-keys mechanism
 *   that CAUSES the bug is exercised exactly as on a user's device.
 * - The PRIMARY assertion is on the emitted list `observeAll()` produces — the
 *   exact `state.connections` the Settings Server list (`ConnectionsList`)
 *   renders one Row per. Counting that list IS counting the rendered rows
 *   (§6.AB rendering-correctness / Sixth Law clause 3) — a real user opening
 *   Settings → Server would see exactly these entries.
 * - This is the on-device equivalent of the committed JVM unit reproduction
 *   `EndpointsRepositoryImplFilterTest.observeAll_deduplicates_same_server_added_via_two_paths`,
 *   run against a REAL Room DB instead of a hand-written FakeEndpointDao — so a
 *   defect in Room's REPLACE semantics or the packed-id converter is also in
 *   scope here.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In EndpointsRepositoryImpl.observeAll() remove the
 *      `.distinctBy(::serverIdentity)` step (the de-dup that fixes the defect).
 *      The Server list still composes — it just shows the SAME server twice (a
 *      NON-crashing break, exactly the operator's defect class).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `chosenOnlineServer_appearsExactlyOnce_inServerList`
 *      fails at the count assertion — the same host:port is emitted twice, so
 *      it throws "The chosen online server MUST appear exactly ONCE in the
 *      Settings Server list, not twice (operator defect 2026-06-14)
 *      expected:<1> but was:<2>".
 *   4. Revert the removal; re-run; the server appears once and the test passes.
 *
 *   The complement guard `twoGenuinelyDifferentServers_bothRemain` proves the
 *   de-dup is by server identity (host+port), NOT a blanket "collapse all
 *   GoApi" — collapsing all GoApi would make a second REAL server the user
 *   added vanish from the list, which is itself a user-visible defect.
 *
 * Honest scope: this Challenge is the DETERMINISTIC on-device data-path proof
 * of the de-dup. The full VISUAL Settings → Server screen render (the Orbit
 * `ConnectionsViewModel` + `ConnectionsList` UI, which require Hilt + the
 * navigation `viewModel()` host) is exercised by the menu/connection Challenges
 * and the HelixQA video pass; that screen iterates over exactly the list this
 * Challenge asserts on.
 *
 * // covers-feature: connection
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge43ServerListNoDuplicateTest"
 */
package lava.app.challenges

import androidx.room.Room
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lava.data.impl.repository.EndpointsRepositoryImpl
import lava.database.AppDatabase
import lava.models.settings.Endpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's Espresso/Compose-on-API36 incident (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json). Kept on every Challenge for matrix consistency even when this one drives no Compose UI.
class Challenge43ServerListNoDuplicateTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: EndpointsRepositoryImpl

    // Same online server reachable via the two real onboarding paths.
    private val onlineHost = "lava.example"
    private val onlinePort = 7777

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The REAL app database (version 12, real EndpointEntity table +
        // converters + DAO SQL), in-memory so the test is hermetic. The schema
        // and OnConflictStrategy.REPLACE semantics are byte-identical to the
        // on-disk production DB — only the storage backing differs.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // The REAL production repository wired to the REAL DAO. No fake.
        repository = EndpointsRepositoryImpl(db.endpointDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // CHALLENGE: the same online server, added once bare (cloud "Add server"
    // path) and once carrying a per-instance key + TXT attributes (on-device /
    // mDNS path), MUST render exactly ONE row in the Settings Server list.
    // Primary assertion on the emitted list = the user-visible rendered rows.
    @Test
    fun chosenOnlineServer_appearsExactlyOnce_inServerList() = runBlocking {
        // Path 1 — cloud "Add server": a bare GoApi (no key/platform/storage).
        repository.add(Endpoint.GoApi(host = onlineHost, port = onlinePort))
        // Path 2 — on-device / mDNS-discovered: the SAME host:port carrying the
        // per-instance key + platform + storage TXT attributes. Different
        // additive fields → a DIFFERENT packed Room primary-key id → a SECOND
        // Room row (REPLACE only de-dups on a matching id). This is the exact
        // mechanism that produced the operator's "appears twice" defect.
        repository.add(
            Endpoint.GoApi(
                host = onlineHost,
                port = onlinePort,
                platform = Endpoint.GoApi.PLATFORM_ANDROID,
                storage = "sqlite",
                key = "instance-key-abc",
            ),
        )

        // The list the Settings Server screen (ConnectionsList) renders one Row
        // per. observeAll() is the REAL production flow (purge + seed + filter +
        // distinctBy), so this traverses the same code path the screen does.
        val rendered = repository.observeAll().first()

        val sameServerRows = rendered.count {
            it is Endpoint.GoApi && it.host == onlineHost && it.port == onlinePort
        }
        // PRIMARY (user-visible): exactly one row for the chosen online server.
        assertEquals(
            "The chosen online server MUST appear exactly ONCE in the Settings " +
                "Server list, not twice (operator defect 2026-06-14)",
            1,
            sameServerRows,
        )
        // And there is nothing else in the list — the de-dup did not leak a
        // second hidden entry the user could scroll to.
        assertEquals(
            "The Settings Server list MUST contain exactly the one chosen server",
            1,
            rendered.size,
        )
    }

    // COMPLEMENT GUARD: two GENUINELY different servers (different host:port)
    // MUST both remain — de-dup is by server identity (host+port), not a
    // blanket collapse-all-GoApi that would hide a second real server the user
    // added (itself a user-visible defect).
    @Test
    fun twoGenuinelyDifferentServers_bothRemain() = runBlocking {
        repository.add(Endpoint.GoApi(host = onlineHost, port = onlinePort))
        repository.add(Endpoint.GoApi(host = "other.example", port = 8443))

        val rendered = repository.observeAll().first()

        assertEquals(
            "Two genuinely different servers MUST both appear in the Settings Server list",
            2,
            rendered.count { it is Endpoint.GoApi },
        )
    }
}
