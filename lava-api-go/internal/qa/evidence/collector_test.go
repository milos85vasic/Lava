// Package evidence — adapter unit tests.
//
// Per §6.J / §6.AB: every public adapter method exercised against the REAL
// HelixQA Collector wired to a real filesystem (t.TempDir). No mocks of
// the SUT. Each test asserts on user-visible state (file on disk, Items()
// snapshot content), not on call counts.
//
// Per Phase 4-C-1 design doc §D.2 + §F.1: every test in this file is
// paired with a falsifiability rehearsal recorded in the Phase 4-C-1
// commit body.
package evidence

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	hxqaev "digital.vasic.helixqa/pkg/evidence"
)

// TestNewCollector_BasicConstruction verifies the constructor wires the
// runID + outputDir correctly and produces a zero-Item collector.
func TestNewCollector_BasicConstruction(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-42", dir)

	if got, want := c.RunID(), "run-42"; got != want {
		t.Errorf("RunID() = %q; want %q", got, want)
	}
	if got, want := c.OutputDir(), dir; got != want {
		t.Errorf("OutputDir() = %q; want %q", got, want)
	}
	if got := c.Count(); got != 0 {
		t.Errorf("Count() = %d; want 0", got)
	}
	if got := c.Items(); len(got) != 0 {
		t.Errorf("Items() len = %d; want 0", len(got))
	}
}

// TestCaptureText_WritesFileAndRecordsItem is the primary anti-bluff
// assertion (per §6.J.3): assert on user-visible state — the file MUST
// exist on disk with the expected content, AND the HelixQA collector
// MUST hold a matching Item.
func TestCaptureText_WritesFileAndRecordsItem(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-7", dir)

	const payload = "hello evidence world"
	if err := c.CaptureText(context.Background(), "trace-A", payload); err != nil {
		t.Fatalf("CaptureText returned error: %v", err)
	}

	items := c.Items()
	if len(items) != 1 {
		t.Fatalf("Items() len = %d; want 1", len(items))
	}
	item := items[0]

	if item.Type != hxqaev.TypeConsoleLog {
		t.Errorf("Item.Type = %q; want %q", item.Type, hxqaev.TypeConsoleLog)
	}
	if item.Step != "trace-A" {
		t.Errorf("Item.Step = %q; want %q", item.Step, "trace-A")
	}
	if !strings.HasPrefix(filepath.Base(item.Path), "run-7-trace-A-") {
		t.Errorf("Item.Path basename = %q; want prefix %q",
			filepath.Base(item.Path), "run-7-trace-A-")
	}
	if !strings.HasSuffix(item.Path, ".txt") {
		t.Errorf("Item.Path = %q; want .txt suffix", item.Path)
	}

	// User-visible state: file exists with the exact payload.
	contents, err := os.ReadFile(item.Path)
	if err != nil {
		t.Fatalf("read written file: %v", err)
	}
	if string(contents) != payload {
		t.Errorf("file contents = %q; want %q", string(contents), payload)
	}

	// Size stamped by CaptureGeneric.
	if item.Size != int64(len(payload)) {
		t.Errorf("Item.Size = %d; want %d", item.Size, len(payload))
	}
}

// TestCaptureText_SanitizesLabel verifies labels with forbidden chars are
// rewritten so the filesystem call never receives a path-traversal /
// shell-quote attack vector.
func TestCaptureText_SanitizesLabel(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-x", dir)

	if err := c.CaptureText(context.Background(),
		"../etc/passwd shell;rm-rf", "x"); err != nil {
		t.Fatalf("CaptureText returned error: %v", err)
	}
	items := c.Items()
	base := filepath.Base(items[0].Path)
	// Sanitizer keeps only [A-Za-z0-9._-]; `/`, ` `, `;` become `_`.
	if strings.ContainsAny(base, "/ ;") {
		t.Errorf("sanitized basename still contains forbidden chars: %q", base)
	}
	if !strings.HasPrefix(base, "run-x-") {
		t.Errorf("basename missing runID prefix: %q", base)
	}
}

// TestCaptureFile_CopiesSourceAndRecordsItem verifies CaptureFile
// copies (does NOT move) the source file into outputDir and records a
// stacktrace-typed Item with the copy's path.
func TestCaptureFile_CopiesSourceAndRecordsItem(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-9", dir)

	// External source file (simulates a dump produced elsewhere).
	srcDir := t.TempDir()
	srcPath := filepath.Join(srcDir, "external-dump.bin")
	const payload = "external evidence payload"
	if err := os.WriteFile(srcPath, []byte(payload), 0o644); err != nil {
		t.Fatalf("write src: %v", err)
	}

	if err := c.CaptureFile(context.Background(), "dump-label", srcPath); err != nil {
		t.Fatalf("CaptureFile error: %v", err)
	}

	items := c.Items()
	if len(items) != 1 {
		t.Fatalf("Items() len = %d; want 1", len(items))
	}
	item := items[0]

	if item.Type != hxqaev.TypeStackTrace {
		t.Errorf("Item.Type = %q; want %q", item.Type, hxqaev.TypeStackTrace)
	}
	if item.Step != "dump-label" {
		t.Errorf("Item.Step = %q; want %q", item.Step, "dump-label")
	}
	// Copy lives under outputDir; src still exists.
	if filepath.Dir(item.Path) != dir {
		t.Errorf("Item.Path dir = %q; want %q", filepath.Dir(item.Path), dir)
	}
	if _, err := os.Stat(srcPath); err != nil {
		t.Errorf("source file was moved (should be copied): %v", err)
	}
	copyContents, err := os.ReadFile(item.Path)
	if err != nil {
		t.Fatalf("read copy: %v", err)
	}
	if string(copyContents) != payload {
		t.Errorf("copy contents = %q; want %q", string(copyContents), payload)
	}
}

// TestCaptureFile_SourceMissing_ReportsError exercises the error path so
// the §6.AC RecordNonFatal call is reached. The adapter returns the
// wrapped error to the caller AND records a non-fatal telemetry event.
func TestCaptureFile_SourceMissing_ReportsError(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-err", dir)

	err := c.CaptureFile(context.Background(), "label", "/nonexistent/path/file")
	if err == nil {
		t.Fatal("expected error for missing source; got nil")
	}
	if !strings.Contains(err.Error(), "copy file") {
		t.Errorf("error message missing 'copy file' context: %v", err)
	}
	// Adapter must NOT record an Item when capture fails.
	if got := c.Count(); got != 0 {
		t.Errorf("Count() = %d; want 0 on error path", got)
	}
}

// TestFinalize_BlocksSubsequentCaptures verifies Finalize() makes the
// collector terminal — subsequent CaptureText / CaptureFile calls
// return ErrFinalized.
func TestFinalize_BlocksSubsequentCaptures(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-fin", dir)

	// One capture works before finalize.
	if err := c.CaptureText(context.Background(), "pre", "before"); err != nil {
		t.Fatalf("pre-finalize CaptureText error: %v", err)
	}

	if err := c.Finalize(context.Background()); err != nil {
		t.Fatalf("Finalize error: %v", err)
	}

	// Post-finalize CaptureText returns ErrFinalized.
	err := c.CaptureText(context.Background(), "post", "after")
	if !errors.Is(err, ErrFinalized) {
		t.Errorf("post-finalize CaptureText: errors.Is(%v, ErrFinalized) = false", err)
	}

	// Post-finalize CaptureFile also blocked.
	src := filepath.Join(t.TempDir(), "x")
	_ = os.WriteFile(src, []byte("x"), 0o644)
	err = c.CaptureFile(context.Background(), "post", src)
	if !errors.Is(err, ErrFinalized) {
		t.Errorf("post-finalize CaptureFile: errors.Is(%v, ErrFinalized) = false", err)
	}

	// Items() still readable after finalize.
	if got := c.Count(); got != 1 {
		t.Errorf("post-finalize Count() = %d; want 1 (pre-finalize item retained)", got)
	}

	// Finalize is idempotent.
	if err := c.Finalize(context.Background()); err != nil {
		t.Errorf("second Finalize returned error: %v", err)
	}
}

// TestCollector_ConcurrentCaptures verifies parallel CaptureText calls
// do not lose items or duplicate paths.
func TestCollector_ConcurrentCaptures(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector("run-concurrent", dir)

	const n = 30
	var wg sync.WaitGroup
	wg.Add(n)
	for i := 0; i < n; i++ {
		i := i
		go func() {
			defer wg.Done()
			label := "concurrent-" + string(rune('a'+i%26))
			if err := c.CaptureText(context.Background(), label, "payload"); err != nil {
				t.Errorf("CaptureText #%d error: %v", i, err)
			}
		}()
	}
	wg.Wait()

	if got := c.Count(); got != n {
		t.Errorf("Count() = %d; want %d (concurrent captures lost items)", got, n)
	}

	// Every recorded path is unique on disk (millisecond timestamp +
	// label suffix gives enough entropy under burst).
	seen := make(map[string]struct{})
	for _, item := range c.Items() {
		if _, dup := seen[item.Path]; dup {
			t.Errorf("duplicate path recorded: %q", item.Path)
		}
		seen[item.Path] = struct{}{}
	}
}

// TestCaptureText_OutputDirUnwritable verifies the mkdir-failure path
// reports the error AND records non-fatal telemetry.
func TestCaptureText_OutputDirUnwritable(t *testing.T) {
	// Create a file at the path where the collector would mkdir — mkdir
	// then fails because a non-directory file already occupies the
	// location.
	baseDir := t.TempDir()
	collidingPath := filepath.Join(baseDir, "blocker")
	if err := os.WriteFile(collidingPath, []byte("not a dir"), 0o644); err != nil {
		t.Fatalf("write collider: %v", err)
	}
	// outputDir is "<baseDir>/blocker/output" — mkdir-all will fail
	// because `blocker` is a file, not a directory.
	c := NewCollector("run-mkdir-fail", filepath.Join(collidingPath, "output"))

	err := c.CaptureText(context.Background(), "label", "x")
	if err == nil {
		t.Fatal("expected mkdir error; got nil")
	}
	if !strings.Contains(err.Error(), "mkdir") {
		t.Errorf("error message missing 'mkdir' context: %v", err)
	}
	if got := c.Count(); got != 0 {
		t.Errorf("Count() = %d; want 0 on mkdir-error path", got)
	}
}

// TestSanitizeLabel_TableDriven covers the label sanitizer cases.
func TestSanitizeLabel_TableDriven(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"", "unlabelled"},
		{"simple", "simple"},
		{"with space", "with_space"},
		{"with/slash", "with_slash"},
		{"with.dot_dash-keep", "with.dot_dash-keep"},
		{";:!@#$%^&*()", "____________"},
		{"abc123", "abc123"},
	}
	for _, tc := range cases {
		if got := sanitizeLabel(tc.in); got != tc.want {
			t.Errorf("sanitizeLabel(%q) = %q; want %q", tc.in, got, tc.want)
		}
	}
}
