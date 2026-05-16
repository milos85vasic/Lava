// Phase 4-C-1 — REAL-STACK integration test for internal/qa/evidence.
//
// Per docs/plans/2026-05-16-helixqa-go-package-linking-design.md §D.3:
// gated by the `helixqa_realstack` build tag. Default `go test ./...`
// runs skip this file (transitive HelixQA deps may not be present in
// every CI environment; running this test requires the sibling
// Submodules/HelixQA/ present + module-graph resolved).
//
// Per §6.J / §6.AE.4: exercises the REAL HelixQA Collector against
// the REAL filesystem. The adapter is the SUT; nothing in this test
// mocks HelixQA. Falsifiability rehearsal recorded in the Phase 4-C-1
// commit body.
//
// Run with:
//   go test -tags=helixqa_realstack ./tests/qa/...

//go:build helixqa_realstack

package qa_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	hxqaev "digital.vasic.helixqa/pkg/evidence"

	qaevidence "digital.vasic.lava.apigo/internal/qa/evidence"
)

// TestEvidenceAdapter_RealStack_EndToEnd exercises the full happy path:
// adapter → real HelixQA Collector → real filesystem → snapshot
// serialization. This is the §6.AE.4 acceptance-gate test that proves
// a real consumer can record evidence end-to-end.
func TestEvidenceAdapter_RealStack_EndToEnd(t *testing.T) {
	dir := t.TempDir()
	c := qaevidence.NewCollector("e2e-run-001", dir)

	// 1. Capture text — simulates a server-side log dump.
	if err := c.CaptureText(context.Background(),
		"http-trace", "GET /v1/health 200 OK"); err != nil {
		t.Fatalf("CaptureText error: %v", err)
	}

	// 2. Capture file — simulates an external dump produced by some
	// other tool (a tcpdump, a heap snapshot, a coverage report).
	srcDir := t.TempDir()
	srcPath := filepath.Join(srcDir, "heap-snapshot.bin")
	if err := os.WriteFile(srcPath,
		[]byte("\x00\x01\x02 heap snapshot bytes \x03\x04"), 0o644); err != nil {
		t.Fatalf("write src: %v", err)
	}
	if err := c.CaptureFile(context.Background(),
		"heap-snapshot", srcPath); err != nil {
		t.Fatalf("CaptureFile error: %v", err)
	}

	// 3. Snapshot the items. The downstream Phase 4-C-3 ticket adapter
	// consumes this exact shape via the same HelixQA Item type.
	items := c.Items()
	if len(items) != 2 {
		t.Fatalf("Items() len = %d; want 2", len(items))
	}

	// 4. Finalize and confirm post-finalize captures are blocked.
	if err := c.Finalize(context.Background()); err != nil {
		t.Fatalf("Finalize error: %v", err)
	}
	if err := c.CaptureText(context.Background(),
		"post-finalize", "x"); err == nil {
		t.Errorf("post-finalize CaptureText should fail; got nil")
	}

	// 5. JSON-serialize the items (proves the Item struct's json
	// tags work end-to-end — the downstream ticket adapter will
	// embed these in markdown closure logs).
	blob, err := json.MarshalIndent(items, "", "  ")
	if err != nil {
		t.Fatalf("json marshal items: %v", err)
	}
	s := string(blob)
	if !strings.Contains(s, "console_log") {
		t.Errorf("serialized snapshot missing console_log type: %s", s)
	}
	if !strings.Contains(s, "stacktrace") {
		t.Errorf("serialized snapshot missing stacktrace type: %s", s)
	}

	// 6. The HelixQA Item.Path values point at real files we can stat.
	for _, item := range items {
		info, err := os.Stat(item.Path)
		if err != nil {
			t.Errorf("Item.Path %q does not exist on disk: %v", item.Path, err)
			continue
		}
		if info.Size() == 0 {
			t.Errorf("Item.Path %q is empty (Size = 0)", item.Path)
		}
		if info.Size() != item.Size {
			t.Errorf("Item.Size %d != actual on-disk size %d (path: %s)",
				item.Size, info.Size(), item.Path)
		}
	}

	// 7. The adapter wraps a real *hxqaev.Collector — proves the
	// WRAP strategy actually wraps rather than re-implements.
	if items[0].Type != hxqaev.TypeConsoleLog {
		t.Errorf("first item Type = %q; want %q",
			items[0].Type, hxqaev.TypeConsoleLog)
	}
}

// TestEvidenceAdapter_RealStack_CapturesPersistAcrossSnapshots verifies
// successive Items() calls return consistent (and growing) snapshots,
// matching HelixQA's Collector contract.
func TestEvidenceAdapter_RealStack_CapturesPersistAcrossSnapshots(t *testing.T) {
	dir := t.TempDir()
	c := qaevidence.NewCollector("snap-run", dir)

	for i := 0; i < 5; i++ {
		label := "log-" + string(rune('a'+i))
		if err := c.CaptureText(context.Background(),
			label, "payload-"+label); err != nil {
			t.Fatalf("CaptureText #%d error: %v", i, err)
		}
		if got := c.Count(); got != i+1 {
			t.Errorf("after capture #%d, Count() = %d; want %d", i, got, i+1)
		}
		if got := len(c.Items()); got != i+1 {
			t.Errorf("after capture #%d, Items() len = %d; want %d", i, got, i+1)
		}
	}
}
