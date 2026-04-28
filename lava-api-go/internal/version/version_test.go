package version

import (
	"regexp"
	"testing"
)

var semverPat = regexp.MustCompile(`^\d+\.\d+\.\d+$`)

func TestNameIsStrictSemver(t *testing.T) {
	if !semverPat.MatchString(Name) {
		t.Fatalf("Name = %q, want strict MAJOR.MINOR.PATCH semver", Name)
	}
}

func TestCodeIsPositive(t *testing.T) {
	if Code <= 0 {
		t.Fatalf("Code = %d, want positive", Code)
	}
}

// Removed: TestNameAndCodeMatchInitialRelease was the test-re-asserts-the-constant
// anti-pattern. It would have rotted at every release tag because scripts/tag.sh
// rewrites Name/Code mechanically — and a self-asserting test would just have to
// be rewritten in lockstep, providing zero behavioural guarantee beyond what
// TestNameIsStrictSemver and TestCodeIsPositive already enforce. The format
// invariants are the real properties; the literal value is a moving target.
