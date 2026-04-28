// Package scripts_test verifies that the Lava lifecycle shell scripts
//
//	start.sh
//	stop.sh
//	scripts/tag.sh
//	build_and_release.sh
//	lava-api-go/scripts/pretag-verify.sh   (Phase 13.1)
//	lava-api-go/scripts/mutation.sh        (Phase 13.2)
//	lava-api-go/scripts/ci.sh              (Phase 13.3 — security gates)
//
// (a) parse with `bash -n` (no syntax errors), and
// (b) contain the SP-2 Phase 12 + Phase 13 wiring strings the production flow
//     depends on:
//   - start.sh forwards --legacy / --both / --with-observability / --dev-docs
//   - tag.sh registers the api-go app with prefix Lava-API-Go-
//   - tag.sh refuses to operate without .lava-ci-evidence/<commit>.json
//     for api-go (bypass: --no-evidence-required)
//   - build_and_release.sh populates releases/{version}/api-go/
//   - pretag-verify.sh emits .lava-ci-evidence/<commit>.json with checks/
//     timestamp/base_url/exit_code
//   - mutation.sh wraps go-mutesting
//   - ci.sh appends gosec / govulncheck / trivy with LAVA_CI_SKIP_<TOOL>
//     and --strict
//
// (c) `scripts/tag.sh --dry-run --app api-go` prints the expected tag string
// without performing any git mutations. This is the falsifiability target
// for Phase 12; Phase 13 adds TestTagShReferencesEvidenceGate as the
// falsifiability target for the evidence-required gate (drop the
// require_evidence_for_apigo call → that test fails).
//
// These tests are pure shell-text inspection plus one safe invocation of
// tag.sh in --dry-run mode (which is documented as not touching git or
// files). They do not start containers, do not call git, and do not write
// outside the repo.
package scripts_test

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
)

// repoRoot returns the absolute path to the Lava repo root by walking up
// from this test file (the same approach the compose tests use).
func repoRoot(t *testing.T) string {
	t.Helper()
	_, here, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	// here = .../lava-api-go/tests/scripts/scripts_test.go
	root := filepath.Clean(filepath.Join(filepath.Dir(here), "..", "..", ".."))
	if _, err := os.Stat(filepath.Join(root, "start.sh")); err != nil {
		t.Fatalf("repoRoot autodetect failed: %v (computed %q)", err, root)
	}
	return root
}

// readScript loads a script as a string for substring assertions.
func readScript(t *testing.T, root, rel string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(root, rel))
	if err != nil {
		t.Fatalf("read %s: %v", rel, err)
	}
	return string(b)
}

// bashSyntaxCheck runs `bash -n` against the script file, which validates
// the script parses without executing it.
func bashSyntaxCheck(t *testing.T, root, rel string) {
	t.Helper()
	bash, err := exec.LookPath("bash")
	if err != nil {
		t.Skipf("bash not found in PATH: %v", err)
	}
	cmd := exec.Command(bash, "-n", filepath.Join(root, rel))
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("`bash -n %s` failed: %v\nstderr: %s", rel, err, stderr.String())
	}
}

func TestStartShParses(t *testing.T) {
	bashSyntaxCheck(t, repoRoot(t), "start.sh")
}

func TestStopShParses(t *testing.T) {
	bashSyntaxCheck(t, repoRoot(t), "stop.sh")
}

func TestTagShParses(t *testing.T) {
	bashSyntaxCheck(t, repoRoot(t), "scripts/tag.sh")
}

func TestBuildAndReleaseShParses(t *testing.T) {
	bashSyntaxCheck(t, repoRoot(t), "build_and_release.sh")
}

func TestStartShContainsProfileFlags(t *testing.T) {
	body := readScript(t, repoRoot(t), "start.sh")
	for _, want := range []string{
		"--legacy",
		"--both",
		"--with-observability",
		"--dev-docs",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("start.sh missing expected flag wiring %q", want)
		}
	}
}

func TestTagShContainsApiGo(t *testing.T) {
	body := readScript(t, repoRoot(t), "scripts/tag.sh")
	if !strings.Contains(body, "api-go") {
		t.Fatalf("tag.sh missing api-go entry")
	}
	if !strings.Contains(body, "Lava-API-Go-") {
		t.Fatalf("tag.sh missing Lava-API-Go- tag prefix wiring")
	}
	// Specifically: SUPPORTED_APPS must include api-go (the dispatch
	// guard refuses unknown apps, so this is the load-bearing line).
	// Tolerate extra whitespace and reordering.
	supportedRe := regexp.MustCompile(`(?m)^[[:space:]]*SUPPORTED_APPS=\(([^)]*)\)`)
	m := supportedRe.FindStringSubmatch(body)
	if m == nil {
		t.Fatalf("tag.sh missing SUPPORTED_APPS=(...) array declaration")
	}
	apps := strings.Fields(m[1])
	hasApiGo := false
	for _, a := range apps {
		if a == "api-go" {
			hasApiGo = true
			break
		}
	}
	if !hasApiGo {
		t.Fatalf("tag.sh SUPPORTED_APPS missing api-go entry; got %v", apps)
	}
}

func TestBuildAndReleaseShPopulatesApiGo(t *testing.T) {
	body := readScript(t, repoRoot(t), "build_and_release.sh")
	// Must reference the per-version api-go release subdir somehow. Two
	// valid shapes: literal `releases/$APP_VERSION/api-go` (current code
	// composes it via $RELEASE_DIR/api-go after RELEASE_DIR=releases/...)
	// — accept either an explicit `releases/.../api-go` or the composed
	// `RELEASE_DIR/api-go` form.
	if !strings.Contains(body, "/api-go") {
		t.Fatalf("build_and_release.sh missing /api-go release subdir reference")
	}
	if !strings.Contains(body, "RELEASE_DIR") {
		t.Fatalf("build_and_release.sh missing RELEASE_DIR base (releases/{version}/) anchor")
	}
	// Must build the lava-api-go binary with the Phase 12 flags.
	if !strings.Contains(body, "go build") {
		t.Fatalf("build_and_release.sh missing `go build` step for lava-api-go")
	}
	if !strings.Contains(body, "CGO_ENABLED=0") {
		t.Fatalf("build_and_release.sh go build step must set CGO_ENABLED=0 (static binary)")
	}
}

// TestTagShDryRunApiGoEmitsTag actually invokes scripts/tag.sh with
// --dry-run --app api-go --no-bump --no-push and asserts that the dry-run
// log contains the expected `Lava-API-Go-<name>-<code>` tag, sourced from
// lava-api-go/internal/version/version.go.
//
// --dry-run makes this safe: tag.sh's `run` helper detects DRY_RUN=true
// and prints the action without invoking git. --no-bump --no-push remove
// the bump/commit and push branches as belt-and-suspenders so even if the
// dry-run guard regressed, no remote mutation could occur.
func TestTagShDryRunApiGoEmitsTag(t *testing.T) {
	root := repoRoot(t)
	bash, err := exec.LookPath("bash")
	if err != nil {
		t.Skipf("bash not found in PATH: %v", err)
	}

	// Read current api-go version from version.go to know what to expect.
	verBytes, err := os.ReadFile(filepath.Join(root, "lava-api-go", "internal", "version", "version.go"))
	if err != nil {
		t.Fatalf("read version.go: %v", err)
	}
	verSrc := string(verBytes)
	name := extractFirstQuoted(t, verSrc, "Name")
	code := extractFirstNumber(t, verSrc, "Code")
	wantTag := "Lava-API-Go-" + name + "-" + code

	cmd := exec.Command(bash, filepath.Join(root, "scripts", "tag.sh"),
		"--dry-run", "--app", "api-go", "--no-bump", "--no-push")
	cmd.Dir = root
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("tag.sh --dry-run --app api-go failed: %v\nstdout:\n%s\nstderr:\n%s",
			err, stdout.String(), stderr.String())
	}
	combined := stdout.String() + stderr.String()
	if !strings.Contains(combined, wantTag) {
		t.Fatalf("tag.sh --dry-run --app api-go did not print expected tag %q\noutput:\n%s",
			wantTag, combined)
	}
}

// extractFirstQuoted finds the first `<key> = "VALUE"` occurrence (with `=`
// directly between key and value, ignoring whitespace) and returns VALUE.
// Skips occurrences of `key` inside doc comments where the next token isn't
// an `=` sign. Used to read Name from version.go without importing the
// version package (which would be a build-time coupling we don't want for
// a lifecycle-script test).
func extractFirstQuoted(t *testing.T, src, key string) string {
	t.Helper()
	pos := 0
	for {
		idx := strings.Index(src[pos:], key)
		if idx < 0 {
			t.Fatalf("version.go missing assignment for key %q", key)
		}
		after := strings.TrimLeft(src[pos+idx+len(key):], " \t")
		if !strings.HasPrefix(after, "=") {
			pos = pos + idx + len(key)
			continue
		}
		afterEq := strings.TrimLeft(after[1:], " \t")
		if !strings.HasPrefix(afterEq, `"`) {
			pos = pos + idx + len(key)
			continue
		}
		q2 := strings.Index(afterEq[1:], `"`)
		if q2 < 0 {
			t.Fatalf("version.go: no closing quote after key %q", key)
		}
		return afterEq[1 : 1+q2]
	}
}

// extractFirstNumber finds the first `<key> = NUMBER` occurrence and returns
// NUMBER as a string. Skips occurrences of `key` inside doc comments.
func extractFirstNumber(t *testing.T, src, key string) string {
	t.Helper()
	pos := 0
	for {
		idx := strings.Index(src[pos:], key)
		if idx < 0 {
			t.Fatalf("version.go missing assignment for key %q", key)
		}
		after := strings.TrimLeft(src[pos+idx+len(key):], " \t")
		if !strings.HasPrefix(after, "=") {
			pos = pos + idx + len(key)
			continue
		}
		tail := strings.TrimLeft(after[1:], " \t")
		end := 0
		for end < len(tail) && tail[end] >= '0' && tail[end] <= '9' {
			end++
		}
		if end == 0 {
			pos = pos + idx + len(key)
			continue
		}
		return tail[:end]
	}
}

// ----------------------------------------------------------------------
// SP-2 Phase 13 tests
//
// (1) lava-api-go/scripts/pretag-verify.sh exists, is executable, parses,
//     and contains the load-bearing strings (.lava-ci-evidence, commit,
//     the JSON-emit shape).
// (2) lava-api-go/scripts/mutation.sh exists, parses, and references
//     go-mutesting.
// (3) lava-api-go/scripts/ci.sh references gosec, govulncheck, and trivy
//     (Phase 13.3 security gates).
// (4) scripts/tag.sh references the pretag evidence gate
//     (`pretag-verify` AND `.lava-ci-evidence`) — falsifiability target:
//     dropping the require_evidence_for_apigo call from the api-go
//     dispatch, or removing the .lava-ci-evidence reference, makes
//     TestTagShReferencesEvidenceGate fail.
// ----------------------------------------------------------------------

func TestPretagVerifyShParses(t *testing.T) {
	bashSyntaxCheck(t, repoRoot(t), "lava-api-go/scripts/pretag-verify.sh")
}

func TestPretagVerifyShIsExecutable(t *testing.T) {
	root := repoRoot(t)
	rel := "lava-api-go/scripts/pretag-verify.sh"
	info, err := os.Stat(filepath.Join(root, rel))
	if err != nil {
		t.Fatalf("stat %s: %v", rel, err)
	}
	// Any execute bit (owner/group/other) is sufficient — exec.LookPath
	// resolves a script as runnable if the OS would run it. We assert at
	// least the owner-execute bit (0o100) is set.
	if info.Mode()&0o100 == 0 {
		t.Fatalf("%s is not executable; mode=%v", rel, info.Mode())
	}
}

func TestPretagVerifyShContainsExpectedStrings(t *testing.T) {
	body := readScript(t, repoRoot(t), "lava-api-go/scripts/pretag-verify.sh")
	for _, want := range []string{
		// Evidence directory — load-bearing for the tag.sh gate.
		".lava-ci-evidence",
		// Computes the commit SHA to key the evidence file.
		"git rev-parse HEAD",
		"commit",
		// Five user-flow steps.
		"/forum",
		"/search",
		"/torrent/",
		"/favorites",
		// JSON-emit pattern (the recorded fields from spec §13.1).
		`"checks"`,
		`"exit_code"`,
		`"timestamp"`,
		`"base_url"`,
		// Operator-bypass discipline: must NOT auto-start the stack.
		"./start.sh",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("pretag-verify.sh missing expected substring %q", want)
		}
	}
}

func TestMutationShParses(t *testing.T) {
	bashSyntaxCheck(t, repoRoot(t), "lava-api-go/scripts/mutation.sh")
}

func TestMutationShReferencesGoMutesting(t *testing.T) {
	body := readScript(t, repoRoot(t), "lava-api-go/scripts/mutation.sh")
	for _, want := range []string{
		"go-mutesting",
		"github.com/zimmski/go-mutesting",
		"mutation-report",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("mutation.sh missing expected substring %q", want)
		}
	}
}

func TestCiShReferencesSecurityGates(t *testing.T) {
	body := readScript(t, repoRoot(t), "lava-api-go/scripts/ci.sh")
	for _, want := range []string{
		"gosec",
		"govulncheck",
		"trivy",
		// Per-tool bypass env vars must be honoured.
		"LAVA_CI_SKIP_GOSEC",
		"LAVA_CI_SKIP_GOVULNCHECK",
		"LAVA_CI_SKIP_TRIVY",
		// --strict mode flag (Phase 14 acceptance).
		"--strict",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("ci.sh missing expected substring %q", want)
		}
	}
}

// TestTagShReferencesEvidenceGate is the falsifiability anchor for the
// Phase 13 wiring: removing the require_evidence_for_apigo call from
// scripts/tag.sh's api-go dispatch, or stripping the .lava-ci-evidence
// reference, must make this test fail loudly.
func TestTagShReferencesEvidenceGate(t *testing.T) {
	body := readScript(t, repoRoot(t), "scripts/tag.sh")
	for _, want := range []string{
		"pretag-verify",
		".lava-ci-evidence",
		"require_evidence_for_apigo",
		"--no-evidence-required",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("tag.sh missing expected substring %q (Phase 13.1 evidence-required gate)", want)
		}
	}
}
