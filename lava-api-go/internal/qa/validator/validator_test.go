// Package validator — adapter unit tests.
//
// Per §6.J / §6.AB: the adapter is the SUT. We use the New() constructor
// which takes HelixQA primitives DIRECTLY (no Lava-side detector / ticket
// adapter dependency) so this test compiles + runs in isolation while the
// parallel Phases 4-C-2 and 4-C-3 are still in flight.
//
// We mock at the HelixQA boundary (CommandRunner — the lowest-level seam
// in HelixQA's Detector). The HelixQA Validator + HelixQA Detector + the
// Lava qa.validator adapter are ALL real instances. Per §6.J clause 4:
// mocking the SUT is forbidden; mocking the boundary below the SUT is
// permitted + necessary for hermetic tests.
//
// Per Phase 4-C-1's pattern: tests assert on user-visible state (real
// files on disk, real StepResult content, real markdown ticket file)
// rather than mock call-counts. Each test in this file has a paired
// falsifiability rehearsal recorded in the Phase 4-C-4 commit body.
package validator

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	hxqaconfig "digital.vasic.helixqa/pkg/config"
	hxqadetector "digital.vasic.helixqa/pkg/detector"
	hxqaticket "digital.vasic.helixqa/pkg/ticket"

	qaevidence "digital.vasic.lava.apigo/internal/qa/evidence"
)

// fakeRunner is the boundary mock — it implements HelixQA's CommandRunner
// so the underlying Detector can be driven into known-good or known-bad
// states without spawning real `pgrep` / `adb` processes. This is the
// canonical seam HelixQA's own detector tests use (see
// submodules/helixqa/pkg/validator/validator_test.go).
type fakeRunner struct {
	mu        sync.Mutex
	responses map[string]fakeResponse
}

type fakeResponse struct {
	output []byte
	err    error
}

func newFakeRunner() *fakeRunner {
	return &fakeRunner{responses: make(map[string]fakeResponse)}
}

func (f *fakeRunner) On(key string, output []byte, err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.responses[key] = fakeResponse{output: output, err: err}
}

func (f *fakeRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := name
	if len(args) > 0 {
		key = name + " " + strings.Join(args, " ")
	}
	if r, ok := f.responses[key]; ok {
		return r.output, r.err
	}
	// HelixQA's prefix-matching mirrors the upstream test fake.
	for k, r := range f.responses {
		if strings.HasPrefix(key, k) {
			return r.output, r.err
		}
	}
	if r, ok := f.responses[name]; ok {
		return r.output, r.err
	}
	return nil, fmt.Errorf("no mock: %s", key)
}

// passingDetector returns a HelixQA Detector wired to a CommandRunner
// that reports the target process is alive — drives ValidateStep to
// StepPassed.
func passingDetector(t *testing.T) *hxqadetector.Detector {
	t.Helper()
	f := newFakeRunner()
	f.On("pgrep", []byte("12345"), nil)
	return hxqadetector.New(
		hxqaconfig.PlatformDesktop,
		hxqadetector.WithCommandRunner(f),
	)
}

// failingDetector returns a HelixQA Detector wired to a CommandRunner
// that reports the target process is dead — drives ValidateStep to
// StepFailed.
func failingDetector(t *testing.T) *hxqadetector.Detector {
	t.Helper()
	f := newFakeRunner()
	f.On("pgrep", []byte(""), errors.New("not found"))
	return hxqadetector.New(
		hxqaconfig.PlatformDesktop,
		hxqadetector.WithCommandRunner(f),
	)
}

// --- Constructor coverage ---

func TestNew_BasicConstruction(t *testing.T) {
	v := New("run-1", t.TempDir(), passingDetector(t))
	if v == nil {
		t.Fatal("New returned nil")
	}
	if v.RunID() != "run-1" {
		t.Errorf("RunID = %q; want %q", v.RunID(), "run-1")
	}
	if v.TotalCount() != 0 {
		t.Errorf("TotalCount = %d; want 0", v.TotalCount())
	}
	if v.HelixQA() == nil {
		t.Error("HelixQA() returned nil — wrapping broken")
	}
}

func TestNew_WithEvidenceCollector(t *testing.T) {
	evDir := t.TempDir()
	c := qaevidence.NewCollector("run-2", evDir)
	v := New("run-2", t.TempDir(), passingDetector(t),
		WithEvidenceCollector(c))

	// Drive one step; the evidence-aware screenshot func should
	// produce a non-empty pre/post screenshot path because the
	// collector is wired.
	result, err := v.ValidateStep(context.Background(), "step-1",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.PreScreenshot == "" {
		t.Errorf("PreScreenshot is empty; wiring not engaged")
	}
	if !strings.HasPrefix(result.PreScreenshot, evDir) {
		t.Errorf("PreScreenshot %q not rooted under evidence dir %q",
			result.PreScreenshot, evDir)
	}
	if result.PostScreenshot == "" {
		t.Errorf("PostScreenshot is empty; wiring not engaged")
	}
}

func TestNew_WithoutEvidenceCollector_NoScreenshotPath(t *testing.T) {
	v := New("run-3", t.TempDir(), passingDetector(t))
	result, err := v.ValidateStep(context.Background(), "step-3",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	// HelixQA's Validator skips ScreenshotFunc when not wired —
	// pre/post screenshots are empty.
	if result.PreScreenshot != "" {
		t.Errorf("PreScreenshot=%q; want empty when no evidence collector",
			result.PreScreenshot)
	}
	if result.PostScreenshot != "" {
		t.Errorf("PostScreenshot=%q; want empty when no evidence collector",
			result.PostScreenshot)
	}
}

// --- ValidateStep happy-path ---

func TestValidateStep_Passed(t *testing.T) {
	v := New("run-pass", t.TempDir(), passingDetector(t))
	result, err := v.ValidateStep(context.Background(), "launch",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.Status != StepPassed {
		t.Errorf("Status=%q; want StepPassed", result.Status)
	}
	if result.StepName != "launch" {
		t.Errorf("StepName=%q; want %q", result.StepName, "launch")
	}
	if result.Platform != hxqaconfig.PlatformDesktop {
		t.Errorf("Platform=%q; want desktop", result.Platform)
	}
	if v.PassedCount() != 1 {
		t.Errorf("PassedCount=%d; want 1", v.PassedCount())
	}
	if v.FailedCount() != 0 {
		t.Errorf("FailedCount=%d; want 0", v.FailedCount())
	}
}

func TestValidateStep_Failed_DriveCrashDetection(t *testing.T) {
	v := New("run-fail", t.TempDir(), failingDetector(t))
	result, err := v.ValidateStep(context.Background(), "tap-button",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.Status != StepFailed {
		t.Errorf("Status=%q; want StepFailed", result.Status)
	}
	if !strings.Contains(result.Error, "crash") {
		t.Errorf("Error=%q does not mention crash", result.Error)
	}
	if v.FailedCount() != 1 {
		t.Errorf("FailedCount=%d; want 1", v.FailedCount())
	}
}

// --- Auto-emission of tickets on failed steps ---

func TestValidateStep_FailedWithAutoEmit_WritesTicket(t *testing.T) {
	ticketDir := t.TempDir()
	gen := hxqaticket.New(hxqaticket.WithOutputDir(ticketDir))
	v := New("run-emit", t.TempDir(), failingDetector(t),
		WithTicketGenerator(gen),
		WithTestCaseID("TC-INT-001"))

	result, err := v.ValidateStep(context.Background(), "submit",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.Status != StepFailed {
		t.Fatalf("Status=%q; want StepFailed", result.Status)
	}

	// User-visible-state assertion: the markdown ticket file is on
	// disk at the expected location with §6.O-relevant content.
	entries, err := os.ReadDir(ticketDir)
	if err != nil {
		t.Fatalf("ReadDir(%s): %v", ticketDir, err)
	}
	if len(entries) != 1 {
		t.Fatalf("ticket dir has %d entries; want 1", len(entries))
	}
	name := entries[0].Name()
	if !strings.HasSuffix(name, ".md") {
		t.Errorf("ticket filename %q not .md", name)
	}
	content, err := os.ReadFile(filepath.Join(ticketDir, name))
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	body := string(content)
	if !strings.Contains(body, "submit") {
		t.Errorf("ticket body lacks step name 'submit':\n%s", body)
	}
	if !strings.Contains(body, "TC-INT-001") {
		t.Errorf("ticket body lacks test case ID 'TC-INT-001':\n%s", body)
	}
	if !strings.Contains(strings.ToLower(body), "crash") {
		t.Errorf("ticket body lacks crash mention:\n%s", body)
	}
}

func TestValidateStep_PassedWithAutoEmit_NoTicketEmitted(t *testing.T) {
	ticketDir := t.TempDir()
	gen := hxqaticket.New(hxqaticket.WithOutputDir(ticketDir))
	v := New("run-no-emit", t.TempDir(), passingDetector(t),
		WithTicketGenerator(gen))

	_, err := v.ValidateStep(context.Background(), "step-ok",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	entries, err := os.ReadDir(ticketDir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("ticket dir has %d entries on a PASSED step; want 0", len(entries))
	}
}

func TestValidateStep_FailedWithoutTicketGen_NoEmission(t *testing.T) {
	// No WithTicketGenerator wired -- failure recorded, no panic.
	v := New("run-no-gen", t.TempDir(), failingDetector(t))
	result, err := v.ValidateStep(context.Background(), "step-x",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if result.Status != StepFailed {
		t.Errorf("Status=%q; want StepFailed", result.Status)
	}
	if v.FailedCount() != 1 {
		t.Errorf("FailedCount=%d; want 1", v.FailedCount())
	}
}

func TestValidateStep_FailedAutoEmit_CustomTicketDir(t *testing.T) {
	// WithTicketDir overrides the Generator's default output dir.
	genDir := t.TempDir()
	overrideDir := t.TempDir()
	gen := hxqaticket.New(hxqaticket.WithOutputDir(genDir))
	v := New("run-ovr", t.TempDir(), failingDetector(t),
		WithTicketGenerator(gen),
		WithTicketDir(overrideDir))

	_, err := v.ValidateStep(context.Background(), "step-ovr",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}

	// User-visible-state assertion: the OVERRIDE dir has the ticket;
	// the Generator's default dir is empty.
	ovrEntries, _ := os.ReadDir(overrideDir)
	defEntries, _ := os.ReadDir(genDir)
	if len(ovrEntries) != 1 {
		t.Errorf("override dir has %d entries; want 1", len(ovrEntries))
	}
	if len(defEntries) != 0 {
		t.Errorf("default dir has %d entries; want 0 (override should win)", len(defEntries))
	}
}

func TestValidateStep_FailedAutoEmit_WriteErrorIsNonFatal(t *testing.T) {
	// Inject a writeFileFn that fails to prove the error path doesn't
	// propagate (the StepResult is still returned to the caller).
	ticketDir := t.TempDir()
	gen := hxqaticket.New(hxqaticket.WithOutputDir(ticketDir))
	v := New("run-werr", t.TempDir(), failingDetector(t),
		WithTicketGenerator(gen),
		WithTicketDir(t.TempDir())) // forces writeFileFn path

	orig := writeFileFn
	writeFileFn = func(_ string, _ []byte, _ os.FileMode) error {
		return errors.New("simulated disk full")
	}
	defer func() { writeFileFn = orig }()

	result, err := v.ValidateStep(context.Background(), "step-disk",
		hxqaconfig.PlatformDesktop)
	if err != nil {
		t.Fatalf("ValidateStep error: %v; expected nil (ticket failure is non-fatal)", err)
	}
	if result == nil {
		t.Fatal("result is nil after ticket-write failure")
	}
	if result.Status != StepFailed {
		t.Errorf("Status=%q; want StepFailed", result.Status)
	}
}

// --- Counters + Reset ---

func TestCounters_AcrossMultipleSteps(t *testing.T) {
	// Build a Detector whose runner serves a moving target: first
	// pgrep PASS, then we swap the runner to make pgrep FAIL.
	f := newFakeRunner()
	f.On("pgrep", []byte("12345"), nil)
	det := hxqadetector.New(hxqaconfig.PlatformDesktop,
		hxqadetector.WithCommandRunner(f))

	v := New("run-mix", t.TempDir(), det)

	// Two passing steps.
	if _, err := v.ValidateStep(context.Background(), "p1",
		hxqaconfig.PlatformDesktop); err != nil {
		t.Fatalf("p1 error: %v", err)
	}
	if _, err := v.ValidateStep(context.Background(), "p2",
		hxqaconfig.PlatformDesktop); err != nil {
		t.Fatalf("p2 error: %v", err)
	}

	// Flip to failing.
	f.mu.Lock()
	f.responses = map[string]fakeResponse{}
	f.mu.Unlock()
	f.On("pgrep", []byte(""), errors.New("not found"))

	if _, err := v.ValidateStep(context.Background(), "f1",
		hxqaconfig.PlatformDesktop); err != nil {
		t.Fatalf("f1 error: %v", err)
	}

	if v.TotalCount() != 3 {
		t.Errorf("TotalCount=%d; want 3", v.TotalCount())
	}
	if v.PassedCount() != 2 {
		t.Errorf("PassedCount=%d; want 2", v.PassedCount())
	}
	if v.FailedCount() != 1 {
		t.Errorf("FailedCount=%d; want 1", v.FailedCount())
	}
	if got := len(v.Results()); got != 3 {
		t.Errorf("Results len=%d; want 3", got)
	}
}

func TestReset_ClearsResults(t *testing.T) {
	v := New("run-reset", t.TempDir(), passingDetector(t))
	if _, err := v.ValidateStep(context.Background(), "s",
		hxqaconfig.PlatformDesktop); err != nil {
		t.Fatalf("ValidateStep error: %v", err)
	}
	if v.TotalCount() != 1 {
		t.Fatalf("TotalCount=%d; want 1", v.TotalCount())
	}
	v.Reset()
	if v.TotalCount() != 0 {
		t.Errorf("TotalCount after Reset=%d; want 0", v.TotalCount())
	}
	if len(v.Results()) != 0 {
		t.Errorf("Results after Reset is non-empty")
	}
}

// --- Concurrency ---

func TestValidator_ConcurrentValidateStep(t *testing.T) {
	v := New("run-conc", t.TempDir(), passingDetector(t))

	const N = 20
	var ok int32
	var wg sync.WaitGroup
	wg.Add(N)
	for i := 0; i < N; i++ {
		go func(idx int) {
			defer wg.Done()
			_, err := v.ValidateStep(context.Background(),
				fmt.Sprintf("c-%d", idx),
				hxqaconfig.PlatformDesktop)
			if err == nil {
				atomic.AddInt32(&ok, 1)
			}
		}(i)
	}
	wg.Wait()

	if int(atomic.LoadInt32(&ok)) != N {
		t.Errorf("only %d/%d concurrent steps succeeded", ok, N)
	}
	if v.TotalCount() != N {
		t.Errorf("TotalCount=%d; want %d", v.TotalCount(), N)
	}
}

// --- StepStatus + StepResult re-export verification ---

func TestStepStatus_Reexports_MatchUpstream(t *testing.T) {
	// Per Q4 (preserve terminology): the Lava re-exports MUST be
	// identical values to HelixQA's so consumer switch statements
	// stay portable.
	cases := []struct {
		name string
		got  StepStatus
		want StepStatus
	}{
		{"passed", StepPassed, StepStatus("passed")},
		{"failed", StepFailed, StepStatus("failed")},
		{"skipped", StepSkipped, StepStatus("skipped")},
		{"error", StepError, StepStatus("error")},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s: got %q; want %q", c.name, c.got, c.want)
		}
	}
}

// --- sanitizeLabel helper coverage ---

func TestSanitizeLabel(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"", "unlabelled"},
		{"plain", "plain"},
		{"path/with/slash", "path_with_slash"},
		{"colon:and space", "colon_and_space"},
		{"keep.dots_and-dashes", "keep.dots_and-dashes"},
	}
	for _, c := range cases {
		if got := sanitizeLabel(c.in); got != c.want {
			t.Errorf("sanitizeLabel(%q)=%q; want %q", c.in, got, c.want)
		}
	}
}
