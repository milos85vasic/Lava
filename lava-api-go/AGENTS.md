# AGENTS.md — lava-api-go

Local CI: `scripts/ci.sh`. Single source of truth.

## Tools and gates
- `go vet ./...`, `go build ./...`, `go test -race -count=1 ./...`
- `oapi-codegen` invariant (regenerate; assert empty diff)
- `go mod tidy` invariant (sha256 before/after)
- Fuzz: `go test -fuzz=. -fuzztime=30s` per package with `Fuzz*` tests
- gosec, govulncheck, trivy
- Quarterly: `scripts/mutation.sh` (go-mutesting)
- Pre-tag: `scripts/pretag-verify.sh` records `.lava-ci-evidence/<commit>.json`

## Workflow
1. Branch off `master` of the parent repo (Lava monorepo); the Go module lives at `lava-api-go/`.
2. Modify `api/openapi.yaml` first if changing wire shape.
3. Run `make generate` to update `internal/gen/`.
4. Implement the change.
5. `scripts/ci.sh` until green.
6. For every test added/modified: run the falsifiability rehearsal (see `CLAUDE.md`). Record the result in the commit body.
7. Commit. Push to `master` of the parent repo on github + gitlab.

## Things to avoid
- Adding hosted-CI configuration (forbidden; constitutional).
- Importing Lava-Android-specific code (Android lives in `app/`; Go API has no Android dep).
- Re-implementing functionality that exists in a vasic-digital submodule (Decoupled Reusable rule).
- Mocking internal Lava code in non-unit tests (Sixth Law clause 2).

## Host Machine Stability Directive
Per `/CLAUDE.md` and propagated through `Submodules/*/CLAUDE.md`: never run commands that suspend, hibernate, sign-out, or kill the user session. Cap test parallelism (`GOMAXPROCS=2`, `nice -n 19` are recommended).

### Clause 6.M — Host-Stability Forensic Discipline (added 2026-05-04 evening)

Inherits root `/CLAUDE.md` §6.M. Every perceived-instability event during a session that touches this service MUST be classified into one of three categories AND audited via the 7-step forensic protocol BEFORE concluding anything:

1. **Class I** — verifiable host event (poweroff, suspend, hibernate, sign-out). `uptime` reset, logged-in users changed, journalctl shows logind transition. Triggers post-poweroff recovery (orphan-container audit, pre-push verification).
2. **Class II** — measurable resource pressure (kernel OOM kill, swap exhaustion, thermal throttling, fs full). `uptime` continuous but `journalctl` shows kernel intervention.
3. **Class III** — operator-perceived instability with NO forensic evidence. `uptime` continuous, no journal events. Often a long-running build that paused GUI responsiveness, an emulator GPU lockup, or a remote SSH disconnect.

**7-step audit (60-second forensic):** uptime+who → journalctl logind events → kernel critical events → free -h → df -h → forbidden-command grep across tracked files → container state. Findings recorded under `.lava-ci-evidence/sixth-law-incidents/<date>-<slug>.json` (or, when this service runs standalone, `lava-api-go/.evidence/host-stability/<date>.json`).

**Container-runtime safety analysis (recorded once in root §6.M, referenced forever):** rootless Podman has NO host-level power-management privileges; rootful Docker is not installed on the operator's primary host. The `lava-api-go` container, the integration-test Postgres container, and `make image`'s OCI-tar export are all session-scoped operations that cannot cause Class I host events. This is recorded so a future agent does not re-derive it.

A perceived-instability event without an audit record is itself a Seventh Law violation. Forensic anchors: 2026-04-28 Class I poweroff (`docs/INCIDENT_2026-04-28-HOST-POWEROFF.md` in parent Lava project) and 2026-05-04 Class III perceived-instability (`.lava-ci-evidence/sixth-law-incidents/2026-05-04-perceived-host-instability.json` in parent Lava project).

## SP-3a-bridge expectations (added 2026-04-30)

The Kotlin-side multi-tracker SDK shipped with SP-3a binds the Go-side
rutracker bridge work (planned post-SP-2) to root constitutional
clauses 6.D, 6.E, and 6.F:

- **6.D Behavioral Coverage Contract.** Every Go interface added to
  mirror the Kotlin tracker SDK shape (e.g. a Go `tracker.Client` or
  equivalent) MUST have real-stack test coverage at the same surfaces
  a real client touches. Coverage exemptions go in
  `docs/superpowers/specs/<bridge-spec>-coverage-exemptions.md`.
- **6.E Capability Honesty.** When the Go server adds rutor (or any
  other tracker), every declared capability MUST resolve to a real
  handler. The cross-backend parity test in `tests/parity/` is the
  mechanical gate; a 501 / "Not implemented" body for a declared
  capability is a constitutional violation.
- **6.F Anti-Bluff Submodule Inheritance.** All vasic-digital
  submodules pulled in by lava-api-go inherit 6.A-6.E and the Seventh
  Law recursively. The Bluff-Audit commit-message stamp applies to
  every Go test commit (`*_test.go`).

The bridge plan is not yet written. The Go-side bridge plan link will
appear here once SP-2 ships and the bridge spec is drafted. Until then
the binding is on the contract, not on a specific PR.

## Seventh Law — Anti-Bluff Enforcement

The full authoritative text lives in root `/CLAUDE.md` and `/AGENTS.md`.
All clauses apply recursively to `lava-api-go`:

1. **Bluff-Audit Stamp.** Every `*_test.go` diff requires a `Bluff-Audit:` block in the commit message — test name, deliberate production break, observed failure, revert confirmation.
2. **Real-Stack Verification Gate.** Every user-visible feature requires real-stack verification (real HTTP/3 against real providers, real Postgres with `-Pintegration=true`).
3. **Pre-Tag Real-Device Attestation.** Required per `scripts/pretag-verify.sh`.
4. **Forbidden Test Patterns.** No mocking the SUT, no `verify { mock.foo() }` as primary assertion, no `@Ignore` without tracking issue, no tests that build but never invoke the SUT.
5. **Recurring Bluff Hunt.** Each phase ends with a bluff hunt: 5 random `*_test.go` files, deliberate mutation, confirm failure. Output to `.lava-ci-evidence/bluff-hunt/<date>.json`.
6. **Bluff Discovery Protocol.** Real-user bug + green tests = Seventh Law incident. Fix commit MUST include regression test; bluff diagnosed in `.lava-ci-evidence/sixth-law-incidents/<date>.json`.
7. **Inheritance and Propagation.** Applies recursively to every submodule and every new artifact. Submodule constitutions MAY add stricter rules but MUST NOT relax.

## Clause 6.G — End-to-End Provider Operational Verification (added 2026-05-04)

Inherited per 6.F. This module has no user-facing providers today; the user-facing surface is the HTTP API itself. Every endpoint advertised in `api/openapi.yaml` MUST have a real-stack test (real Gin engine, real Postgres in podman, real upstream tracker over the network) that confirms a real client can complete the flow. An endpoint declared in the spec but unable to complete its flow against the real stack is a constitutional violation. The clause becomes load-bearing on any future multi-tracker bridge work; the bridge spec MUST cite 6.G in its acceptance criteria.

## Clause 6.H — Credential Security Inviolability (added 2026-05-04)

Directly applicable. No tracker username, password, API key, signing key, JWT secret, or database credential shall appear in any tracked file (`.go`, `.sql`, `.yaml`, `.yml`, `.md`, `.sh`, `Makefile`, …). Credentials come from a gitignored `.env` or a local secrets manager at runtime. The Auth-Token redaction rule (CONSTITUTION §Module-specific rules) is necessary but not sufficient. `scripts/check-constitution.sh` rejects pushes that introduce credential patterns; do not work around it — fix the leak.

## Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage (added 2026-05-05)

Inherits root `/CLAUDE.md` §6.N. Beyond the Seventh Law clause 5 baseline (every 2-4 weeks), bluff hunts fire IN-cycle on operator-mandate invocation, matrix-runner/gate change, and new attestation file. Bluff hunts MUST sample production code (2 files mandatory + 0-2 recommended per phase). Pre-push hook enforcement of the in-cycle triggers is owed via §6.N-debt (next brainstorming target after Group A lands). For this service: bluff-hunt the `tests/contract/` real-binary contract gate, `tests/parity/` cross-backend parity gate, and the production handlers behind them. Forensic anchor: 2026-05-05 architectural-bluff discovery in `Submodules/Containers/pkg/emulator`.

## Clause 6.L — Anti-Bluff Functional Reality Mandate (Operator's Standing Order)

Inherited verbatim from parent Lava `/CLAUDE.md` §6.L. The operator has invoked this mandate **TEN TIMES** across two working days; the repetition itself is the forensic record. The 10th invocation (2026-05-05, immediately after Phase 7 readiness was reported, when the operator commissioned the full rebuild-and-test-everything cycle for tag Lava-Android-1.2.3): "Rebuild Go API and client app(s), put new builds into releases dir (with properly updated version codes) and execute all existing tests and Challenges! Any issue that pops up MUST BE properly addressed by addressing the root causes (fixing them) and covering everything with validation and verification tests and Challenges!"

Every test, every contract test, every parity test, every e2e test, every CI gate added to or maintained in this service has exactly one job: confirm the feature it claims to cover actually works for an end user, end-to-end, on the gating matrix (real Gin engine, real Postgres in podman, real HTTP/3 + HTTP/2 socket, and where applicable the emulator-container path for the Android-client-facing surface). CI green is necessary, NEVER sufficient. Tests must guarantee the product works — anything else is theatre. If you find yourself rationalizing a "small exception" — STOP. There are no small exceptions. The Internet Archive stuck-on-loading bug, the broken post-login navigation, the credential leak in C2, the bluffed C1-C8 — these are what "small exceptions" produce.

Inheritance is recursive: this clause applies to every dependency, every test, every Challenge, every CI gate this service introduces.

## Clause 6.O (added 2026-05-05, inherited per 6.F)

- **Clause 6.O — Crashlytics-Resolved Issue Coverage Mandate** — see root `/CLAUDE.md` §6.O. Every Crashlytics-recorded issue (fatal OR non-fatal) closed/resolved by any commit MUST gain (a) a validation test in the language of the crashing surface that reproduces the conditions, (b) a Challenge Test under `app/src/androidTest/kotlin/lava/app/challenges/` (client) or `tests/e2e/` (server) that drives the same user-facing path, and (c) a closure log at `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md` recording the issue ID, root-cause analysis, fix commit SHA, and links to the tests. `scripts/tag.sh` MUST refuse release tags whose CHANGELOG mentions Crashlytics fixes without matching closure logs. Marking a Crashlytics issue "closed" in the Console requires the test coverage to land first — never close-mark before the regression-immunity tests exist. Forensic anchor: 2026-05-05, 2 Crashlytics-recorded crashes within minutes of the first Firebase-instrumented APK distribution (Lava-Android-1.2.3-1023, commit `e9de508`); post-mortem at `.lava-ci-evidence/crashlytics-resolved/2026-05-05-firebase-init-hardening.md`. The operator's ELEVENTH §6.L invocation made this clause load-bearing.

## Clause 6.P (added 2026-05-05, inherited per 6.F)

- **Clause 6.P — Distribution Versioning + Changelog Mandate** — see root `/CLAUDE.md` §6.P. Every distribute action (Firebase App Distribution, container registry pushes, releases/ snapshots, scripts/tag.sh) MUST: (1) carry a strictly increasing versionCode (no re-distribution of already-published codes); (2) include a CHANGELOG entry — canonical file `CHANGELOG.md` at repo root + per-version snapshot at `.lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md`; (3) inject the changelog into the App Distribution release-notes via `--release-notes`. `scripts/firebase-distribute.sh` REFUSES to operate when current versionCode ≤ last-distributed versionCode for the channel, OR when CHANGELOG.md lacks an entry for the current version, OR when the per-version snapshot file is missing. `scripts/tag.sh` enforces the same gates pre-tag. Re-distributing the same versionCode is forbidden across distribute sessions; idempotent retry within a single session is permitted. Forensic anchor: 2026-05-05 23:11 operator's TWELFTH §6.L invocation: "when distributing new build it must have version code bigger by at least one then the last version code available for download (already distribited). Every distributed build MUST CONTAIN changelog with the details what it includes compared to previous one we have published!"

## Clause 6.Q (added 2026-05-05, inherited per 6.F)

- **Clause 6.Q — Compose Layout Antipattern Guard** — see root `/CLAUDE.md` §6.Q. Forbids nesting vertically-scrolling lazy layouts (LazyColumn, LazyVerticalGrid, LazyVerticalStaggeredGrid) inside parents giving unbounded vertical space (verticalScroll, unbounded wrapContentHeight, LinearLayout-with-weight wrapper). Equivalent rule horizontally for LazyRow / LazyHorizontalGrid / LazyHorizontalStaggeredGrid. Per-feature structural tests + Compose UI Challenge Tests on the §6.I matrix are the load-bearing acceptance gates. Forensic anchor: 2026-05-05 23:51 operator-reported "Opening Trackers from Settings crashes the app" — TrackerSelectorList used LazyColumn nested in TrackerSettingsScreen's Column(verticalScroll). Closure log: `.lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md`. Pattern guard: `feature/tracker_settings/src/test/.../TrackerSelectorListLazyColumnRegressionTest.kt`. The operator THIRTEENTH §6.L invocation triggered this clause.
