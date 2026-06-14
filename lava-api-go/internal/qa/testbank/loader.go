// SPDX-FileCopyrightText: 2026 Milos Vasic
// SPDX-License-Identifier: Apache-2.0

// Package testbank is Lava's thin adapter over HelixQA's pkg/testbank
// loader. It exposes the REAL HelixQA bank loader
// (digital.vasic.helixqa/pkg/testbank.LoadFile / LoadDir) plus a small
// Lava-domain assertion that the reported-issues bank actually parses
// into HelixQA's BankFile schema and is machine-loadable — not just a
// hand-written doc.
//
// Why an adapter exists rather than calling testbank.LoadFile directly:
// the reported-issues bank is a Lava artefact whose load+validate proof
// must travel with lava-api-go's own `go test` suite. This package gives
// that suite a single, named entry point (LoadReportedIssuesBank) that
// resolves the bank path relative to the repo, calls the genuine HelixQA
// loader, and re-runs HelixQA's own per-case validation so a regression
// in the bank (missing name, duplicate id, malformed YAML) fails the
// Lava build — exactly the same contract HelixQA enforces internally.
//
// HONESTY (§6.J / §11.4.123): this package performs NO LLM vision
// validation and makes NO such claim. It proves the bank is a real,
// schema-valid, machine-loadable HelixQA bank. The per-issue EXECUTION
// proof is the mapped Compose UI Challenge verdict + recorded video.
//
// Classification: project-specific (the reported-issues bank + its repo
// path are Lava-specific; the loader-wraps-HelixQA pattern is universal
// per HelixConstitution §11.4.74 catalogue-first reuse).
package testbank

import (
	"fmt"
	"os"
	"path/filepath"

	hxqatestbank "digital.vasic.helixqa/pkg/testbank"
)

// ReportedIssuesBankRelPath is the bank file's path relative to the
// lava-api-go module root. The four operator-reported issues live here,
// one TestCase per issue, in HelixQA's testbank YAML schema.
const ReportedIssuesBankRelPath = "internal/qa/testbank/banks/lava-reported-issues.yaml"

// ExpectedReportedIssueIDs are the stable case IDs the reported-issues
// bank MUST contain — one per operator-reported issue. Used by the
// load+validate test to assert the bank is complete, not just parseable.
var ExpectedReportedIssueIDs = []string{
	"LVA-REPORTED-ISSUE-01-ONBOARDING-SELECT-ALL",
	"LVA-REPORTED-ISSUE-02-PROVIDER-CONFIG-PASSWORD-MASK",
	"LVA-REPORTED-ISSUE-03-SEARCH-PER-ENDPOINT-V1-AUTH",
	"LVA-REPORTED-ISSUE-04-SETTINGS-DUPLICATE-ONLINE-SERVER",
}

// LoadBankFile loads an arbitrary HelixQA test bank file through the REAL
// HelixQA loader (testbank.LoadFile), returning the parsed BankFile. Any
// schema violation HelixQA enforces (missing name, duplicate id, parse
// error) surfaces here as a non-nil error.
func LoadBankFile(path string) (*hxqatestbank.BankFile, error) {
	return hxqatestbank.LoadFile(path)
}

// LoadReportedIssuesBank resolves the reported-issues bank relative to
// moduleRoot (the lava-api-go module root), loads it through the real
// HelixQA loader, and returns the parsed BankFile. The HelixQA loader
// already validates every case (IsValid) and rejects duplicate ids; this
// adds nothing on top of that contract — it just gives Lava a named call
// site for the proof.
func LoadReportedIssuesBank(moduleRoot string) (*hxqatestbank.BankFile, error) {
	path := filepath.Join(moduleRoot, ReportedIssuesBankRelPath)
	if _, err := os.Stat(path); err != nil {
		return nil, fmt.Errorf("reported-issues bank not found at %s: %w", path, err)
	}
	bf, err := LoadBankFile(path)
	if err != nil {
		return nil, fmt.Errorf("load reported-issues bank %s: %w", path, err)
	}
	return bf, nil
}

// ValidateReportedIssuesBank loads the bank and asserts it is complete:
// every ExpectedReportedIssueID is present, every case carries a non-empty
// name + an EffectiveChallengeID (challenge_id or id), and no expected ID
// is missing. Returns the parsed BankFile on success so callers can
// inspect cases further. This is the Lava-domain completeness check layered
// on top of HelixQA's parse+per-case validation.
func ValidateReportedIssuesBank(moduleRoot string) (*hxqatestbank.BankFile, error) {
	bf, err := LoadReportedIssuesBank(moduleRoot)
	if err != nil {
		return nil, err
	}

	byID := make(map[string]*hxqatestbank.TestCase, len(bf.TestCases))
	for i := range bf.TestCases {
		tc := &bf.TestCases[i]
		byID[tc.ID] = tc
	}

	for _, want := range ExpectedReportedIssueIDs {
		tc, ok := byID[want]
		if !ok {
			return nil, fmt.Errorf("reported-issues bank missing required case id %q", want)
		}
		if tc.Name == "" {
			return nil, fmt.Errorf("reported-issues case %q has empty name", want)
		}
		if tc.EffectiveChallengeID() == "" {
			return nil, fmt.Errorf("reported-issues case %q has no effective challenge id", want)
		}
		if tc.ExpectedResult == "" {
			return nil, fmt.Errorf("reported-issues case %q has empty expected_result (user-visible outcome)", want)
		}
		if len(tc.Steps) == 0 {
			return nil, fmt.Errorf("reported-issues case %q has no Given/When/Then steps", want)
		}
	}
	return bf, nil
}
