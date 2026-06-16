// SPDX-FileCopyrightText: 2026 Milos Vasic
// SPDX-License-Identifier: Apache-2.0

// Package testbank load+validate proof for the Lava reported-issues bank.
//
// This test makes the reported-issues YAML MACHINE-LOADABLE, not just a
// doc: it loads the bank through the REAL HelixQA loader
// (digital.vasic.helixqa/pkg/testbank.LoadFile, wired via the go.mod
// replace directive `digital.vasic.helixqa => ../submodules/helixqa`) and
// asserts the bank parses into HelixQA's BankFile schema, passes HelixQA's
// own per-case validation, and contains exactly the four
// operator-reported issues, each mapped to its Compose UI Challenge.
//
// FALSIFIABILITY REHEARSAL (§6.J / §11.4.115). To confirm this test
// genuinely catches a broken bank rather than rubber-stamping, the bank
// was deliberately mutated and the test re-run:
//
//	Mutation A — rename `name:` to `title:` on case 01 (the canonical S1B
//	defect HelixQA's loader guards against):
//	  => TestReportedIssuesBank_LoadsAndValidatesAgainstHelixQASchema FAILED
//	     with: "load reported-issues bank ...: bank file ...: test case 0:
//	     test case LVA-REPORTED-ISSUE-01-ONBOARDING-SELECT-ALL missing name"
//	Mutation B — delete the case 03 (per-endpoint auth) block entirely:
//	  => TestReportedIssuesBank_HasAllFourReportedIssues FAILED with:
//	     "reported-issues bank missing required case id
//	     \"LVA-REPORTED-ISSUE-03-SEARCH-PER-ENDPOINT-V1-AUTH\""
//	Mutation C — duplicate case 02's id onto case 04:
//	  => load FAILED with HelixQA's: "duplicate test case id ... at
//	     indices 1 and 3"
//	All three mutations reverted; the test PASSES on the real bank.
//
// The mutation targets the production loader path the test claims to
// cover (testbank.LoadFile + IsValid + duplicate-id guard), per §6.J
// clause 2 (provably falsifiable on real defects).
//
// HONESTY: this test proves load + schema validation ONLY. It performs no
// LLM vision validation and makes no such claim. Per-issue EXECUTION proof
// is the mapped Challenge verdict + recorded video.
package testbank

import (
	"path/filepath"
	"runtime"
	"testing"

	hxqatestbank "digital.vasic.helixqa/pkg/testbank"
)

// moduleRoot returns the lava-api-go module root by walking up from this
// test file's location (internal/qa/testbank/loader_test.go -> three dirs
// up = module root). Robust to the CWD `go test` runs in.
func moduleRoot(t *testing.T) string {
	t.Helper()
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller(0) failed; cannot resolve module root")
	}
	// thisFile = <root>/internal/qa/testbank/loader_test.go
	return filepath.Clean(filepath.Join(filepath.Dir(thisFile), "..", "..", ".."))
}

// TestReportedIssuesBank_LoadsAndValidatesAgainstHelixQASchema is the
// primary load+validate proof: the bank deserialises into HelixQA's
// BankFile via the REAL HelixQA loader and passes its per-case validation.
func TestReportedIssuesBank_LoadsAndValidatesAgainstHelixQASchema(t *testing.T) {
	root := moduleRoot(t)

	bf, err := LoadReportedIssuesBank(root)
	if err != nil {
		t.Fatalf("reported-issues bank must load+validate against the real HelixQA schema: %v", err)
	}
	if bf == nil {
		t.Fatal("loaded a nil BankFile")
	}
	if bf.Name != "lava-reported-issues" {
		t.Errorf("bank Name = %q, want %q", bf.Name, "lava-reported-issues")
	}
	if len(bf.TestCases) == 0 {
		t.Fatal("loaded 0 test cases from the reported-issues bank")
	}

	// Re-run HelixQA's own per-case validation explicitly so a regression
	// in HelixQA's IsValid surfaces here loudly, not just implicitly via
	// the loader's pre-check.
	for i := range bf.TestCases {
		tc := &bf.TestCases[i]
		if msg := tc.IsValid(); msg != "" {
			t.Errorf("case %d (%s) fails HelixQA IsValid: %s", i, tc.ID, msg)
		}
	}
}

// TestReportedIssuesBank_HasAllFourReportedIssues asserts the bank is
// COMPLETE: exactly the four operator-reported issues are present, each
// with a non-empty user-visible expected_result, Given/When/Then steps,
// and an effective challenge id.
func TestReportedIssuesBank_HasAllFourReportedIssues(t *testing.T) {
	root := moduleRoot(t)

	bf, err := ValidateReportedIssuesBank(root)
	if err != nil {
		t.Fatalf("reported-issues bank completeness check failed: %v", err)
	}
	if got, want := len(bf.TestCases), len(ExpectedReportedIssueIDs); got != want {
		t.Errorf("bank has %d cases, want exactly %d (one per reported issue)", got, want)
	}
}

// TestReportedIssuesBank_EachCaseMapsToAChallenge asserts every case
// targets the Android platform and names a Compose UI Challenge class via
// dispatches_to, so the bank's specification is wired to a real execution
// surface (the connectedAndroidTest Challenge whose verdict + recorded
// video is the per-issue proof).
func TestReportedIssuesBank_EachCaseMapsToAChallenge(t *testing.T) {
	root := moduleRoot(t)

	bf, err := LoadReportedIssuesBank(root)
	if err != nil {
		t.Fatalf("load reported-issues bank: %v", err)
	}

	// challenge_id -> the case it must appear in (one per reported issue).
	wantChallenge := map[string]string{
		"LVA-REPORTED-ISSUE-01-ONBOARDING-SELECT-ALL":            "Challenge41",
		"LVA-REPORTED-ISSUE-02-PROVIDER-CONFIG-PASSWORD-MASK":    "Challenge42",
		"LVA-REPORTED-ISSUE-03-SEARCH-PER-ENDPOINT-V1-AUTH":      "Challenge44",
		"LVA-REPORTED-ISSUE-04-SETTINGS-DUPLICATE-ONLINE-SERVER": "Challenge43",
	}

	byID := make(map[string]*hxqatestbank.TestCase, len(bf.TestCases))
	for i := range bf.TestCases {
		byID[bf.TestCases[i].ID] = &bf.TestCases[i]
	}

	for id, challenge := range wantChallenge {
		tc, ok := byID[id]
		if !ok {
			t.Errorf("missing case %q", id)
			continue
		}
		if tc.ChallengeID != challenge {
			t.Errorf("case %q challenge_id = %q, want %q", id, tc.ChallengeID, challenge)
		}
		if tc.DispatchesTo == "" {
			t.Errorf("case %q has empty dispatches_to (no execution surface)", id)
		}
		if !tc.AppliesToPlatform("android") {
			t.Errorf("case %q does not target the android platform", id)
		}
	}
}
