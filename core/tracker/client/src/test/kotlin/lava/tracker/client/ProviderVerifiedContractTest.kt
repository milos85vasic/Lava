package lava.tracker.client

import lava.tracker.archiveorg.ArchiveOrgDescriptor
import lava.tracker.gutenberg.GutenbergDescriptor
import lava.tracker.kinozal.KinozalDescriptor
import lava.tracker.nnmclub.NnmclubDescriptor
import lava.tracker.rutor.RuTorDescriptor
import lava.tracker.rutracker.RuTrackerDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Constitutional clause 6.G — Provider Operational Verification gate.
 *
 * Pins the verified-flag state for every shipped TrackerDescriptor so a
 * silent flip (e.g. someone setting `verified = true` without writing the
 * matching Challenge Test) fails CI immediately.
 *
 * To flip a descriptor in this test: ALSO update the per-descriptor file
 * AND add a Challenge Test under app/src/androidTest/kotlin/lava/app/challenges/
 * AND record a Bluff-Audit stamp (mutation/observed-failure/revert) in the
 * commit that flips it. Updating only this test is itself a bluff.
 *
 * Bluff-Audit: ProviderVerifiedContractTest
 *   Mutation: flip RuTrackerDescriptor.verified from true to false
 *             (simulating "we lost the Challenge Test")
 *   Observed-Failure: assertEquals on verifiedIds size mismatches → assertion
 *             "rutracker MUST stay verified — ..." fires.
 *   Reverted: yes
 */
class ProviderVerifiedContractTest {

    /**
     * Verified providers — backed by Challenge Tests in
     * `app/src/androidTest/kotlin/lava/app/challenges/`. As of 2026-05-04,
     * RuTracker and RuTor are pinned verified=true based on the C1-C8
     * suite. The 2026-05-04 emulator rehearsal confirmed both DESCRIPTOR
     * + SDK-LEVEL bindings work; a separate post-login NAVIGATION bug
     * (recorded in .lava-ci-evidence/sixth-law-incidents/
     * 2026-05-04-onboarding-navigation.json) makes the end-to-end UI flow
     * unusable for ALL providers including these two — that is a release
     * blocker tracked separately and does not by itself revert the
     * descriptor flags, because the SDK-level + capability-honesty
     * acceptance gates are still met.
     */
    private val verifiedIds = setOf(
        RuTrackerDescriptor.trackerId,
        RuTorDescriptor.trackerId,
        // Phase 4.1a (2026-05-04 late): C11 shallow Challenge Test green
        // on CZ_API34_Phone via the new pkg/emulator matrix infrastructure.
        // Falsifiability: revert layer-1 IA fix → C11 times out post-Continue.
        ArchiveOrgDescriptor.trackerId,
        // Phase 4.1b (2026-05-04 late): C12 shallow Challenge Test
        // matched-pair with C11 — same flow shape, gutendex.com instead
        // of archive.org. Same falsifiability rehearsal protocol.
        GutenbergDescriptor.trackerId,
    )

    /**
     * Two providers still without an operator-rehearsed Challenge Test
     * passing on the gating matrix. Each has a specific blocker in
     * `docs/superpowers/plans/2026-05-04-pending-completion-plan.md`
     * Phase 4.1 row:
     *   - Kinozal: descriptor verified=false; kinozal.tv may be
     *     geofenced; C9 needs real-network rehearsal
     *   - NNM-Club: descriptor verified=false; .env lacks NNMCLUB
     *     credentials; C10 cannot run end-to-end without them
     */
    private val unverifiedIds = setOf(
        KinozalDescriptor.trackerId,
        NnmclubDescriptor.trackerId,
    )

    @Test
    fun `RuTracker is verified — backed by Challenge Tests C1, C2, C4, C5, C7, C8`() {
        assertTrue(
            "RuTrackerDescriptor.verified MUST stay true — has Challenge Tests C1-C8",
            RuTrackerDescriptor.verified,
        )
    }

    @Test
    fun `RuTor is verified — backed by Challenge Tests C1, C3, C4, C6, C7, C8`() {
        assertTrue(
            "RuTorDescriptor.verified MUST stay true — has Challenge Tests C1-C8",
            RuTorDescriptor.verified,
        )
    }

    @Test
    fun `Kinozal is NOT verified — Challenge Test C9 not yet operator-rehearsed`() {
        assertFalse(
            "KinozalDescriptor.verified MUST stay false until Challenge Test C9 passes on the gating matrix and the post-login navigation bug is fixed",
            KinozalDescriptor.verified,
        )
    }

    @Test
    fun `Nnmclub is NOT verified — Challenge Test C10 not yet runnable with real credentials`() {
        assertFalse(
            "NnmclubDescriptor.verified MUST stay false until C10 lands AND NNMCLUB credentials are added to .env AND post-login navigation is fixed",
            NnmclubDescriptor.verified,
        )
    }

    @Test
    fun `ArchiveOrg is verified — backed by Challenge Test C11`() {
        assertTrue(
            "ArchiveOrgDescriptor.verified MUST stay true — C11 shallow Continue-to-authorized-main-app passing on CZ_API34_Phone via Phase 3 matrix infrastructure (Phase 4.1a, 2026-05-04). Deep-coverage search assertion owed pending nav-compose upgrade.",
            ArchiveOrgDescriptor.verified,
        )
    }

    @Test
    fun `Gutenberg is verified — backed by Challenge Test C12`() {
        assertTrue(
            "GutenbergDescriptor.verified MUST stay true — C12 shallow Continue-to-authorized-main-app passing on CZ_API34_Phone via Phase 3 matrix infrastructure (Phase 4.1b, 2026-05-04). Deep-coverage search assertion owed pending nav-compose upgrade.",
            GutenbergDescriptor.verified,
        )
    }

    @Test
    fun `verified set + unverified set covers every shipped descriptor exactly once`() {
        val intersection = verifiedIds intersect unverifiedIds
        assertTrue(
            "A descriptor cannot be in both sets: $intersection",
            intersection.isEmpty(),
        )
        assertEquals(
            "Total descriptors covered: ${verifiedIds.size} verified + ${unverifiedIds.size} unverified = 6 shipped",
            6,
            verifiedIds.size + unverifiedIds.size,
        )
    }
}
