// Package detector is Lava's adapter over HelixQA's pkg/detector.
//
// Phase 4-C-2 (2026-05-16) — second of the four-phase Group A rollout per
// docs/plans/2026-05-16-helixqa-go-package-linking-design.md. This adapter
// wraps HelixQA's Detector (Q2 WRAP strategy per operator decisions) and
// preserves HelixQA's terminology (Q4: Detector keeps its name) so
// HelixQA-familiar developers can navigate this package without a glossary.
//
// The adapter exists because HelixQA's Detector centers on three platform
// targets — Android (ADB), web (browser process), desktop (JVM / Go) — none
// of which match lava-api-go's actual need at the SoT level. lava-api-go
// runs as a Go binary on Linux servers; what it needs is "is the lava-api-go
// process still alive, and if it died, what was the last we saw?". HelixQA's
// desktop path (`pgrep -f <name>` + `kill -0 <pid>`) covers exactly that
// case, but HelixQA's API exposes the Android-shaped fields (HasANR,
// StackTrace, ScreenshotPath) too. The Lava-side Report struct flattens to
// the four fields lava-api-go actually consumes (Crashed, Alive, StackTrace,
// EvidencePath); the WRAP keeps Lava callers stable across HelixQA version
// bumps.
//
// The adapter exposes two Lava-shaped methods:
//
//   - CheckGoProcess(ctx, processName) — the canonical lava-api-go path.
//     Wires HelixQA's desktop detector with WithProcessName + invokes
//     Check; translates the result.
//   - CheckGoProcessByPID(ctx, pid) — PID-based variant for tests / chaos
//     scenarios where the process was launched in-process and the PID is
//     known.
//
// Per §6.AC (Comprehensive Non-Fatal Telemetry Mandate): every error path
// records via observability.RecordNonFatal so server-side telemetry surfaces
// in the operator's Crashlytics / OTLP dashboards.
//
// Per Q7 (operator decision, Phase 4-C-1 cycle): NO recover() wrapping. If
// HelixQA's Detector panics, the panic propagates; the project files a
// sixth-law-incident JSON per §6.M and a HelixQA upstream issue per Q5.
//
// Per §6.J / §6.AE.4: every public method has a falsifiability rehearsal
// recorded in the Phase 4-C-2 commit body, and a real-stack integration
// test under tests/qa/ exercises the REAL HelixQA Detector against a real
// process the test owns (gated by -tags=helixqa_realstack).
package detector

import (
	"context"
	"errors"
	"fmt"
	"strings"

	hxqaconfig "digital.vasic.helixqa/pkg/config"
	hxqadetector "digital.vasic.helixqa/pkg/detector"

	"digital.vasic.lava.apigo/internal/observability"
)

// telemetryFeature is the canonical §6.AC AttrFeature value for events
// originating in this adapter. Stable string so dashboards can filter.
const telemetryFeature = "qa.detector"

// Report is Lava's flattened view of HelixQA's DetectionResult — exposes
// only the fields lava-api-go actually consumes today. The Android-only
// HasANR, the LogEntries slice, and the Timestamp are intentionally
// dropped; if a future caller needs them, extend Report and re-translate
// from DetectionResult in the same commit (do not leak hxqadetector types
// into Lava's call sites — that breaks the WRAP).
type Report struct {
	// Crashed indicates the target process is no longer alive. Maps to
	// HelixQA's DetectionResult.HasCrash for desktop platform (set true
	// when ProcessAlive is false).
	Crashed bool

	// Alive indicates the target process is currently running. Maps
	// directly to HelixQA's DetectionResult.ProcessAlive.
	Alive bool

	// StackTrace contains the crash stack trace if available. Maps
	// directly to HelixQA's DetectionResult.StackTrace. For the Go
	// desktop path this is typically empty — HelixQA's desktop detector
	// does not parse Go panics from the OS process table; callers
	// wanting a stack trace pre-attach it as evidence via the qa/evidence
	// adapter (Phase 4-C-1).
	StackTrace string

	// EvidencePath is the path under which the detector configured its
	// evidence directory (mirrors HelixQA's WithEvidenceDir). Useful for
	// callers correlating detector reports with evidence captured via
	// qa/evidence.Collector under the same root.
	EvidencePath string
}

// Detector is Lava's adapter over HelixQA's pkg/detector.Detector.
// Q4 (preserve HelixQA terminology): the type name "Detector" matches
// HelixQA's so developers reading both codebases see the same noun.
// Q2 (WRAP strategy): Lava-shaped methods translate to/from HelixQA's
// shape; HelixQA-version bumps only require touching this file.
//
// Concurrency: a Detector is NOT safe for concurrent CheckGoProcess /
// CheckGoProcessByPID calls. HelixQA's Detector mutates internal
// configuration (processName, processPID) on each call; concurrent
// callers MUST construct one Detector per goroutine OR serialise calls
// through a Mutex. The lava-api-go consumers today are single-threaded
// chaos / lifecycle paths.
type Detector struct {
	evidenceDir string
	runner      hxqadetector.CommandRunner
}

// New constructs a Detector rooted at evidenceDir. If a custom
// CommandRunner is desired (tests, fault injection), pass it via
// WithCommandRunner. The default runner is HelixQA's NewExecRunner
// (real `pgrep` / `kill -0` invocations).
//
// evidenceDir is passed through to every per-check Detector invocation
// via WithEvidenceDir. It does NOT have to exist on disk at construction
// time — the underlying HelixQA desktop detector does not create files
// (it only reads /proc and runs pgrep). The field is preserved on
// the Report so downstream consumers (Phase 4-C-3 ticket adapter) can
// correlate detector findings with evidence collected elsewhere.
func New(evidenceDir string, opts ...Option) *Detector {
	d := &Detector{evidenceDir: evidenceDir}
	for _, opt := range opts {
		opt(d)
	}
	return d
}

// Option configures a Detector. Functional-options pattern mirrors
// HelixQA's own Detector API for symmetry.
type Option func(*Detector)

// WithCommandRunner sets a custom HelixQA CommandRunner. Useful for
// tests that want deterministic process-table responses; production
// callers should leave this unset (default uses real system commands).
func WithCommandRunner(runner hxqadetector.CommandRunner) Option {
	return func(d *Detector) {
		d.runner = runner
	}
}

// CheckGoProcess checks whether a Go process matching processName is
// currently alive. The check is name-based: HelixQA's desktop detector
// invokes `pgrep -f <name>` and inspects the result.
//
// On success, the Report's Alive field is set; if Alive is false,
// Crashed is set true. StackTrace is typically empty for the Go path
// (HelixQA doesn't parse Go panics from /proc); callers wanting one
// should attach it as evidence via the qa/evidence adapter (Phase 4-C-1).
//
// Per §6.AC: errors from HelixQA's Detector.Check propagate to a
// non-fatal telemetry event before being returned. An empty processName
// is rejected immediately with ErrEmptyProcessName (not a HelixQA
// passthrough — would default to "java" on the HelixQA side, which is
// almost certainly wrong for a Go API service).
func (d *Detector) CheckGoProcess(ctx context.Context, processName string) (*Report, error) {
	if strings.TrimSpace(processName) == "" {
		err := ErrEmptyProcessName
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CheckGoProcess",
		})
		return nil, err
	}

	hxqa := hxqadetector.New(
		hxqaconfig.PlatformDesktop,
		hxqadetector.WithProcessName(processName),
		hxqadetector.WithEvidenceDir(d.evidenceDir),
		d.runnerOption(),
	)
	dr, err := hxqa.Check(ctx)
	if err != nil {
		err = fmt.Errorf("qa/detector: HelixQA Check: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CheckGoProcess",
			"process_name":              processName,
		})
		return nil, err
	}
	return d.translate(dr), nil
}

// CheckGoProcessByPID checks whether a process with the given PID is
// currently alive. The check is PID-based: HelixQA's desktop detector
// invokes `kill -0 <pid>` and inspects the exit status.
//
// pid MUST be positive; a non-positive PID is rejected with
// ErrInvalidPID (HelixQA's detector silently falls back to processName
// in that case, which is a surprise for Lava callers expecting an
// explicit PID lookup).
//
// Per §6.AC: errors from HelixQA's Detector.Check propagate to a
// non-fatal telemetry event before being returned.
func (d *Detector) CheckGoProcessByPID(ctx context.Context, pid int) (*Report, error) {
	if pid <= 0 {
		err := ErrInvalidPID
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CheckGoProcessByPID",
			"pid":                       fmt.Sprintf("%d", pid),
		})
		return nil, err
	}

	hxqa := hxqadetector.New(
		hxqaconfig.PlatformDesktop,
		hxqadetector.WithProcessPID(pid),
		hxqadetector.WithEvidenceDir(d.evidenceDir),
		d.runnerOption(),
	)
	dr, err := hxqa.Check(ctx)
	if err != nil {
		err = fmt.Errorf("qa/detector: HelixQA Check: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CheckGoProcessByPID",
			"pid":                       fmt.Sprintf("%d", pid),
		})
		return nil, err
	}
	return d.translate(dr), nil
}

// EvidenceDir returns the detector's configured evidence directory.
// Stable across HelixQA-version bumps; the underlying Detector instances
// are constructed per-check, so this field is the only place the value
// is retained between calls.
func (d *Detector) EvidenceDir() string { return d.evidenceDir }

// runnerOption returns the HelixQA Option for the configured runner.
// When the caller did not pass WithCommandRunner, returns a no-op
// Option (HelixQA's New picks its default execRunner).
func (d *Detector) runnerOption() hxqadetector.Option {
	if d.runner == nil {
		return func(*hxqadetector.Detector) {}
	}
	return hxqadetector.WithCommandRunner(d.runner)
}

// translate maps HelixQA's DetectionResult onto Lava's Report. The
// mapping is intentionally narrow:
//
//   - HasCrash   → Report.Crashed
//   - ProcessAlive → Report.Alive
//   - StackTrace → Report.StackTrace
//   - (constant) → Report.EvidencePath (from the Detector's stored dir)
//
// Per the Phase 4-C-2 commit body: the falsifiability rehearsal for
// this function is "invert the Crashed mapping" — TestTranslate_Mapping
// asserts the precise direction and FAILs when negated.
func (d *Detector) translate(dr *hxqadetector.DetectionResult) *Report {
	if dr == nil {
		return &Report{EvidencePath: d.evidenceDir}
	}
	return &Report{
		Crashed:      dr.HasCrash,
		Alive:        dr.ProcessAlive,
		StackTrace:   dr.StackTrace,
		EvidencePath: d.evidenceDir,
	}
}

var (
	// ErrEmptyProcessName is returned by CheckGoProcess when processName
	// is empty or whitespace-only.
	ErrEmptyProcessName = errors.New("qa/detector: process name is empty")

	// ErrInvalidPID is returned by CheckGoProcessByPID when pid <= 0.
	ErrInvalidPID = errors.New("qa/detector: pid must be positive")
)
