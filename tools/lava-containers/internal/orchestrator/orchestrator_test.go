package orchestrator

import (
	"strings"
	"testing"

	"digital.vasic.lava.tools.containers/internal/runtime"
)

func newTestOrch(profile string, obs, dev bool) *Orchestrator {
	return &Orchestrator{
		Runtime:           &runtime.Runtime{Name: "docker", Binary: "/usr/bin/docker", ComposeCmd: []string{"/usr/bin/docker", "compose"}},
		ProjectDir:        "/tmp/lava",
		Profile:           profile,
		WithObservability: obs,
		WithDevDocs:       dev,
	}
}

func TestOrchestrator_Profiles_DefaultApiGo(t *testing.T) {
	o := newTestOrch("api-go", false, false)
	got := o.Profiles()
	if len(got) != 1 || got[0] != "api-go" {
		t.Fatalf("expected [api-go], got %v", got)
	}
}

func TestOrchestrator_Profiles_LegacyOnly(t *testing.T) {
	o := newTestOrch("legacy", false, false)
	got := o.Profiles()
	if len(got) != 1 || got[0] != "legacy" {
		t.Fatalf("expected [legacy], got %v", got)
	}
}

func TestOrchestrator_Profiles_BothPlusObservability(t *testing.T) {
	o := newTestOrch("both", true, false)
	got := o.Profiles()
	want := []string{"both", "observability"}
	if !equalStrings(got, want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
}

func TestOrchestrator_Profiles_AllThree(t *testing.T) {
	o := newTestOrch("api-go", true, true)
	got := o.Profiles()
	want := []string{"api-go", "observability", "dev-docs"}
	if !equalStrings(got, want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
}

func TestOrchestrator_ComposeArgs_SingleProfile(t *testing.T) {
	o := newTestOrch("api-go", false, false)
	got := o.ComposeArgs()
	want := []string{"--profile", "api-go"}
	if !equalStrings(got, want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
}

func TestOrchestrator_ComposeArgs_WithObservabilityAndDevDocs(t *testing.T) {
	o := newTestOrch("both", true, true)
	got := o.ComposeArgs()
	want := []string{"--profile", "both", "--profile", "observability", "--profile", "dev-docs"}
	if !equalStrings(got, want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
}

// TestOrchestrator_ComposeArgs_FlagOrderMatchesProfilesOrder verifies that
// the --profile pairs appear in the exact order Profiles() returns. This is
// a primary-on-user-visible-state check: if compose ever receives the
// profiles in the wrong order it could pick the wrong default in some edge
// cases, so the order is part of the contract.
func TestOrchestrator_ComposeArgs_FlagOrderMatchesProfilesOrder(t *testing.T) {
	o := newTestOrch("legacy", true, true)
	args := o.ComposeArgs()
	joined := strings.Join(args, " ")
	want := "--profile legacy --profile observability --profile dev-docs"
	if joined != want {
		t.Fatalf("expected %q, got %q", want, joined)
	}
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
