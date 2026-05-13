# SP-4 Phase F — Clone-with-new-name actually searchable (detailed design)

**Spec date:** 2026-05-13
**Parent SP:** `docs/superpowers/specs/2026-05-12-sp4-multi-provider-redesign.md`
**Prerequisite:** Phases A + B + C + D complete (HEAD `657f74c1`).
**Status:** detailed-design locked. Implementation plan in `docs/superpowers/plans/2026-05-13-sp4-phase-f-implementation.md`.

## Goal (from parent SP-4 design)

> **Phase F — Provider clone-with-new-name.** UI clone affordance. Domain support for "multiple instances of the same provider type with different mirror sets". Challenge for clone+search the clone.

## Current state audit (post Phase D, HEAD `657f74c1`)

| Surface | What's there | What's missing |
|---|---|---|
| Phase B `CloneSection` UI | `ProviderConfigAction.ConfirmClone(displayName, primaryUrl)` → `CloneProviderUseCase` → `ClonedProviderDao` row + `SyncOutbox` enqueue | OK |
| Phase A `ClonedProviderDao` / `ClonedProviderEntity` | Persists `(syntheticId, sourceTrackerId, displayName, primaryUrl)` | OK |
| `LavaTrackerSdk.listAvailableTrackers()` | Returns base + synthetic `ClonedTrackerDescriptor`s (capabilities + AuthType + apiSupported all delegate to source; trackerId + displayName + baseUrls are the clone's) | OK |
| `LavaTrackerSdk.search` / `multiSearch` / `streamMultiSearch` | All call `registry.get(id, MapPluginConfig())` synchronously | **CRASHES on clone IDs** — `DefaultPluginRegistry.get(id)` throws `IllegalArgumentException: Unknown plugin id: rutracker.clone.<uuid>` because clones have no registered factory. Phase B looks complete but the search path against clones is dead. |
| Source tracker clients (e.g. `RuTrackerClient`) | Construct their HTTP/Ktor stack with `descriptor.baseUrls` baked in at factory.create-time. | URL override is per-factory; no `PluginConfig.baseUrlOverride` contract today. |

## What this is and isn't

This phase is **F.1** — close the SDK crash + tag results with clone id so the multi-provider UI correctly displays "from RuTracker EU" instead of "from RuTracker". Searches against a clone route to the **source** tracker's TrackerClient and use the **source's** baseUrls — the clone's `primaryUrl` is recorded in the DAO but NOT yet routed.

**F.2 (owed, separate phase)** wires the URL override end-to-end: per-clone `MirrorManager` OR `PluginConfig.baseUrlOverride` support across factories, so a search against the "RuTracker EU" clone hits `rutracker.eu` instead of `rutracker.org`.

**Honest disclosure:** when the user creates a clone, the ConfirmClone Toast text changes from "Cloned" to "Cloned (URL routing pending — searches use source URLs until Phase F.2)" so the user is not misled about what the clone is doing today. This is the §6.J anti-bluff mechanic: ship value, name the gap, no false promise.

## Design decisions (locked)

### F-D1 — Resolution at the SDK seam, not in the registry

The clone's synthetic id is NOT registered as a separate factory. Instead, every SDK call that does `registry.get(id, MapPluginConfig())` goes through a new private suspend helper `clientFor(id: String): TrackerClient` that:

- If `id` is a synthetic clone (looked up in `ClonedProviderDao`): construct a wrapping `ClonedRoutingTrackerClient` that
  - Reports the clone's descriptor (via `ClonedTrackerDescriptor`)
  - Delegates `getFeature(...)` to a **clone-aware shim** that wraps the source feature: calls the source feature's method, then re-tags result items with the clone's synthetic id.
- Else: `registry.get(id, MapPluginConfig())` as today.

Why at the SDK seam, not at the registry: keeping the synthetic-id resolution out of the registry preserves the registry's contract ("if you call register, you can call get"; clones live outside that contract — they're a Lava-domain composition on top).

### F-D2 — Result re-tagging

`SearchableTracker.search(...)` returns `SearchResult(items, totalPages, currentPage)` where each `TorrentItem` carries `trackerId`. The clone-shim wraps the source feature and re-maps `items` to have `trackerId = clone.syntheticId` so the multi-provider UI's per-provider grouping shows the clone's display name correctly.

Same pattern for `BrowsableTracker.browse(...) → BrowseResult` items, and `FavoritesTracker.list()` items.

### F-D3 — getActiveClient also resolved

`LavaTrackerSdk.search / browse / getTopic / login / checkAuth / logout / downloadTorrent / getMagnetLink / addFavorite / removeFavorite / getCommentsPage / addComment / getForumTree / getFavorites` ALL call `getActiveClient()` which does `registry.get(activeTrackerId, ...)`. If a user marks a clone as active (legacy `switchTracker(...)` — now `@Deprecated`), every one of those crashes today.

`getActiveClient()` is refactored to a suspend `getActiveClientSuspend()` that uses `clientFor(activeTrackerId)`. The non-suspend overload is kept as a deprecated fallback that calls `runBlocking { ... }` (consistent with the existing `listAvailableTrackers()` pattern that already does this for `ClonedProviderDao.getAll()`).

### F-D4 — UI disclosure of the F.2 gap

`ProviderConfigSideEffect.ShowToast("Cloned")` becomes `ShowToast("Cloned (URL routing pending — searches use source URLs)")`. No new UI surfaces; just honest copy.

### F-D5 — `UserMirrorDao` keyed by clone id

When the user adds a custom mirror for a clone (via Phase B's MirrorsSection), the `UserMirrorEntity.trackerId` field carries the clone's synthetic id. The infrastructure already supports this — DAO doesn't care if the trackerId is a clone or not. F.2 will read this DAO to construct the per-clone MirrorManager.

## Affected surfaces

### Files to add

- `core/tracker/client/src/main/kotlin/lava/tracker/client/ClonedRoutingTrackerClient.kt` — the wrapping client.
- `core/tracker/client/src/test/kotlin/lava/tracker/client/LavaTrackerSdkCloneSearchTest.kt` — unit tests: search a clone returns items tagged with clone id; the source's parser ran (asserted by the items' contents).

### Files to modify

- `core/tracker/client/src/main/kotlin/lava/tracker/client/LavaTrackerSdk.kt`:
  - Add `private suspend fun clientFor(id: String): TrackerClient`.
  - Replace every `registry.get(id, MapPluginConfig())` call with `clientFor(id)`. (~12 call sites: search, browse, getTopic, getTopicPage, getCommentsPage, addComment, downloadTorrent, getMagnetLink, getForumTree, getFavorites, addFavorite, removeFavorite, login, checkAuth, logout, multiSearch, streamMultiSearch).
  - `getActiveClient()` → `getActiveClientSuspend()` for callers that already suspend; the non-suspend version is kept for the `internal` SwitchingNetworkApi compat path.
- `feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigViewModel.kt` — Toast copy updated.
- `docs/CONTINUATION.md` — Phase F.1 landed; F.2 owed.

## Challenge Tests

**C34 (new)** `CloneAndSearchClonedProviderTest.kt`:
1. Start on Menu → tap RuTracker.org provider row → ProviderConfig → tap "Clone provider…" → fill name "RuTracker EU" + URL "https://rutracker.eu" → Confirm.
2. Toast "Cloned (URL routing pending...)" displayed.
3. Press back → Menu now shows two RuTracker rows: original + "RuTracker EU".
4. Tap "RuTracker EU" → ProviderConfig opens correctly.

Operator-bound (gating emulator).

Falsifiability rehearsal for the unit test:

**Unit test 1** asserts that `sdk.multiSearch(clone-id)` does NOT throw (today it does throw `IllegalArgumentException`). Mutation: revert `clientFor(id)` back to `registry.get(id, MapPluginConfig())`. Test fails with the same IllegalArgumentException.

**Unit test 2** asserts that returned items carry the CLONE's synthetic id, not the SOURCE's id. Mutation: in the clone-shim, return source items unmodified (skip the re-tag). Test fails — the items' trackerId is the source's id.

## Anti-bluff posture

Per §6.J / §6.L:

1. The Toast copy "(URL routing pending — searches use source URLs)" disclosures the F.2 gap at the moment the user creates the clone — no false promise about the URL override.
2. Unit tests assert on user-observable outcomes: no crash, items tagged with clone id, source parser actually ran.
3. CONTINUATION.md explicitly names F.2 as owed; the SP-4 sequence record includes the gap.
4. F.1 alone IS a complete user-visible improvement: today the search path against clones is a crash; after F.1 the clone appears as a normal-looking provider that returns results. The URL gap is documented, not hidden.

## Out of scope for Phase F (this is F.2 owed)

- Per-clone `MirrorManager` wiring so search HTTP traffic actually hits the clone's `primaryUrl`.
- Result-content variation when source URLs are degraded (Phase F.2 lets clones be a failover surface).
- Removing the Toast copy disclosure (it stays until F.2 ships).

## Operator's invariants reasserted

- §6.J: searching a clone now produces a real, user-visible outcome (items in the search-result list, correctly tagged with the clone's display name).
- §6.Q: no UI layout changes; ProviderConfig screen is unchanged structurally.
- §6.R: no new hardcoded literals.
- §6.S: CONTINUATION updated in implementation commit.
- §6.W: pushed to both mirrors in lockstep.

## Implementation plan

See `docs/superpowers/plans/2026-05-13-sp4-phase-f-implementation.md` — 5 tasks, ~15 steps.
