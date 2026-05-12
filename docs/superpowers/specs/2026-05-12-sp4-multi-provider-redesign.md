# SP-4 — Multi-Provider Redesign (Trackers screen removal, generic Credentials, parallel search, sync)

**Status:** specification only — implementation in phases that follow.
**Filed:** 2026-05-12 per operator directives during the v1.2.16 release cycle.

## Problem statement (operator's words, paraphrased)

1. **The Trackers screen exposes a "current tracker" picker, which is
   wrong.** The product MUST work with ALL enabled+configured providers
   simultaneously. The Trackers screen as a switch-between-providers
   surface is deprecated.

2. **API performs search + sync across ALL enabled providers in
   parallel, emits results as async events to subscribers.** Search
   results UI shows results from every responding provider with
   per-row labels identifying which provider returned which row.

3. **Per-provider configuration screen reached by tapping the provider
   name in the Menu.** That screen MUST support:
   - assigning one of the user's stored Credentials
   - creating new Credentials inline
   - adding mirror URLs
   - listing all pre-installed mirrors
   - all other per-provider options
   - connection probing per mirror
   - sync ON / OFF toggle for the provider

4. **CLONE existing provider** — duplicate a provider with a new
   display name + new URL set, so users can search multiple
   instances of the same provider type (e.g. two rutracker mirrors
   on different hosts, including LAN-hosted Tracker instances on
   the local network).

5. **All provider parameters sync via Lava API.** Sync settings of
   the app gate which sub-sets sync and at what cadence.

6. **Generic Credentials** as their own entity:
   - User creates a Credentials entry by name (e.g. "My main creds.")
   - Each Credentials has a type: username+password, token, api-key,
     or any of the supported authentication shapes.
   - Each Credentials carries the actual secret values for its type.
   - **One Credentials entry can be assigned to multiple providers
     simultaneously** (e.g. "My main creds." used for both RuTracker
     and RuTor).
   - Credentials sync via the Lava API and are end-to-end encrypted on
     both client and API sides. No leak of any kind permitted.

7. **Removed entries MUST also be removed from backups.** Once a user
   removes anything, a re-sync is mandatory.

8. **Full test coverage** — unit + integration + Compose UI
   Challenges. Anti-bluff per §6.J: every test asserts on
   user-visible state, falsifiability rehearsal per test, no
   `@Ignore` without tracking issue, no verify-only assertions.

## Why this is multi-phase

The scope crosses every layer of the app + the Go API + the data
model:

- **Domain model:** introduce `CredentialsEntry`, `ProviderConfig`,
  `SyncToggle` types. Migrate existing `ProviderCredentialManager`
  (Phase-11 per-provider username+password) to the new generic shape.
- **Persistence:** Room schema changes (new tables for credentials,
  provider config, sync toggle). Migration from the v17 schema.
  Backup-exclusion rules audit (settings.xml is already excluded as of
  v1.2.16; new credentials table needs encrypted-at-rest semantics
  AND sync-back semantics).
- **Network:** new Go API endpoints for credentials + provider config
  sync, with end-to-end encryption (operator-supplied key material;
  the existing per-build LAVA_AUTH crypto chain is the starting
  point but credentials need a separate per-user secret derivation).
- **SDK:** `LavaTrackerSdk` learns to fan-out search/browse/auth
  across N providers in parallel and emit per-provider events on a
  SharedFlow. Backpressure + cancellation semantics specified.
- **UI:**
  - Search results screen: render per-provider rows with provider
    labels + provider color dot.
  - Menu: tap on provider row → per-provider config screen (new).
  - Per-provider config screen: assign / create / pick credentials,
    list + add mirrors, probe, clone-with-new-name, sync toggle.
  - Trackers screen: remove the picker entirely. The screen either
    goes away or becomes a per-provider summary (TBD in phase plan).
- **Tests:** every layer needs unit + integration + Compose UI
  Challenge coverage. §6.J primary-on-user-visible-state. Anti-bluff
  rehearsals recorded per commit.
- **Documentation:** user guide, developer guide, architecture
  diagram updates. The Continuation document tracks phase status.

A realistic phased breakdown (see `docs/superpowers/plans/` for the
implementation plan that follows this design):

- **Phase A — Generic Credentials model.** Domain types + Room
  migration + per-build encrypted-at-rest persistence + unit tests +
  Challenge for the create-credentials flow.
- **Phase B — Per-provider config screen.** Compose screen reachable
  from Menu tap on provider row. Assign existing credentials, add
  mirror URL, probe. Per-provider sync ON/OFF toggle. Challenge for
  full screen interactions.
- **Phase C — Trackers screen removal.** Replace the screen with a
  multi-provider summary OR delete from the navigation graph. C04-C08
  Challenges remapped or rewritten honestly.
- **Phase D — Multi-provider parallel search SDK.** SharedFlow-based
  fan-out in `LavaTrackerSdk`. Per-provider result labeling. Search
  results UI updated to render labels. Cancellation + backpressure
  tests.
- **Phase E — Credentials sync via Lava API.** New Go endpoints
  `/v1/credentials` (PUT/DELETE/GET) with per-user key encryption.
  Client-side sync mechanism + retry. End-to-end encryption tests.
- **Phase F — Provider clone-with-new-name.** UI clone affordance.
  Domain support for "multiple instances of the same provider type
  with different mirror sets". Challenge for clone+search the clone.
- **Phase G — Removal-syncs-to-backup.** Soft-delete with sync-up
  semantics so a removal propagates to other devices and survives
  reinstall via backup. Backup-restore test on a second emulator.
- **Phase H — Documentation refresh.** User guide, developer guide,
  diagrams, CONTINUATION.md final state.

Each phase ships its own commit (or commit chain) and its own
distribute round. Anti-bluff is enforced phase-by-phase: a phase that
cannot produce a green Challenge Test against the real production
stack is not marked complete.

## Out of scope for SP-4

- **iOS client** — Lava is Android-only as of v1.2.16.
- **Cross-tracker deduplication** (treating two mirrors of the same
  rutracker post as one logical search result) — separate SP if the
  operator requests it later.
- **Per-credential MFA** — the credential types in scope are
  username+password, token, api-key. Adding TOTP / push-MFA is a
  future SP.

## Operator's invariants reasserted in the design

- §6.J anti-bluff: every Challenge Test asserts on user-visible
  state. No verify-only. No `@Ignore` without an issue. No
  mock-the-SUT.
- §6.H credential security: credentials never appear in tracked
  files, in logs, or in non-encrypted persistence. All credential
  paths through `LavaAuth` style codegen-pepper-derived AES-GCM keys
  for at-rest; the per-user-key-derivation for sync is a Phase E
  design item.
- §6.P distribution versioning: each phase that ships an artifact
  bumps versionCode strictly + carries a CHANGELOG entry +
  per-version snapshot.
- §6.S CONTINUATION.md: every phase that lands updates
  CONTINUATION.md in the same commit.
- §6.W mirrors: GitHub + GitLab only, both updated in lockstep.

## Forensic anchor

Operator's directives during the v1.2.15 / v1.2.16 release cycle
(2026-05-12 sequence after the C03 + Cloudflare + anti-bluff cycle):

> "When opening the Trackers screen, we can switch between the
> Providers!? This is not how we have requested in last iterations
> to be! WE MUST work with all enabled and configured providers!"

> "The whole Trackers screen is not needed anymore! We MUST make
> sure we can open Provider configuration screen my tap on it from
> Menu on its name."

> "Make sure we can CLONE existing provider and assign it a new name
> and new URL so practically users can sync and search multiple
> instances running on various hosting platforms of particular
> Tracker (Provider) type we support!"

> "Make sure all Providers DO SYNC and can be toggled for sync-ing
> ON and OFF!"

> "The Credentials we create MUST BE generic! ... heavily protected
> and encryped on both sides ... user can choose for one or more
> providers the Credentials. Like this 'My main creds.' can be used
> at the same time for RuTracker provider and RuTor provider!"

The 19th §6.L invocation (same 2026-05-12 wave) reaffirms the
anti-bluff mandate: every phase MUST guarantee real-user-visible
functionality, not green-tests-with-broken-features.
