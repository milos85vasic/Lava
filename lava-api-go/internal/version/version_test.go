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

func TestNameAndCodeMatchInitialRelease(t *testing.T) {
	// Pinned to spec §15 — initial release is 2.0.0 / 2000.
	// When we cut a new release, this test gets bumped along with the constants.
	if Name != "2.0.0" {
		t.Errorf("Name = %q, want 2.0.0 for initial SP-2 release", Name)
	}
	if Code != 2000 {
		t.Errorf("Code = %d, want 2000 for initial SP-2 release", Code)
	}
}
