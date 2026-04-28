package main

import "testing"

func TestValidateProfile_Accepts(t *testing.T) {
	for _, p := range []string{"api-go", "legacy", "both"} {
		if err := validateProfile(p); err != nil {
			t.Fatalf("validateProfile(%q) returned error: %v", p, err)
		}
	}
}

func TestValidateProfile_Rejects(t *testing.T) {
	for _, p := range []string{"", "observability", "dev-docs", "garbage", "API-GO"} {
		if err := validateProfile(p); err == nil {
			t.Fatalf("validateProfile(%q) accepted, expected error", p)
		}
	}
}
