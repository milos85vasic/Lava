package lava.data.api.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sub-project 2 (on-device API): unit test for the pure TXT-`platform` → label
 * mapping used by the discovered "available API instances" list (onboarding
 * ApiSelection step, Connections screen).
 *
 * The on-device Lava-API app advertises mDNS TXT `platform=android`; the
 * host/server advertiser omits the attribute. The label MUST distinguish the
 * two for the user, while a missing/blank platform MUST render exactly as a
 * host instance does (backward compatibility — pre-Sub-project-2 advertisers
 * carried no platform).
 *
 * FALSIFIABILITY REHEARSAL (per §6.J / Sixth Law clause 2). Deliberate break
 * tried while authoring: in [discoveredApiLabel] drop the
 * `normalized == PLATFORM_ANDROID` branch (so android falls through to the
 * else / network branch). Expected failure:
 *   - `android platform maps to the on-device label` fails with
 *     expected:<On this network · Android device> but was:<On this network (android)>
 *     (the else branch appends the raw platform), proving the android branch is
 *     load-bearing. Reverted; production code unchanged.
 *
 * No emulator / NsdManager required: the mapping is a pure function.
 */
class DiscoveredApiLabelTest {

    @Test
    fun `android platform maps to the on-device label`() {
        assertEquals(
            "platform=android MUST surface the distinct Android-device label",
            LABEL_ANDROID_DEVICE,
            discoveredApiLabel("android"),
        )
    }

    @Test
    fun `android platform is matched case-insensitively and trimmed`() {
        assertEquals(
            "platform value MUST be normalised (trim + lowercase) before matching",
            LABEL_ANDROID_DEVICE,
            discoveredApiLabel("  Android "),
        )
    }

    @Test
    fun `null platform renders as a host network instance`() {
        assertEquals(
            "an advertiser with no platform attribute (host/server) MUST render unchanged",
            LABEL_NETWORK,
            discoveredApiLabel(null),
        )
    }

    @Test
    fun `blank platform renders as a host network instance`() {
        assertEquals(
            "a blank platform attribute MUST be treated as absent (host/server)",
            LABEL_NETWORK,
            discoveredApiLabel("   "),
        )
    }

    @Test
    fun `unexpected platform is surfaced honestly rather than mislabeled as host`() {
        // An unknown platform (e.g. a future iOS-hosted API) MUST NOT be
        // silently labeled as a host; it carries the network label plus the raw
        // value so the surface is honest (§6.J).
        assertEquals(
            "On this network (ios)",
            discoveredApiLabel("ios"),
        )
    }

    @Test
    fun `no label contains an underscore`() {
        // Operator directive: no underscores in user-facing labels.
        val labels = listOf(
            discoveredApiLabel("android"),
            discoveredApiLabel(null),
            discoveredApiLabel("ios"),
            LABEL_ANDROID_DEVICE,
            LABEL_NETWORK,
        )
        labels.forEach { label ->
            assertEquals(
                "user-facing label must not contain underscores: '$label'",
                false,
                label.contains('_'),
            )
        }
    }
}
