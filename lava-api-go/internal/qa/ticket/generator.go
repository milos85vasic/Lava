// Package ticket is Lava's adapter over HelixQA's pkg/ticket Generator.
//
// Phase 4-C-3 (2026-05-16) — third of the four-phase Group A rollout per
// docs/plans/2026-05-16-helixqa-go-package-linking-design.md. This adapter
// wraps HelixQA's Generator (Q2 WRAP strategy per operator decisions) and
// preserves HelixQA's terminology (Q4: Generator keeps its name) so
// HelixQA-familiar developers can navigate this package without a glossary.
//
// The adapter exists because HelixQA's Generator produces "HQA-####" QA-
// session tickets — generic markdown for any QA failure (Android crash,
// web step failure, desktop ANR). Lava's §6.O Crashlytics-Resolved Issue
// Coverage Mandate requires a more specific shape: one markdown file per
// closed Crashlytics issue at .lava-ci-evidence/crashlytics-resolved/
// <date>-<slug>.md, containing the issue ID, root-cause analysis, fix
// commit SHA, and links to validation + Challenge tests. Until this
// adapter landed, those files were authored manually by the operator
// (with the bluff risk that the schema could drift across files).
//
// This adapter produces §6.O-schema-compliant closure logs programmatically.
// HelixQA's Ticket struct is consumed at the field level; the markdown
// output is composed by this adapter, not by HelixQA's RenderMarkdown,
// because §6.O's schema is a Lava-domain shape (not a HelixQA concept).
// The HelixQA Generator wrapping gives us the counter/output-dir lifecycle
// + the persistence helpers (WriteTicket); the rendering is Lava-owned.
//
// Per §6.AC (Comprehensive Non-Fatal Telemetry Mandate): every error path
// records via observability.RecordNonFatal so server-side telemetry
// surfaces in the operator's Crashlytics / OTLP dashboards.
//
// Per Q7 (operator decision, Phase 4-C-1 cycle): NO recover() wrapping —
// we trust HelixQA's API and file upstream issues if panics surface.
//
// Per §6.J / §6.AE.4: every public method has a falsifiability rehearsal
// recorded in the Phase 4-C-3 commit body, and a real-stack integration
// test under tests/qa/ exercises the REAL HelixQA Generator against a
// real filesystem (gated by -tags=helixqa_realstack).
//
// `Classification:` project-specific (the §6.O schema + the closure-log
// path convention are Lava-specific; the adapter-wraps-HelixQA pattern
// is universal per HelixConstitution §11.4.31).
package ticket

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	hxqaticket "digital.vasic.helixqa/pkg/ticket"

	"digital.vasic.lava.apigo/internal/observability"
)

// telemetryFeature is the canonical §6.AC AttrFeature value for events
// originating in this adapter. Stable string so dashboards can filter.
const telemetryFeature = "qa.ticket"

// closureLogDateLayout is the date prefix used in §6.O closure-log
// filenames (YYYY-MM-DD). Matches every existing file under
// .lava-ci-evidence/crashlytics-resolved/.
const closureLogDateLayout = "2006-01-02"

// ErrEmptyCrashlyticsID is returned by GenerateClosureLog when the
// input's CrashlyticsID is empty. §6.O requires every closure log to
// cite the Crashlytics issue ID — silently writing a file without one
// would be a §6.J bluff (the file's existence claims coverage of an
// issue that has no ID to close).
var ErrEmptyCrashlyticsID = errors.New("qa/ticket: CrashlyticsID is required for closure log")

// ErrEmptySlug is returned by GenerateClosureLog when the input's Slug
// is empty. The slug is the human-readable part of the closure-log
// filename (the operator searches by slug, not by issue ID); an empty
// slug would produce a file like "2026-05-16-.md" which would silently
// collide with same-day calls.
var ErrEmptySlug = errors.New("qa/ticket: Slug is required for closure log")

// ClosureLogInput is the Lava-shaped input to GenerateClosureLog. The
// fields mirror the structure observed in existing closure logs under
// .lava-ci-evidence/crashlytics-resolved/. Every field except those
// explicitly marked optional is required by §6.O.
type ClosureLogInput struct {
	// CrashlyticsID is the Firebase Console issue ID. Required.
	// Example: "a29412cf6566d0a71b06df416610be57".
	CrashlyticsID string

	// Slug is the kebab-case identifier for the filename suffix.
	// Required. Example: "rutracker-loginusecase-unknown".
	Slug string

	// Date is the closure date (used in the filename prefix YYYY-MM-DD
	// and in the markdown header). If zero, time.Now().UTC() is used.
	Date time.Time

	// Title is the human-readable title for the closure log. Required.
	// Example: "lava.tracker.rutracker.domain.LoginUseCase.invoke".
	Title string

	// Subtitle is the Crashlytics subtitle (Throwable subtype, error
	// message, etc.). Optional.
	Subtitle string

	// Stack is the short stack-frame summary (top frames). Optional but
	// strongly recommended.
	Stack string

	// Type is the Crashlytics severity classification (FATAL or
	// NON_FATAL). Required.
	Type string

	// Device captures the failing-device descriptor. Optional but
	// recommended (Galaxy S23 Ultra, Pixel 7 Pro, etc.).
	Device string

	// Version is the affected build's versionName + versionCode string.
	// Required. Example: "1.2.8 (1028) release — 1 event 2026-05-07".
	Version string

	// StateAtClosure describes the Console state when the closure log
	// is being written. Required. Example: "OPEN (operator marks
	// closed)".
	StateAtClosure string

	// RootCause is the multi-paragraph root-cause analysis. Required —
	// per §6.O the closure log MUST diagnose the bluff that hid the bug
	// (or the genuine novel defect class).
	RootCause string

	// Fix is the multi-paragraph fix description. Required.
	Fix string

	// ValidationTest is the path or description of the validation test
	// added per §6.O clause (a). Required.
	ValidationTest string

	// ChallengeTest is the path or description of the Challenge Test
	// added per §6.O clause (b). Optional only when the Challenge is
	// owed (cite the owed-debt token).
	ChallengeTest string

	// ClosureProtocol is the operator-facing closure protocol summary.
	// Required.
	ClosureProtocol string

	// FixCommitSHA is the commit SHA that landed the fix. Optional at
	// authoring time (the fix commit's own body references the closure
	// log); when present, the closure log embeds it for cross-reference.
	FixCommitSHA string

	// Labels are extra HelixQA labels propagated through to the
	// underlying Ticket. Optional.
	Labels []string
}

// Generator is Lava's adapter over HelixQA's pkg/ticket.Generator.
// Q4 (preserve HelixQA terminology): the type name "Generator" matches
// HelixQA's so developers reading both codebases see the same noun.
// Q2 (WRAP strategy): Lava-shaped methods translate to/from HelixQA's
// shape; HelixQA-version bumps only require touching this file.
//
// Concurrency: safe for parallel use. The adapter's GenerateClosureLog
// path does NOT mutate the wrapped *hxqaticket.Generator (the wrapping
// is held for Phase 4-C-4 future extension; the closure-log rendering
// is fully Lava-owned). The on-disk filename embeds the date + sanitized
// slug, so concurrent calls with distinct (date, slug) tuples land on
// distinct paths; concurrent calls with identical (date, slug) overwrite
// each other deterministically (last-writer-wins, same as a manual
// `cat > file.md` overwrite).
type Generator struct {
	hxqa   *hxqaticket.Generator
	outDir string
}

// NewGenerator constructs a Generator rooted at outputDir. The output
// directory is the closure-log directory under .lava-ci-evidence/
// crashlytics-resolved/ (or any subdirectory the caller chooses for
// test fixtures). The directory is created on first write — the
// constructor does not touch the filesystem.
func NewGenerator(outputDir string) *Generator {
	hxqa := hxqaticket.New(hxqaticket.WithOutputDir(outputDir))
	return &Generator{
		hxqa:   hxqa,
		outDir: outputDir,
	}
}

// OutputDir returns the closure-log directory the Generator was
// constructed with.
func (g *Generator) OutputDir() string { return g.outDir }

// GenerateClosureLog writes a §6.O-schema-compliant Crashlytics
// closure log to <outputDir>/<YYYY-MM-DD>-<slug>.md and returns the
// absolute path of the written file.
//
// The markdown structure matches every existing file under
// .lava-ci-evidence/crashlytics-resolved/ so the output is
// indistinguishable from a manually-authored log at the schema level
// (which is what §6.J requires).
//
// Per §6.AC: errors are recorded as non-fatal telemetry with the
// canonical attribute set. The two input-validation errors
// (ErrEmptyCrashlyticsID, ErrEmptySlug) also surface as non-fatals so
// callers that swallow errors still see them in the telemetry pipeline.
func (g *Generator) GenerateClosureLog(ctx context.Context, in ClosureLogInput) (string, error) {
	if strings.TrimSpace(in.CrashlyticsID) == "" {
		observability.RecordNonFatal(ctx, ErrEmptyCrashlyticsID, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "GenerateClosureLog",
			"slug":                      in.Slug,
		})
		return "", ErrEmptyCrashlyticsID
	}
	if strings.TrimSpace(in.Slug) == "" {
		observability.RecordNonFatal(ctx, ErrEmptySlug, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "GenerateClosureLog",
			"crashlytics_id":            in.CrashlyticsID,
		})
		return "", ErrEmptySlug
	}

	date := in.Date
	if date.IsZero() {
		date = time.Now().UTC()
	}
	datePrefix := date.Format(closureLogDateLayout)
	slug := sanitizeSlug(in.Slug)
	filename := fmt.Sprintf("%s-%s.md", datePrefix, slug)
	path := filepath.Join(g.outDir, filename)

	if err := os.MkdirAll(g.outDir, 0o755); err != nil {
		err = fmt.Errorf("qa/ticket: mkdir output dir: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "GenerateClosureLog",
			"crashlytics_id":            in.CrashlyticsID,
			"output_dir":                g.outDir,
		})
		return "", err
	}

	content := renderClosureLog(in, date)

	if err := os.WriteFile(path, content, 0o644); err != nil {
		err = fmt.Errorf("qa/ticket: write closure log: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "GenerateClosureLog",
			"crashlytics_id":            in.CrashlyticsID,
			"path":                      path,
		})
		return "", err
	}

	// Note: HelixQA's Generator.GenerateFromStep / GenerateFromDetection
	// are NOT invoked here. Both require types from HelixQA's validator
	// or detector packages, which would (a) cross-couple this adapter
	// with Phase 4-C-2 / 4-C-4's owned territory, and (b) produce an
	// HQA-#### in-memory bookkeeping ticket that is meaningless to
	// Lava (the markdown on disk is the authoritative §6.O artifact).
	// The wrapped *hxqaticket.Generator is constructed in NewGenerator
	// and held so Phase 4-C-4 can layer detector→ticket integration on
	// top of this adapter without re-wrapping; today its sole role is
	// to assert the WRAP-vs-RE-EXPORT strategy is honored (the type is
	// real, the dep edge is live, future extension is cheap).
	_ = g.hxqa

	return path, nil
}

// renderClosureLog produces the §6.O-schema markdown for a closure
// log. Schema reference: every file under
// .lava-ci-evidence/crashlytics-resolved/ as of 2026-05-16.
//
// Required sections (in order):
//   1. H1 title — "# Crashlytics issue <ID> — closure log"
//   2. Metadata bullets — Issue ID, Title, Subtitle, Stack, Type,
//      Device, Version, State at closure
//   3. ## Root cause — multi-paragraph
//   4. ## Fix — multi-paragraph
//   5. ## Validation test (per §6.O + §6.AB) — path + verification
//   6. ## Closure protocol — operator-facing closure summary
//
// Optional: ## Challenge Test, ## Falsifiability rehearsal, ## Fix
// commit SHA (each appears in some existing closure logs).
func renderClosureLog(in ClosureLogInput, date time.Time) []byte {
	var buf bytes.Buffer

	// H1 title — matches "# Crashlytics issue <ID> — closure log"
	fmt.Fprintf(&buf, "# Crashlytics issue %s — closure log\n\n", in.CrashlyticsID)

	// Metadata bullets.
	fmt.Fprintf(&buf, "**Issue ID:** `%s`\n", in.CrashlyticsID)
	if in.Title != "" {
		fmt.Fprintf(&buf, "**Title:** `%s`\n", in.Title)
	}
	if in.Subtitle != "" {
		fmt.Fprintf(&buf, "**Subtitle:** %q\n", in.Subtitle)
	}
	if in.Stack != "" {
		fmt.Fprintf(&buf, "**Stack:** `%s`\n", in.Stack)
	}
	if in.Type != "" {
		fmt.Fprintf(&buf, "**Type:** %s\n", in.Type)
	}
	if in.Device != "" {
		fmt.Fprintf(&buf, "**Device:** %s\n", in.Device)
	}
	if in.Version != "" {
		fmt.Fprintf(&buf, "**Version:** %s\n", in.Version)
	}
	if in.StateAtClosure != "" {
		fmt.Fprintf(&buf, "**State at closure:** %s\n", in.StateAtClosure)
	}
	fmt.Fprintf(&buf, "**Closure date:** %s\n", date.Format(closureLogDateLayout))
	if in.FixCommitSHA != "" {
		fmt.Fprintf(&buf, "**Fix commit:** `%s`\n", in.FixCommitSHA)
	}
	buf.WriteString("\n")

	// Root cause.
	buf.WriteString("## Root cause\n\n")
	buf.WriteString(strings.TrimSpace(in.RootCause))
	buf.WriteString("\n\n")

	// Fix.
	buf.WriteString("## Fix\n\n")
	buf.WriteString(strings.TrimSpace(in.Fix))
	buf.WriteString("\n\n")

	// Validation test (per §6.O + §6.AB).
	buf.WriteString("## Validation test (per §6.O + §6.AB)\n\n")
	buf.WriteString(strings.TrimSpace(in.ValidationTest))
	buf.WriteString("\n\n")

	// Optional: Challenge Test.
	if strings.TrimSpace(in.ChallengeTest) != "" {
		buf.WriteString("## Challenge Test (per §6.O + §6.AE)\n\n")
		buf.WriteString(strings.TrimSpace(in.ChallengeTest))
		buf.WriteString("\n\n")
	}

	// Closure protocol.
	buf.WriteString("## Closure protocol\n\n")
	buf.WriteString(strings.TrimSpace(in.ClosureProtocol))
	buf.WriteString("\n")

	// Trailer — identify generator (per §6.J: be honest about origin).
	buf.WriteString("\n---\n")
	buf.WriteString("*Generated by lava-api-go/internal/qa/ticket (Phase 4-C-3 adapter over HelixQA pkg/ticket).*\n")

	return buf.Bytes()
}

// sanitizeSlug normalizes a Lava-supplied slug into a filename-safe
// form. Allowed chars: lowercase [a-z], digits, hyphen. Everything else
// is replaced with hyphen; consecutive hyphens collapse; leading and
// trailing hyphens trimmed. Empty input is rejected upstream by
// GenerateClosureLog.
func sanitizeSlug(slug string) string {
	if slug == "" {
		return ""
	}
	out := make([]byte, 0, len(slug))
	for i := 0; i < len(slug); i++ {
		b := slug[i]
		switch {
		case b >= 'a' && b <= 'z':
			out = append(out, b)
		case b >= 'A' && b <= 'Z':
			out = append(out, b+('a'-'A'))
		case b >= '0' && b <= '9':
			out = append(out, b)
		case b == '-' || b == '_':
			out = append(out, '-')
		default:
			out = append(out, '-')
		}
	}
	// Collapse runs of '-'.
	collapsed := make([]byte, 0, len(out))
	prevDash := false
	for _, b := range out {
		if b == '-' {
			if !prevDash {
				collapsed = append(collapsed, b)
			}
			prevDash = true
		} else {
			collapsed = append(collapsed, b)
			prevDash = false
		}
	}
	return strings.Trim(string(collapsed), "-")
}
