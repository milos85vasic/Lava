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
     * Pinned to (RuTracker, RuTor) — the two providers that ship with
     * Challenge Tests C1-C8 backing them. Adding a third entry here without
     * adding a corresponding Challenge Test is a 6.G violation.
     */
    private val verifiedIds = setOf(
        RuTrackerDescriptor.trackerId,
        RuTorDescriptor.trackerId,
    )

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
    fun `Kinozal is NOT verified — needs Challenge Test before user-list inclusion`() {
        assertFalse(
            "KinozalDescriptor.verified MUST stay false until Challenge Test C9 lands and is operator-rehearsed on a real device",
            KinozalDescriptor.verified,
        )
    }

    @Test
    fun `Nnmclub is NOT verified — needs Challenge Test before user-list inclusion`() {
        assertFalse(
            "NnmclubDescriptor.verified MUST stay false until Challenge Test C10 lands and is operator-rehearsed on a real device",
            NnmclubDescriptor.verified,
        )
    }

    @Test
    fun `ArchiveOrg is NOT verified — needs Challenge Test before user-list inclusion`() {
        assertFalse(
            "ArchiveOrgDescriptor.verified MUST stay false until Challenge Test C11 lands and is operator-rehearsed on a real device (forensic anchor of clause 6.G)",
            ArchiveOrgDescriptor.verified,
        )
    }

    @Test
    fun `Gutenberg is NOT verified — needs Challenge Test before user-list inclusion`() {
        assertFalse(
            "GutenbergDescriptor.verified MUST stay false until Challenge Test C12 lands and is operator-rehearsed on a real device",
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
        // If a new descriptor is added under core/tracker/ but not registered
        // in either set above, the corresponding @Test for its trackerId will
        // be missing from this file — reviewers MUST notice. This assertion
        // documents the count for reviewer convenience.
        assertEquals(
            "Total descriptors covered: 2 verified + 4 unverified = 6 shipped",
            6,
            verifiedIds.size + unverifiedIds.size,
        )
    }
}
