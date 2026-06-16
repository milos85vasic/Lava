// Package contract — version_binary_contract_test.go is a §6.L 68th
// §6.A real-binary contract test for the lava-api-go `--version` flag.
//
// §6.P / §6.Y make the binary's self-reported version the authority for
// distribution gating (strictly-increasing Code, semver Name). If the
// binary's `--version` output ever diverges from internal/version's
// constants — e.g. a refactor prints a hard-coded string, or reads the
// wrong field — every distribute-time version check is reasoning about a
// number the shipped artifact does NOT actually report. That is a §6.A
// "script invokes a binary we own" drift: scripts/firebase-distribute.sh
// + scripts/tag.sh trust this output. No existing test builds the binary
// and asserts its `--version` line matches the version package.
//
// This test BUILDS the real binary and runs it — it is the actual
// artifact's behavior, not a unit test of the constant. PRIMARY
// assertion is on the process's stdout (the operator/script-observable
// surface).
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.A clause 4, §6.J):
//
//	Mutation: in cmd/lava-api-go/main.go, change the --version Printf to
//	  a hard-coded `fmt.Printf("lava-api-go 0.0.0 (build 0)\n")`.
//	Observed: TestVersionBinaryContract_MatchesVersionPackage FAILS:
//	  "--version stdout %q does not contain Name %q" (got 0.0.0, want
//	  2.3.22).
//	Reverted: yes (production code restored; final commit unmutated).
package contract

import (
	"bytes"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/version"
)

// buildLavaAPIGo builds cmd/lava-api-go to a temp dir and returns the
// binary path. Mirrors buildHealthprobe in healthcheck_contract_test.go.
func buildLavaAPIGo(t *testing.T) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("lava-api-go binary contract not built for windows")
	}
	root := repoRoot(t)
	apigoDir := filepath.Join(root, "lava-api-go")
	binPath := filepath.Join(t.TempDir(), "lava-api-go")

	cmd := exec.Command("go", "build", "-trimpath", "-o", binPath, "./cmd/lava-api-go")
	cmd.Dir = apigoDir
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("go build lava-api-go: %v\nstderr:\n%s", err, stderr.String())
	}
	return binPath
}

// TestVersionBinaryContract_MatchesVersionPackage is the load-bearing
// §6.A contract: the shipped binary's `--version` output MUST report the
// SAME Name and Code the version package declares — because that is what
// the distribution gates read and trust.
func TestVersionBinaryContract_MatchesVersionPackage(t *testing.T) {
	bin := buildLavaAPIGo(t)

	out, err := exec.Command(bin, "--version").CombinedOutput()
	if err != nil {
		t.Fatalf("--version: %v\noutput: %s", err, out)
	}
	got := string(out)

	if !strings.Contains(got, version.Name) {
		t.Fatalf("--version stdout %q does not contain version.Name %q — binary identity drifted from the constant the distribution gates trust", strings.TrimSpace(got), version.Name)
	}
	codeStr := strconv.Itoa(version.Code)
	if !strings.Contains(got, codeStr) {
		t.Fatalf("--version stdout %q does not contain version.Code %q — §6.P monotonic-code gate would compare against the wrong number", strings.TrimSpace(got), codeStr)
	}
}

// TestVersion_NameIsThreeComponentSemver pins the §6.P/§6.Y format
// invariant declared in version.go's doc: Name MUST be MAJOR.MINOR.PATCH.
// A two- or four-component Name would break the semver-bump tooling.
func TestVersion_NameIsThreeComponentSemver(t *testing.T) {
	re := regexp.MustCompile(`^\d+\.\d+\.\d+$`)
	if !re.MatchString(version.Name) {
		t.Fatalf("version.Name=%q is not strict three-component semver MAJOR.MINOR.PATCH (§6.P invariant)", version.Name)
	}
}

// TestVersion_CodeIsPositive pins the monotonic-counter invariant: Code
// MUST be a positive integer (the distribution gate compares it as a
// strictly increasing value; a zero/negative would break the comparison).
func TestVersion_CodeIsPositive(t *testing.T) {
	if version.Code <= 0 {
		t.Fatalf("version.Code=%d must be a positive integer (§6.P monotonic counter)", version.Code)
	}
}
