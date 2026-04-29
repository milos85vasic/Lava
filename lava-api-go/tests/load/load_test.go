// Package load_test verifies that the k6 load-test artifacts under
// tests/load/ and the wrapper script scripts/load-quick.sh are
// well-formed without requiring k6 itself to be installed.
//
// Why this test exists:
//
// Phase 10 / Task 10.4 ships three artifacts (k6-quick.js, k6-soak.js,
// load-quick.sh). k6 is NOT a project build dependency — it's installed
// only on the host that runs Phase 14 acceptance. So if we shipped these
// files with no Go-side check, a typo in a threshold string, a missing
// scenario block, or a non-executable wrapper would silently slip
// through every gate until the Phase 14 operator tried to run them.
//
// This test catches the "structural" failure modes:
//   - a load-test file went missing or got truncated to zero bytes;
//   - the bash wrapper became syntactically invalid (parse error under
//     `bash -n`);
//   - the wrapper lost its executable bit;
//   - a load-bearing threshold string was deleted from k6-quick.js.
//
// Sixth Law alignment:
//   - clause 1 (same surfaces): the test reads the EXACT files the
//     Phase 14 acceptance run will execute. No copy, no fixture, no
//     "expected content" duplicated elsewhere — read-and-assert against
//     the on-disk artifacts.
//   - clause 2 (falsifiability): the threshold-grep assertions are
//     designed to be falsifiable by deletion. The Phase 10 / Task 10.4
//     commit body records the rehearsal: removing the cached_hits p99
//     threshold made TestK6QuickContainsThresholds fail with a clear
//     missing-token message; restoring the threshold made the test
//     pass again.
//   - clause 3 (user-visible primary assertion): the threshold strings
//     are what k6 evaluates against real wire-level latency / failure
//     metrics on a real running stack — they are as close to a
//     user-visible measurement as a load-test fixture can carry.
package load_test

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// repoFile resolves a path relative to the lava-api-go module root,
// which is two parents up from this test file (tests/load/ → tests/ →
// lava-api-go/).
func repoFile(t *testing.T, rel string) string {
	t.Helper()
	_, here, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatalf("runtime.Caller failed")
	}
	root := filepath.Clean(filepath.Join(filepath.Dir(here), "..", ".."))
	return filepath.Join(root, rel)
}

// assertNonEmptyFile checks that path exists, is a regular file, and
// has a non-zero size. Returns the file's contents on success.
func assertNonEmptyFile(t *testing.T, path string) []byte {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat %s: %v", path, err)
	}
	if info.IsDir() {
		t.Fatalf("%s is a directory, expected file", path)
	}
	if info.Size() == 0 {
		t.Fatalf("%s is empty (0 bytes)", path)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return body
}

// TestK6QuickFileExists is the most basic guard — without the file the
// Phase 14 acceptance run can't even start. Catches truncation,
// accidental deletion, and packaging mistakes.
func TestK6QuickFileExists(t *testing.T) {
	path := repoFile(t, "tests/load/k6-quick.js")
	body := assertNonEmptyFile(t, path)
	if len(body) < 100 {
		t.Fatalf("%s is implausibly small: %d bytes", path, len(body))
	}
}

// TestK6SoakFileExists mirrors the quick-file guard for the soak
// script. Soak isn't run by CI, but the file still needs to be
// well-formed for the operator who runs it manually.
func TestK6SoakFileExists(t *testing.T) {
	path := repoFile(t, "tests/load/k6-soak.js")
	body := assertNonEmptyFile(t, path)
	if len(body) < 100 {
		t.Fatalf("%s is implausibly small: %d bytes", path, len(body))
	}
}

// TestLoadQuickScriptExistsAndExecutable verifies the wrapper exists,
// has a non-zero size, and carries at least one execute bit. Without
// the +x bit, scripts/ci.sh can't invoke it directly.
func TestLoadQuickScriptExistsAndExecutable(t *testing.T) {
	path := repoFile(t, "scripts/load-quick.sh")
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat %s: %v", path, err)
	}
	if info.Size() == 0 {
		t.Fatalf("%s is empty", path)
	}
	mode := info.Mode().Perm()
	if mode&0o111 == 0 {
		t.Fatalf("%s is not executable: mode=%o", path, mode)
	}
}

// TestLoadQuickScriptParses runs `bash -n` against the wrapper. A
// syntax error in the script will trip this test before any operator
// ever tries to run it. We skip cleanly if `bash` isn't on PATH (this
// matches the skip-discipline used by tests/e2e/e2e_test.go for
// podman-not-installed environments).
func TestLoadQuickScriptParses(t *testing.T) {
	bashPath, err := exec.LookPath("bash")
	if err != nil {
		t.Skip("bash not on PATH; skipping syntax check")
	}
	script := repoFile(t, "scripts/load-quick.sh")
	out, err := exec.Command(bashPath, "-n", script).CombinedOutput()
	if err != nil {
		t.Fatalf("bash -n %s failed: %v\noutput: %s", script, err, string(out))
	}
}

// TestK6QuickContainsThresholds is the load-bearing assertion of this
// file: the threshold strings k6 evaluates against real wire metrics
// MUST appear in the script, otherwise the load gate would silently
// pass against a slow / failing backend. Each required token is
// asserted independently so the failure message names the missing one.
//
// Falsifiability rehearsal target: remove the cached_hits threshold,
// observe this test fail with the exact missing-token message naming
// "p(99)<200" on the cached_hits line.
func TestK6QuickContainsThresholds(t *testing.T) {
	path := repoFile(t, "tests/load/k6-quick.js")
	body := string(assertNonEmptyFile(t, path))
	required := []string{
		// cached_hits p99 < 200ms — the "cache works" SLO.
		`'http_req_duration{scenario:cached_hits}': ['p(99)<200']`,
		// anonymous_health p99 — calibrated for live-rutracker LAN
		// deployment (300ms, was 100ms aspirational before the
		// 2026-04-29 calibration commit).
		`'http_req_duration{scenario:anonymous_health}': ['p(99)<300']`,
		// global failure rate ceiling — calibrated to 20% to accept
		// circuit-breaker trips when the upstream rate-limits us
		// under sustained 50-VU load. That's the breaker working as
		// designed; over-tightening this threshold would make the
		// test green only on a quiet upstream.
		`'http_req_failed': ['rate<0.20']`,
		// global checks-pass floor matches the http_req_failed budget.
		`'checks': ['rate>0.80']`,
	}
	for _, tok := range required {
		if !strings.Contains(body, tok) {
			t.Errorf("k6-quick.js missing required threshold string: %q", tok)
		}
	}
}

// TestK6QuickContainsScenarios asserts the three scenarios the task
// brief mandates are all wired up. Without them, the thresholds above
// have nothing to attach to.
func TestK6QuickContainsScenarios(t *testing.T) {
	path := repoFile(t, "tests/load/k6-quick.js")
	body := string(assertNonEmptyFile(t, path))
	required := []string{
		"cached_hits:",
		"cold_searches:",
		"anonymous_health:",
	}
	for _, tok := range required {
		if !strings.Contains(body, tok) {
			t.Errorf("k6-quick.js missing required scenario: %q", tok)
		}
	}
}

// TestK6SoakIsThirtyMinutes asserts the soak duration is set to '30m'.
// A typo here ('30s', '3m', etc) would silently turn a soak into a
// smoke. The soak is operator-only so CI won't catch the mistake by
// running it.
func TestK6SoakIsThirtyMinutes(t *testing.T) {
	path := repoFile(t, "tests/load/k6-soak.js")
	body := string(assertNonEmptyFile(t, path))
	if !strings.Contains(body, "'30m'") {
		t.Errorf("k6-soak.js does not declare '30m' duration anywhere")
	}
}

// TestLoadQuickScriptHasGracefulSkip asserts the wrapper still implements
// the "skip cleanly when k6 is missing" branch. Without it, every dev
// machine without k6 installed would fail the wider ci.sh gate, which
// is exactly the regression we want to prevent.
func TestLoadQuickScriptHasGracefulSkip(t *testing.T) {
	path := repoFile(t, "scripts/load-quick.sh")
	body := string(assertNonEmptyFile(t, path))
	required := []string{
		"command -v k6",
		"LAVA_LOAD_REQUIRE",
	}
	for _, tok := range required {
		if !strings.Contains(body, tok) {
			t.Errorf("load-quick.sh missing required skip-discipline token: %q", tok)
		}
	}
}
