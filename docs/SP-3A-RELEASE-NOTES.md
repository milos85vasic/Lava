# Lava-Android-1.2.0-1020 — Release Notes (SP-3a)

> **Status:** Feature-complete (HEAD `b899474` at the start of the
> documentation-polish pass; subsequent commits are doc-only). Tagging is
> **BLOCKED** pending operator real-device attestation per Tasks 5.22 / 5.25
> / 4.20 — see [Operator verification checklist](#operator-verification-checklist) below.
>
> **Audience:** Lava operators preparing to cut the 1.2.0 tag, plus
> downstream users wanting a one-page summary of what landed.

---

## 1. What shipped

The SP-3a "Multi-Tracker SDK Foundation" arc replaces the rutracker-only
Lava client with a multi-tracker SDK. Lava-Android-1.2.0-1020 ships with
two trackers — **RuTracker** (existing, decoupled in Phase 2) and
**RuTor** (new, added in Phase 3) — and the scaffolding to add a third
without touching the app module.

User-visible features:

- **Tracker selector in Settings → Trackers.** RuTracker (the existing
  default) and RuTor (new) appear side by side; tapping either sets it
  active. The active tracker drives every search / browse / topic /
  download invocation.
- **Per-tracker mirror lists.** Bundled defaults plus user-added
  customs. Each row carries a colored health dot (HEALTHY / DEGRADED /
  UNHEALTHY / UNKNOWN).
- **Add custom mirror dialog.** Operators can extend the bundled set
  per tracker; entries persist in the `tracker_mirror_user` Room
  table.
- **Cross-tracker fallback modal.** When all mirrors of the active
  tracker hit UNHEALTHY, the SDK prompts the user with "All RuTracker
  mirrors are unhealthy. Try RuTor?". Accept → re-issues the query
  on the alt tracker. Dismiss → explicit failure UI (snackbar). **No
  silent fallback** is possible.
- **15-minute health probe.** A WorkManager `PeriodicWorkRequest`
  HEAD-probes every registered mirror every 15 minutes and updates
  the persisted health state.
- **Anonymous-by-default RuTor flow.** Searching, browsing, viewing
  topics, and downloading torrents work without a login. Login is
  invoked only when the user attempts an authenticated operation
  (post a comment, in 1.2.0).

Internal SDK shape:

- A new `:core:tracker:*` module family (api, registry, mirror,
  client, rutracker, rutor, testing).
- A Hilt-injected orchestrator (`LavaTrackerSdk`) that feature
  ViewModels consume.
- A pluggable per-tracker plugin shape — adding a third tracker is a
  documented 7-step recipe in
  [`docs/sdk-developer-guide.md`](sdk-developer-guide.md).
- Generic primitives (`MirrorUrl`, `Protocol`, `Mirror` state machine,
  in-memory registry, mirror-config store, test scaffolding) extracted
  to a `vasic-digital/Tracker-SDK` submodule mounted at
  `submodules/tracker_sdk/` per the Decoupled Reusable Architecture
  rule.

For the architectural overview see
[`docs/ARCHITECTURE.md` § Multi-Tracker SDK](ARCHITECTURE.md#multi-tracker-sdk-sp-3a-120).
For the design rationale and the 14-section design doc see
[`docs/superpowers/specs/2026-04-30-sp3a-multi-tracker-sdk-foundation-design.md`](superpowers/specs/2026-04-30-sp3a-multi-tracker-sdk-foundation-design.md).

---

## 2. What changed (commit-level summary)

### Phase commit counts

| Phase                                | Scope                                                                | Commits |
|--------------------------------------|----------------------------------------------------------------------|---------|
| Phase 0 — Pre-implementation audit   | Ledger seeding, fake-equivalence test scaffolding, fixture protocol  | 5       |
| Phase 1 — Foundation                 | `core:tracker:api/registry/mirror/testing`, capability enum, DTOs    | 12      |
| Phase 2 — RuTracker decoupling       | git-mv from `core/network/rutracker`, `RuTrackerClient`, parser refit| 40      |
| Phase 3 — RuTor implementation       | Descriptor, parsers, feature impls, fixtures, `RealRuTorIntegrationTest` | 41   |
| Phase 4 — Mirror health + UI         | `MirrorHealthCheckWorker`, `CrossTrackerFallbackPolicy`, `feature:tracker_settings` | 20 |
| Phase 5 — Constitution + Challenges  | Seventh Law, 8 Compose UI Challenge Tests, scripts/ci.sh, tag gate   | 26      |
| Misc                                 | Seventh Law landing, JVM-17 hardening, bluff audit wraps             | 4       |
| Docs polish (this release)           | README, ARCHITECTURE, SDK guide, per-module READMEs, release notes   | 5       |
| **SP-3a total**                      |                                                                      | **153** |

### Test count delta (SP-3a → 1.2.0)

| Surface                                       | Count |
|-----------------------------------------------|-------|
| New `*Test.kt` files in `core/tracker/*`      | 43    |
| New `*Test.kt` files anywhere in the repo     | 60+   |
| Compose UI Challenge Tests in `:app`          | 8     |
| Real-tracker integration test (gated)         | 1 (`RealRuTorIntegrationTest`) |

Pre-SP-3a the project had **one** unit-test file
(`EndpointConverterTest`) and zero Compose UI tests. The 1.2.0 release
ships **>60 unit-test files** including the eight Compose UI Challenge
Tests that are the operator-attested acceptance gate.

---

## 3. Latent findings summary

Tracked in
[`docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`](superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md):

| Finding | State    | Mitigation trigger                                              |
|---------|----------|-----------------------------------------------------------------|
| LF-1    | OPEN     | First `MenuViewModel` test path that exercises bookmarks        |
| LF-2    | OPEN     | Re-introducing any `healthcheck:` block on lava-api-go          |
| LF-3    | RESOLVED | (Phase 2 Section E wrap-up; Tracker-SDK pin enforces JVM 17)    |
| LF-5    | OPEN     | `RuTrackerDescriptor` declares UPLOAD/USER_PROFILE without backing feature interfaces — fix before any phase that depends on those capabilities |
| LF-6    | OPEN     | `TorrentItem.sizeBytes` permanently null for rutracker — fix before any cross-tracker numeric-size comparison |

None of these findings block the 1.2.0 release. Each is a forward-looking
tripwire with a documented mitigation trigger.

---

## 4. Operator verification checklist

> **Tag is BLOCKED until every box below is ticked.** `scripts/tag.sh`
> mechanically refuses without the evidence pack.

### Pre-conditions

- [ ] `git rev-parse HEAD` matches the commit being tagged.
- [ ] A physical Android device (API 26+, internet access) is connected;
      `adb devices` shows it as `device`.
- [ ] Host machine plugged in (not battery-only).
- [ ] `KEYSTORE_PASSWORD` and `KEYSTORE_ROOT_DIR` set in `.env`.
- [ ] `submodules/tracker_sdk/` is at the pinned hash; `git submodule
      status` shows no `-` or `+` prefix.

### Build + install

```bash
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:installDebug
```

- [ ] APK builds + installs without error.
- [ ] App icon `Lava (dev)` appears in launcher.
- [ ] First launch shows home screen without a crash dialog.

### Local CI gate (Layer 2 of the pre-push hook, full mode)

```bash
./scripts/ci.sh --full
```

- [ ] Exit code 0.
- [ ] Output saved as
      `.lava-ci-evidence/Lava-Android-1.2.0-1020/ci.sh.json`.

### 8 Challenge Tests (C1–C8)

Each test is documented in
`app/src/androidTest/kotlin/lava/app/challenges/Challenge0<n>*Test.kt`.
For each `n` ∈ {1..8}:

1. Run / perform the scenario on the device.
2. Capture a screenshot of the user-visible state described in the
   test's primary assertion.
3. Apply the falsifiability mutation listed in the test header,
   re-run, capture verbatim failure assertion.
4. Revert mutation, re-run, confirm pass.
5. Update
   `.lava-ci-evidence/Lava-Android-1.2.0-1020/challenges/C<n>.json`:

```json
{
  "challenge_id": "C<n>",
  "status": "VERIFIED",
  "test_run_timestamp": "<iso8601>",
  "device": "<model + Android version>",
  "rehearsal_observed_failure": "<verbatim assertion message>",
  "screenshots_sha256": ["<hash1>", "<hash2>"]
}
```

Checklist:

- [ ] **C1** App launch + tracker selection — RuTracker + RuTor both
      visible in Settings → Trackers; tapping either updates active.
- [ ] **C2** Authenticated search on RuTracker — login, search "ubuntu",
      ≥1 result row with size + seeders.
- [ ] **C3** Anonymous search on RuTor — switch to RuTor, search
      "ubuntu", ≥1 result row with size + seeders. No login prompt.
- [ ] **C4** Switch tracker and re-search — banner changes from
      "Results from RuTracker" to "Results from RuTor".
- [ ] **C5** View topic detail — title, description, file list, magnet
      URI button all rendered.
- [ ] **C6** Download `.torrent` file — file written to app downloads
      dir, parses as bencoded torrent (`info.pieces` present).
- [ ] **C7** Cross-tracker fallback accept — with all RuTracker mirrors
      UNHEALTHY (test seed), modal appears, "Try RuTor" shows RuTor
      results.
- [ ] **C8** Cross-tracker fallback dismiss — same setup, "Cancel"
      shows explicit failure UI; **NO silent fallback to RuTor**.

### Mirror smoke

- [ ] `submodules/tracker_sdk` at pinned hash; `scripts/sync-tracker-sdk-mirrors.sh
      --check` reports OK.
- [ ] Mirror smoke output saved to
      `.lava-ci-evidence/Lava-Android-1.2.0-1020/mirror-smoke/`.

### Bluff hunt (Seventh Law clause 5)

```bash
./scripts/bluff-hunt.sh
```

- [ ] Pass — 5 randomly selected `*Test.kt` files mutate-and-fail.
- [ ] Output appended to `.lava-ci-evidence/bluff-hunt/<date>.json`.

### Real-device attestation sign-off

- [ ] Replace `status: PENDING_OPERATOR` with `status: VERIFIED` at the
      top of
      `.lava-ci-evidence/Lava-Android-1.2.0-1020/real-device-verification.md`.
- [ ] Append the sign-off block:

```
verified_by:        <operator name or signer key id>
verified_at:        <iso8601 timestamp>
commit_sha:         <git rev-parse HEAD at verification time>
device:             <model + Android version + IMEI/serial>
evidence_zip_sha256: <sha256 of zipped evidence pack>
```

---

## 5. Tag protocol (after operator attestation lands)

Once every box in §4 is ticked and `real-device-verification.md` reports
`status: VERIFIED`:

```bash
# 1. Confirm pre-conditions one more time.
git rev-parse HEAD                        # matches the tagged commit
git submodule status                      # no -/+ prefix on Tracker-SDK
ls .lava-ci-evidence/Lava-Android-1.2.0-1020/challenges/ \
  | xargs -I{} grep -l '"status".*"VERIFIED"' \
    .lava-ci-evidence/Lava-Android-1.2.0-1020/challenges/{}
# expected: C1.json … C8.json all listed (eight matches)

# 2. Tag + push, all 4 upstreams.
./scripts/tag.sh --app android --bump patch

# scripts/tag.sh internally:
#   - calls require_evidence_for_android (validates the evidence pack)
#   - creates Lava-Android-1.2.0-1020 tag at HEAD
#   - pushes the tag to github + gitflic + gitlab + gitverse
#   - bumps versionName to 1.2.1 / versionCode to 1021 in :app
#   - commits the bump and pushes the bump commit
#   - per clause 6.C: verifies post-push tip-SHA convergence across
#     all four mirrors; refuses to report success on divergence

# 3. Confirm convergence.
for r in github gitflic gitlab gitverse; do
  echo "$r: $(git ls-remote $r refs/tags/Lava-Android-1.2.0-1020 | awk '{print $1}')"
done
# expected: identical SHA on all four lines.
```

If `scripts/tag.sh` exits non-zero on the evidence-pack gate, treat the
output as the authoritative diagnosis — fix the gap (typically a
missing `VERIFIED` status on a Challenge Test attestation) and re-run.
Routine bypass (`--no-evidence-required` outside dry-run rehearsals) is
itself a Seventh Law incident.

---

## 6. Deferred to SP-3a-bridge

Items intentionally deferred (not regressions; they were never SP-3a's
scope):

- **Third tracker.** The 7-step recipe is in
  [`docs/sdk-developer-guide.md`](sdk-developer-guide.md); the first
  third-tracker module will exercise it end-to-end.
- **`UploadableTracker` + `ProfileTracker` feature interfaces.** RuTracker's
  `RuTrackerInnerApi` already has the underlying scrapers; LF-5 tracks
  the surfacing.
- **Numeric `TorrentItem.sizeBytes` for RuTracker.** LF-6 tracks the
  byte-count parser refit.
- **`connectedAndroidTest` runner wiring.** The 8 Challenge Tests run as
  manual operator scenarios in 1.2.0; the gradle task wiring lands in
  SP-3a-bridge.
- **Region-aware mirror health probes.** `mirrors.json` schema reserves
  the `region` field; routing logic that uses it is post-1.2.0.

---

## 7. Cross-references

- Architecture overview: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)
- Developer guide for adding a tracker:
  [`docs/sdk-developer-guide.md`](sdk-developer-guide.md)
- Coverage exemption ledger:
  [`docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`](superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md)
- Full design doc:
  [`docs/superpowers/specs/2026-04-30-sp3a-multi-tracker-sdk-foundation-design.md`](superpowers/specs/2026-04-30-sp3a-multi-tracker-sdk-foundation-design.md)
- Constitutional law: root [`CLAUDE.md`](../CLAUDE.md) (Anti-Bluff Pact,
  Seventh Law, Local-Only CI/CD rule, Decoupled Reusable Architecture
  rule)
- Per-module READMEs:
  - [`core/tracker/api/README.md`](../core/tracker/api/README.md)
  - [`core/tracker/client/README.md`](../core/tracker/client/README.md)
  - [`core/tracker/registry/README.md`](../core/tracker/registry/README.md)
  - [`core/tracker/mirror/README.md`](../core/tracker/mirror/README.md)
  - [`core/tracker/rutracker/README.md`](../core/tracker/rutracker/README.md)
  - [`core/tracker/rutor/README.md`](../core/tracker/rutor/README.md)
  - [`core/tracker/testing/README.md`](../core/tracker/testing/README.md)
  - [`feature/tracker_settings/README.md`](../feature/tracker_settings/README.md)

---

*Last updated: 2026-05-01 (SP-3a docs polish; HEAD prior to tag).*
