package lava.network.api

/**
 * LVA-052 — Lava-domain seam for resolving the DOWNLOAD shape of a provider so
 * the topic download action can branch HTTP-file (Internet Archive, Project
 * Gutenberg) vs `.torrent` (RuTracker, RuTor, …) WITHOUT `core:domain` ever
 * importing `core:tracker:*`.
 *
 * This mirrors the [HttpDownloadSource] / [NetworkApi] decoupling: the
 * implementation lives in `core:network:impl` and reads the SDK's
 * [lava.tracker.api.TrackerDescriptor.capabilities] (Capability Honesty,
 * clause 6.E — the descriptor declares only the capabilities its `getFeature`
 * actually resolves), then maps the capability set to the domain-safe
 * [ProviderDownloadKind] enum below.
 */
interface ProviderCapabilitySource {
    /**
     * Resolves how the provider [trackerId] serves its downloadable artifact.
     *
     * @param trackerId the provider whose topic is being viewed. When blank,
     *   the implementation falls back to the active tracker — preserving the
     *   legacy single-active-tracker behaviour for callers (favorites /
     *   visited / deep-link) that cannot yet supply an explicit provider id.
     * @return [ProviderDownloadKind.HTTP] when the provider declares
     *   `HTTP_DOWNLOAD` and NOT `TORRENT_DOWNLOAD`; [ProviderDownloadKind.TORRENT]
     *   when it declares `TORRENT_DOWNLOAD`; [ProviderDownloadKind.NONE] when it
     *   declares neither or the provider id is unknown.
     */
    suspend fun downloadKind(trackerId: String): ProviderDownloadKind
}

/**
 * The download shape of a provider — the domain-safe projection of the SDK's
 * `TrackerCapability` set that the topic download action branches on.
 *
 * Kept deliberately minimal (no `core:tracker:*` types) so `core:domain` and
 * the feature layer can consume it without pulling in the SDK.
 */
enum class ProviderDownloadKind {
    /** Provider serves its artifact over plain HTTP(S) (e-book / media file). */
    HTTP,

    /** Provider serves a bencoded `.torrent` (or magnet) artifact. */
    TORRENT,

    /** Provider declares no downloadable artifact, or the id is unknown. */
    NONE,
}
