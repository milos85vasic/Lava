// Package evidence is Lava's adapter over HelixQA's pkg/evidence Collector.
//
// Phase 4-C-1 (2026-05-16) — first of the four-phase Group A rollout per
// docs/plans/2026-05-16-helixqa-go-package-linking-design.md. This adapter
// wraps HelixQA's Collector (Q2 WRAP strategy per operator decisions) and
// preserves HelixQA's terminology (Q4: Collector keeps its name) so
// HelixQA-familiar developers can navigate this package without a glossary.
//
// The adapter exists because HelixQA's Collector centers on ADB-driven
// Android screenshots / Playwright web screenshots / desktop `import` —
// none of which apply to a backend Go service. lava-api-go consumers need
// to record server-side text dumps (HTTP traces, log captures, metric
// snapshots, JSON request/response bodies) as typed evidence with the
// same Item shape so the downstream pkg/ticket adapter (Phase 4-C-3) can
// consume them uniformly.
//
// The Lava-side methods (CaptureText, CaptureFile, Finalize) all route
// through HelixQA's pkg/evidence.Collector.CaptureGeneric, the public
// method this adapter's Phase 4-C-1 cycle contributed upstream (HelixQA
// PR https://github.com/HelixDevelopment/HelixQA/pull/1, commit
// a1e2020dd759d025b67ef8e024061b103940470d).
//
// Per §6.AC (Comprehensive Non-Fatal Telemetry Mandate): every error
// path records via observability.RecordNonFatal so server-side telemetry
// surfaces in the operator's Crashlytics / OTLP dashboards. Per §6.AC.7
// (operator decision Q7): NO recover() wrapping — we trust HelixQA's API
// and file upstream issues if panics surface. Per §6.J: each method's
// falsifiability rehearsal is recorded in the Phase 4-C-1 commit body.
package evidence

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	hxqaconfig "digital.vasic.helixqa/pkg/config"
	hxqaev "digital.vasic.helixqa/pkg/evidence"

	"digital.vasic.lava.apigo/internal/observability"
)

// telemetryFeature is the canonical §6.AC AttrFeature value for events
// originating in this adapter. Stable string so dashboards can filter.
const telemetryFeature = "qa.evidence"

// Collector is Lava's adapter over HelixQA's pkg/evidence.Collector.
// Q4 (preserve HelixQA terminology): the type name "Collector" matches
// HelixQA's so developers reading both codebases see the same noun.
// Q2 (WRAP strategy): Lava-shaped methods translate to/from HelixQA's
// shape; HelixQA-version bumps only require touching this file.
//
// Concurrency: safe for parallel use (delegates to HelixQA Collector's
// existing mutex via CaptureGeneric; the adapter adds no shared state).
type Collector struct {
	hxqa   *hxqaev.Collector
	runID  string
	outDir string

	mu        sync.Mutex
	finalized bool

	// seq is a monotonic counter used to disambiguate filenames produced
	// in the same millisecond — without it, two CaptureText calls with
	// the same label landing in the same millisecond would overwrite
	// each other (real production hazard surfaced by the concurrent
	// test in this package).
	seq atomic.Uint64
}

// NewCollector constructs a Collector rooted under outputDir. The
// runID is included in capture filenames + telemetry attributes so
// concurrent test runs against a shared outputDir do not collide.
//
// The underlying HelixQA Collector is configured for the Linux/Desktop
// platform because lava-api-go is a server-side Go service. Item.Platform
// can still be overridden per CaptureFile / CaptureText call when needed.
//
// The output directory is created on construction (MkdirAll). A failure
// here returns a Collector that will surface the error on the first
// capture call; we do not panic in the constructor (Q7: trust HelixQA
// but ALSO trust Go stdlib — `os.MkdirAll` on a sane path is reliable).
func NewCollector(runID, outputDir string) *Collector {
	hxqa := hxqaev.New(
		hxqaev.WithOutputDir(outputDir),
		hxqaev.WithPlatform(hxqaconfig.PlatformDesktop),
	)
	return &Collector{
		hxqa:   hxqa,
		runID:  runID,
		outDir: outputDir,
	}
}

// CaptureText records a text payload as evidence. The content is
// written to a UTF-8 file under the collector's outputDir, then the
// resulting file is recorded as a console_log-typed Item in HelixQA's
// Collector via CaptureGeneric.
//
// The filename is `<runID>-<label>-<unix-millis>.txt`. The label is
// best-effort sanitized (only [A-Za-z0-9._-] kept) so callers can pass
// human-readable labels without breaking the filesystem.
//
// Per §6.AC: errors are recorded as non-fatal telemetry with the
// canonical attribute set (feature + operation + error_class +
// error_message + endpoint=run_id).
func (c *Collector) CaptureText(ctx context.Context, label, content string) error {
	if err := c.ensureLive(); err != nil {
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CaptureText",
			observability.AttrRequestID: c.runID,
			"label":                     label,
		})
		return err
	}
	if err := os.MkdirAll(c.outDir, 0o755); err != nil {
		err = fmt.Errorf("qa/evidence: mkdir output dir: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CaptureText",
			observability.AttrRequestID: c.runID,
			"label":                     label,
			"output_dir":                c.outDir,
		})
		return err
	}
	seq := c.seq.Add(1)
	filename := fmt.Sprintf("%s-%s-%d-%d.txt",
		c.runID, sanitizeLabel(label), time.Now().UnixMilli(), seq)
	path := filepath.Join(c.outDir, filename)
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		err = fmt.Errorf("qa/evidence: write text payload: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CaptureText",
			observability.AttrRequestID: c.runID,
			"label":                     label,
			"path":                      path,
		})
		return err
	}
	c.hxqa.CaptureGeneric(hxqaev.Item{
		Type:     hxqaev.TypeConsoleLog,
		Path:     path,
		Step:     label,
		Platform: hxqaconfig.PlatformDesktop,
	})
	return nil
}

// CaptureFile records a pre-existing file as evidence. The file at
// srcPath is copied (not moved) into the collector's outputDir under
// a `<runID>-<label>-<basename>` name, then registered as a stacktrace-
// typed Item in HelixQA's Collector via CaptureGeneric.
//
// Use this when the test scaffolding produces a file elsewhere (e.g. a
// dump tool writes to /tmp) and the test wants to attach it to the
// evidence chain.
//
// Per §6.AC: errors are recorded as non-fatal telemetry. Per §6.J: the
// adapter is provably falsifiable — see the commit body's mutation
// rehearsal.
func (c *Collector) CaptureFile(ctx context.Context, label, srcPath string) error {
	if err := c.ensureLive(); err != nil {
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CaptureFile",
			observability.AttrRequestID: c.runID,
			"label":                     label,
			"src_path":                  srcPath,
		})
		return err
	}
	if err := os.MkdirAll(c.outDir, 0o755); err != nil {
		err = fmt.Errorf("qa/evidence: mkdir output dir: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CaptureFile",
			observability.AttrRequestID: c.runID,
			"label":                     label,
			"output_dir":                c.outDir,
		})
		return err
	}
	seq := c.seq.Add(1)
	dstPath := filepath.Join(c.outDir, fmt.Sprintf("%s-%s-%d-%s",
		c.runID, sanitizeLabel(label), seq, filepath.Base(srcPath)))
	if err := copyFile(srcPath, dstPath); err != nil {
		err = fmt.Errorf("qa/evidence: copy file: %w", err)
		observability.RecordNonFatal(ctx, err, observability.NonFatalAttributes{
			observability.AttrFeature:   telemetryFeature,
			observability.AttrOperation: "CaptureFile",
			observability.AttrRequestID: c.runID,
			"label":                     label,
			"src_path":                  srcPath,
			"dst_path":                  dstPath,
		})
		return err
	}
	c.hxqa.CaptureGeneric(hxqaev.Item{
		Type:     hxqaev.TypeStackTrace,
		Path:     dstPath,
		Step:     label,
		Platform: hxqaconfig.PlatformDesktop,
	})
	return nil
}

// Finalize marks the collector as terminal. Subsequent CaptureText /
// CaptureFile calls return ErrFinalized. Finalize is idempotent: a
// second call returns nil without effect. After Finalize, Items() can
// still be read for evidence-snapshot serialization.
//
// Per §6.AC: a no-op-finalize (already finalized) is NOT a telemetry
// event — it is benign idempotency. Errors during finalize would be
// recorded but no operations within Finalize today can fail.
func (c *Collector) Finalize(_ context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.finalized = true
	return nil
}

// Items returns a snapshot of all recorded HelixQA Items. Safe to call
// after Finalize. The returned slice is a copy; modifying it does not
// affect the underlying Collector.
func (c *Collector) Items() []hxqaev.Item {
	return c.hxqa.Items()
}

// Count returns the number of recorded Items.
func (c *Collector) Count() int {
	return c.hxqa.Count()
}

// RunID returns the collector's run ID (for telemetry / labelling).
func (c *Collector) RunID() string { return c.runID }

// OutputDir returns the collector's root output directory.
func (c *Collector) OutputDir() string { return c.outDir }

// ErrFinalized is returned by CaptureText / CaptureFile after Finalize.
var ErrFinalized = fmt.Errorf("qa/evidence: collector is finalized")

func (c *Collector) ensureLive() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.finalized {
		return ErrFinalized
	}
	return nil
}

// sanitizeLabel keeps only [A-Za-z0-9._-]; replaces every other char
// with '_'. Empty input returns "unlabelled".
func sanitizeLabel(label string) string {
	if label == "" {
		return "unlabelled"
	}
	out := make([]byte, 0, len(label))
	for i := 0; i < len(label); i++ {
		b := label[i]
		switch {
		case b >= 'A' && b <= 'Z',
			b >= 'a' && b <= 'z',
			b >= '0' && b <= '9',
			b == '.' || b == '_' || b == '-':
			out = append(out, b)
		default:
			out = append(out, '_')
		}
	}
	return string(out)
}

// copyFile copies src to dst. dst is created or truncated.
func copyFile(src, dst string) error {
	srcFile, err := os.Open(src)
	if err != nil {
		return err
	}
	defer srcFile.Close()
	dstFile, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	defer dstFile.Close()
	if _, err := io.Copy(dstFile, srcFile); err != nil {
		return err
	}
	return nil
}
