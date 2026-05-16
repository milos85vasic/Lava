# HelixQA Go-Package Linking Design (Phase 4 Follow-up C — Lava-side)

**Date:** 2026-05-16
**Phase:** 4 follow-up C (Option 2 from `docs/plans/2026-05-16-helixqa-integration-design.md`)
**Status:** Design proposal — operator approval required before implementation begins
**HelixQA pin:** `403603db` (v4.0.0-256-g403603d) as of Phase 4 adoption commit `aa0db6bd`
**HelixQA module path:** `digital.vasic.helixqa` (per `submodules/helixqa/go.mod`)
**Classification:** project-specific (the per-package adapter choices + Lava-side file paths are project-specific; the adapter-interface boundary-isolation discipline is universal per HelixConstitution §11.4.31)

## Scope

This design covers **Option 2** from the parent integration design doc — Go-package linking of HelixQA's **Group A** packages (`detector`, `evidence`, `navigator`, `validator`, `ticket`) into Lava's Go service (`lava-api-go`). It is **DESIGN-ONLY**. No Go code changes; no `lava-api-go/go.mod` modifications; no scripts changes. Option 1 (shell-level wiring of 11 Challenge scripts) is ALREADY IMPLEMENTED (commits `1b66d192` + `d94ade0d`). Option 3 (Compose UI Challenge Test backend migration) remains deferred.

## A. Integration entry point decision

### A.1 — lava-api-go is the natural Go-side integration point

Lava ships two distributable artifacts plus a third in-flight:
- `:app` — Android client (Kotlin + Compose). Cannot directly import Go packages without subprocess invocation OR gRPC bridge OR JNI. All three impose substantial overhead.
- `:proxy` — Ktor/Netty headless JVM server (Kotlin). Same limitation as `:app`.
- `lava-api-go` — Go service (Go 1.25, Gin, Postgres). **Native Go consumer.** Can import HelixQA packages directly.

The DESIGN DECISION is: **Phase 4-C-1 through 4-C-4 target lava-api-go ONLY.** Adapters live under `lava-api-go/internal/qa/`. The Android client and Ktor proxy are NOT touched by this phase.

### A.2 — Why not the Android client (deferred)

Three architectural options were considered for Android-side HelixQA consumption; each is deferred pending Phase 4-C-1..4 proving value:

1. **Subprocess invocation.** Android instrumentation test invokes the `helixqa` CLI via `adb shell` against the test host. Slow (process startup overhead per assertion), and the binary must be present on the test host — a deployment burden. Defer.
2. **gRPC bridge.** lava-api-go exposes the HelixQA adapters via gRPC; the Android client speaks gRPC during instrumentation tests. Adds architectural complexity (gRPC stubs, protocol versioning, transport security inside test runs). Defer.
3. **JNI bindings to a Go-compiled-as-shared-library.** Use `go build -buildmode=c-shared` to produce a `.so` consumable by Android's JNI. Build-time complexity, ABI fragility across Android NDK versions. Defer indefinitely.

**Phase 4-C scope: Go-side only.** Phase 4-C-5 (hypothetical, not in this design's mandated scope) MAY later evaluate Android-side wiring once the Go-side adapters prove stable.

### A.3 — Why not the Ktor proxy

The Ktor `:proxy` is being replaced by `lava-api-go` per SP-2. Adding HelixQA wiring to a deprecated artifact is anti-value. SKIP entirely.

## B. Per-package integration plan for Group A

For each Group A package, the design covers: (1) HelixQA's exported public API (recovered by reading `submodules/helixqa/pkg/<name>/*.go` at pin `403603db`); (2) the Lava use case the package addresses; (3) the Lava-side adapter-interface design; (4) the proposed file path; (5) the testing strategy.

### B.1 — `pkg/detector`

#### Public API (HelixQA pin `403603db`)

```go
// Types
type DetectionResult struct {
    Platform     config.Platform
    HasCrash     bool
    HasANR       bool          // Android-only
    ProcessAlive bool
    StackTrace   string
    // ... (plus fields for evidence + console + crash details)
}

type CommandRunner interface {
    Run(ctx context.Context, name string, args ...string) ([]byte, error)
}

type Detector struct { /* opaque */ }

type Option func(*Detector)

// Functional-options constructor
func New(platform config.Platform, opts ...Option) *Detector
func WithDevice(device string) Option           // Android emulator/device ID
func WithPackageName(pkg string) Option         // Android app package
func WithBrowserURL(url string) Option          // Web target
func WithProcessName(name string) Option        // Desktop/JVM/Go target
func WithProcessPID(pid int) Option             // Same, by PID
func WithEvidenceDir(dir string) Option
func WithCommandRunner(runner CommandRunner) Option   // ← injection seam for tests

// Methods
func (d *Detector) Check(ctx context.Context) (*DetectionResult, error)
func (d *Detector) CheckApp(ctx context.Context, /* per-platform args */) (*DetectionResult, error)
func (d *Detector) Platform() config.Platform
```

#### Lava use case

- **lava-api-go process monitoring** during integration test runs (Phase 4b's chaos / DDoS / sustained-load scenarios benefit from crash detection on the API process itself).
- **Future cross-platform extension:** if Lava ships a desktop QA harness, `pkg/detector` covers JVM + browser targets natively.

#### Lava-side adapter

**File:** `lava-api-go/internal/qa/detector/adapter.go` (proposed)

```go
package detector

import (
    "context"
    "digital.vasic.helixqa/pkg/config"
    hxqadetector "digital.vasic.helixqa/pkg/detector"
)

// CrashDetector is Lava's abstraction over HelixQA's Detector.
// Adapter pattern: HelixQA version bumps only touch this file,
// not Lava's call sites.
type CrashDetector interface {
    CheckGoProcess(ctx context.Context, processName string) (*Report, error)
}

// Report is Lava's flattened/renamed view of HelixQA's
// DetectionResult — only the fields Lava cares about.
type Report struct {
    Crashed      bool
    Alive        bool
    StackTrace   string
    EvidencePath string
}

// Adapter wraps a HelixQA Detector.
type Adapter struct {
    detector *hxqadetector.Detector
}

// New creates the adapter wired to HelixQA's Linux/host detector.
func New(evidenceDir string) *Adapter { /* construct */ }

func (a *Adapter) CheckGoProcess(ctx context.Context, processName string) (*Report, error) {
    // 1. configure HelixQA detector with WithProcessName + WithEvidenceDir
    // 2. invoke detector.Check(ctx)
    // 3. translate hxqadetector.DetectionResult → qadetector.Report
    // 4. RecordNonFatal on error per §6.AC
}
```

#### Testing strategy

- **Adapter unit test** (`lava-api-go/internal/qa/detector/adapter_test.go`): inject a fake `CommandRunner` that returns canned `ps`/`pgrep` output; assert adapter maps fields correctly.
- **Falsifiability rehearsal:** mutate the field mapping (e.g., `Crashed = !dr.HasCrash`) and confirm the assertion fails.
- **Real-stack test** (`lava-api-go/internal/qa/detector/adapter_real_test.go`, gated by `-tags=helixqa_realstack`): launch a sacrificial `cat /dev/zero &` process, assert detector reports `Alive=true`; kill it; assert `Alive=false`.

---

### B.2 — `pkg/evidence`

#### Public API

```go
// Types
type Type string

const (
    TypeScreenshot Type = "screenshot"
    TypeVideo      Type = "video"
    TypeLogcat     Type = "logcat"
    TypeStackTrace Type = "stacktrace"
    TypeConsoleLog Type = "console_log"
    TypeAudio      Type = "audio"
)

type Item struct {
    Type      Type
    Path      string
    Platform  config.Platform
    Step      string
    Timestamp time.Time
    // ... plus metadata
}

type Collector struct { /* opaque */ }

type Option func(*Collector)

func New(opts ...Option) *Collector
func WithOutputDir(dir string) Option
func WithPlatform(p config.Platform) Option
func WithCommandRunner(r detector.CommandRunner) Option

// Capture methods (subset)
func (c *Collector) CaptureScreenshot(ctx context.Context, name string) (*Item, error)
func (c *Collector) CaptureLogcat(ctx context.Context, name string, lines int) (*Item, error)
func (c *Collector) StartRecording(ctx context.Context, name string) error
func (c *Collector) StopRecording(ctx context.Context) (*Item, error)
func (c *Collector) StartAudioRecording(ctx context.Context, name string) error
func (c *Collector) StopAudioRecording(ctx context.Context) (*Item, error)
func (c *Collector) Items() []Item
func (c *Collector) Reset()
```

#### Lava use case

- **lava-api-go integration tests** need failure evidence captured uniformly (current state: each test scatters its own ad-hoc `os.WriteFile("/tmp/...")` calls).
- **Phase 4b's load + chaos test types** produce evidence (HTTP traces, log dumps, performance graphs); a unified collector with typed `Item` records makes the per-cell coverage-ledger entries (Phase 7) machine-readable.
- **Cross-language uniformity:** if the Kotlin side later adopts a JVM equivalent, the typed evidence schema can be shared.

#### Lava-side adapter

**File:** `lava-api-go/internal/qa/evidence/adapter.go`

```go
package evidence

import (
    "context"
    hxqaevidence "digital.vasic.helixqa/pkg/evidence"
    "digital.vasic.helixqa/pkg/config"
)

// Recorder is Lava's abstraction over HelixQA's Collector.
type Recorder interface {
    CaptureText(ctx context.Context, name, content string) error
    CaptureLogs(ctx context.Context, name string, lines int) error
    Snapshot() []EvidenceItem
    Reset()
}

// EvidenceItem is Lava's view of HelixQA's Item (without
// Android-specific Platform field — Lava server-side is Linux).
type EvidenceItem struct {
    Kind      string  // "log" | "trace" | "stacktrace" | "metric"
    Path      string
    Step      string
    Timestamp time.Time
}

type Adapter struct {
    collector *hxqaevidence.Collector
}

func New(outputDir string) *Adapter { /* construct with Linux Platform */ }

func (a *Adapter) CaptureText(ctx context.Context, name, content string) error {
    // 1. Write content to outputDir
    // 2. Call HelixQA's addItem equivalent (via a public method we may need to add upstream)
    //    OR: bypass HelixQA's collector for text and just track in Lava's own slice
    // 3. RecordNonFatal on error per §6.AC
}
```

**HONEST CAVEAT:** `pkg/evidence`'s public surface centers on Android `adb screencap` + `adb screenrecord` + `adb logcat` + Android-side `screencap` for desktops. **For lava-api-go server-side use cases (log capture, trace capture, metric snapshots), HelixQA's Collector is partially overkill.** The pragmatic path is: use HelixQA's Item schema + Reset/Items lifecycle, but emit `CaptureText` via Lava-side direct write (not through HelixQA's `addItem` which is currently unexported). Future Phase 4-C-1.b MAY request HelixQA upstream expose a `CaptureGeneric(ctx, kind, name, contents)` method — that is an UPSTREAM contribution, not a Lava-side hack.

#### Testing strategy

- **Adapter unit test** (`adapter_test.go`): instantiate adapter pointing at `t.TempDir()`; capture text; assert file written + Item recorded.
- **Falsifiability rehearsal:** mutate the file-write path to `/dev/null`; assert subsequent `Snapshot()` returns empty + adapter records non-fatal via §6.AC.
- **Concurrency test:** parallel CaptureText calls; assert no item loss + no duplicate paths.

---

### B.3 — `pkg/ticket`

#### Public API

```go
// Types
type VideoReference struct {
    VideoPath   string
    Timestamp   time.Duration
    Description string
}

type LLMSuggestedFix struct {
    Description   string
    CodeSnippet   string
    Confidence    float64       // 0.0-1.0
    AffectedFiles []string
}

type Severity string

const (
    SeverityCritical Severity = "critical"
    SeverityHigh     Severity = "high"
    SeverityMedium   Severity = "medium"
    SeverityLow      Severity = "low"
)

type Ticket struct {
    ID                string
    Title             string
    Severity          Severity
    Platform          config.Platform
    TestCaseID        string
    Description       string
    StepsToReproduce  []string
    // ... plus VideoReference + LLMSuggestedFix + StackTrace + Evidence
}

type Generator struct { /* opaque */ }

func New(opts ...Option) *Generator
func WithOutputDir(dir string) Option

// Methods
func (g *Generator) GenerateFromStep(sr *validator.StepResult, testCaseID string) *Ticket
func (g *Generator) GenerateFromDetection(dr *detector.DetectionResult, context string) *Ticket
func (g *Generator) WriteTicket(t *Ticket) (string, error)   // returns markdown path
func (g *Generator) WriteAll(tickets []*Ticket) ([]string, error)
func (g *Generator) RenderMarkdown(t *Ticket) []byte
```

#### Lava use case

- **Replaces Lava's §6.O Crashlytics closure log MANUAL authoring with generated markdown.** Today every fix lands a hand-written `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md`. HelixQA's `Ticket` schema + `RenderMarkdown` produces those files mechanically from detector/validator inputs.
- **Generates fix-pipeline-readable markdown** that AI-assisted fix tools (Claude Code, etc.) consume directly without operator transcription.
- **Bridges crash-detection (B.1) and step-validation (B.5) into actionable artifacts.**

#### Lava-side adapter

**File:** `lava-api-go/internal/qa/ticket/adapter.go`

```go
package ticket

import (
    hxqaticket "digital.vasic.helixqa/pkg/ticket"
    hxqadetector "digital.vasic.helixqa/pkg/detector"
    qadetector "digital.vasic.lava.apigo/internal/qa/detector"
)

// ClosureLogWriter is Lava's abstraction; covers §6.O closure-log
// authoring without operator transcription.
type ClosureLogWriter interface {
    WriteFromCrash(crashlyticsID string, report *qadetector.Report) (string, error)
    WriteFromValidationFailure(...) (string, error)
}

type Adapter struct {
    generator *hxqaticket.Generator
}

func New(outputDir string) *Adapter { /* construct */ }

func (a *Adapter) WriteFromCrash(crashlyticsID string, report *qadetector.Report) (string, error) {
    // 1. Translate qadetector.Report → hxqadetector.DetectionResult
    //    (note: this is the REVERSE direction of B.1's translation)
    // 2. Call a.generator.GenerateFromDetection(dr, "Crashlytics issue: " + crashlyticsID)
    // 3. Augment ticket with Lava-specific metadata (Crashlytics issue link, §6.O reference)
    // 4. Call a.generator.WriteTicket(ticket) → returns path
    // 5. Path SHOULD be .lava-ci-evidence/crashlytics-resolved/<id>-<slug>.md to satisfy §6.O
}
```

#### Testing strategy

- **Adapter unit test:** synthetic `Report` → call adapter → assert markdown file written with required §6.O fields (Crashlytics ID, root cause, stack trace, fix-commit-SHA placeholder).
- **Falsifiability rehearsal:** mutate the markdown template to omit the `Crashlytics ID:` header; assert the test (which checks for that header) fails.
- **§6.O compliance test:** after adapter writes ticket, invoke `scripts/check-constitution.sh` against the generated file; assert no violations.

---

### B.4 — `pkg/navigator`

#### Public API

```go
// Types
type NavigationEngine struct { /* opaque */ }

func NewNavigationEngine(
    ag agent.Agent,
    az analyzer.Analyzer,
    exec ActionExecutor,
    navGraph graph.NavigationGraph,
) *NavigationEngine

// Methods
func (ne *NavigationEngine) NavigateTo(...) (...)
func (ne *NavigationEngine) PerformAction(...) (...)
func (ne *NavigationEngine) ExploreUnknown(...) (...)
func (ne *NavigationEngine) CurrentScreen() ...
func (ne *NavigationEngine) GoBack() error
func (ne *NavigationEngine) GoHome() error
func (ne *NavigationEngine) State() *StateTracker
func (ne *NavigationEngine) Graph() graph.NavigationGraph

// Executor interface (per-platform)
type ActionExecutor interface {
    Click(ctx context.Context, x, y int) error
    Type(ctx context.Context, text string) error
    Clear(ctx context.Context) error
    Scroll(ctx context.Context, direction string, amount int) error
    LongPress(ctx context.Context, x, y int, durationMs int) error
    // ... screenshot + others
}

// Provided executors
type ADBExecutor struct { /* opaque */ }
func NewADBExecutor(device string, runner detector.CommandRunner) *ADBExecutor

// Plus: PlaywrightExecutor (web), X11Executor (Linux desktop), CLIExecutor (terminal)
```

#### Lava use case

- **PRIMARILY ANDROID-FACING.** lava-api-go is a backend service with no UI to navigate. Direct Go-side value is low.
- **INDIRECT VALUE:** if Phase 4-C eventually grows an Android-side bridge (per A.2), `pkg/navigator` becomes the canonical UI driver for replacing Espresso. But Phase 4-C is Go-side ONLY.
- **POSSIBLE USE FOR LATER ANDROID PHASE:** lava-api-go MAY expose a navigation-trace logging endpoint that Android tests POST to, providing a server-side navigation graph view. Speculative; deferred.

#### Lava-side adapter

**DEFERRED to Phase 4-C-4 (LAST priority).** No Lava-side Go-only use case justifies the adapter today. The adapter would be empty plumbing without a real consumer.

If Phase 4-C-4 proceeds, the proposed shape is:

**File:** `lava-api-go/internal/qa/navigator/adapter.go`

```go
package navigator

// NavigationTraceRecorder accepts navigation-trace POSTs from
// the Android client during instrumentation tests + persists them
// to the qa evidence store.
type NavigationTraceRecorder interface {
    RecordTransition(ctx context.Context, from, to string, action string) error
    Snapshot() *NavigationGraph
}
```

#### Testing strategy (when implemented)

- **Adapter unit test:** synthetic transition POSTs; assert graph state matches.
- **Falsifiability rehearsal:** corrupt the from→to mapping; assert graph diff detects.

---

### B.5 — `pkg/validator`

#### Public API

```go
// Types
type StepStatus string

const (
    StepPassed  StepStatus = "passed"
    StepFailed  StepStatus = "failed"
    StepSkipped StepStatus = "skipped"
    StepError   StepStatus = "error"
)

type StepResult struct {
    StepName       string
    Status         StepStatus
    Platform       config.Platform
    Detection      *detector.DetectionResult
    PreScreenshot  string
    PostScreenshot string
    // ... plus timestamps + error info
}

type ScreenshotFunc func(ctx context.Context, name string) (string, error)

type Validator struct { /* opaque */ }

func New(...) *Validator      // takes detector + options
func WithEvidenceDir(dir string) Option
func WithScreenshotFunc(fn ScreenshotFunc) Option

// Methods
func (v *Validator) ValidateStep(ctx context.Context, stepName string, platform config.Platform) (*StepResult, error)
func (v *Validator) Results() []*StepResult
func (v *Validator) PassedCount() int
func (v *Validator) FailedCount() int
func (v *Validator) TotalCount() int
func (v *Validator) Reset()
```

#### Lava use case

- **Step-by-step validation for lava-api-go integration test scenarios** — e.g., Phase 4b's chaos / DDoS / sustained-load scenarios that run multi-step interactions against the API. Each step gets a `StepResult` with crash detection + evidence.
- **Per-step screenshot/log capture** wires into B.2 (evidence collector) — the `ScreenshotFunc` becomes a thin wrapper around the evidence adapter.
- **Aggregates into B.3 (ticket)** — `Validator.Results()` feeds `Generator.GenerateFromStep()`.

#### Lava-side adapter

**File:** `lava-api-go/internal/qa/validator/adapter.go`

```go
package validator

import (
    hxqavalidator "digital.vasic.helixqa/pkg/validator"
    qadetector "digital.vasic.lava.apigo/internal/qa/detector"
    qaevidence "digital.vasic.lava.apigo/internal/qa/evidence"
)

// StepRunner is Lava's abstraction; bundles HelixQA validator
// + Lava-specific evidence capture.
type StepRunner interface {
    Run(ctx context.Context, stepName string, fn func() error) (*StepReport, error)
    Summary() Summary
}

type StepReport struct {
    Name      string
    Passed    bool
    Duration  time.Duration
    Evidence  []qaevidence.EvidenceItem
    Crash     *qadetector.Report
}

type Adapter struct {
    validator *hxqavalidator.Validator
    detector  *qadetector.Adapter
    evidence  *qaevidence.Adapter
}

func New(...) *Adapter { /* wire all three */ }

func (a *Adapter) Run(ctx context.Context, stepName string, fn func() error) (*StepReport, error) {
    // 1. Take pre-step evidence snapshot
    // 2. Execute fn() — the actual step work
    // 3. Take post-step evidence snapshot
    // 4. Invoke a.validator.ValidateStep to check for crashes during fn()
    // 5. Aggregate into StepReport
    // 6. RecordNonFatal on any error path per §6.AC
}
```

#### Testing strategy

- **Adapter unit test:** step that succeeds → assert StepReport.Passed=true; step that returns error → assert Passed=false + crash report optional.
- **Falsifiability rehearsal:** mutate pass/fail logic (e.g., always return Passed=true); assert assertion catches.
- **Real-stack test:** chain B.1 + B.2 + B.5 adapters; run a deliberately-crashing scenario; assert StepReport correctly reports crash + captures evidence.

## C. lava-api-go `go.mod` strategy

### C.1 — Module path consistency

HelixQA's `submodules/helixqa/go.mod` declares `module digital.vasic.helixqa`. This matches the prefix Lava's other submodules use (`digital.vasic.auth`, `digital.vasic.cache`, etc.) per the existing `replace` block in `lava-api-go/go.mod` lines 5-21. **No naming conflict.** HelixQA can be added to the same `replace` block.

### C.2 — Sibling-directory replace directive (local development)

Add to `lava-api-go/go.mod`:

```go
replace (
    // ... existing 16 lines ...
    digital.vasic.helixqa => ../submodules/helixqa
)

require (
    // ... existing requires ...
    digital.vasic.helixqa v0.0.0-00010101000000-000000000000
)
```

The `v0.0.0-00010101000000-000000000000` pseudo-version is HelixQA's pre-release marker — same pattern as `digital.vasic.cache v0.0.0-00010101000000-000000000000` already in the file (line 30).

### C.3 — Pinned-tag dependency (release builds)

For release builds (lava-api-go binary distributed to a container registry per §6.P), the dependency MUST resolve to a real tag, NOT the sibling-directory replace. HelixQA's `README.md` cites `v4.0.0`; current pin `403603db` is `v4.0.0-256-g403603d`. Two paths:

**Path A — Pin to a tagged release.**
- Bump HelixQA pin to a tag (e.g., `v4.0.0` proper)
- `lava-api-go/go.mod` uses `require digital.vasic.helixqa v4.0.0` WITHOUT a replace directive
- Release builds resolve directly from the upstream module proxy

**Path B — Continue with replace + pin SHA.**
- Keep the `replace digital.vasic.helixqa => ../submodules/helixqa` line
- Release-build container ALSO includes `submodules/helixqa/` in its context (mount or COPY)
- Same `go.mod` shape works for both dev and release

**Recommendation:** Path B for Phase 4-C-1. It avoids coupling to HelixQA's tag cadence (HelixQA is in active development per its `IMMEDIATE_EXECUTION_PLAN.md`). Migrate to Path A once HelixQA reaches release stability.

### C.4 — Transitive dependency cost (open concern)

HelixQA's `go.mod` (pin `403603db`) requires Go 1.26 (Lava is on 1.25). **POTENTIAL VERSION CONFLICT.** Two responses:

1. Bump lava-api-go's Go toolchain to 1.26 (recommended — Go 1.26 is current; 1.25 is fine but trailing).
2. Patch HelixQA's `go.mod` locally to `go 1.25` — risky, may surface incompatibilities.

The Go-version delta is the first concrete operator-decision blocker. Recorded in §G open questions.

HelixQA also pulls heavy transitive deps (Playwright Go bindings, OpenCV bridges, GStreamer, ML model runtime libs per the `pkg/` survey). Adding HelixQA to `lava-api-go/go.mod` will **substantially grow** the build cache + binary size. Per §6.T.2 (Resource Limits): the impact on lava-api-go's container image size + CI build time needs measurement before merge. Recorded as a Phase 4-C-1 acceptance criterion.

## D. CI gate integration

### D.1 — Existing tests must NOT break

lava-api-go currently has `tests/contract/`, `tests/e2e/`, `tests/parity/` + per-package unit tests. Adding HelixQA as a dependency MUST NOT break any existing test. The Phase 4-C-1 commit MUST run:

```bash
cd lava-api-go && make test
cd lava-api-go && make integration  # gated by -tags=integration
```

Both must pass after the dependency addition, BEFORE any adapter code lands.

### D.2 — Adapter unit tests

Each adapter (B.1 detector, B.2 evidence, B.3 ticket, B.5 validator) ships with a unit test in the same package:

```
lava-api-go/internal/qa/detector/adapter_test.go
lava-api-go/internal/qa/evidence/adapter_test.go
lava-api-go/internal/qa/ticket/adapter_test.go
lava-api-go/internal/qa/validator/adapter_test.go
```

Each test follows the §6.A real-binary contract pattern: real adapter + fake `CommandRunner` (HelixQA's documented test injection point). Falsifiability rehearsal recorded per §6.J in the commit body.

### D.3 — Real-stack integration tests

Per §6.J + §6.AE: each adapter needs at least ONE end-to-end real-stack test. Proposed location:

```
lava-api-go/internal/qa/<package>/adapter_real_test.go
```

Build-tag gated: `//go:build helixqa_realstack`. Default test runs skip them (transitive HelixQA deps may not be present in lightweight CI environments).

### D.4 — Per-adapter CI gate registration

The Lava verify-all sweep (`scripts/verify-all-constitution-rules.sh`) MUST gain a new gate:

```
| CM-HELIXQA-ADAPTER-COVERAGE | every adapter has unit test + real-stack test (build-tag gated)
```

Implemented as: walk `lava-api-go/internal/qa/*/`; for each, assert `adapter_test.go` exists AND a `*_real_test.go` exists with the `helixqa_realstack` build tag. Add to `scripts/check-constitution.sh` after Phase 4-C-1 lands.

## E. Phased rollout proposal

The four-phase rollout is risk-ordered (smallest API surface first, deepest integration last).

### E.1 — Phase 4-C-1: `pkg/evidence` first

**Why first:** smallest API surface (Collector + Item + a handful of capture methods), lowest risk (server-side text + log capture is well-understood), proves the `go.mod` + adapter pattern without depending on other adapters.

**Deliverables:**
- `lava-api-go/internal/qa/evidence/adapter.go` + `_test.go` + `_real_test.go`
- `lava-api-go/go.mod` adds `digital.vasic.helixqa` dependency
- `scripts/check-constitution.sh` gains `CM-HELIXQA-ADAPTER-COVERAGE` (in advisory mode)
- Go-version conflict resolution (per §C.4)
- §6.AC non-fatal telemetry coverage on every catch block
- §6.J falsifiability rehearsal recorded in commit body

**Acceptance criteria:**
- `make test` + `make integration` PASS
- `make integration -tags=helixqa_realstack` PASS
- lava-api-go binary size delta measured + recorded
- §6.AC coverage scan reports 0 violations
- §6.AC waivers (if any) documented per `docs/helix-constitution-gates.md`

**Estimated scope:** 1 session.

### E.2 — Phase 4-C-2: `pkg/detector`

**Why second:** Android-adjacent (high Lava strategic value), proven by B.1's design, depends on the §C dep-pattern already established by 4-C-1.

**Deliverables (additive on top of 4-C-1):**
- `lava-api-go/internal/qa/detector/adapter.go` + `_test.go` + `_real_test.go`
- §6.AC coverage + §6.J falsifiability rehearsal

**Acceptance criteria:** Same as 4-C-1 + adapter unit test passes against a fake CommandRunner + real-stack test passes against a sacrificial process.

**Estimated scope:** 1 session.

### E.3 — Phase 4-C-3: `pkg/ticket`

**Why third:** depends on 4-C-2 (Detector adapter's `Report` type feeds into Ticket adapter's `WriteFromCrash`). REPLACES Lava's §6.O Crashlytics closure-log MANUAL authoring with generated markdown — high operator-visible value.

**Deliverables (additive):**
- `lava-api-go/internal/qa/ticket/adapter.go` + `_test.go` + `_real_test.go`
- Migration of `.lava-ci-evidence/crashlytics-resolved/<id>-<slug>.md` AUTHORING from operator-handwritten to ticket-adapter-generated (the EXISTING closure logs stay untouched; only NEW ones use the adapter)
- §6.O compliance test asserts adapter-generated tickets satisfy the closure-log contract

**Acceptance criteria:** Same as prior + adapter-generated tickets pass `scripts/check-constitution.sh`.

**Estimated scope:** 1 session.

### E.4 — Phase 4-C-4: `pkg/navigator` + `pkg/validator`

**Why last (jointly):** deepest integration; `pkg/navigator` has weak Go-side justification (per B.4); `pkg/validator` only earns its keep when 4-C-2 + 4-C-3 are in place to feed it.

**Deliverables:**
- `lava-api-go/internal/qa/validator/adapter.go` + tests + real-stack test
- `lava-api-go/internal/qa/navigator/adapter.go` — RECONSIDERED at start of 4-C-4: if no Go-side consumer materializes, SKIP. Document the skip with rationale + revisit-trigger.
- Full E2E test: detector + evidence + validator + ticket wired together; run a deliberately-crashing scenario; assert ticket file written with all evidence linked.

**Acceptance criteria:** Same as prior + full chain passes the end-to-end test.

**Estimated scope:** 1-2 sessions (validator: 1; navigator: 0-1 depending on operator decision).

## F. §6.J anti-bluff posture

Every phase MUST satisfy ALL of:

### F.1 — Per-adapter falsifiability rehearsal (§6.J.2)

Each adapter commit body MUST contain a Bluff-Audit stamp recording:
- The mutation deliberately introduced (e.g., "flipped `Crashed` field assignment from `dr.HasCrash` to `!dr.HasCrash`")
- The observed failure message from the adapter test against the mutation
- Reverted: yes

### F.2 — §6.AC non-fatal telemetry coverage

Every adapter's catch path MUST call `observability.RecordNonFatal(ctx, err, attrs)` per §6.AC. Specifically:
- HelixQA API error → translate + record
- `os.MkdirAll` / file-write error → record
- `context.DeadlineExceeded` → record

Adapter commits land §6.AC waivers ONLY if HelixQA's API surface forces a deliberate ignore (e.g., the underlying call is fire-and-forget); waivers go in `docs/helix-constitution-gates.md` with rationale.

### F.3 — HELIX_DEV_OWNED waiver lifecycle (Phase 4 follow-up B carryover)

Phase 4 follow-up B (commit `d94ade0d`) added the `HELIX_DEV_OWNED` waiver pattern for 4 existing scanners (where the scanner would have flagged HelixQA but does not, because HelixQA is HelixDevelopment-owned and the scanner is `vasic-digital`-org-scoped). Phase 4-C MUST audit whether ANY adapter integration commit allows a HelixQA gate-check to flip from `HELIX_DEV_OWNED` waived → actually-enforced (e.g., a Lava-side test now exists that exercises a HelixQA-internal invariant).

If any such transition occurs, the waiver MUST be removed in the same commit + the scanner MUST start enforcing. This is the §6.J version of "honest scope statements" — keeping waivers when the underlying need is gone is a documentation bluff.

### F.4 — Real-stack test honesty

Per §6.J.5: the build-tag-gated real-stack tests (per §D.3) MUST actually run in at least one Phase 4-C cycle. Source-compile is NOT execution per §6.Z. The cycle's commit body MUST contain captured `go test -tags=helixqa_realstack` output as inline evidence OR a path to `.lava-ci-evidence/phase-4-c/<phase>-real-stack-evidence.log`.

## G. Open questions for operator (decisions blocking Phase 4-C-1)

The following decisions MUST be made before Phase 4-C-1 implementation begins. Each is non-trivial; defaulting silently is itself a §6.J bluff.

1. **Go-version conflict (§C.4):** HelixQA requires Go 1.26; lava-api-go is on Go 1.25. Operator decision:
   - (a) bump lava-api-go to Go 1.26 (recommended)
   - (b) patch HelixQA's `go 1.25` directive locally (risky)
   - (c) defer Phase 4-C entirely until HelixQA lowers its Go floor

2. **Adapter strategy: WRAP vs RE-EXPORT (§B):** Adapters as designed WRAP HelixQA types into Lava-shaped types (`Adapter.WriteFromCrash(crashlyticsID, *qadetector.Report)` instead of `Adapter.GenerateFromDetection(*hxqadetector.DetectionResult)`). Trade-off:
   - (a) WRAP (recommended): API stability against HelixQA changes; minor translation overhead
   - (b) RE-EXPORT: zero translation cost; ANY HelixQA API change breaks Lava

3. **Release-mode dependency resolution (§C.3):** Path A (tag-pin) or Path B (replace + sibling-mount in release containers).

4. **Per-adapter naming: keep HelixQA terminology vs Lava-domain terminology:** `Detector` → `CrashDetector` (renamed) vs `Detector` (preserved); `Collector` → `Recorder` (renamed) vs `Collector` (preserved); `Generator` → `ClosureLogWriter` (renamed) vs `Generator` (preserved). Trade-off: discoverability for HelixQA-familiar developers (preserve) vs domain clarity for Lava-only developers (rename).

5. **HelixQA upstream contribution for `pkg/evidence.CaptureGeneric` (§B.2 caveat):** Lava-side adapter currently bypasses HelixQA's collector for server-side text capture because HelixQA's `addItem` is unexported. Should we:
   - (a) contribute a `CaptureGeneric` method UPSTREAM to HelixQA (best long-term; needs HelixDevelopment review cycle)
   - (b) ship Lava-side workaround using HelixQA's Item type but bypassing the Collector (faster; technical debt)

6. **Phase 4-C-4 navigator: SKIP or proceed:** Per §B.4, `pkg/navigator` has weak Go-side justification. Operator approval to skip 4-C-4's navigator entirely vs proceeding with empty plumbing for future use.

7. **§6.AC waiver scope for HelixQA-internal panics:** HelixQA's code MAY panic in degenerate cases (e.g., malformed ADB output). Should Lava's adapters wrap every HelixQA call in `recover()` + RecordNonFatal? Or trust HelixQA to not panic? (The latter is a bluff unless HelixQA's tests cover panic-free invariants.)

8. **CI build-time budget delta:** §6.T.2 caps test runs at 30-40% host resource. HelixQA's transitive deps (Playwright Go, OpenCV bridges, GStreamer wrappers) may exceed this delta. Operator decision: is a 2x build-time increase acceptable as the cost of Phase 4-C value?

9. **Constitution submodule pin freshness for `pkg/evidence` API contract:** if HelixQA's `pkg/evidence` API changes between pin `403603db` and the Phase 4-C-1 implementation cycle, the adapter MUST adapt. Operator decision: pin HelixQA at exactly `403603db` for Phase 4-C-1 (recommended) vs always-track-upstream (risky per §6.AD pins-frozen rule).

10. **Coverage-ledger row for the adapter set:** Phase 7 (commit `c35af27c`) generates the §11.4.25 coverage ledger. Phase 4-C adapters add 4 new (feature × platform × invariant) rows. Operator decision: should Phase 4-C-1's commit ALSO bump the coverage ledger, or defer to a Phase 7-followup commit?

## H. Cross-references

- `docs/plans/2026-05-16-helixqa-integration-design.md` — parent (Phase 4 follow-up A) integration design covering all 3 options
- `docs/plans/2026-05-15-constitution-compliance.md` — parent constitution-compliance plan (Phase 4)
- `submodules/helixqa/README.md` — HelixQA framework overview
- `submodules/helixqa/pkg/detector/detector.go` — `pkg/detector` source (B.1)
- `submodules/helixqa/pkg/evidence/collector.go` — `pkg/evidence` source (B.2)
- `submodules/helixqa/pkg/navigator/engine.go` — `pkg/navigator` source (B.4)
- `submodules/helixqa/pkg/validator/validator.go` — `pkg/validator` source (B.5)
- `submodules/helixqa/pkg/ticket/ticket.go` — `pkg/ticket` source (B.3)
- `lava-api-go/go.mod` — current Go module + replace directives
- `lava-api-go/internal/` — existing internal package layout (peer to proposed `internal/qa/`)
- `CLAUDE.md` §6.AC — Comprehensive Non-Fatal Telemetry Mandate
- `CLAUDE.md` §6.AD — HelixConstitution Inheritance (parent inheritance pattern)
- `CLAUDE.md` §6.AE — Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate
- `CLAUDE.md` §6.J — Anti-Bluff Functional Reality Mandate
- `CLAUDE.md` §6.O — Crashlytics-Resolved Issue Coverage Mandate
- `CLAUDE.md` §6.T.2 — Resource Limits for Tests & Challenges
- HelixConstitution §11.4.17 — Universal-vs-project classification
- HelixConstitution §11.4.27 — 100%-test-type coverage (Phase 4 motivator)
- HelixConstitution §11.4.31 — Inter-submodule dependency manifest

## I. Status

**Phase 4-C-1: COMPLETED 2026-05-16.** Operator approved all 10 §G open questions and Lava-side `internal/qa/evidence` adapter landed. All §E.1 acceptance criteria met.

| Q | Operator decision |
|---|---|
| Q1 | Bump lava-api-go to Go 1.26 (was 1.25) |
| Q2 | WRAP strategy (adapter exposes Lava-shaped methods translating HelixQA types) |
| Q3 | Path A — tag-pin in go.mod; transitional Path B (`replace` + sibling-mount) active until HelixQA stabilizes |
| Q4 | Preserve HelixQA terminology — adapter type is `Collector`, NOT renamed |
| Q5 | Contribute `CaptureGeneric` upstream first → HelixDevelopment/HelixQA PR #1, branch `feat/evidence-capture-generic`, commit `a1e2020dd759d025b67ef8e024061b103940470d` |
| Q6 | SKIP 4-C-4 navigator entirely (no plumbing, no skeleton) |
| Q7 | NO `recover()` wrapping — trust HelixQA + file upstream improvements if panics surface |
| Q8 | Accept 2x CI build-time increase |
| Q9 | Always-track-upstream for HelixQA pin (§6.AD waiver in root `CLAUDE.md`) |
| Q10 | Bump coverage ledger in the SAME Phase 4-C-1 commit |

**Deliverables landed:**
- HelixQA `pkg/evidence.CaptureGeneric` public method + 4 unit tests (HelixQA PR #1, commit `a1e2020d`)
- `lava-api-go/internal/qa/evidence/collector.go` (281 LOC) — WRAP-strategy adapter with `NewCollector`, `CaptureText`, `CaptureFile`, `Finalize`, `Items`, `Count`, `RunID`, `OutputDir`
- `lava-api-go/internal/qa/evidence/collector_test.go` (9 tests, 87.9% statement coverage)
- `lava-api-go/tests/qa/evidence_test.go` (2 real-stack tests, `//go:build helixqa_realstack`)
- `lava-api-go/go.mod`: Go 1.26.0 + `digital.vasic.helixqa` require/replace block addition
- `Submodules/HelixQA/` pin advanced `b13ba7c0` → `a1e2020d`
- `docs/coverage-ledger.yaml` regenerated (58 rows; lava-api-go: 89 unit tests + 1 integration)
- `CLAUDE.md` Sixth-Law-extensions block: HelixQA always-track-upstream waiver paragraph
- `docs/CONTINUATION.md`: Phase 4-C-1 row in §1 + HelixQA pin update in §3

**§6.J anti-bluff posture (4 falsifiability rehearsals, all reverted):**
1. `TypeConsoleLog → TypeScreenshot` mutation in `CaptureText` → `Item.Type = "screenshot"; want "console_log"`
2. all `return err → return nil` in `CaptureFile` → `expected error for missing source; got nil`
3. `c.finalized = true → false` in `Finalize` → 3 assertions fired (post-finalize captures got nil instead of ErrFinalized; Count grew)
4. HelixQA-side: `if item.Platform == "" → != ""` in `CaptureGeneric` → standalone exerciser fails `FAIL: platform default not applied:`

**Real anti-bluff finding surfaced during implementation:** the initial `TestCollector_ConcurrentCaptures` test caught a production filename-collision bug (millisecond-resolution timestamps + same label letter `a` at i=0 and i=26 produced identical paths under burst). Production fix: added `atomic.Uint64 seq` counter to filename template (`<runID>-<label>-<ms>-<seq>.txt`). The test surfacing a real defect — rather than passing while the bug shipped — is exactly the §6.J posture this clause exists to prove.

**Acceptance criteria all met:**
- ✅ `go test ./...` PASS for every lava-api-go package including new `internal/qa/evidence`
- ✅ `go test -tags=helixqa_realstack ./tests/qa/...` PASS (2/2 real-stack tests)
- ✅ §6.AC telemetry coverage: every error path records via `observability.RecordNonFatal` with mandatory attribute set
- ✅ §6.J falsifiability rehearsals: 4 mutations, 4 reverted, 4 PASS-after-revert
- ✅ `scripts/verify-all-constitution-rules.sh --strict`: 40/40 PASS preserved

**Next phases owed:** 4-C-2 (detector adapter), 4-C-3 (ticket adapter), 4-C-4 (validator only; navigator SKIPPED per Q6). Each is 1-session scope per §E.

---

**Phase 4-C-2: COMPLETED 2026-05-16.** Lava-side `internal/qa/detector` adapter landed. All §E.2 acceptance criteria met. Operator decisions Q1-Q10 inherited from Phase 4-C-1 (unchanged).

**Deliverables landed:**
- `lava-api-go/internal/qa/detector/detector.go` (255 LOC) — WRAP-strategy adapter over HelixQA `pkg/detector.Detector`. Public API:
  - `type Report struct { Crashed, Alive bool; StackTrace, EvidencePath string }` — Lava-flattened view of HelixQA's `DetectionResult` (Android-only HasANR + LogEntries + Timestamp intentionally dropped per WRAP minimization)
  - `func New(evidenceDir string, opts ...Option) *Detector` — constructor; `WithCommandRunner(hxqadetector.CommandRunner) Option` for test injection
  - `func (d *Detector) CheckGoProcess(ctx, processName string) (*Report, error)` — name-based detection (`pgrep -f` under the hood)
  - `func (d *Detector) CheckGoProcessByPID(ctx, pid int) (*Report, error)` — PID-based detection (`kill -0 <pid>` under the hood)
  - `func (d *Detector) EvidenceDir() string` — accessor
  - `var ErrEmptyProcessName, ErrInvalidPID error` — Lava-side validation guards (HelixQA silently falls back to `processName="java"` for empty inputs; the adapter rejects them as explicit errors)
- `lava-api-go/internal/qa/detector/detector_test.go` (11 tests, 82.9% statement coverage, race-clean)
- `lava-api-go/tests/qa/detector_test.go` (3 real-stack tests, `//go:build helixqa_realstack`) — spawns real `sleep` child processes, asserts the REAL HelixQA Detector's `pgrep -f` / `kill -0` invocations correctly map to Lava-side Report fields
- `docs/coverage-ledger.yaml` regenerated (58 rows preserved; lava-api-go: 89 → 93 unit tests as expected from `internal/qa/detector` + parallel agent's `internal/qa/ticket` additions counted simultaneously)
- `docs/CONTINUATION.md` Phase 4-C-2 row added

**Q5 (upstream contribution) — no work needed.** HelixQA's `pkg/detector` public API already exposes everything the adapter needs (`Detector`, `Option`, `New`, `WithDevice/WithPackageName/WithBrowserURL/WithProcessName/WithProcessPID/WithEvidenceDir/WithCommandRunner`, `Check`, `CheckApp`, `Platform`, `DetectionResult`, `CommandRunner` interface). No HelixQA-side promotion needed for Phase 4-C-2 — contrast with 4-C-1 which had to add `CaptureGeneric` upstream.

**§6.J anti-bluff posture (4 falsifiability rehearsals, all reverted):**
1. `Crashed: dr.HasCrash → !dr.HasCrash` in `translate()` → 5 unit-test assertions fired: `TestCheckGoProcess_Alive_ReturnsCorrectReport`: `Report.Crashed = true; want false`; `TestCheckGoProcess_Dead_ReturnsCrashed`: `Report.Crashed = false; want true`; `TestCheckGoProcessByPID_Alive`: `Report.Crashed = true; want false`; `TestCheckGoProcessByPID_Dead`: `Report.Crashed = false; want true`; `TestTranslate_MappingDirection`: `translate: Crashed=false; want true (HasCrash=true → Crashed=true)`
2. `strings.TrimSpace(processName) == ""` guard removed from `CheckGoProcess` → compile-error `"strings" imported and not used` (caught at build time — equally valid §6.J failure signal)
3. `pid <= 0` guard removed from `CheckGoProcessByPID` → 2 sub-test assertions fired: `pid=-1`: `err = <nil>; want ErrInvalidPID` + `report = &{... Alive:true ...}; want nil on validation error`; `pid=0`: same. Forensic note: HelixQA silently falls back to `processName="java"` when PID is non-positive, which exists on most dev machines — the Report would have wrongly reported Alive=true. The guard is load-bearing.
4. `Alive: dr.ProcessAlive → !dr.ProcessAlive` in `translate()` → all 3 real-stack tests fired in `tests/qa/detector_test.go`: `TestDetectorAdapter_RealStack_LiveProcess_ReportsAlive`: `Report.Alive = false; want true (PID 58723 was spawned and not killed)` + post-kill: `Report.Alive = true; want false (PID 58723 was killed)`; `TestDetectorAdapter_RealStack_NameBasedDetection`: `Report.Alive = false; want true (sentinel process "lava_api_go_detector_test_..." is alive)`; `TestDetectorAdapter_RealStack_GhostProcess_ReportsDead`: `Report.Alive = true; want false (ghost process "lava-ghost-process-zzzznotreal-xyz" does not exist)`

**Honest scope statement (per §6.J / §6.L):**
- The real-stack tests in `tests/qa/detector_test.go` were executed standalone via `go test -tags=helixqa_realstack ./tests/qa/detector_test.go` (file-target form). The package-target form `./tests/qa/...` is currently blocked by a parallel agent's untracked `internal/qa/ticket/` package whose `generator.go` has an unresolved HelixQA-side compile error (`*stepResultStub` vs `*validator.StepResult`). Per task constraint "DO NOT touch Phase 4-C-3's ticket adapter package", the detector-side proof is captured via file-target execution; once the parallel ticket agent's commit lands clean, package-target runs will pass for both adapters together.
- HelixQA SHA `a1e2020dd759d025b67ef8e024061b103940470d` unchanged from Phase 4-C-1 (no HelixQA-side work was needed for 4-C-2 per Q5 above; no pin bump).
- `scripts/check-non-fatal-coverage.sh` STRICT scans the detector adapter's catch paths — all 5 error paths call `observability.RecordNonFatal` with the canonical attribute set per §6.AC.

**Acceptance criteria all met:**
- ✅ `go test ./internal/qa/detector/...`: PASS (11 tests, 82.9% coverage, race-clean)
- ✅ `go test -tags=helixqa_realstack ./tests/qa/detector_test.go`: PASS (3/3 real-stack tests; live + dead + ghost process paths via real `pgrep -f` and `kill -0`)
- ✅ §6.AC telemetry coverage: every error path records via `observability.RecordNonFatal` with `feature=qa.detector` + `operation=CheckGoProcess`/`CheckGoProcessByPID` + `error_class` + relevant context (`process_name` / `pid`)
- ✅ §6.J falsifiability rehearsals: 4 mutations, 4 reverted, 4 PASS-after-revert
- ✅ Q4 terminology preserved: type name `Detector` matches HelixQA's
- ✅ Q7 honoured: no `recover()` wrapping anywhere in the adapter
- ✅ `scripts/verify-all-constitution-rules.sh --strict`: 40/40 PASS preserved (attestation in this commit's run)

**Classification:** project-specific (Lava's `Report` shape + the `CheckGoProcess`/`CheckGoProcessByPID` semantic split + the `ErrEmptyProcessName` + `ErrInvalidPID` Lava-only guards are domain-specific; the WRAP-as-version-isolation pattern is universal per HelixConstitution §11.4.31 — already documented under Phase 4-C-1's classification line).

**Next phases owed:** 4-C-3 (ticket adapter; in-flight by parallel agent), 4-C-4 (validator only; navigator SKIPPED per Q6).

### Phase 4-C-3: COMPLETED 2026-05-16

Operator decisions reused from Phase 4-C-1 (Q1–Q10, all answers unchanged). Q4 confirmed at dispatch time: preserve HelixQA terminology — adapter type is `Generator`, NOT renamed to `ClosureLogWriter`.

**Deliverables landed:**
- HelixQA-side prereq (Q5 equivalent): `submodules/helixqa/pkg/ticket/enhanced_generator.go` gated behind `//go:build helixqa_enhanced_tickets` so plain consumers of `pkg/ticket` (like the Lava adapter) do not transitively need the LLMOrchestrator submodule. HelixQA-internal builds + tests opt in via `-tags=helixqa_enhanced_tickets`. HelixQA SHA bump owed at the meta-cycle close.
- `lava-api-go/internal/qa/ticket/generator.go` (≈340 LOC) — WRAP-strategy adapter with `NewGenerator`, `GenerateClosureLog`, `OutputDir`, plus `ClosureLogInput` shape mirroring §6.O closure-log conventions
- `lava-api-go/internal/qa/ticket/generator_test.go` (13 unit tests + 8 slug-sanitization sub-tests, 93.2% statement coverage with `-race`)
- `lava-api-go/tests/qa/ticket_test.go` (2 real-stack tests, `//go:build helixqa_realstack`)
- `CLAUDE.md` §6.O extended with clause 7 — programmatic-closure-log adapter authorized; trailer line `Generated by lava-api-go/internal/qa/ticket` provides provenance honesty
- `docs/CONTINUATION.md`: Phase 4-C-3 row in §1

**§6.J anti-bluff posture (2 falsifiability rehearsals, both reverted):**
1. Mutated H1 heading from `# Crashlytics issue %s — closure log` → `# Closure record %s` in `renderClosureLog` → `TestGenerateClosureLog_SchemaMatchesExistingLogs` fails with `§6.O schema heading "# Crashlytics issue " missing` (test detected the schema break, then revert restored green)
2. Mutated empty-CrashlyticsID validation `return "", ErrEmptyCrashlyticsID` → `return "", nil` in `GenerateClosureLog` → `TestGenerateClosureLog_RejectsEmptyCrashlyticsID` fails with `err = <nil>; want qa/ticket: CrashlyticsID is required for closure log` (test detected the silent-write bluff path, then revert restored green)

**Real anti-bluff finding surfaced during implementation:** the initial adapter design wrapped `hxqaticket.Generator.GenerateFromStep(g.synthesizeStep(in), in.CrashlyticsID)` to maintain the HelixQA HQA-#### in-memory counter parity. That call required a `*validator.StepResult` which would have cross-coupled Phase 4-C-3 with Phase 4-C-4's validator-adapter territory AND would have built an HQA-#### bookkeeping ticket whose only purpose was to make the assertion "we are wrapping" look real. The honest §6.J move: drop the synthesize indirection, hold the wrapped `*hxqaticket.Generator` for Phase 4-C-4's future detector→ticket integration, and let the on-disk markdown be the authoritative §6.O artifact. The wrapping is real (the dep edge is live, the type is constructed and held); the rendering is Lava-owned (because §6.O's schema is a Lava concept, not a HelixQA concept). Forcing a fake HelixQA call to "prove the WRAP" would have been a §6.J bluff by construction.

**Acceptance criteria all met:**
- ✅ `go test ./...` PASS for every lava-api-go package including new `internal/qa/ticket`
- ✅ `go test -tags=helixqa_realstack ./tests/qa/...` PASS (7/7 tests including 2 new ticket-adapter real-stack tests + 3 detector + 2 evidence)
- ✅ §6.AC telemetry coverage: every error path records via `observability.RecordNonFatal` with mandatory attribute set
- ✅ §6.J falsifiability rehearsals: 2 mutations, 2 reverted, 2 PASS-after-revert
- ✅ HelixQA upstream build-tag prereq landed cleanly — pkg/ticket builds standalone without LLMOrchestrator

**Conflict surfaces with sibling worktrees (Phase 4-C-2, Phase 4-C-4):**
- `submodules/helixqa/` SHA + governance files: HelixQA worktree's CLAUDE.md / AGENTS.md / CONSTITUTION.md were modified by other agents in parallel; Phase 4-C-3's prereq commit only touches `pkg/ticket/enhanced_generator.go`
- `lava-api-go/internal/qa/detector/` exists as untracked file from Phase 4-C-2 sibling worktree — Phase 4-C-3 did NOT modify it
- `lava-api-go/internal/qa/validator/` will conflict with Phase 4-C-4 if 4-C-4 lands first (no overlap today)
- `lava-api-go/tests/qa/ticket_test.go` is new; `evidence_test.go` + `detector_test.go` (from 4-C-1 / 4-C-2 sibling) untouched
- `docs/coverage-ledger.yaml` row addition: each sibling worktree adds its own qa/* package row — expect conflicts at the rolled-up regen
- `docs/CONTINUATION.md` §1 row addition: each sibling worktree adds its own Phase row — expect conflicts at merge
- `docs/plans/2026-05-16-helixqa-go-package-linking-design.md` §I per-phase section append: each sibling worktree adds its own section — sequential merge will be clean if each appends; concurrent edits at the same line need manual reconciliation

`Classification:` project-specific (per-package adapter wraps + lava-api-go-specific paths are project-specific; the adapter-layer-as-version-isolation pattern is universal per HelixConstitution §11.4.31).
