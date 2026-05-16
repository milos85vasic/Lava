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

**DESIGN-ONLY.** No code changes in this commit. Operator approval owed on all 10 §G open questions before Phase 4-C-1 implementation begins. Phase 4-C-1 cycle deliverables enumerated in §E.1 are blocked on those decisions.

`Classification:` project-specific (per-package adapter wraps + lava-api-go-specific paths are project-specific; the adapter-layer-as-version-isolation pattern is universal per HelixConstitution §11.4.31).
