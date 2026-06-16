# REDISTRIBUTE-READY — 2026-06-16

Prep for the §6.AA two-stage redistribute of **client** + **api-app** after the overnight
10-provider retry-resilience work landed in `lava-api-go`. Authored as artifact-prep only —
**nothing distributed; the §6.Z device gate is still owed** (see Blockers).

## What changed (the reason for the redistribute)

Three overnight commits added bounded transient-failure retry to **10 providers** in the
embedded `lava-api-go` (Go-only; **zero `.kt` changes** — confirmed via `git show --name-only`):

- `a88467df` — tokyotosho bounded retry (nezha real-tracker E2E finding) + api-go bump 2.3.30→2.3.31 / 2330→2331
- `cd54341c` — propagate to 6 siblings: knaben, nyaa, bitsearch, torrentdownloads, gutenberg, flaresolverr
- `307b4a5d` — retry-inside-breaker for kinozal, nnmclub, rutracker

Both APKs embed `liblavaapi.so`, so both are behaviorally different at the embedded-API layer
(`LAVA_API_SOURCE_HASH` changes) → both warrant a redistribute.

HEAD at prep time: `d256ed1a` (ENOSPC test-guard fix atop the 3 retry commits). Tree clean
except a dirty `submodules/containers` pointer.

## Version-bump decision (§6.Y.3)

| Artifact  | Before (last published) | This build | Rationale |
|-----------|-------------------------|------------|-----------|
| client    | 1.3.10 / 1067           | **1.3.11 / 1068** | User-visible reliability fix (search retries) → patch versionName bump per §6.Y.3 "user-facing bug fix → patch". versionCode 1068 was already bumped post-1067. |
| api-app   | 0.2.10 / 15             | **0.2.11 / 16**   | Same user-visible reliability fix; patch bump. versionCode 16 already bumped post-15. |
| api-go    | 2.3.30 / 2330           | 2.3.31 / 2331 (already bumped in `a88467df`) | Embedded artifact; bumped at fix time. |

versionName patch-bumped (not held) because the change IS user-facing — §6.Y.3 "when in doubt,
bump versionName". versionCodes were already at 1068/16 (>last-distributed 1067/15), so Gate 1
is satisfied without a second code bump.

## Files prepared this session (artifact prep, no commit)

- `app/build.gradle.kts` — versionName `1.3.10` → `1.3.11` (versionCode unchanged at 1068)
- `api-app/build.gradle.kts` — versionName `0.2.10` → `0.2.11` (versionCode unchanged at 16)
- `CHANGELOG.md` — two new top-of-file entries: `Lava-Android-1.3.11-1068`, `Lava-API-App-0.2.11-16`
- `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.3.11-1068.md` (snapshot)
- `.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/0.2.11-16.md` (snapshot)
- this note

## §6.P gates — pre-verified PASS against the prepared artifacts

- Gate 1 (monotonic code): client 1068 > 1067 ✅ ; api-app 16 > 15 ✅
- Gate 2 (CHANGELOG entry): both headers match `firebase-distribute.sh` regex (lines 88 / 98) ✅
- Gate 3 (snapshot present): both per-version snapshot files exist ✅

## Remaining GATED steps (execute in order once the device gate is available)

1. **Edit `.env` (gitignored) — client Gate 5 prerequisite.** Currently
   `LAVA_AUTH_CURRENT_CLIENT_NAME=android-1.3.10-1067`. It MUST become `android-1.3.11-1068`
   (= `android-<versionName>-<versionCode>`), and that exact name MUST be appended to
   `LAVA_AUTH_ACTIVE_CLIENTS=` (currently only `android-1.3.10-1067:`). Without this,
   `firebase-distribute.sh --app client` aborts at Phase-1 Gate 5. Gate 5 is SKIPPED for api-app.
   (Not done here: `.env` is gitignored + carries secrets — operator/runtime edit per §6.H.)
2. **Gate 4 (client) — rotate `LAVA_AUTH_OBFUSCATION_PEPPER` in `.env`** if its SHA already
   appears in `pepper-history.sha256` for a DIFFERENT release id (`openssl rand -base64 32`).
   Reuse WITHIN the same 1.3.11-1068 debug→release pair is allowed.
3. **Build the artifacts** via `./build_and_release.sh` (per §6.K, the gate build goes through
   the Containers path). Produces `releases/1.3.11/android-{debug,release}/` and
   `releases/api-app/0.2.11/android-{debug,release}/`. (Current `releases/` only has 1.3.10 / 0.2.10.)
4. **§6.Z device evidence — THE LOAD-BEARING BLOCKER.** Execute the Compose UI Challenge Tests
   (at minimum C00 cold-start + C01 + any Cn whose target is in the cycle diff) against the
   EXACT APKs about to ship, on the emulator matrix booted via the Containers submodule
   (§6.X / §6.AG / §6.AH — container/VM only, no host-direct, no live ADB device). Capture
   `BUILD SUCCESSFUL` verbatim + AVD/model/API/SHA/timestamp into
   `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.3.11-1068-test-evidence.md`
   and `.../firebase-app-distribution-api-app/0.2.11-16-test-evidence.md`. SHA must equal the
   distributed commit; timestamp ≤24h. **This is currently BLOCKED** — the emu matrix is
   load-blocked on this macOS host (`/dev/kvm` absent in the podman VM; §6.AH-debt / §6.X-debt).
   No host-direct fallback is permitted (§6.AH clause 3). Until a KVM-capable Linux gate host
   (e.g. nezha) runs the matrix, the redistribute CANNOT proceed without violating §6.Z.
5. **Stage 1 — debug.** `./scripts/firebase-distribute.sh --debug-only --app api-app` then
   `--app client`. Advances `last-version-debug` per channel.
6. **Operator verifies the Firebase-distributed debug builds** on the failure-surface device
   (the matched signed pair: client + api-app), real onboarding + live search.
7. **Stage 2 — release.** After written operator confirmation: `--release-only --app api-app`
   then `--app client`. Advances `last-version-release`. §6.AA gate requires stage-1 debug to
   have advanced the same versionCode first.

## Honest status

Artifact prep is COMPLETE and the §6.P gates are pre-verified. The redistribute is **NOT ready
to execute**: the §6.Z device-evidence gate (step 4) is genuinely blocked on the load-blocked
emulator matrix, and steps 1–2 require gitignored `.env` edits not made here. Distributing
without step 4 would be the exact §6.Z bluff that bricked 1.2.19-1039.
