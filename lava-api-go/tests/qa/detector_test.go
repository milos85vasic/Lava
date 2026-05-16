// Phase 4-C-2 — REAL-STACK integration test for internal/qa/detector.
//
// Per docs/plans/2026-05-16-helixqa-go-package-linking-design.md §B.1
// testing strategy: gated by the `helixqa_realstack` build tag. Default
// `go test ./...` runs skip this file. Running this test requires the
// sibling submodules/helixqa/ present + module-graph resolved.
//
// Per §6.J / §6.AE.4: exercises the REAL HelixQA Detector against a
// REAL OS process the test owns (a sacrificial `sleep` child). The
// adapter is the SUT; nothing in this test mocks HelixQA. The HelixQA
// Detector's default execRunner runs real `pgrep -f` and `kill -0`
// invocations.
//
// Falsifiability rehearsal recorded in the Phase 4-C-2 commit body:
// invert the Alive mapping in detector.translate() — this test
// FAILs because after spawning a real process, the Report would
// report Alive=false despite the process actually being alive.
//
// Run with:
//   go test -tags=helixqa_realstack ./tests/qa/...

//go:build helixqa_realstack

package qa_test

import (
	"context"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	qadetector "digital.vasic.lava.apigo/internal/qa/detector"
)

// TestDetectorAdapter_RealStack_LiveProcess_ReportsAlive spawns a real
// `sleep` child process, asks the adapter to detect it by PID, asserts
// Alive=true. Then kills the child and asserts Alive=false. This is the
// §6.AE.4 acceptance-gate test — a real consumer can detect a real
// crashed process end-to-end through the adapter without any mocks.
func TestDetectorAdapter_RealStack_LiveProcess_ReportsAlive(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping real-stack detector test in -short mode")
	}

	// Spawn a sacrificial child process. `sleep 30` is enough to outlive
	// the detector roundtrip even on slow CI; the test kills it well
	// before the timeout.
	cmd := exec.Command("sleep", "30")
	if err := cmd.Start(); err != nil {
		t.Fatalf("spawn sacrificial sleep: %v", err)
	}
	pid := cmd.Process.Pid
	defer func() {
		// Belt-and-suspenders cleanup: even if the test body kills the
		// process explicitly, ensure no orphan.
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()

	d := qadetector.New(t.TempDir())

	// 1. Alive check by PID — real `kill -0 <pid>` invocation.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	report, err := d.CheckGoProcessByPID(ctx, pid)
	if err != nil {
		t.Fatalf("CheckGoProcessByPID(alive) error: %v", err)
	}
	if !report.Alive {
		t.Errorf("Report.Alive = false; want true (PID %d was spawned and not killed)", pid)
	}
	if report.Crashed {
		t.Errorf("Report.Crashed = true; want false (process is alive)")
	}

	// 2. Kill the process; wait for it to be reaped.
	if err := cmd.Process.Signal(syscall.SIGKILL); err != nil {
		t.Fatalf("kill sacrificial process: %v", err)
	}
	if _, err := cmd.Process.Wait(); err != nil {
		// Process.Wait can return "wait: no child processes" if the
		// kernel already reaped; either way the process is gone.
		t.Logf("Wait returned (expected after SIGKILL): %v", err)
	}

	// 3. Dead check by PID — kill -0 now fails. Allow a brief grace
	// period for the OS to flush the PID from /proc.
	deadCtx, deadCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer deadCancel()

	var deadReport *qadetector.Report
	for attempt := 0; attempt < 10; attempt++ {
		deadReport, err = d.CheckGoProcessByPID(deadCtx, pid)
		if err != nil {
			t.Fatalf("CheckGoProcessByPID(dead) error: %v", err)
		}
		if !deadReport.Alive {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	if deadReport.Alive {
		t.Errorf("Report.Alive = true; want false (PID %d was killed)", pid)
	}
	if !deadReport.Crashed {
		t.Errorf("Report.Crashed = false; want true (process killed)")
	}
}

// TestDetectorAdapter_RealStack_NameBasedDetection spawns a real
// process whose full command line carries a unique sentinel token and
// asserts the name-based path (CheckGoProcess) detects it. The test
// uses `sh -c 'exec -a <sentinel> sleep 30'` so argv[0] (visible to
// pgrep -f) contains the sentinel even on BSD `sleep` implementations
// that reject extra positional arguments (macOS).
func TestDetectorAdapter_RealStack_NameBasedDetection(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping real-stack detector test in -short mode")
	}

	// Unique sentinel string in argv[0] ensures pgrep -f matches
	// only OUR sacrificial process, not someone else's sleep.
	sentinel := "lava_api_go_detector_test_" + strconv.FormatInt(time.Now().UnixNano(), 36)

	// `exec -a NAME sleep N` replaces sh with sleep, setting argv[0] to
	// NAME. pgrep -f then matches the sentinel in argv[0]. Works on
	// macOS BSD sh + Linux dash/bash uniformly.
	cmd := exec.Command("sh", "-c", "exec -a "+sentinel+" sleep 30")
	if err := cmd.Start(); err != nil {
		t.Fatalf("spawn sentinel sleep: %v", err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()

	// Give the OS a moment to fork/exec/replace argv before probing.
	time.Sleep(150 * time.Millisecond)

	d := qadetector.New(t.TempDir())

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// pgrep -f matches against the FULL command line including argv[0];
	// our sentinel makes the match unique.
	report, err := d.CheckGoProcess(ctx, sentinel)
	if err != nil {
		t.Fatalf("CheckGoProcess error: %v", err)
	}
	if !report.Alive {
		t.Errorf("Report.Alive = false; want true (sentinel process %q is alive)", sentinel)
	}
	if report.Crashed {
		t.Errorf("Report.Crashed = true; want false (process alive)")
	}
}

// TestDetectorAdapter_RealStack_GhostProcess_ReportsDead exercises the
// canonical "process never existed" path. A clearly-bogus process name
// MUST be reported as Crashed/!Alive, NOT cause an error.
func TestDetectorAdapter_RealStack_GhostProcess_ReportsDead(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping real-stack detector test in -short mode")
	}

	// Name that cannot exist as a real process (sentinel chars).
	const ghost = "lava-ghost-process-zzzznotreal-xyz"

	d := qadetector.New(t.TempDir())

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	report, err := d.CheckGoProcess(ctx, ghost)
	if err != nil {
		t.Fatalf("CheckGoProcess(ghost) returned error (should swallow): %v", err)
	}
	if report.Alive {
		t.Errorf("Report.Alive = true; want false (ghost process %q does not exist)", ghost)
	}
	if !report.Crashed {
		t.Errorf("Report.Crashed = false; want true (no process matched)")
	}
	if !strings.HasPrefix(report.EvidencePath, "/") {
		// t.TempDir() returns an absolute path on every supported OS.
		t.Errorf("Report.EvidencePath = %q; want absolute path", report.EvidencePath)
	}
}
