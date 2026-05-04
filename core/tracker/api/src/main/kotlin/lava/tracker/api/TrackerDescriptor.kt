package lava.tracker.api

import lava.sdk.api.HasId
import lava.sdk.api.MirrorUrl

interface TrackerDescriptor : HasId {
    /** Stable identifier, e.g. "rutracker", "rutor". Equal to [id] from HasId. */
    val trackerId: String
    override val id: String get() = trackerId

    /** Human-readable display name shown in UI. */
    val displayName: String

    /** Primary + mirror URLs. The first MirrorUrl with isPrimary=true is the canonical address. */
    val baseUrls: List<MirrorUrl>

    /** Capabilities this tracker actually supports — not declarations of intent. Constitutional 6.E. */
    val capabilities: Set<TrackerCapability>

    /** Authentication mechanism. */
    val authType: AuthType

    /** Encoding used by the tracker, e.g. "UTF-8", "Windows-1251". */
    val encoding: String

    /** Substring (case-insensitive) that must appear on the tracker's root page for a HEALTHY probe. */
    val expectedHealthMarker: String

    /**
     * Constitutional clause 6.G — Provider Operational Verification gate.
     *
     * `true` ONLY when this tracker has at least one passing Challenge Test
     * exercising its primary user-visible flow (login / search / browse /
     * download as appropriate for its capability set) on a real Android
     * device or in the project's emulator container.
     *
     * Defaults to `false` so any new descriptor is hidden from the
     * user-facing provider list until it earns verification. Per 6.G clause
     * 4: "Unsupported providers MUST NOT appear in the provider list
     * shipped to end users." The provider-list UI MUST filter on this flag.
     *
     * To flip a descriptor from `false` to `true` in code:
     *   1. Add a Challenge Test in `app/src/androidTest/kotlin/lava/app/challenges/`
     *      that traverses the real screen → ViewModel → SDK → real network
     *      stack for the tracker's primary flow.
     *   2. Run the falsifiability rehearsal (deliberate-mutation + observe
     *      failure + revert) and record the Bluff-Audit stamp in the commit
     *      that flips this flag.
     *   3. Operator records a real-device attestation under
     *      `.lava-ci-evidence/<tag>/real-device-verification.md` before
     *      cutting the next release tag.
     */
    val verified: Boolean get() = false

    /**
     * Phase 1.5 (2026-05-04) — per-provider anonymous-mode capability.
     *
     * Whether this tracker permits browse/search WITHOUT authenticated
     * credentials. NOT the same as [authType] — a FORM_LOGIN tracker can
     * still permit anonymous browse if its API exposes public endpoints
     * (e.g. RuTor per decision 7b-ii in the SP-3a plan). And an
     * [AuthType.NONE] tracker is implicitly anonymous regardless of this
     * flag (the field is meaningful for FORM_LOGIN/CAPTCHA_LOGIN providers).
     *
     * Truth table (post-Phase-1.5 semantics):
     *
     *   | authType        | supportsAnonymous | runtime semantics                                 |
     *   |-----------------|-------------------|---------------------------------------------------|
     *   | NONE            | (irrelevant)      | always anonymous; no toggle needed                |
     *   | FORM_LOGIN      | true              | toggle visible; user chooses anonymous OR creds   |
     *   | FORM_LOGIN      | false             | toggle hidden; user MUST enter creds              |
     *   | CAPTCHA_LOGIN   | true              | toggle visible (rare; not used today)             |
     *   | CAPTCHA_LOGIN   | false             | toggle hidden; user MUST enter creds              |
     *
     * Defaults to `false` so the toggle is hidden by default — opt-in is
     * the safe default. The forensic anchor is the operator's 2026-05-04
     * observation that the global toggle was misleading: a user could
     * toggle "Anonymous Access" on, pick RuTracker (which has no
     * anonymous mode), and the bypass would silently fire — a bluff.
     *
     * `ProviderLoginViewModel.onSubmitClick` MUST gate `state.anonymousMode`
     * on `provider.supportsAnonymous == true`. The provider-list UI MAY
     * additionally use this flag to decide whether to render the toggle.
     */
    val supportsAnonymous: Boolean get() = false
}
