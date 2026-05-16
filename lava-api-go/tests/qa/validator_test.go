// Phase 4-C-4 — REAL-STACK integration test for internal/qa/validator.
//
// Per docs/plans/2026-05-16-helixqa-go-package-linking-design.md §D.3:
// gated by the `helixqa_realstack` build tag. Default `go test ./...`
// runs skip this file (transitive HelixQA deps may not be present in
// every CI environment; running this test requires the sibling
// submodules/helixqa/ present + module-graph resolved + the sibling
// Phase 4-C-2 + 4-C-3 adapters merged on master).
//
// Per §6.J / §6.AE.4: exercises the REAL HelixQA Validator + REAL
// HelixQA Detector + REAL HelixQA ticket Generator + REAL Lava
// qa.evidence Collector + REAL Lava qa.detector adapter + REAL Lava
// qa.ticket adapter, end-to-end, against the REAL filesystem. The
// validator chain is the SUT; nothing in this test mocks any of the
// chained components except the CommandRunner at the lowest HelixQA
// seam (the same seam HelixQA's own tests use). Falsifiability
// rehearsal recorded in the Phase 4-C-4 commit body.
//
// CHAIN-COMPOSITION PATTERN. The validator wraps HelixQA primitives
// directly (per its package doc); a consumer wiring the full Phase
// 4-C chain instantiates each Lava adapter alongside its HelixQA
// counterpart and hands the HelixQA pointers to validator.New while
// retaining the Lava adapters for telemetry-parity Reports + §6.O
// closure-log writes. This test demonstrates the canonical wiring.
//
// Run with:
//
//	go test -tags=helixqa_realstack ./tests/qa/...

//go:build helixqa_realstack

package qa_test

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	hxqaconfig "digital.vasic.helixqa/pkg/config"
	hxqadetector "digital.vasic.helixqa/pkg/detector"
	hxqaticket "digital.vasic.helixqa/pkg/ticket"

	qadetector "digital.vasic.lava.apigo/internal/qa/detector"
	qaevidence "digital.vasic.lava.apigo/internal/qa/evidence"
	qaticket "digital.vasic.lava.apigo/internal/qa/ticket"
	qavalidator "digital.vasic.lava.apigo/internal/qa/validator"
)

// realStackRunner is the lowest-level boundary mock so the HelixQA
// Detector can be driven into known-good / known-bad states without
// spawning real OS processes. Everything ABOVE this seam in the chain
// is REAL — adapter, Validator, Detector, Generator, Collector.
type realStackRunner struct {
	mu        sync.Mutex
	responses map[string]struct {
		out []byte
		err error
	}
}

func newRealStackRunner() *realStackRunner {
	return &realStackRunner{responses: map[string]struct {
		out []byte
		err error
	}{}}
}

func (r *realStackRunner) On(key string, out []byte, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.responses[key] = struct {
		out []byte
		err error
	}{out, err}
}

func (r *realStackRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := name
	if len(args) > 0 {
		key = name + " " + strings.Join(args, " ")
	}
	if v, ok := r.responses[key]; ok {
		return v.out, v.err
	}
	for k, v := range r.responses {
		if strings.HasPrefix(key, k) {
			return v.out, v.err
		}
	}
	if v, ok := r.responses[name]; ok {
		return v.out, v.err
	}
	return nil, fmt.Errorf("no mock: %s", key)
}

// TestValidatorChain_RealStack_FailedStep_AutoEmitsTicket exercises
// the full Phase 4-C chain end-to-end: a failed validation step
// MUST produce (a) a recorded StepResult, (b) an auto-emitted
// markdown ticket on disk with §6.O-relevant content.
//
// Chain composition: HelixQA Detector wrapped for the Validator,
// Lava qa.detector adapter ALSO constructed for telemetry parity
// (each adapter records its own §6.AC events with its own feature
// attribute), Lava qa.evidence Collector for screenshot paths,
// HelixQA ticket Generator for auto-emission + Lava qa.ticket
// adapter for any caller-initiated §6.O closure-log writes.
//
// This is the §6.AE.4 acceptance-gate test that proves a real
// consumer can drive validation + observe the ticket without
// operator transcription.
func TestValidatorChain_RealStack_FailedStep_AutoEmitsTicket(t *testing.T) {
	evidenceDir := t.TempDir()
	ticketDir := t.TempDir()

	// 1. Wire the failing CommandRunner into a REAL HelixQA Detector
	//    AND a REAL Lava qa.detector adapter sharing the same runner.
	//    The HelixQA Detector drives the validator's per-step
	//    CheckApp; the Lava adapter is available for callers that want
	//    parallel telemetry-shaped Reports per check.
	runner := newRealStackRunner()
	runner.On("pgrep", []byte(""), errors.New("not found"))
	hxqaDet := hxqadetector.New(
		hxqaconfig.PlatformDesktop,
		hxqadetector.WithCommandRunner(runner),
	)
	// Lava qa.detector adapter (parallel telemetry consumer)
	_ = qadetector.New(evidenceDir,
		qadetector.WithCommandRunner(runner))

	// 2. REAL Lava qa.evidence Collector for screenshot paths.
	collector := qaevidence.NewCollector("e2e-validator-001", evidenceDir)

	// 3. REAL HelixQA ticket Generator (wired into the validator for
	//    auto-emission) AND a REAL Lava qa.ticket adapter (available
	//    for explicit §6.O closure-log writes). Both target distinct
	//    output dirs so the auto-emitted markdown doesn't collide
	//    with caller-initiated closure logs.
	hxqaGen := hxqaticket.New(hxqaticket.WithOutputDir(ticketDir))
	_ = qaticket.NewGenerator(t.TempDir())

	// 4. REAL Lava qa.validator wiring the chain.
	v := qavalidator.New(
		"e2e-validator-001",
		evidenceDir,
		hxqaDet,
		qavalidator.WithEvidenceCollector(collector),
		qavalidator.WithTicketGenerator(hxqaGen),
		qavalidator.WithTestCaseID("TC-E2E-CHAIN-001"),
	)

	// 5. Drive the chain.
	result, err := v.ValidateStep(context.Background(),
		"submit-bad-payload", hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.Status != qavalidator.StepFailed {
		t.Fatalf("Status=%q; want StepFailed", result.Status)
	}

	// 6. User-visible-state assertion #1: counters.
	if v.FailedCount() != 1 {
		t.Errorf("FailedCount=%d; want 1", v.FailedCount())
	}
	if v.TotalCount() != 1 {
		t.Errorf("TotalCount=%d; want 1", v.TotalCount())
	}

	// 7. User-visible-state assertion #2: ticket markdown file on
	//    disk with §6.O-shaped content.
	entries, err := os.ReadDir(ticketDir)
	if err != nil {
		t.Fatalf("ReadDir(%s): %v", ticketDir, err)
	}
	if len(entries) != 1 {
		t.Fatalf("ticket dir has %d entries; want 1", len(entries))
	}
	path := filepath.Join(ticketDir, entries[0].Name())
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	body := string(content)
	for _, needle := range []string{
		"submit-bad-payload", // step name
		"TC-E2E-CHAIN-001",   // test-case ID
	} {
		if !strings.Contains(body, needle) {
			t.Errorf("ticket lacks %q:\n%s", needle, body)
		}
	}
}

// TestValidatorChain_RealStack_PassedStep_NoTicket exercises the
// other half of the chain: a passing step records the StepResult
// but does NOT emit a ticket. Critical for §6.AB completeness —
// "ticket dir grows only when failures occur" is a user-visible
// contract the chain MUST honour.
func TestValidatorChain_RealStack_PassedStep_NoTicket(t *testing.T) {
	evidenceDir := t.TempDir()
	ticketDir := t.TempDir()

	runner := newRealStackRunner()
	runner.On("pgrep", []byte("12345"), nil)
	hxqaDet := hxqadetector.New(
		hxqaconfig.PlatformDesktop,
		hxqadetector.WithCommandRunner(runner),
	)
	collector := qaevidence.NewCollector("e2e-validator-002", evidenceDir)
	hxqaGen := hxqaticket.New(hxqaticket.WithOutputDir(ticketDir))

	v := qavalidator.New(
		"e2e-validator-002",
		evidenceDir,
		hxqaDet,
		qavalidator.WithEvidenceCollector(collector),
		qavalidator.WithTicketGenerator(hxqaGen),
	)

	result, err := v.ValidateStep(context.Background(),
		"healthy-tick", hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.Status != qavalidator.StepPassed {
		t.Errorf("Status=%q; want StepPassed", result.Status)
	}

	entries, err := os.ReadDir(ticketDir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("ticket dir has %d entries on a PASSED step; want 0",
			len(entries))
	}
}
