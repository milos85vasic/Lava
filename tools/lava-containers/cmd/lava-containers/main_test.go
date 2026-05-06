package main

import "testing"

func TestValidateProfile_Accepts(t *testing.T) {
	// Post-2026-05-06: only api-go is supported (the legacy Ktor proxy
	// was removed; legacy/both are no longer valid profiles).
	for _, p := range []string{"api-go"} {
		if err := validateProfile(p); err != nil {
			t.Fatalf("validateProfile(%q) returned error: %v", p, err)
		}
	}
}

func TestValidateProfile_Rejects(t *testing.T) {
	for _, p := range []string{"", "legacy", "both", "observability", "dev-docs", "garbage", "API-GO"} {
		if err := validateProfile(p); err == nil {
			t.Fatalf("validateProfile(%q) accepted, expected error", p)
		}
	}
}
