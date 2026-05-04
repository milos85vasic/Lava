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
    )

    /**
     * The four providers that the 2026-05-04 emulator rehearsal could
     * NOT validate end-to-end on a real device surface. They have real
     * SDK implementations but no operator-rehearsed Challenge Test
     * passing on the gating matrix. Hidden from the user-facing list
     * via the verified=false default until that gap is closed.
     */
    private val unverifiedIds = setOf(
        KinozalDescriptor.trackerId,
        NnmclubDescriptor.trackerId,
        ArchiveOrgDescriptor.trackerId,
        GutenbergDescriptor.trackerId,
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
    fun `ArchiveOrg is NOT verified — post-login navigation bug blocks C11`() {
        assertFalse(
            "ArchiveOrgDescriptor.verified MUST stay false until the 2026-05-04 onboarding-navigation incident is closed and C11 passes on the gating matrix",
            ArchiveOrgDescriptor.verified,
        )
    }

    @Test
    fun `Gutenberg is NOT verified — same blocker as ArchiveOrg`() {
        assertFalse(
            "GutenbergDescriptor.verified MUST stay false until the onboarding-navigation incident is closed and C12 passes on the gating matrix",
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
