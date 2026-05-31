# New Tests Evidence — §6.L 68th cycle (lava-api-go)

Author: test-creation subagent. Date: 2026-05-31 (UTC).
Toolchain: `go1.26.2 darwin/arm64`. Resource limit: every run prefixed `GOMAXPROCS=2` (§6.T.2).

This file records REAL, re-runnable evidence per HelixConstitution §11.4.98 +
§11.4.27 + §6.J/§6.L anti-bluff: each new test passes against unmutated
production code AND fails against deliberately-broken production code
(falsifiability rehearsal), with the mutation reverted afterward. Production
code (`internal/`, `cmd/`) is unmodified at the end — `git diff` on the five
mutated prod files is empty.

---

## 0. Baseline pass state (HONEST)

`GOMAXPROCS=2 go test ./internal/... ./cmd/... -count=1` produced:

- **All packages PASS EXCEPT one PRE-EXISTING failure** in
  `internal/qa/validator` (6 failing tests in `validator_test.go`:
  `TestValidateStep_Failed_DriveCrashDetection`,
  `TestValidateStep_FailedWithAutoEmit_WritesTicket`,
  `TestValidateStep_FailedWithoutTicketGen_NoEmission`,
  `TestValidateStep_FailedAutoEmit_CustomTicketDir`,
  `TestValidateStep_FailedAutoEmit_WriteErrorIsNonFatal`,
  `TestCounters_AcrossMultipleSteps`).
- This failure is **PRE-EXISTING and UNRELATED** to this cycle's work — it is
  in the HelixQA-bridge `qa/validator` package, which none of the new tests
  touch. UNKNOWN (§11.4.6): root cause not investigated in this cycle; flagged
  for the main agent. The new tests live in `internal/auth`, `internal/gutenberg`,
  `internal/observability`, `internal/handlers/v1`, and `tests/contract` — all
  of which were green at baseline and remain green.

`go build ./...` exits 0 (whole module compiles).

---

## 1. New test files added (5 files, 3 distinct test types)

| # | File | Type | Production code covered |
|---|------|------|-------------------------|
| 1 | `internal/auth/upstream_cookie_regression_test.go` | regression-immunity unit | `auth.UpstreamCookie` / `auth.RealmHash` (SP-3.5 double-prefix de-auth bug) |
| 2 | `internal/gutenberg/format_selection_test.go` | table-driven unit | `gutenberg.pickBestFormatURL` / `bestFormatName` (download-format selection, previously UNCOVERED) |
| 3 | `internal/observability/nonfatal_errorclass_test.go` | unit (telemetry-output assertion) | `observability.classOf` (the `error_class` telemetry attr — previously UNCOVERED) + §6.H redaction-with-survival |
| 4 | `internal/handlers/v1/error_mapping_test.go` | handler-level real-HTTP (httptest → real Gin engine) | `v1.writeProviderError` 403/401/503/502 arms + success body content (only 404 was tested before) |
| 5 | `tests/contract/version_binary_contract_test.go` | §6.A real-binary contract | `cmd/lava-api-go --version` ↔ `internal/version` constants (distribution-gate authority) |

PRIMARY assertion in every test is on user-visible / observable state (HTTP
status + wire body, telemetry bytes, the actual returned cookie/URL string,
the binary's stdout) — never a mock call count.

---

## 2. Verbatim PASS output (unmutated production code)

`GOMAXPROCS=2 go test ./internal/auth/ ./internal/gutenberg/ ./internal/observability/ ./internal/handlers/v1/ ./tests/contract/ -count=1`

```
ok  	digital.vasic.lava.apigo/internal/auth	0.227s
ok  	digital.vasic.lava.apigo/internal/gutenberg	0.215s
ok  	digital.vasic.lava.apigo/internal/observability	0.387s
ok  	digital.vasic.lava.apigo/internal/handlers/v1	0.398s
ok  	digital.vasic.lava.apigo/tests/contract	3.31s
```

Per-test `-v` confirmation (selected):

```
--- PASS: TestUpstreamCookie_AlreadyNameValue_ForwardsVerbatim (0.00s)
--- PASS: TestUpstreamCookie_BareToken_GetsCanonicalPrefix (0.00s)
--- PASS: TestUpstreamCookie_NoHeader_Anonymous (0.00s)
--- PASS: TestRealmHash_Distinguishes_Anonymous_From_EmptyHash (0.00s)
--- PASS: TestPickBestFormatURL_PrefersEpubOverPdf (0.00s)
--- PASS: TestPickBestFormatURL_Table (9 subtests) (0.00s)
--- PASS: TestPickBestFormatURL_UnknownMimeDeterministic (0.00s)
--- PASS: TestBestFormatName_Table (7 subtests) (0.00s)
--- PASS: TestBestFormatName_MatchesPickedURLFamily (0.00s)
--- PASS: TestRecordNonFatal_ErrorClass_PrefersNamedType (0.00s)
--- PASS: TestRecordNonFatal_ErrorClass_WalksWrapChain (0.00s)
--- PASS: TestRecordNonFatal_ErrorClass_DistinguishesSentinels (0.00s)
--- PASS: TestRecordNonFatal_ErrorClass_PlainErrorIsStable (0.00s)
--- PASS: TestRecordNonFatal_PreservesNonSensitiveAndRedactsSensitive (0.00s)
--- PASS: TestSearch_ProviderErrorMapping (6 subtests: 404/403/401/503/wrapped-503/502) (0.00s)
--- PASS: TestSearch_Success_BodyContentAndStatus (0.00s)
--- PASS: TestVersionBinaryContract_MatchesVersionPackage (1.16s)
--- PASS: TestVersion_NameIsThreeComponentSemver (0.00s)
--- PASS: TestVersion_CodeIsPositive (0.00s)
```

---

## 3. Falsifiability rehearsals (mutation → FAIL → revert → PASS)

Each mutation targeted the EXACT production path the test claims to cover.
Reverted via `git checkout <prod-file>`; final `git diff` on all five prod
files is empty.

### Rehearsal 1 — `auth.UpstreamCookie` (file 1)
- **Mutation:** deleted the `if strings.Contains(tok, "=") { return tok }`
  verbatim-forward branch in `internal/auth/passthrough.go`.
- **Observed FAIL:**
  ```
  --- FAIL: TestUpstreamCookie_AlreadyNameValue_ForwardsVerbatim
    cookie="bb_session=bb_session=0-1-deadbeefcafef00d; expires=...; ..."
    want "bb_session=0-1-deadbeefcafef00d; expires=...; ..."
    (a name=value cookie line MUST forward verbatim — wrapping reproduces
    the SP-3.5 double-prefix de-auth bug)
  ```
- **Reverted:** yes — re-run `ok digital.vasic.lava.apigo/internal/auth 0.222s`.

### Rehearsal 2 — `gutenberg.pickBestFormatURL` (file 2)
- **Mutation:** moved `application/pdf` to the front of the `preferred` slice
  in `internal/gutenberg/utils.go` (PDF wins over EPUB).
- **Observed FAIL:**
  ```
  --- FAIL: TestPickBestFormatURL_PrefersEpubOverPdf
    picked "https://gutenberg.example/1342.pdf" want the EPUB url
    "https://gutenberg.example/1342.epub" (EPUB is the highest-preference format)
  ```
- **Reverted:** yes — re-run `ok digital.vasic.lava.apigo/internal/gutenberg 0.227s`.

### Rehearsal 3 — `observability.classOf` (file 3)
- **Mutation:** replaced `classOf`'s `return underlyingTypeName(t)` with
  `return "error"` in `internal/observability/nonfatal.go` (collapse all classes).
- **Observed FAIL:**
  ```
  --- FAIL: TestRecordNonFatal_ErrorClass_PrefersNamedType
    error_class="error" want "rutracker.ErrCircuitOpen" ...
  --- FAIL: TestRecordNonFatal_ErrorClass_DistinguishesSentinels
    two distinct sentinel errors collapsed to the same error_class "error" —
    operator cannot distinguish NotFound from Forbidden in telemetry
  ```
- **Reverted:** yes — re-run `ok digital.vasic.lava.apigo/internal/observability 0.318s`.

### Rehearsal 4 — `cmd/lava-api-go --version` (file 5)
- **Mutation:** changed the `--version` Printf in `cmd/lava-api-go/main.go` to a
  hard-coded `fmt.Printf("lava-api-go 0.0.0 (build 0)\n")`.
- **Observed FAIL:**
  ```
  --- FAIL: TestVersionBinaryContract_MatchesVersionPackage
    --version stdout "lava-api-go 0.0.0 (build 0)" does not contain
    version.Name "2.3.22" — binary identity drifted from the constant the
    distribution gates trust
  ```
- **Reverted:** yes — re-run `ok digital.vasic.lava.apigo/tests/contract 2.54s`.

### Rehearsal 5 — `v1.writeProviderError` (file 4)
- **Mutation:** changed the `provider.ErrCircuitOpen` case in
  `internal/handlers/v1/handlers.go` from `StatusServiceUnavailable` (503) to
  `StatusBadGateway` (502).
- **Observed FAIL:**
  ```
  --- FAIL: TestSearch_ProviderErrorMapping/circuit_open_503
    status=502 want 503 for provider: circuit breaker open; body={}
  ```
- **Reverted:** yes — re-run `ok digital.vasic.lava.apigo/internal/handlers/v1 0.331s`.

---

## 4. Operator-gated / not done (HONEST)

- **Real-Postgres integration test (NOT added).** podman 5.8.2 IS present on
  this host, BUT per the task's resource-limit constraint (do not launch a
  second concurrent heavy build) and to avoid pulling a Postgres image +
  bringing up a container in a subagent context, a NEW real-Postgres
  integration test was NOT written this cycle. The existing
  `internal/cache/integration_test.go` + `tests/integration/` already cover the
  real-Postgres path under the project's `-Pintegration`-style gate. Marking a
  new Postgres test **OPERATOR_GATED** rather than faking one: the main agent /
  operator should add it under `tests/integration/` and run it against a podman
  Postgres on a host where a heavy container bring-up is acceptable.
- **§6.X emulator / Android Challenge path:** out of scope for lava-api-go
  (server artifact); no emulator involved.

---

## 5. Files for the main agent to `git add`

```
lava-api-go/internal/auth/upstream_cookie_regression_test.go
lava-api-go/internal/gutenberg/format_selection_test.go
lava-api-go/internal/observability/nonfatal_errorclass_test.go
lava-api-go/internal/handlers/v1/error_mapping_test.go
lava-api-go/tests/contract/version_binary_contract_test.go
lava-api-go/tests/NEW-TESTS-EVIDENCE.md
```

Production code (`internal/`, `cmd/`) is UNMODIFIED — confirm with
`git diff -- lava-api-go/internal lava-api-go/cmd` returning empty.
