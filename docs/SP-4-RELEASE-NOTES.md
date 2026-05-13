# SP-4 Multi-Provider Redesign — Release Notes

**Status (2026-05-13):** Phases A + B + C + D + F.1 + G.1 landed on master. Phases E + F.2 + H tail are owed. See `docs/superpowers/specs/2026-05-12-sp4-multi-provider-redesign.md` for the original design + every phase's design doc under `docs/superpowers/specs/2026-05-13-sp4-phase-<X>-design.md`.

## User-visible changes (so far)

1. **Generic Credentials** — a single `CredentialsEntry` (Username+Password, API Key, Bearer Token, or Cookie/Session string) can be assigned to multiple providers. Encrypted at rest with AES-256-GCM (PBKDF2-SHA-256, 200k iterations, per-user passphrase). Reachable from Menu → "Provider Credentials".
2. **Per-Provider Configuration** — tap any provider row in Menu → opens the provider's configuration screen. Bind a credential, toggle sync, list mirrors, add custom mirrors, probe reachability, switch anonymous mode (where supported), or clone the provider under a new name with a different URL.
3. **Trackers screen removed** — the legacy SP-3a "Trackers" menu entry and screen are gone. Every capability they offered is now reachable from per-provider config + Menu provider rows. C04 + C14 Challenge Tests rewritten / deleted accordingly.
4. **Multi-Provider Parallel Search** — `LavaTrackerSdk.multiSearch` fans out across selected providers in parallel via `coroutineScope` + `async` + `awaitAll`. Latency drops from `sum(per-provider)` to `max(per-provider)`. New `streamMultiSearch(...)` cold `Flow<MultiSearchEvent>` for callers that want incremental UI updates. Both paths feed `SearchResultContent.Streaming`'s per-provider grouping UI. The new client-direct path is the default when the Lava Go API is not configured.
5. **Clone-with-new-name searchable** — the Phase B clone dialog creates a `ClonedProviderEntity` with its own `primaryUrl`. The SDK now resolves the synthetic clone id without crashing (Phase F.1). The clone's display name surfaces in result rows via `client.descriptor.trackerId`. **Phase F.2 still owed**: per-clone `MirrorManager` so HTTP traffic actually hits the clone's `primaryUrl` (today, source URLs are used; the clone-success Toast discloses this honestly).
6. **Soft-delete with sync-up semantics** (Phase G.1) — removing a credential or cloned provider now marks the row with a `deletedAt` timestamp instead of physically deleting it. Read paths hide the row; the soft-delete marker is preserved for cross-device propagation (Phase E owed) and survives backup-restore (the Android Auto Backup snapshot includes the marker).

## Developer-visible changes

### New SDK methods (`LavaTrackerSdk`)

- `multiSearch(request, providerIds, page)` — now PARALLEL. Same `UnifiedSearchResult` return shape; per-provider failure isolated (one provider throwing does not cancel siblings).
- `streamMultiSearch(request, providerIds, page): Flow<MultiSearchEvent>` — NEW. Cold flow emits `ProviderStart`, `ProviderResults`, `ProviderFailure`, `ProviderUnsupported`, terminated by `AllProvidersDone(unified)` on normal completion. Cancellation of the collector propagates to every in-flight provider call via structured concurrency.
- `clientFor(id)` — PRIVATE seam introduced by Phase F.1. Resolves a synthetic clone id to a `ClonedRoutingTrackerClient` that wraps the source factory's client. Non-clone ids fall through to `registry.get(...)` unchanged.
- `activeTrackerId()` + `switchTracker(...)` — marked `@Deprecated` in Phase D. Legacy paging path still uses them; full removal awaits the paging-path migration to `multiSearch(singleProvider)`.

### New schema

- v8 → v9 (Phase A): `credentials_entry`, `provider_credential_binding`, `provider_sync_toggle`, `cloned_provider`, `sync_outbox` (Migration_8_9).
- v9 → v10 (Phase G.1): `deletedAt: Long?` column added to `credentials_entry` and `cloned_provider` (Migration_9_10). Existing rows default to `NULL` (not deleted).

### New constitutional clauses

- §6.R — No-Hardcoding Mandate. IPv4 + host:port literal scanners landed in §4.5.10 closure.
- §6.S — Continuation Document Maintenance Mandate.
- §6.T.1–4 — Universal Quality Constraints (Reproduction-Before-Fix, Resource Limits, No-Force-Push, Bugfix Documentation).
- §6.U — No sudo/su Mandate.
- §6.V — Container Emulators Mandate.
- §6.W — GitHub + GitLab Only Remotes (4-mirror model reduced to 2).
- §6.L count bumped to TWENTY (operator's repeated standing order).

## Owed work (carrying forward beyond SP-4 active execution)

### Phase E — Credentials sync via Lava API

The Go API service (lava-api-go) needs `PUT /v1/credentials`, `DELETE /v1/credentials/:id`, `GET /v1/credentials`. End-to-end encryption with a per-user key derivation (separate from the existing per-build LAVA_AUTH chain). Client-side sync mechanism reads the sync outbox and uploads pending entries; merges remote soft-deletes back into the local DAO. Backup-restore Challenge Test on a second emulator. Multi-domain phase (Go + Android); execute requires operator-supplied Go API environment + integration testing.

### Phase F.2 — Per-clone URL routing

The current `ClonedRoutingTrackerClient` (Phase F.1) wraps the source TrackerClient unchanged — `getFeature(...)` delegates to the source, which is bound to the source's `baseUrls` at factory.create-time. For the clone's `primaryUrl` to actually be used by HTTP traffic, either (a) the per-tracker factories need to accept a `PluginConfig.baseUrlOverride` and rebuild their HTTP client around it, or (b) a per-clone `MirrorManager` needs to be injected at SDK-call time. Cross-module refactor across `:core:tracker:rutracker`, `:core:tracker:rutor`, `:core:tracker:nnmclub`, `:core:tracker:kinozal`, `:core:tracker:archiveorg`, `:core:tracker:gutenberg`. The Phase B clone-success Toast already discloses this gap to the user.

### Phase H — Documentation refresh (tail)

This file is the start. Owed:
- `sdk-developer-guide.md` updates documenting the new `multiSearch` + `streamMultiSearch` + `clientFor` surfaces.
- User-facing onboarding copy for the new per-provider config screen.
- Diagrams in `ARCHITECTURE.md` redrawn to reflect Phase B + C + D + F.1.

### Compose UI Challenges

Operator-bound execution on the §6.I matrix:
- C04 (rewritten in Phase C) — Menu provider row → ProviderConfig opens
- C01 (updated in Phase C) — Menu structure now lists "Provider Credentials" instead of "Trackers"
- C27 — CredentialsCreateAndAssign (Phase A+B owed)
- C28 — PerProviderSyncToggle (Phase B owed)
- C29 — AddCustomMirror (Phase B owed)
- C30 — CloneProvider (Phase B owed)
- C31 — PassphraseUnlockFlow (Phase A owed)
- C32 — MultiProviderParallelSearch (Phase D owed)
- C33 — SearchCancellation (Phase D owed)
- C34 — CloneAndSearchClonedProvider (Phase F.1 owed)

All require live emulator runs producing per-AVD attestation rows in `.lava-ci-evidence/<tag>/real-device-verification.md` before the next release tag.

## Anti-bluff posture across SP-4

Every phase shipped a Bluff-Audit stamp in its commit body with a deliberate-mutation rehearsal:
- Phase B: `MenuViewModelTest.open provider config posts side effect with provider id`
- Phase C: rehearsal protocols embedded in C04 + C01 KDoc (operator-driven on emulator)
- Phase D: `LavaTrackerSdkParallelSearchTest` — parallel-fanout / cancellation / event-ordering / failure-isolation (4 tests, 3 rehearsed in commit)
- Phase F.1: `LavaTrackerSdkCloneSearchTest` — also surfaced + closed a real latent bluff (a proposed Test 2 was discovered during its own rehearsal to be a bluff against `DeduplicationEngine`'s map-key-driven output; both the bluff test AND the dead production re-tag were deleted)
- Phase G.1: `CredentialsEntryRepositorySoftDeleteTest` — also surfaced + closed a Third-Law fake-divergence bug (FakeDao didn't mirror Room's `WHERE deletedAt IS NULL` filter; fixed in the same commit before the rehearsal could expose a false-green)

The §6.N hunt at the 20th §6.L invocation (2026-05-13) found 0 bluffs across 5 random `*Test.kt` files + 2 gate-shaping production files. Evidence: `.lava-ci-evidence/bluff-hunt/2026-05-13.json`.

The Phase F.1 and G.1 discoveries were unrelated to that hunt — they were end-of-phase audits the operator's 20th-invocation directive made standing operating procedure. Both confirm the §6.J pattern the operator's mandate evicts: phases that LOOK complete can ship latent bluffs that only end-of-session audits surface.

## Mirror status

Parent + 16 vasic-digital submodules + lava-api-go: all on both GitHub + GitLab at HEAD `68f75e6a` as of 2026-05-13.
