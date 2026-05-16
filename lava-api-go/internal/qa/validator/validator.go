// Package validator is Lava's adapter over HelixQA's pkg/validator Validator.
//
// Phase 4-C-4 (2026-05-16) — fourth and final adapter of the four-phase
// Group A rollout per docs/plans/2026-05-16-helixqa-go-package-linking-
// design.md §B.5 + §E.4. This adapter wraps HelixQA's Validator (Q2 WRAP
// strategy per operator decisions) and preserves HelixQA's terminology
// (Q4: Validator keeps its name) so HelixQA-familiar developers can
// navigate this package without a glossary.
//
// CHAIN ROLE. The validator adapter is the DOWNSTREAM AGGREGATOR of the
// three sibling Phase 4-C adapters:
//
//	qa.detector  ─┐
//	qa.evidence ──┼──→  qa.validator  ─→  qa.ticket
//	              ┘
//
// The validator drives per-step validation in four phases:
//
//  1. Pre-step screenshot path resolution via Lava's qa.evidence.Collector
//     (wired in through ScreenshotFunc — the bridge to evidence).
//  2. Crash/ANR detection via the wrapped HelixQA Detector.
//  3. Post-step screenshot path resolution on success.
//  4. On FAILED step: auto-generate a §6.O-shaped closure-log ticket
//     via the wrapped HelixQA ticket Generator — the validator routes
//     the StepResult through HelixQA's GenerateFromStep and writes
//     the markdown to the configured ticket directory.
//
// This chain consolidates the validation harness into one HOLISTIC
// per-step cycle — a single ValidateStep call captures pre evidence,
// runs detection, captures post evidence (or detection-time evidence
// on crash), AND emits an actionable ticket for any failure.
//
// SIBLING-ADAPTER CONSUMPTION. The validator depends on the HelixQA
// primitives directly because the sibling Lava qa.detector and
// qa.ticket adapters' present public API does not expose the HelixQA
// types the HelixQA Validator + Generator need. Per the Phase 4-C
// CHAIN ROLE: a downstream consumer that wants to wire all three Lava
// adapters together does so at the CALL SITE — instantiating the
// HelixQA primitives in parallel to constructing the Lava adapters,
// then handing the HelixQA primitives to validator.New() while the
// Lava-shaped adapters retain ownership of telemetry / output-dir
// concerns. The integration test under `tests/qa/validator_test.go`
// shows this pattern end-to-end. A future Phase 4-C follow-up MAY
// extend qa.detector + qa.ticket with HelixQA() accessors at which
// point the validator can offer a NewFromAdapters() convenience
// constructor; not required for the present chain to function.
//
// Per Phase 4-C operator decisions Q4 (preserve HelixQA terminology),
// the type name "Validator" matches HelixQA's. Per Q6, the sibling
// pkg/navigator adapter is SKIPPED entirely — no plumbing, no skeleton.
// The validator is the Group A endgame.
//
// SCOPE. The Lava use case for the validator chain (per design doc
// §B.5):
//
//   - lava-api-go integration test scenarios (Phase 4b chaos / DDoS /
//     sustained-load multi-step interactions against the API) — each
//     step gets a StepResult with crash detection + evidence captured.
//   - The ticket-generation hook produces §6.O-style markdown closure
//     logs for any failed step without operator transcription.
//   - The detector chain ensures every "test step passed" claim is
//     backed by real crash/ANR detection rather than the SUT's own
//     success-signal (the §6.J / §6.L anti-bluff posture).
//
// Per §6.AC (Comprehensive Non-Fatal Telemetry Mandate): every error
// path records via observability.RecordNonFatal so server-side
// telemetry surfaces in the operator's Crashlytics / OTLP dashboards.
// Per §6.AC.7 (operator decision Q7): NO recover() wrapping — we trust
// HelixQA's API and file upstream issues if panics surface. Per §6.J:
// each method's falsifiability rehearsal is recorded in the Phase 4-C-4
// commit body.
package validator

import (
	"context"
	"fmt"
	"path/filepath"
	"sync"
	"time"

	hxqaconfig "digital.vasic.helixqa/pkg/config"
	hxqadetector "digital.vasic.helixqa/pkg/detector"
	hxqaticket "digital.vasic.helixqa/pkg/ticket"
	hxqavalidator "digital.vasic.helixqa/pkg/validator"

	qaevidence "digital.vasic.lava.apigo/internal/qa/evidence"
	"digital.vasic.lava.apigo/internal/observability"
)

// telemetryFeature is the canonical §6.AC AttrFeature value for events
// originating in this adapter. Stable string so dashboards can filter.
const telemetryFeature = "qa.validator"

// StepStatus mirrors HelixQA's StepStatus vocabulary so consumers
// reading both codebases see the same noun. Type alias (vs. wrapper
// type) keeps marshalling, comparisons, and switch statements working
// without round-trip translation.
type StepStatus = hxqavalidator.StepStatus

// Re-exports of HelixQA's StepStatus constants. Preserved verbatim so
// Lava-side switch statements over StepStatus stay readable.
const (
	StepPassed  = hxqavalidator.StepPassed
	StepFailed  = hxqavalidator.StepFailed
	StepSkipped = hxqavalidator.StepSkipped
	StepError   = hxqavalidator.StepError
)

// StepResult mirrors HelixQA's StepResult. Type alias avoids the cost
// of field-by-field translation; consumers can pass the value to the
// wrapped HelixQA ticket Generator.GenerateFromStep() directly because
// the generator accepts the same HelixQA type.
type StepResult = hxqavalidator.StepResult

// Validator is Lava's adapter over HelixQA's pkg/validator.Validator.
// Q4 (preserve HelixQA terminology): the type name "Validator" matches
// HelixQA's so developers reading both codebases see the same noun.
// Q2 (WRAP strategy): Lava-shaped methods translate to/from HelixQA's
// shape; HelixQA-version bumps only require touching this file.
//
// The Validator chains the three sibling adapters at the HelixQA layer:
// it drives the supplied HelixQA Detector for crash/ANR detection,
// wires screenshot capture through the optional qa.evidence.Collector,
// and auto-emits §6.O closure-log tickets via the optional HelixQA
// ticket Generator on failed steps.
//
// Concurrency: safe for parallel use. HelixQA's underlying Validator
// guards its result list with its own mutex; this adapter adds one
// additional mutex to protect ticket-generator state during failure
// auto-emission.
type Validator struct {
	hxqa     *hxqavalidator.Validator
	hxqaTkt  *hxqaticket.Generator
	evidence *qaevidence.Collector

	runID     string
	autoEmit  bool
	testCase  string
	ticketDir string

	mu sync.Mutex
}

// Option configures a Validator.
type Option func(*Validator)

// WithEvidenceCollector wires the validator to a Lava qa.evidence
// Collector for pre/post-step screenshot path resolution. The
// collector's OutputDir is used to compute screenshot file paths the
// HelixQA Validator records on each StepResult.
//
// When unset, screenshots are skipped (matching HelixQA's behavior
// when ScreenshotFunc is nil). Lava integration tests SHOULD always
// wire an evidence collector so post-step state can be captured even
// on success — the captured evidence is what §6.J / §6.AB demand.
func WithEvidenceCollector(c *qaevidence.Collector) Option {
	return func(v *Validator) {
		v.evidence = c
	}
}

// WithTicketGenerator wires the validator to a HelixQA ticket
// Generator for §6.O-style closure-log auto-emission. When wired,
// every failed StepResult is routed through GenerateFromStep and
// the resulting markdown ticket is written to the generator's output
// directory (or the override directory if WithTicketDir is also set).
//
// When unset, failure auto-emission is disabled (the StepResult is
// still recorded; the caller can manually drive ticket generation
// later via Results()).
//
// SIBLING-ADAPTER NOTE. A consumer driving the full Phase 4-C chain
// SHOULD construct the HelixQA ticket Generator in parallel to
// constructing the Lava qa.ticket adapter; pass the HelixQA Generator
// here for auto-emission, and use the Lava qa.ticket adapter for any
// separate §6.O closure-log writes the caller initiates explicitly.
func WithTicketGenerator(g *hxqaticket.Generator) Option {
	return func(v *Validator) {
		v.hxqaTkt = g
		v.autoEmit = g != nil
	}
}

// WithTestCaseID sets the test-case ID propagated to generated
// tickets. Defaults to the validator's runID. Per §6.O the closure-log
// SHOULD identify which test surface produced the failure.
func WithTestCaseID(id string) Option {
	return func(v *Validator) {
		v.testCase = id
	}
}

// WithTicketDir overrides the directory where auto-emitted tickets
// are written. Defaults to the HelixQA ticket Generator's own output
// dir if unset. Honoured only when a ticket generator is wired via
// WithTicketGenerator.
func WithTicketDir(dir string) Option {
	return func(v *Validator) {
		v.ticketDir = dir
	}
}

// New constructs a Validator that wraps the supplied HelixQA Detector.
//
// The runID is included in step-name fallbacks + telemetry attributes
// so concurrent test runs against a shared evidence dir do not
// collide. evidenceDir is forwarded to HelixQA's Validator for any
// evidence-path lookups the validator itself performs.
//
// The Detector parameter is the HelixQA primitive directly because
// the present Lava qa.detector adapter API constructs a fresh HelixQA
// Detector per check and does not expose a stable HelixQA Detector
// handle the HelixQA Validator can drive (Validator.ValidateStep
// calls det.CheckApp internally and expects the same Detector to
// persist across calls). Consumers wiring the full Phase 4-C chain
// instantiate both the HelixQA Detector (for the validator) and the
// Lava qa.detector adapter (for telemetry-parity per-check Reports)
// in parallel — see tests/qa/validator_test.go for the canonical
// pattern.
func New(runID, evidenceDir string, det *hxqadetector.Detector, opts ...Option) *Validator {
	v := &Validator{
		runID:    runID,
		testCase: runID,
	}
	for _, opt := range opts {
		opt(v)
	}
	hxqaOpts := []hxqavalidator.Option{
		hxqavalidator.WithEvidenceDir(evidenceDir),
	}
	if v.evidence != nil {
		hxqaOpts = append(hxqaOpts,
			hxqavalidator.WithScreenshotFunc(v.makeScreenshotFunc()))
	}
	v.hxqa = hxqavalidator.New(det, hxqaOpts...)
	return v
}

// ValidateStep performs a single step's pre-detection + post-detection
// cycle. It is the HelixQA Validator.ValidateStep delegated through
// this adapter PLUS the §6.O ticket-emission step for failures.
//
// Returns the same *StepResult HelixQA produces (it IS HelixQA's type;
// no translation). On failure with an auto-emitting ticket generator
// wired, a markdown closure log is written before returning; the
// returned *StepResult is unchanged by ticket emission.
//
// Per §6.AC: telemetry is recorded on every failure (even when a
// ticket is auto-emitted) so the operator's Crashlytics / OTLP
// dashboard surfaces the failure independently of the on-disk
// ticket markdown.
func (v *Validator) ValidateStep(
	ctx context.Context,
	stepName string,
	platform hxqaconfig.Platform,
) (*StepResult, error) {
	result, err := v.hxqa.ValidateStep(ctx, stepName, platform)
	if err != nil {
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "ValidateStep",
			observability.AttrRequestID: v.runID,
			"step":                      stepName,
			"platform":                  string(platform),
		})
		return result, err
	}

	if result != nil && (result.Status == StepFailed || result.Status == StepError) {
		errClass := "step_failed"
		if result.Status == StepError {
			errClass = "step_error"
		}
		observability.RecordNonFatal(ctx,
			fmt.Errorf("validator: %s: %s", errClass, result.Error),
			observability.NonFatalAttributes{
				observability.AttrFeature:    telemetryFeature,
				observability.AttrOperation:  "ValidateStep",
				observability.AttrErrorClass: errClass,
				observability.AttrRequestID:  v.runID,
				"step":                       stepName,
				"platform":                   string(platform),
			})

		if v.autoEmit && v.hxqaTkt != nil {
			if emitErr := v.emitTicket(ctx, result); emitErr != nil {
				observability.RecordNonFatal(ctx, emitErr,
					observability.NonFatalAttributes{
						observability.AttrFeature:   telemetryFeature,
						observability.AttrOperation: "emitTicket",
						observability.AttrRequestID: v.runID,
						"step":                      stepName,
					})
				// Ticket emission failure is non-fatal — the StepResult
				// is still returned so the caller can retry ticketing.
			}
		}
	}

	return result, nil
}

// Results returns a snapshot of every StepResult collected so far.
// The returned slice is a copy; modifying it does not affect the
// underlying HelixQA Validator. Safe to call concurrently with
// ValidateStep.
func (v *Validator) Results() []*StepResult { return v.hxqa.Results() }

// PassedCount returns the number of StepPassed results.
func (v *Validator) PassedCount() int { return v.hxqa.PassedCount() }

// FailedCount returns the number of StepFailed results.
func (v *Validator) FailedCount() int { return v.hxqa.FailedCount() }

// TotalCount returns the total number of validated steps.
func (v *Validator) TotalCount() int { return v.hxqa.TotalCount() }

// Reset clears all collected step results. Useful between test cases
// when a single Validator is reused. Does NOT reset the evidence
// chain (caller manages evidence.Collector lifecycle) or ticket
// counter (HelixQA ticket Generator manages its own counter).
func (v *Validator) Reset() { v.hxqa.Reset() }

// RunID returns the validator's run ID (for telemetry / labelling).
func (v *Validator) RunID() string { return v.runID }

// HelixQA returns the underlying HelixQA Validator — exposed for
// consumers that need direct access to HelixQA APIs not yet wrapped
// here (e.g., a future Phase 4-C-x adapter that chains validator
// into a higher-level orchestrator). Per Q2 WRAP strategy: most
// consumers should call the adapter's own methods.
func (v *Validator) HelixQA() *hxqavalidator.Validator { return v.hxqa }

// emitTicket auto-generates a §6.O-style closure-log markdown from a
// failed StepResult and writes it via the wrapped HelixQA ticket
// Generator. Called only when WithTicketGenerator is wired AND the
// step is failed/errored.
func (v *Validator) emitTicket(_ context.Context, result *StepResult) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	t := v.hxqaTkt.GenerateFromStep(result, v.testCase)
	if t == nil {
		return fmt.Errorf("qa/validator: ticket generator returned nil for step %q", result.StepName)
	}

	if v.ticketDir != "" {
		filename := fmt.Sprintf("%s.md", t.ID)
		path := filepath.Join(v.ticketDir, filename)
		content := v.hxqaTkt.RenderMarkdown(t)
		if err := writeTicketFile(path, content); err != nil {
			return fmt.Errorf("qa/validator: write ticket %s to %s: %w", t.ID, path, err)
		}
		return nil
	}

	_, err := v.hxqaTkt.WriteTicket(t)
	if err != nil {
		return fmt.Errorf("qa/validator: write ticket %s: %w", t.ID, err)
	}
	return nil
}

// makeScreenshotFunc returns a HelixQA-compatible ScreenshotFunc that
// computes screenshot paths under the wired qa.evidence.Collector's
// output dir. The path returned is exactly what HelixQA's Validator
// stores on the StepResult (PreScreenshot / PostScreenshot).
//
// For lava-api-go server-side use cases (where screenshots are not
// meaningful), callers SHOULD omit WithEvidenceCollector entirely —
// HelixQA skips screenshot work when its ScreenshotFunc is nil.
func (v *Validator) makeScreenshotFunc() hxqavalidator.ScreenshotFunc {
	return func(_ context.Context, name string) (string, error) {
		evDir := v.evidence.OutputDir()
		path := filepath.Join(evDir, fmt.Sprintf("%s-%s-%d.png",
			v.evidence.RunID(), sanitizeLabel(name), time.Now().UnixMilli()))
		return path, nil
	}
}

// sanitizeLabel keeps only [A-Za-z0-9._-]; replaces every other char
// with '_'. Empty input returns "unlabelled".
func sanitizeLabel(label string) string {
	if label == "" {
		return "unlabelled"
	}
	out := make([]byte, 0, len(label))
	for i := 0; i < len(label); i++ {
		b := label[i]
		switch {
		case b >= 'A' && b <= 'Z',
			b >= 'a' && b <= 'z',
			b >= '0' && b <= '9',
			b == '.' || b == '_' || b == '-':
			out = append(out, b)
		default:
			out = append(out, '_')
		}
	}
	return string(out)
}

// writeTicketFile is split out for testability — the underlying var
// (writeFileFn in io.go) is os.WriteFile by default; mutation
// rehearsal tests can swap it temporarily per §6.J clause 2.
func writeTicketFile(path string, content []byte) error {
	return writeFileFn(path, content, 0o644)
}
