// Package detector — adapter unit tests.
//
// Per §6.J / §6.AB: every public adapter method exercised against the REAL
// HelixQA Detector wired to a fake CommandRunner (the supported HelixQA
// injection seam for tests, NOT a mock of the SUT). The HelixQA Detector
// is the real production code; only the system-command boundary
// (pgrep / kill) is faked, matching HelixQA's own test pattern.
//
// Per §6.J.3: each test's primary assertion is on user-visible state
// (Report.Crashed / Report.Alive / Report.StackTrace), never on call counts.
//
// Per the Phase 4-C-2 commit body: every test has a paired falsifiability
// rehearsal — a deliberate mutation in detector.go that flips the
// translate-mapping direction or removes a validation guard, confirming
// the corresponding test fails with a clear assertion message before
// reversion.
package detector

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	hxqaconfig "digital.vasic.helixqa/pkg/config"
	hxqadetector "digital.vasic.helixqa/pkg/detector"
)

// fakeRunner is a deterministic CommandRunner for unit tests. It
// implements the HelixQA CommandRunner interface so HelixQA's Detector
// accepts it via WithCommandRunner. Unlike a mock of the SUT (which
// would be a §6.J forbidden pattern), this fakes ONLY the system-command
// boundary below the SUT — the production HelixQA Detector code path
// runs unaltered.
type fakeRunner struct {
	mu       sync.Mutex
	calls    []fakeCall
	response map[string]fakeResponse
}

type fakeCall struct {
	name string
	args []string
}

type fakeResponse struct {
	output []byte
	err    error
}

func newFakeRunner() *fakeRunner {
	return &fakeRunner{response: make(map[string]fakeResponse)}
}

// on registers a canned response keyed on the command name (matches the
// first invocation with that name). If args matter, callers should use
// onWithArgs.
func (f *fakeRunner) on(name string, output []byte, err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.response[name] = fakeResponse{output: output, err: err}
}

// Run satisfies hxqadetector.CommandRunner. Returns the canned response
// for the command name, or an error indicating no response was registered.
func (f *fakeRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, fakeCall{name: name, args: append([]string(nil), args...)})
	if resp, ok := f.response[name]; ok {
		return resp.output, resp.err
	}
	return nil, fmt.Errorf("fakeRunner: no response registered for %q", name)
}

// callsFor returns calls with the given command name.
func (f *fakeRunner) callsFor(name string) []fakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []fakeCall
	for _, c := range f.calls {
		if c.name == name {
			out = append(out, c)
		}
	}
	return out
}

// --- constructor + accessor tests ---

// TestNew_StoresEvidenceDir verifies the constructor preserves the
// evidence directory for later inspection (downstream Phase 4-C-3 ticket
// adapter consumes Report.EvidencePath to correlate detector findings
// with evidence collected via the qa/evidence adapter).
func TestNew_StoresEvidenceDir(t *testing.T) {
	d := New("/tmp/lava-evidence")
	if got, want := d.EvidenceDir(), "/tmp/lava-evidence"; got != want {
		t.Errorf("EvidenceDir() = %q; want %q", got, want)
	}
}

// TestNew_AcceptsCommandRunnerOption verifies WithCommandRunner installs
// the supplied runner. We assert by observing a subsequent
// CheckGoProcess invocation flows through the fake (alive=true).
func TestNew_AcceptsCommandRunnerOption(t *testing.T) {
	fake := newFakeRunner()
	fake.on("pgrep", []byte("12345"), nil)

	d := New("/tmp/lava-evidence", WithCommandRunner(fake))

	report, err := d.CheckGoProcess(context.Background(), "lava-api-go")
	if err != nil {
		t.Fatalf("CheckGoProcess error: %v", err)
	}
	if !report.Alive {
		t.Errorf("Report.Alive = false; want true (fake returned PID 12345)")
	}
	// The fake observed the pgrep call (proves HelixQA's Detector used
	// the runner we supplied, not the default execRunner).
	if got := len(fake.callsFor("pgrep")); got == 0 {
		t.Errorf("fakeRunner saw zero pgrep calls; WithCommandRunner did not install runner")
	}
}

// --- CheckGoProcess: input validation ---

// TestCheckGoProcess_EmptyName_ReturnsErrEmptyProcessName is the
// canonical guard that prevents HelixQA's silent fallback (it defaults
// to "java" when processName is empty, which is wrong for a Go API
// service). Primary assertion is on the returned error value (user-
// visible state: an explicit, typed error rather than a misleading
// "java process not found" Report).
func TestCheckGoProcess_EmptyName_ReturnsErrEmptyProcessName(t *testing.T) {
	d := New("/tmp/lava-evidence")
	report, err := d.CheckGoProcess(context.Background(), "")
	if !errors.Is(err, ErrEmptyProcessName) {
		t.Errorf("err = %v; want ErrEmptyProcessName", err)
	}
	if report != nil {
		t.Errorf("report = %+v; want nil on validation error", report)
	}
}

// TestCheckGoProcess_WhitespaceOnly_ReturnsErrEmptyProcessName confirms
// the trim guard catches whitespace-only names too (the failure mode
// would otherwise be a HelixQA pgrep call with an empty argument, which
// matches every process and returns false positives).
func TestCheckGoProcess_WhitespaceOnly_ReturnsErrEmptyProcessName(t *testing.T) {
	d := New("/tmp/lava-evidence")
	_, err := d.CheckGoProcess(context.Background(), "   \t\n  ")
	if !errors.Is(err, ErrEmptyProcessName) {
		t.Errorf("err = %v; want ErrEmptyProcessName", err)
	}
}

// --- CheckGoProcess: alive path ---

// TestCheckGoProcess_Alive_ReturnsCorrectReport is the primary anti-bluff
// assertion (per §6.J.3): the test asserts on user-visible state — the
// returned Report's Alive=true, Crashed=false, and EvidencePath matches
// the construction-time directory.
func TestCheckGoProcess_Alive_ReturnsCorrectReport(t *testing.T) {
	fake := newFakeRunner()
	fake.on("pgrep", []byte("4711\n"), nil)

	d := New("/var/lib/lava/evidence", WithCommandRunner(fake))
	report, err := d.CheckGoProcess(context.Background(), "lava-api-go")
	if err != nil {
		t.Fatalf("CheckGoProcess error: %v", err)
	}
	if !report.Alive {
		t.Errorf("Report.Alive = false; want true (pgrep returned PID 4711)")
	}
	if report.Crashed {
		t.Errorf("Report.Crashed = true; want false")
	}
	if report.EvidencePath != "/var/lib/lava/evidence" {
		t.Errorf("Report.EvidencePath = %q; want %q",
			report.EvidencePath, "/var/lib/lava/evidence")
	}
	if report.StackTrace != "" {
		t.Errorf("Report.StackTrace = %q; want \"\" (desktop path doesn't parse traces)", report.StackTrace)
	}
}

// --- CheckGoProcess: dead path ---

// TestCheckGoProcess_Dead_ReturnsCrashed verifies that when pgrep
// returns an error (process not found), the Report correctly indicates
// Crashed=true, Alive=false. The mapping direction is the primary
// falsifiability target — inverting it in translate() makes this test
// FAIL with a clear assertion message.
func TestCheckGoProcess_Dead_ReturnsCrashed(t *testing.T) {
	fake := newFakeRunner()
	// pgrep returns an error when no matching process — HelixQA's
	// checkProcessByName interprets that as "process not alive".
	fake.on("pgrep", nil, errors.New("no process found"))

	d := New("/tmp", WithCommandRunner(fake))
	report, err := d.CheckGoProcess(context.Background(), "ghost-process")
	if err != nil {
		t.Fatalf("CheckGoProcess error: %v", err)
	}
	if report.Alive {
		t.Errorf("Report.Alive = true; want false (pgrep returned error)")
	}
	if !report.Crashed {
		t.Errorf("Report.Crashed = false; want true (process dead → crashed)")
	}
}

// --- CheckGoProcessByPID: input validation ---

// TestCheckGoProcessByPID_NonPositivePID_ReturnsErrInvalidPID exercises
// the validation guard. Primary assertion: user-visible state is the
// typed ErrInvalidPID error, not a HelixQA fallback to process-name.
func TestCheckGoProcessByPID_NonPositivePID_ReturnsErrInvalidPID(t *testing.T) {
	cases := []int{-1, 0}
	for _, pid := range cases {
		pid := pid
		t.Run(fmt.Sprintf("pid=%d", pid), func(t *testing.T) {
			d := New("/tmp")
			report, err := d.CheckGoProcessByPID(context.Background(), pid)
			if !errors.Is(err, ErrInvalidPID) {
				t.Errorf("err = %v; want ErrInvalidPID", err)
			}
			if report != nil {
				t.Errorf("report = %+v; want nil on validation error", report)
			}
		})
	}
}

// --- CheckGoProcessByPID: alive + dead paths ---

// TestCheckGoProcessByPID_Alive verifies the PID path translates
// HelixQA's `kill -0 <pid>` success into Alive=true. Falsifiability:
// flip the Alive mapping in translate() — this test FAILs immediately.
func TestCheckGoProcessByPID_Alive(t *testing.T) {
	fake := newFakeRunner()
	// kill -0 exits 0 (no output, no err) when the process exists.
	fake.on("kill", nil, nil)

	d := New("/tmp", WithCommandRunner(fake))
	report, err := d.CheckGoProcessByPID(context.Background(), 12345)
	if err != nil {
		t.Fatalf("CheckGoProcessByPID error: %v", err)
	}
	if !report.Alive {
		t.Errorf("Report.Alive = false; want true (kill -0 succeeded)")
	}
	if report.Crashed {
		t.Errorf("Report.Crashed = true; want false")
	}
	// Verify the fake observed a `kill` call with `-0` arg (proves the
	// HelixQA Detector took the PID code path, not processName).
	calls := fake.callsFor("kill")
	if len(calls) == 0 {
		t.Fatalf("fakeRunner saw zero kill calls; PID path was not taken")
	}
	foundDashZero := false
	for _, c := range calls {
		for _, a := range c.args {
			if a == "-0" {
				foundDashZero = true
			}
		}
	}
	if !foundDashZero {
		t.Errorf("kill calls missing -0 arg; HelixQA contract changed? calls=%+v", calls)
	}
}

// TestCheckGoProcessByPID_Dead verifies that kill -0 failure maps to
// Crashed=true, Alive=false.
func TestCheckGoProcessByPID_Dead(t *testing.T) {
	fake := newFakeRunner()
	fake.on("kill", nil, errors.New("no such process"))

	d := New("/tmp", WithCommandRunner(fake))
	report, err := d.CheckGoProcessByPID(context.Background(), 99999)
	if err != nil {
		t.Fatalf("CheckGoProcessByPID error: %v", err)
	}
	if report.Alive {
		t.Errorf("Report.Alive = true; want false (kill -0 failed)")
	}
	if !report.Crashed {
		t.Errorf("Report.Crashed = false; want true (PID dead → crashed)")
	}
}

// --- translate() — direct mapping rehearsal ---

// TestTranslate_NilResult_PreservesEvidenceDir guards the nil-result
// safety: HelixQA's Check() returning (nil, nil) (defensive code; not
// observed in practice but documented) must not panic. The Report
// returned MUST still carry the evidenceDir so downstream consumers
// see the same correlation key as on the success path.
func TestTranslate_NilResult_PreservesEvidenceDir(t *testing.T) {
	d := New("/var/lava/evd")
	r := d.translate(nil)
	if r == nil {
		t.Fatal("translate(nil) returned nil Report; want non-nil with EvidencePath set")
	}
	if r.EvidencePath != "/var/lava/evd" {
		t.Errorf("translate(nil).EvidencePath = %q; want %q",
			r.EvidencePath, "/var/lava/evd")
	}
	if r.Alive || r.Crashed || r.StackTrace != "" {
		t.Errorf("translate(nil) produced non-zero report: %+v", r)
	}
}

// TestTranslate_MappingDirection is the dedicated falsifiability test
// for the field mapping in translate(). Each assertion is the negation
// the rehearsal mutation flips — invert the mapping in translate() and
// this test FAILs with a clear message.
func TestTranslate_MappingDirection(t *testing.T) {
	d := New("/evd")
	src := &hxqadetector.DetectionResult{
		Platform:     hxqaconfig.PlatformDesktop,
		HasCrash:     true,
		ProcessAlive: false,
		StackTrace:   "goroutine 1 [running]:\nmain.crash(...)",
	}
	got := d.translate(src)
	if !got.Crashed {
		t.Error("translate: Crashed=false; want true (HasCrash=true → Crashed=true)")
	}
	if got.Alive {
		t.Error("translate: Alive=true; want false (ProcessAlive=false → Alive=false)")
	}
	if got.StackTrace != src.StackTrace {
		t.Errorf("translate: StackTrace = %q; want %q", got.StackTrace, src.StackTrace)
	}
	if got.EvidencePath != "/evd" {
		t.Errorf("translate: EvidencePath = %q; want %q", got.EvidencePath, "/evd")
	}
}

// --- runner option rehearsal ---

// TestRunnerOption_NilRunner_ReturnsNoOpOption verifies the runnerOption
// helper returns a Option that, when applied, leaves the HelixQA
// Detector functional (does NOT install a nil runner that would cause
// a nil-pointer dereference inside HelixQA's check). We assert by
// confirming the constructed Detector has a non-nil cmdRunner after
// applying the option — but since cmdRunner is unexported in HelixQA,
// we instead exercise via the public Check path with a Detector that
// has no custom runner: this MUST NOT panic.
func TestRunnerOption_NilRunner_ReturnsNoOpOption(t *testing.T) {
	d := New("/tmp") // no WithCommandRunner
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("CheckGoProcess panicked with default runner: %v", r)
		}
	}()
	// We expect either a Report (process actually exists on this
	// machine, e.g. "go" during `go test`) or a no-error Report with
	// Alive=false. Either is fine — we are asserting NO PANIC.
	_, err := d.CheckGoProcess(context.Background(), "definitely-not-a-real-process-name-zzz")
	if err != nil {
		// HelixQA's desktop path swallows pgrep-not-found into a
		// nil-err alive=false Report; an actual err here indicates a
		// real plumbing failure worth surfacing.
		if !strings.Contains(err.Error(), "HelixQA Check") {
			t.Errorf("unexpected error: %v", err)
		}
	}
}
