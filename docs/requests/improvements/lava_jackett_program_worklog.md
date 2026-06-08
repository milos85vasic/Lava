# Lava — Jackett / Comprehensive-QA Program Worklog

> Source request: `docs/requests/improvements/lava_request_jackett.md` (credentials redacted out of it 2026-06-08 per §6.H).
> This worklog is the durable record of the program (§6.S spirit). It carries **no credentials**.
> Started: 2026-06-08.

## 0. Program scope (from the operator request)

1. Fetch/pull latest constitution + all submodules; verify full compliance.
2. Add **all supported test types** that, per supported provider: onboard → search (that provider only) → browse results → validate/verify content → **confirm a real, working download option**: a valid (non-broken) download link, a really-downloaded **non-empty valid `.torrent`**, OR a **100%-valid magnet**. Replace manual human testing.
3. Enumerate + test **every real-human use case**, variations, edge cases.
4. **HelixQA**: already a submodule — build comprehensive test banks + **autonomous per-provider QA sessions** (browse/search/obtain-download). Provide a **Claude↔HelixQA model bridge** (LLM/Vision) + real-time monitoring.
5. **Jackett**: deep analysis; ideally submodule + reference (decision pending — language boundary); else port providers, all fully test-covered.
6. Use the **4 provider credentials** (RuTracker, RuTor, IPTorrents, NNMClub); incorporate unsupported ones fully. No leaks via git/logs.
7. **Full decoupling** — providers easily pluggable; future **Android-extension-APK** providers.
8. Maximally extend all docs/diagrams/SQL/architecture.
9. Regular commit+push of all submodules + main to all upstreams (GitHub+GitLab per §6.W).
10. **Maximal test coverage**, all test types, every line. **Real evidence only — zero bluffs.**

## 1. Done (verifiable)

- **§6.H credential safety**: redacted 4 cred lines from the request md (untracked, but `docs/` not gitignored). Leak scan: `nobody85perf*` exists in git only in already-recorded forensic files (2026-05-17 incident JSON + CLAUDE/CHANGELOG anchors). No new leak.
- **`.env`** (gitignored): added `RUTOR_*`, `IPTORRENTS_*`; RuTracker/NNMClub already matched provided values. Honest header note added; **RuTracker password remains UNROTATED** (standing §6.H operator action).
- **`.env.example`** (tracked): added `RUTOR_*` + `IPTORRENTS_*` placeholders (§6.R).
- **Submodules fetched.** Divergence: constitution −12, helixqa −16, challenges −3, containers −1, security −2; rest current. **Pin bumps deferred** until recon agents (reading current pins) finish.

## 2. Wave-1 recon findings

### Agent 1 — Provider inventory (COMPLETE)

6 providers today:

| Provider | Auth | .torrent | Magnet | §6.E status |
|---|---|---|---|---|
| rutracker | CAPTCHA_LOGIN | ✓ | async-only (sync returns null) | PARTIAL |
| rutor | FORM_LOGIN | ✓ | sync from search HTML | PARTIAL (sync getMagnetLink null) |
| kinozal | FORM_LOGIN | ✓ | **null** (but present in search HTML) | **CONFIRMED GAP / bluff-risk** |
| nnmclub | FORM_LOGIN | ✓ | **null** (but present in search HTML) | **CONFIRMED GAP / bluff-risk** |
| archiveorg | NONE | HTTP file (not torrent) | n/a | correct by design |
| gutenberg | NONE | HTTP e-book (not .torrent) | n/a | **TORRENT_DOWNLOAD label mismatch** |

Registration: `DefaultTrackerRegistry.register(factory)` (core/tracker/client/di/TrackerClientModule.kt:270-275) → `LavaTrackerSdk` → `factory.create(config)`. Capability gate: `TrackerClient.getFeature(KClass)` reads `descriptor.capabilities` (§6.E).

Download URLs (cited):
- rutor `https://d.rutor.info/download/{id}` (RuTorDownload.kt:38)
- kinozal `https://kinozal.tv/download.php?id={id}` (KinozalDownload.kt:29)
- nnmclub `https://nnmclub.to/forum/download.php?id={id}` (NnmclubDownload.kt:29)
- archiveorg `https://archive.org/download/{id}/{file}` (ArchiveOrgDownload.kt:48)
- gutenberg Gutendex `https://gutendex.com/books/{id}/` (GutenbergDownload.kt:44)

**§6.R**: all 6 base URLs hardcoded literals in descriptors (rutracker:13, rutor:25, kinozal:19, nnmclub:19, archiveorg:23, gutenberg:24). Clone-override path partially mitigates (download impls accept ctor baseUrl override) but descriptor mirror lists are static.

**Derived workable items (evidence-backed):**
- W1. Wire kinozal magnet through (data in KinozalSearchParser.kt:52) + regression test (falsifiable). 
- W2. Wire nnmclub magnet through (NnmclubSearchParser.kt:52) + regression test.
- W3. Resolve gutenberg capability label (TORRENT_DOWNLOAD → HTTP_DOWNLOAD or correct semantics).
- W4. Resolve rutracker/rutor sync-magnet-null vs declared MAGNET_LINK (per §6.E).
- W5. §6.R base-URL config-driving (broad; coordinate with mirror manager).

### Agent 2 — Test infrastructure (COMPLETE)

127 test files (3 Android unit + 45 Challenges + Go contract/e2e/integration/parity/load/qa + panoptic). **The operator's core ask has ~zero real coverage:**
- Deep Android flows (search → topic → download → bencode-validate → magnet-validate) are MISSING or were gutted to "shallow" by a **nav-compose 2.9.0 lifecycle bug** (C04-C08 + deep C11; see `.lava-ci-evidence/sp3a-challenges/C4-thru-C8-2026-05-04-shallow-redesign.json`).
- Go e2e `/download/{id}` returns a **synthetic** payload `d8:announce10:fake-trackere` (e2e_test.go:280) — validates wire contract, NOT real bencode/magnet.
- **GAP-5/6**: no bencode `.torrent` structural validation, no magnet-format validation anywhere.
- Creds injected via BuildConfig from `.env` (app/build.gradle.kts), gated by `assumeTrue()` (honest SKIP, not false PASS).

### Agent 3 — HelixQA (COMPLETE)

Go autonomous-QA framework. **Claude↔HelixQA bridge ALREADY EXISTS**: `pkg/llm/bridge_provider.go` `BridgedCLIProvider` shells to the `claude` CLI as an LLM/Vision `Provider` (Chat/Vision/Name/SupportsVision). Vision: `pkg/vision/cheaper`, nav: `pkg/visionnav`. 4-phase autonomous session (setup→doc-verify→curiosity→report) w/ video + ADB nav + issue-detect + ticket-gen. Banks = YAML (≥30 floor). **Lava wires today**: qa/detector, qa/evidence, qa/validator, qa/ticket. **Missing**: LLM/vision/navigator(Q6 skipped) wiring, NO Lava-Android banks, no per-provider session multiplexing, no autonomous run against the Lava app.

### Agent 4 — Jackett decision matrix (COMPLETE)

Jackett = C#/.NET proxy; tracker coverage = **Cardigann YAML** defs (`src/Jackett.Common/Definitions/`, schema v1-v11, ~500+ indexers, shared w/ Prowlarr/Indexers) interpreted by a C# engine. **No in-process cross-language reuse** (CLR vs JVM/Go). Consumed via **Torznab HTTP** (`/api/v2.0/indexers/<id>/results/torznab/api`): RSS+`torznab:attr` → `<enclosure>` .torrent URL + `magneturl` + `infohash` per `<item>`. **License = GPL-2.0** (decisive):
- Option A run-as-service (Torznab sidecar) — GPL arm's-length SAFE; server-side only (no on-device APK); effort LOW; anti-bluff evidence EXCELLENT.
- Option B port interpreter+YAML to Kotlin/Go — on-device capable + APK-ready, but **GPL-2.0 contaminates the distributed APK** (Play-Store friction); effort HIGH; needs legal sign-off.
- Option C hybrid — A now for breadth + clean-room non-GPL interpreter later for on-device.

UNCONFIRMED/legal: exact YAML-only count; Prowlarr/Indexers exact license (GPL-family); vasic-digital policy on GPL submodule; browse/forum flows vs Torznab search-centric mapping.

## Synthesized program direction

- **Required 4 trackers**: rutracker ✓, rutor ✓, nnmclub ✓ already native; **IPTorrents = NEW native provider** (safe, on-device-ready, no GPL) — proceed regardless of Jackett choice.
- **Existing-provider hardening** (the heart of the request): validators + magnet §6.E fixes + real-network download/magnet verification + HelixQA banks — in flight, no operator gate.
- **Jackett mass-tracker breadth**: pending operator decision (A/B/C) — GPL + on-device-APK fork.
- **nav-compose 2.9.0** → bump to 2.9.1 (root cause confirmed; apply in integration pass; device-verify when Genymotion VM booted).
- **kinozal/nnmclub magnet §6.E bluffs**: fixes in flight (TDD + Bluff-Audit).

### Agent 5 — Decoupling + APK feasibility (COMPLETE)

Clean compile-time plugin abstraction (PluginFactory / getFeature<T> / 7 feature interfaces). **But hard-coupled registration**: `TrackerClientModule.provideTrackerRegistry()` (core/tracker/client/di/TrackerClientModule.kt:260-276) manually injects+registers each factory — no service-loader/reflection. To add a provider: new `core/tracker/<id>/` module (descriptor+client+factory+feature files+fixtures+tests+Challenge) + edit `settings.gradle.kts` + edit that one registry method. **Android-extension-APK**: ~4-6wk refactor — needs `core/tracker/extension-api/` (Parcelable manifest + ContentProvider/AIDL IPC), secondary runtime registry, per-extension test duplication. Tachiyomi/Mihon model cited UNCONFIRMED.

### Agent 6 — Constitution compliance (COMPLETE)

Governing: §6.D/6.E/6.G (coverage+capability honesty+provider E2E), §6.H/§11.4.10 (creds), §6.R (no-hardcode), §6.I/6.V/6.X/6.AE/6.AH (emulator-in-container/VM matrix), §6.W (mirrors), §6.S (CONTINUATION), §6.O (Crashlytics), §6.P (versioning), §11.4.6 (no-guessing), §11.4.76 (forbidden emulator cmds). **§6.AH-debt reportedly resolved 2026-06-06 via Genymotion VM + S23 Ultra** — but VM not live now (verified: adb 0 devices, :6555 closed). Per-new-provider checklist: capability-honesty unit test, §6.D behavioral test, §6.G auth-honesty + real-stack + falsifiability + Challenge, §6.R no-hardcode, per-AVD attestation on VM/device.

## 3. Known blockers (honest)

- **§6.AH-debt / §6.X-debt**: container/VM Android-emulator gate does NOT boot on this macOS host (no `/dev/kvm`, no HVF-in-container). Device-level Challenge evidence blocked here. Real download/magnet proof to be driven at Go-API + JVM + real-network layers (fetch real `.torrent`, assert non-empty valid bencode; validate magnet structure) — no bluffed device PASS.

## 4. Wave-2 — VERIFIED + COMMITTED (2026-06-08)

Independently re-verified by forced `--rerun-tasks` (not agent-trusted): `core:common:test` + `core:tracker:kinozal:test` + `core:tracker:nnmclub:test` → BUILD SUCCESSFUL 35s; `TorrentFileValidatorTest` 11/0/0, `MagnetLinkValidatorTest` 9/0/0, `KinozalDownloadTest` 2/0/0, `NnmclubMagnetExposureTest` 3/0/0. Spotless clean.

Committed to master (NOT yet pushed — batching with rutor fix):
- `776dc934` feat(core/common): bencode + magnet validation library (`lava.common.torrent.{Bencode,TorrentFileValidator,MagnetLinkValidator}`).
- `6fa31ad2` fix(core/tracker/kinozal): §6.E magnet exposure via KinozalMagnetCache.
- `13703bc8` fix(core/tracker/nnmclub): §6.E magnet exposure via NnmclubMagnetCache.

In flight: rutor magnet §6.E fix (same pattern), Jackett local-stack deep research (→ docs/qa/jackett-local-stack-research.md).

## 5. Operator decisions captured

- **Credentials** (all 5): in gitignored `.env` (RuTracker, RuTor, IPTorrents, NNMClub, **Kinozal updated to nobody85perf/01G…**). `.env.example` carries placeholders. NEVER committed.
- **Jackett = Option 1 (Torznab sidecar)** BUT must run as part of the **Lava local stack** (mDNS-discovered local API, same model as lava-api-go; NOT in the APK). Deep research first (in flight).
- **IPTorrents** = NEW native provider (the one of the 4 required trackers not yet supported).

## 6. Pending workable items (backlog)

- W4: rutracker/rutor sync-magnet §6.E (rutor fix in flight; rutracker has async GetMagnetLinkUseCase — verify honest).
- W3: gutenberg TORRENT_DOWNLOAD label vs HTTP e-book — resolve capability semantics.
- nav-compose 2.9.0→2.9.1 bump (apply + compile-verify; device-verify when Genymotion VM booted).
- Real-network per-provider download/magnet verification tests (the manual-testing replacement) using the new validators, gated `-PrealTrackers`, real `.env` creds.
- IPTorrents native provider (after Jackett/FlareSolverr research informs Cloudflare handling).
- HelixQA binary won't build — `submodules/helixqa/go.mod` has git conflict markers (L124/131/138); bump pin (always-track Q9) to fix.
- Jackett sidecar local-stack implementation (after research).
- Submodule pin bumps (constitution −12, challenges −3, containers −1, security −2) — deliberate, after current wave settles.
- Push wave-2+rutor batch to GitHub+GitLab (§6.W).
